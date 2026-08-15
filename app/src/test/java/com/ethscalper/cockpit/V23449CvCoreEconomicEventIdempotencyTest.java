package com.ethscalper.cockpit;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class V23449CvCoreEconomicEventIdempotencyTest {
    @Test public void completePublicationCreatesOneCanonicalOpenAndOneConfirmedTrade() {
        Harness h=new Harness();PlanAndSignal value=plan("episode-open",1_000);
        CvCoreEconomicEventJournal.Result result=h.journal.recordOpen(1_000,null,value.plan,
                value.signal,value.snapshot,true,true,Map.of("entryWindowStatus","VALIDE"));
        assertTrue(result.recorded);assertEquals(1,h.events("CV_CORE_PLAN_PERSISTED"));
        assertEquals(1,h.recorder.summary().get("confirmedTrades"));assertEquals(1L,h.summary.snapshot(true).get("openings"));
        Map<String,Object> event=result.record.values;for(String field:List.of("engineId","policyId","schema","routeId",
                "episodeId","signature","sourceType","sourceFamily","sourceSleeve","entry","tp","sl","quantity","A",
                "routeRiskBudget","resultCostPerUnit","plannedNetRR","theoreticalMaximumLoss","qualificationAt","entryValidUntil","persisted"))
            assertTrue(field,event.containsKey(field));
        assertEquals(Boolean.TRUE,event.get("persisted"));
    }

    @Test public void sameOpeningTwiceIsOneEventAndOneTrade() {
        Harness h=new Harness();PlanAndSignal value=plan("episode-duplicate",2_000);
        assertTrue(h.open(value,2_000).recorded);CvCoreEconomicEventJournal.Result duplicate=h.open(value,2_001);
        assertTrue(duplicate.duplicate);assertEquals(1,h.events("CV_CORE_PLAN_PERSISTED"));
        assertEquals(1,h.recorder.summary().get("confirmedTrades"));assertEquals(1L,h.summary.snapshot(true).get("openings"));
    }

    @Test public void alertRetryCannotRecreateOpen() {
        Harness h=new Harness();PlanAndSignal value=plan("episode-alert",3_000);h.open(value,3_000);
        Map<String,Object> alert=CvCoreTelemetry.alert(false,false);assertEquals(Boolean.TRUE,alert.get("retryScheduled"));
        h.open(value,3_001);h.open(value,3_002);assertEquals(1,h.events("CV_CORE_PLAN_PERSISTED"));
    }

    @Test public void rememberedKeysPreventReopenAfterRestart() {
        Harness first=new Harness();PlanAndSignal value=plan("episode-restart",4_000);first.open(value,4_000);
        Harness restarted=new Harness();restarted.journal.restore(first.journal.rememberedKeys());
        assertTrue(restarted.open(value,4_100).duplicate);assertEquals(0,restarted.events("CV_CORE_PLAN_PERSISTED"));
        assertEquals(0,restarted.recorder.summary().get("confirmedTrades"));
    }

    @Test public void boundedLedgerEvictsOldestDeterministically() {
        Harness h=new Harness();List<String> keys=new ArrayList<>();
        for(int i=0;i<CvCoreEconomicEventJournal.MAX_KEYS+1;i++)keys.add(
                CvCoreEconomicEventJournal.openKey(CvCorePolicy.ENGINE_ID,"signature-"+i));
        h.journal.restore(keys);assertEquals(CvCoreEconomicEventJournal.MAX_KEYS,h.journal.size());
        assertFalse(h.journal.rememberedKeys().contains(keys.get(0)));
        assertEquals(keys.get(keys.size()-1),h.journal.rememberedKeys().get(h.journal.size()-1));
    }

    @Test public void canonicalPayloadIsJsonSafe() {
        Harness h=new Harness();PlanAndSignal value=plan("episode-json",5_000);h.open(value,5_000);
        String json=new JSONObject(h.recorder.eventMaps().get(0)).toString();
        assertFalse(json.contains("NaN"));assertFalse(json.contains("Infinity"));
    }

    @Test public void canonicalEconomicEventsAreNeverCoalesced() {
        for(String type:List.of("CV_CORE_PLAN_PERSISTED","CV_CORE_TP_TOUCHED","CV_CORE_SL_TOUCHED"))
            assertFalse(type,DiagnosticEventCoalescer.coalescible(Map.of("eventType",type)));
    }

    private static PlanAndSignal plan(String episode,long at){
        MarketSnapshot snapshot=CvCoreTestFixtures.snapshot(1900,1900.01,1.2,0,0);
        CvCoreObservation observation=new CvCoreObservation(CvCorePolicy.RAW,"RANGE_FADE","",
                MarketProfile.ETH_SYMBOL,"SHORT",at,0,0,0,Double.NaN,snapshot,
                CvCoreTestFixtures.metrics(-.001,-.001,-.2,.1,0,0),CvCoreTestFixtures.common());
        CvCorePlan.BuildResult built=CvCorePlan.build(CvCorePolicy.DUAL_EXHAUSTION_SHORT,episode,
                "SHORT",CvCorePolicy.RAW,at,snapshot.marketBid,snapshot.marketAsk,1.2,observation);
        assertTrue(built.reasonCode,built.accepted());CvCorePlan plan=built.plan;
        SignalDecision signal=SignalDecision.confirmed(MarketProfile.eth(),plan.side,plan.route.family,
                plan.route.reasonCode,"CV Core",plan.route.score,plan.quantity,plan.entry,plan.takeProfit,
                plan.stopLoss,plan.targetDistance,plan.stopDistance,"CV_CORE",false,
                snapshot.marketLast,snapshot.marketLast,plan.targetDistance);
        return new PlanAndSignal(plan,signal,snapshot);
    }

    private static final class Harness{
        final MarketDiagnosticRecorder recorder=new MarketDiagnosticRecorder(MarketProfile.eth());
        final CvCoreSummary summary=new CvCoreSummary();
        final CvCoreEconomicEventJournal journal=new CvCoreEconomicEventJournal(recorder,summary);
        CvCoreEconomicEventJournal.Result open(PlanAndSignal value,long at){return journal.recordOpen(at,null,
                value.plan,value.signal,value.snapshot,true,true,Collections.emptyMap());}
        int events(String type){int count=0;for(Map<String,Object> event:recorder.eventMaps())
            if(type.equals(event.get("eventType")))count++;return count;}
    }
    private static final class PlanAndSignal{final CvCorePlan plan;final SignalDecision signal;final MarketSnapshot snapshot;
        PlanAndSignal(CvCorePlan plan,SignalDecision signal,MarketSnapshot snapshot){this.plan=plan;this.signal=signal;this.snapshot=snapshot;}}
}
