package com.ethscalper.cockpit;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Functional proof that real, disabled and failing shadow observers leave public state identical. */
public final class ShadowPublicIsolationIntegrationTest {
    @Test public void p02ConfirmationIsIdenticalWithRealNoopAndFailingShadow(){
        Run real=confirmP02(new MarketPlanOrchestrator());
        Run noop=confirmP02(new MarketPlanOrchestrator(MarketPlanOrchestrator.noOpShadowObserver()));
        Run failure=confirmP02(new MarketPlanOrchestrator((operation,action)->{
            throw new IllegalStateException("deliberate "+operation);
        }));
        assertSamePublic(real,noop);assertSamePublic(real,failure);
        assertEquals("CONFIRMED",failure.event.type);assertNotNull(failure.runtime.activePlan);
        assertEquals(0,failure.runtime.observedSignals.size());
        assertTrue(failure.runtime.recorder.eventMaps().stream().anyMatch(e->
                "SHADOW_INTERNAL_ERROR".equals(e.get("eventType"))));
        Map<String,Object> decision=real.runtime.recorder.eventMaps().stream().filter(e->
                "SHADOW_AB_DECISION".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertEquals(1,real.runtime.recorder.eventMaps().stream().filter(e->
                "SHADOW_AB_DECISION".equals(e.get("eventType"))).count());
        assertEquals(Boolean.TRUE,decision.get("productionConfirmed"));
        assertEquals(Boolean.TRUE,decision.get("productionActivePlan"));
        for(Map<String,Object> event:real.runtime.recorder.eventMaps())if("SHADOW_OBSERVABILITY".equals(event.get("classification"))){
            assertEquals(ShadowCalibrationPolicy.VERSION,event.get("shadowPolicyVersion"));
            assertEquals(ShadowCalibrationPolicy.SCHEMA_VERSION,event.get("shadowSchemaVersion"));
        }
    }

    @Test public void publicTerminalIsIdenticalWhenShadowTerminalThrows(){
        Run real=terminal(new MarketPlanOrchestrator());
        Run noop=terminal(new MarketPlanOrchestrator(MarketPlanOrchestrator.noOpShadowObserver()));
        Run failure=terminal(new MarketPlanOrchestrator((operation,action)->{
            if("OBSERVE_TERMINAL".equals(operation))throw new IllegalStateException("terminal");
            action.run();
        }));
        assertSamePublic(real,noop);assertSamePublic(real,failure);
        assertEquals("TERMINAL",failure.event.type);assertEquals("TP_TOUCHED",failure.event.status);
        assertNull(failure.runtime.activePlan);assertEquals(4_000_000L,failure.runtime.lastTerminalAt);
    }

    @Test public void candidatePathIsIdenticalWhenShadowOpeningThrows(){
        Run noop=earlyCandidate(new MarketPlanOrchestrator(MarketPlanOrchestrator.noOpShadowObserver()));
        Run failure=earlyCandidate(new MarketPlanOrchestrator((operation,action)->{
            if("CONSIDER_OPEN".equals(operation))throw new IllegalStateException("open");
            action.run();
        }));
        assertSamePublic(noop,failure);assertNull(failure.runtime.activePlan);
        assertEquals(1,failure.runtime.observedSignals.size());
    }

    private static Run confirmP02(MarketPlanOrchestrator orchestrator){
        long now=3_600_000L;MarketRuntime runtime=new MarketRuntime(MarketProfile.sol());
        runtime.lastP01ConfirmedAt=1234;runtime.last=75.08;runtime.bid=75.07;runtime.ask=75.08;
        runtime.lastTickerAt=now;
        for(int i=0;i<60;i++){double close=75+i*.001;
            if(i==56)close=75.056;if(i==57)close=75.060;if(i==58)close=75.068;if(i==59)close=75.080;
            runtime.candles.addLast(bar(i*60_000L,close,close+.01,close-.01,close,1));}
        runtime.aggTrades.addLast(new MarketRuntime.AggTrade(1,now-1000,75.08,.2,false));
        SharedReferenceContext btc=btc(now);
        SignalDecision seed=SignalDecision.signal(runtime.profile,"LONG","v2.34 P02_CONTINUATION",80,3,
                75.08,75.20,75.02,.12,.06,"P02",true,75,75.08,.08);
        runtime.observedSignals.addLast(new MarketPlanOrchestrator.RuntimeCandidate(seed,
                CandidateLifecycle.SLEEVE_P02,now-20_001));
        return new Run(orchestrator.evaluate(runtime,btc,now,true,true),runtime);
    }

    private static Run earlyCandidate(MarketPlanOrchestrator orchestrator){
        long now=3_600_000L;MarketRuntime runtime=new MarketRuntime(MarketProfile.eth());
        runtime.last=100;runtime.bid=99.99;runtime.ask=100.01;runtime.lastTickerAt=now;
        for(int i=0;i<60;i++)runtime.candles.addLast(bar(i*60_000L,100,101,99,100,1));
        runtime.aggTrades.addLast(new MarketRuntime.AggTrade(1,now-1000,100,.2,false));
        SignalDecision seed=ShadowTestFixtures.candidate(runtime.profile,"LONG",95);
        runtime.observedSignals.addLast(new MarketPlanOrchestrator.RuntimeCandidate(seed,
                CandidateLifecycle.SLEEVE_P01,now-1000));
        return new Run(orchestrator.evaluate(runtime,btc(now),now,true,true),runtime);
    }

    private static Run terminal(MarketPlanOrchestrator orchestrator){
        long now=4_000_000L;MarketRuntime runtime=new MarketRuntime(MarketProfile.eth());
        runtime.activePlan=ActivePlanState.builder().market(runtime.profile).side("LONG").family("P01")
                .reasonCode("OK").reasonText("public").score(95).quantity(3).prices(100,105,95)
                .risk(5,5).times(1,1,1).premium15m(false).notification("public",321)
                .lastMarket(105,105,105.01,2).lastP01ConfirmedAt(1).movement("",false,0,0,0)
                .unitRisk(1.43,2.35,14.55,12).build();
        runtime.lastSignal=runtime.activePlan.toSignalDecision();runtime.last=105;runtime.bid=105;
        runtime.ask=105.01;runtime.lastTickerAt=now;
        return new Run(orchestrator.evaluate(runtime,btc(now),now,true,true),runtime);
    }

    private static SharedReferenceContext btc(long now){SharedReferenceContext b=new SharedReferenceContext();
        b.last=60_000;b.bid=59_999;b.ask=60_001;b.lastTickerAt=now;return b;}
    private static MarketRuntime.MarketBar bar(long at,double o,double h,double l,double c,double v){
        return new MarketRuntime.MarketBar(at,o,h,l,c,v);}
    private static void assertSamePublic(Run expected,Run actual){
        assertEquals(expected.event.type,actual.event.type);assertEquals(expected.event.reasonCode,actual.event.reasonCode);
        assertEquals(expected.event.status,actual.event.status);assertEquals(expected.runtime.lastTerminalAt,actual.runtime.lastTerminalAt);
        assertEquals(expected.runtime.lastP01ConfirmedAt,actual.runtime.lastP01ConfirmedAt);
        assertEquals(expected.runtime.rearmRemainingMs(4_000_000),actual.runtime.rearmRemainingMs(4_000_000));
        assertEquals(expected.runtime.observedSignals.size(),actual.runtime.observedSignals.size());
        assertEquals(publicEvents(expected.runtime),publicEvents(actual.runtime));
        if(expected.runtime.activePlan==null)assertNull(actual.runtime.activePlan);else{
            assertNotNull(actual.runtime.activePlan);assertEquals(expected.runtime.activePlan.entry,actual.runtime.activePlan.entry,0);
            assertEquals(expected.runtime.activePlan.takeProfit,actual.runtime.activePlan.takeProfit,0);
            assertEquals(expected.runtime.activePlan.stopLoss,actual.runtime.activePlan.stopLoss,0);
            assertEquals(expected.runtime.activePlan.quantity,actual.runtime.activePlan.quantity);
        }
    }
    private static List<String> publicEvents(MarketRuntime runtime){List<String> out=new ArrayList<>();
        for(Map<String,Object> e:runtime.recorder.eventMaps())if(!"SHADOW_OBSERVABILITY".equals(e.get("classification")))
            out.add(e.get("eventType")+"|"+e.get("reasonCode"));return out;}
    private static final class Run{final MarketPlanOrchestrator.Event event;final MarketRuntime runtime;
        Run(MarketPlanOrchestrator.Event event,MarketRuntime runtime){this.event=event;this.runtime=runtime;}}
}
