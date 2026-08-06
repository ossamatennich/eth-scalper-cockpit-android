package com.ethscalper.cockpit;

import org.junit.Test;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class V23442LegacyEthShadowBridgeTest {
    private static final long CREATED=100_000L;

    @Test public void legacyEthConfirmationIsObservedAfterPublicStateIsActive(){
        long now=CREATED+20_000;MarketProfile profile=MarketProfile.eth();
        MarketSnapshot snapshot=snapshot(now);SignalDecision source=candidate(profile,"P01_CONTINUATION",96);
        CandidateLifecycle.FillResult fill=CandidateLifecycle.processPendingCandidate(source,snapshot,true,
                CREATED,now,0,.50,false);
        assertTrue(fill.confirmed);
        MarketRuntime runtime=new MarketRuntime(profile);
        runtime.activePlan=ActivePlanState.builder().market(profile).side(fill.publishedSignal.side)
                .family(fill.publishedSignal.family).reasonCode(fill.publishedSignal.reasonCode)
                .reasonText(fill.publishedSignal.reasonText).score(fill.publishedSignal.score)
                .quantity(fill.publishedSignal.quantity).prices(fill.publishedSignal.entry,
                        fill.publishedSignal.takeProfit,fill.publishedSignal.stopLoss)
                .risk(fill.publishedSignal.targetMove,fill.publishedSignal.stopDistance)
                .times(CREATED,now,now).premium15m(false).notification("sig",1)
                .lastMarket(snapshot.marketLast,snapshot.marketBid,snapshot.marketAsk,snapshot.avgRange20)
                .lastP01ConfirmedAt(now).movement("",false,0,0,0).unitRisk(1.43,2.35,14.55,10).build();
        MarketWatchService.ObservedSignal item=new MarketWatchService.ObservedSignal(1,CREATED,
                source,snapshot.marketLast,snapshot);item.candidateSignature="eth-p01";
        item.adverseExcursion60=.50;
        new LegacyEthShadowBridge().observeConfirmation(runtime,item,source,snapshot,now,true,true,
                0,fill,Collections.emptyList());
        Map<String,Object> event=event(runtime,"SHADOW_AB_DECISION");
        assertEquals(MarketProfile.ETH_SYMBOL,event.get("symbol"));
        assertEquals(Boolean.TRUE,event.get("productionConfirmed"));
        assertEquals(Boolean.TRUE,event.get("productionActivePlan"));
        assertEquals(.50,((Number)event.get("E60")).doubleValue(),0);
        assertEquals(.50/Math.max(.35,snapshot.avgRange20),
                ((Number)event.get("eNormalized")).doubleValue(),1e-12);
        assertNotNull(event(runtime,"SHADOW_FEE_AWARE_SIZING"));
    }

    @Test public void failingBridgeIsFailOpenAndRecordsBoundedError(){
        long now=CREATED+1_000;MarketRuntime runtime=new MarketRuntime(MarketProfile.eth());
        MarketSnapshot snapshot=snapshot(now);SignalDecision source=candidate(MarketProfile.eth(),
                "P01_CONTINUATION",96);
        MarketWatchService.ObservedSignal item=new MarketWatchService.ObservedSignal(1,CREATED,
                source,snapshot.marketLast,snapshot);item.candidateSignature="same";
        String status=item.status;SignalDecision publicSignal=item.signal;
        LegacyEthShadowBridge bridge=new LegacyEthShadowBridge((operation,action)->{
            throw new IllegalStateException("deliberate "+operation);
        });
        bridge.observeCandidate(runtime,item,snapshot,now,true,true,false,0,false,
                Collections.emptyList());
        assertSame(publicSignal,item.signal);assertEquals(status,item.status);
        assertNull(runtime.activePlan);assertNull(runtime.lastSignal);assertEquals(0,runtime.lastTerminalAt);
        assertNotNull(event(runtime,"SHADOW_INTERNAL_ERROR"));
    }

    @Test public void ethAndSolConfirmationsUseTheSharedSchema(){
        long now=CREATED+20_000;MarketSnapshot snapshot=snapshot(now);
        CandidateLifecycle.FillResult fill=CandidateLifecycle.processPendingCandidate(
                candidate(MarketProfile.eth(),"P01_CONTINUATION",96),snapshot,true,CREATED,now,0,.2,false);
        assertTrue(fill.confirmed);
        int decisions=0;
        for(MarketProfile profile:List.of(MarketProfile.eth(),MarketProfile.eth(),MarketProfile.sol())){
            MarketRuntime runtime=new MarketRuntime(profile);
            ShadowObservationEngine engine=new ShadowObservationEngine();
            SignalDecision signal=candidate(profile,profile==MarketProfile.sol()?"P02_CONTINUATION":"P01_CONTINUATION",
                    profile==MarketProfile.sol()?80:96);
            ShadowObservationEngine.Candidate observed=new ShadowObservationEngine.Candidate(signal,
                    profile==MarketProfile.sol()?CandidateLifecycle.SLEEVE_P02:CandidateLifecycle.SLEEVE_P01,
                    profile.symbol+decisions,CREATED,.2,0,false,"");
            engine.safeObserveProductionConfirmation(new ShadowObservationEngine.Context(runtime,snapshot,now,
                    true,true,true,true,false,Collections.emptyList()),observed,fill,"");
            decisions+=(int)runtime.recorder.eventMaps().stream().filter(e->
                    "SHADOW_AB_DECISION".equals(e.get("eventType"))).count();
        }
        assertEquals(3,decisions);
    }

    private static Map<String,Object> event(MarketRuntime runtime,String type){return runtime.recorder
            .eventMaps().stream().filter(e->type.equals(e.get("eventType"))).findFirst().orElse(null);}
    private static SignalDecision candidate(MarketProfile p,String family,int score){return SignalDecision.signal(
            p,"LONG",family,score,3,100.01,102.81,98.66,2.80,1.35,"ACTIVE",true,98,102,4);}
    private static MarketSnapshot snapshot(long now){return MarketSnapshot.builder(now)
            .eth(100.01,100,100.01).btc(60_000,59_999,60_001).candleCounts(60,20)
            .averages(1,100).movement(.8,1.6,1.3,106,94).move15(.2).flow(.2,120)
            .flowWindows(.2,.2,.1,.1).professionalFeatures(12,1.2,.5,5.99,6.01,
                    5.99,6.01,0,0,0,0,0,0,0,0,0,0).build();}
}
