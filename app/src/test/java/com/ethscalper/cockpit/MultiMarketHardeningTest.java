package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class MultiMarketHardeningTest {
    @Test public void serviceRouterRoutesBookTickerForEveryRegisteredSymbol() {
        Fixture f=fixture();
        assertTrue(f.router.routeBookTicker("TESTUSDT",9.99,10.01,1_000));
        MarketRuntime runtime=f.coordinator.runtime("TESTUSDT");
        assertEquals(10.0,runtime.last,0);assertEquals(9.99,runtime.bid,0);
        assertEquals(10.01,runtime.ask,0);assertEquals(1_000,runtime.lastTickerAt);
    }

    @Test public void serviceRouterRoutesKlineForEveryRegisteredSymbol() {
        Fixture f=fixture();MarketRuntime.MarketBar bar=bar(60_000,10,10.2,9.8,10.1,5);
        assertTrue(f.router.routeKline("TESTUSDT",bar,61_000));
        assertEquals(1,f.coordinator.runtime("TESTUSDT").candles.size());
        assertEquals(10.1,f.coordinator.runtime("TESTUSDT").last,0);
    }

    @Test public void serviceRouterRoutesAggTradeForEveryRegisteredSymbol() {
        Fixture f=fixture();MarketRuntime.AggTrade trade=new MarketRuntime.AggTrade(7,1_000,10,.4,false);
        assertTrue(f.router.routeAggTrade("TESTUSDT",trade,1_000));
        assertEquals(1,f.coordinator.runtime("TESTUSDT").aggTrades.size());
        assertEquals(7,f.coordinator.runtime("TESTUSDT").lastAggTradeId);
    }

    @Test public void genericPreloadAndFallbackRequireNoSymbolBranch() {
        Fixture f=fixture();List<MarketRuntime.MarketBar> initial=new ArrayList<>();
        for(int i=0;i<180;i++)initial.add(bar(i*60_000L,10,10.1,9.9,10,1));
        assertEquals(180,f.router.replacePreloadedCandles("TESTUSDT",initial,11));
        assertEquals(180,f.coordinator.runtime("TESTUSDT").candles.size());
        assertEquals(1,f.router.mergeFallbackCandles("TESTUSDT",
                Collections.singletonList(bar(180*60_000L,10,10.2,9.9,10.1,2)),12));
        assertEquals(180,f.coordinator.runtime("TESTUSDT").candles.size());
        assertEquals(1,f.router.mergeFallbackAggTrades("TESTUSDT",
                Collections.singletonList(new MarketRuntime.AggTrade(1,12,10.1,1,false)),12));
        assertEquals(1,f.coordinator.runtime("TESTUSDT").restTradeRefreshes);
    }

    @Test public void ethMirrorReceivesSameDataWithoutReplacingHistoricalEngine() {
        Fixture f=fixture();assertTrue(f.router.routeBookTicker("ETHUSDT",1900,1900.02,5));
        assertEquals(1,f.mirrorBooks);assertEquals(1900.01,f.mirrorLast,0);
        assertSame(f.coordinator.runtime("ETHUSDT").signalEngine,
                f.coordinator.runtime("ETHUSDT").signalEngine);
    }

    @Test public void serviceSourceContainsNoSolSpecificRoutingBranch() throws Exception {
        Path source=Path.of("src/main/java/com/ethscalper/cockpit/MarketWatchService.java");
        String text=new String(Files.readAllBytes(source),StandardCharsets.UTF_8).toLowerCase();
        assertFalse(text.contains("startswith(\"solusdt\")"));
        assertTrue(text.contains("marketdatarouter.routebookticker"));
        assertTrue(text.contains("marketdatarouter.routekline"));
        assertTrue(text.contains("marketdatarouter.routeaggtrade"));
        assertTrue(text.contains("for(marketruntime runtime:marketcoordinator.runtimes().values())"));
    }

    @Test public void fakeThirdSymbolProducesThirdUiCardWithoutMainActivityChange() {
        Fixture f=fixture();List<MarketUiCatalog.CardDescriptor> cards=MarketUiCatalog.cards(f.registry);
        assertEquals(3,cards.size());assertEquals("TESTUSDT",cards.get(2).symbol);
    }

    @Test public void solAdmissionRunsEveryStructuralProtectionAndExplainsReplayAbsence() {
        MarketProfile sol=MarketProfile.sol();SignalDecision signal=signal(sol,"LONG");
        MarketAdmissionPolicy.Result result=MarketAdmissionPolicy.evaluate(sol,signal,
                snapshot(sol,"LONG"),context(true,true,false,true,false,false,false,false));
        assertTrue(result.accepted);assertEquals(MarketAdmissionPolicy.ACCEPTED,result.reasonCode);
        assertFalse(result.historicalReplayRiskVeto);
        assertEquals(MarketAdmissionPolicy.SOL_REPLAY_UNAVAILABLE,
                result.historicalDiagnosticCode);
        assertTrue(MarketAdmissionPolicy.rules().stream().anyMatch(r->
                r.classification==MarketAdmissionPolicy.Classification.ETH_HISTORICAL_ONLY));
    }

    @Test public void solAdmissionRejectsIsolatedStaleAndOppositeMemory() {
        MarketProfile sol=MarketProfile.sol();SignalDecision signal=signal(sol,"LONG");
        assertEquals(sol.staleReasonCode,MarketAdmissionPolicy.evaluate(sol,signal,
                snapshot(sol,"LONG"),context(false,true,false,true,false,false,false,false)).reasonCode);
        assertEquals(MarketAdmissionPolicy.OPPOSITE,MarketAdmissionPolicy.evaluate(sol,signal,
                snapshot(sol,"LONG"),context(true,true,false,true,true,false,false,false)).reasonCode);
    }

    @Test public void solAdmissionRejectsDuplicateTombstoneAndActivePlanPerSymbol() {
        MarketProfile sol=MarketProfile.sol();SignalDecision signal=signal(sol,"LONG");MarketSnapshot s=snapshot(sol,"LONG");
        assertEquals(MarketAdmissionPolicy.DUPLICATE,MarketAdmissionPolicy.evaluate(sol,signal,s,
                context(true,true,false,true,false,true,false,false)).reasonCode);
        assertEquals(MarketAdmissionPolicy.TOMBSTONE,MarketAdmissionPolicy.evaluate(sol,signal,s,
                context(true,true,false,true,false,false,true,false)).reasonCode);
        assertEquals(MarketAdmissionPolicy.ACTIVE,MarketAdmissionPolicy.evaluate(sol,signal,s,
                context(true,true,true,true,false,false,false,false)).reasonCode);
    }

    @Test public void resetDiagnosticsPreservesAndReinsertsTwoPlans() {
        MultiMarketCoordinator coordinator=new MultiMarketCoordinator(MarketRegistry.production());
        coordinator.publish("ETHUSDT",state(MarketProfile.eth(),3),1);
        coordinator.publish("SOLUSDT",state(MarketProfile.sol(),70),1);
        for(MarketRuntime runtime:coordinator.runtimes().values()){
            runtime.observedSignals.addLast(new Object());runtime.resetDiagnosticsPreservingActivePlan();
            assertTrue(runtime.hasActivePlan());assertEquals(1,runtime.observedSignals.size());
            assertSame(runtime.activePlan,runtime.observedSignals.peekFirst());
        }
    }

    @Test public void terminalOnOneSymbolLeavesOtherPlanAndNotificationIdentityUntouched() {
        MultiMarketCoordinator coordinator=new MultiMarketCoordinator(MarketRegistry.production());
        ActivePlanState eth=state(MarketProfile.eth(),3),sol=state(MarketProfile.sol(),70);
        coordinator.publish("ETHUSDT",eth,1);coordinator.publish("SOLUSDT",sol,1);
        assertNotEquals(eth.notificationId,sol.notificationId);
        assertTrue(coordinator.terminal("ETHUSDT","TP_TOUCHED",2));
        assertTrue(coordinator.runtime("SOLUSDT").hasActivePlan());
        assertEquals(sol.notificationId,coordinator.runtime("SOLUSDT").activePlan.notificationId);
    }

    @Test public void p02ConfirmationDoesNotAdvanceLastP01Timestamp() {
        long now=3_600_000L;MarketRuntime runtime=new MarketRuntime(MarketProfile.sol());
        runtime.lastP01ConfirmedAt=1_234L;runtime.last=75.08;runtime.bid=75.07;runtime.ask=75.08;
        runtime.lastTickerAt=now;
        for(int i=0;i<60;i++){
            double close=75+i*.001;
            if(i==56)close=75.056;if(i==57)close=75.060;if(i==58)close=75.068;if(i==59)close=75.080;
            runtime.candles.addLast(bar(i*60_000L,close,close+.01,close-.01,close,1));
        }
        runtime.aggTrades.addLast(new MarketRuntime.AggTrade(1,now-1_000,75.08,.2,false));
        SharedReferenceContext btc=new SharedReferenceContext();btc.last=60_000;btc.bid=59_999;btc.ask=60_001;btc.lastTickerAt=now;
        SignalDecision seed=SignalDecision.signal(runtime.profile,"LONG","v2.34 P02_CONTINUATION",80,3,
                75.08,75.20,75.02,.12,.06,"P02",true,75,75.08,.08);
        runtime.observedSignals.addLast(new MarketPlanOrchestrator.RuntimeCandidate(seed,
                CandidateLifecycle.SLEEVE_P02,now-20_001));
        MarketPlanOrchestrator.Event event=new MarketPlanOrchestrator().evaluate(runtime,btc,now,true,true);
        assertEquals("CONFIRMED",event.type);
        assertEquals(CandidateLifecycle.SLEEVE_P02,event.fill.sleeve);
        assertEquals(1_234L,runtime.lastP01ConfirmedAt);
        assertEquals(1_234L,event.plan.lastP01ConfirmedAt);
    }

    private static Fixture fixture(){return new Fixture();}
    private static MarketAdmissionPolicy.Context context(boolean mf,boolean bf,boolean active,
            boolean rearm,boolean opposite,boolean duplicate,boolean tombstone,boolean consumed){
        return new MarketAdmissionPolicy.Context(mf,bf,active,rearm,opposite,duplicate,tombstone,consumed);}
    private static MarketRuntime.MarketBar bar(long at,double o,double h,double l,double c,double v){return new MarketRuntime.MarketBar(at,o,h,l,c,v);}
    private static MarketProfile fake(){return MarketProfile.builder("TESTUSDT","TEST","TEST_V1").referencePrice(10).priceTick(.01).quantity(1,1,20).researchCandidate(true).adaptivePriceScale(true).detection(.01,.02,.02).stops(.02,.10).targets(.05,.20).p02Seed(.05,.03).revalidation(.01,.02).lateDistances(.02,.02).costs(.01,.02).riskBudgets(1,2).qualityBudgets(1,1.2,1.4,1.6,2).staleReasonCode("TEST_STALE").build();}
    private static SignalDecision signal(MarketProfile p,String side){boolean l="LONG".equals(side);return SignalDecision.signal(p,side,"CONTINUATION",90,3,75.8,l?75.92:75.68,l?75.77:75.83,.12,.03,"",true,75,76,1);}
    private static MarketSnapshot snapshot(MarketProfile p,String side){int d="LONG".equals(side)?1:-1;return MarketSnapshot.builder(100_000).market(p,75.8,75.79,75.80).btc(60_000,59_999,60_001).candleCounts(60,60).averages(.02,1).movement(d*.012,d*.024,d*.030,76,75).move15(d*.04).flowWindows(d*.1,d*.1,d*.1,d*.1).professionalFeatures(1,.5,.5,.2,.2,.2,.2,.2,.2,.6,1.2,1.5,0,0,.1,.1,0).build();}
    private static ActivePlanState state(MarketProfile p,int quantity){double entry=MarketProfile.ETH_SYMBOL.equals(p.symbol)?1900:75.8;String signature=p.symbol+"|x";return ActivePlanState.builder().market(p).side("LONG").family("CONTINUATION · P01").reasonCode("OK").reasonText("active").score(90).quantity(quantity).prices(entry,entry+p.targetFloorReference,entry-p.stopMinimumReference).risk(p.targetFloorReference,p.stopMinimumReference).times(1,1,1).notification(signature,3000+Math.floorMod(signature.hashCode(),1_000_000)).lastMarket(entry,entry-.01,entry+.01,p.aMinimumReference).lastP01ConfirmedAt(1).movement("",true,entry,entry,0).unitRisk(p.resultRoundTripCostReference,p.riskExecutionAllowanceReference,p.finalRiskBudgetUsdt,10).build();}

    private static final class Fixture {
        final MarketRegistry registry=new MarketRegistry(Arrays.asList(MarketProfile.eth(),MarketProfile.sol(),fake()));
        final MultiMarketCoordinator coordinator=new MultiMarketCoordinator(registry);
        int mirrorBooks;double mirrorLast;
        final MarketDataRouter router;
        Fixture(){Map<String,MarketDataRouter.LegacyMirror> mirrors=new LinkedHashMap<>();mirrors.put("ETHUSDT",new MarketDataRouter.LegacyMirror(){@Override public void onBookTicker(double last,double bid,double ask,long now){mirrorBooks++;mirrorLast=last;}});router=new MarketDataRouter(registry,coordinator,mirrors);}
    }
}
