package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class V23449CvCoreFullDiagnosticLifecycleTest {
    @Test public void openThenTpProducesExactlyOneEconomicTerminal() {
        Harness h=new Harness();PlanAndSignal value=plan("tp-episode",10_000);h.open(value,10_000);
        CvCorePlan.Terminal terminal=value.plan.observe(11_000,value.plan.takeProfit-.01,value.plan.takeProfit,true);
        assertTrue(h.terminal(value,terminal,11_000).recorded);assertTrue(h.terminal(value,terminal,11_001).duplicate);
        h.assertCounters(1,1,0);assertEquals(1,h.events("CV_CORE_TP_TOUCHED"));assertEquals(0,h.events("TP_TOUCHED"));
        assertEquals(value.plan.takeProfit,(Double)h.only("CV_CORE_TP_TOUCHED").get("fillPrice"),0);
        assertEquals(CvCorePlan.TP,h.only("CV_CORE_TP_TOUCHED").get("terminalStatus"));
    }

    @Test public void openThenSlProducesExactlyOneEconomicTerminal() {
        Harness h=new Harness();PlanAndSignal value=plan("sl-episode",20_000);h.open(value,20_000);
        CvCorePlan.Terminal terminal=value.plan.observe(21_000,value.plan.stopLoss-.01,value.plan.stopLoss,true);
        assertTrue(h.terminal(value,terminal,21_000).recorded);assertTrue(h.terminal(value,terminal,21_001).duplicate);
        h.assertCounters(1,0,1);assertEquals(1,h.events("CV_CORE_SL_TOUCHED"));assertEquals(0,h.events("SL_TOUCHED"));
        assertEquals(-1d,(Double)h.only("CV_CORE_SL_TOUCHED").get("resultR"),0);
    }

    @Test public void repeatedRefreshAfterTerminalCannotChangeCounters() {
        Harness h=new Harness();PlanAndSignal value=plan("refresh-episode",30_000);h.open(value,30_000);
        CvCorePlan.Terminal terminal=value.plan.observe(31_000,value.plan.takeProfit-.01,value.plan.takeProfit,true);
        for(int i=0;i<100;i++)h.terminal(value,terminal,31_000+i);h.assertCounters(1,1,0);
    }

    @Test public void restartWithActivePlanCountsOnlyItsFutureTerminal() {
        Harness before=new Harness();PlanAndSignal value=plan("active-restart",40_000);before.open(value,40_000);
        Harness after=new Harness();after.journal.restore(before.journal.rememberedKeys());
        assertTrue(after.open(value,40_100).duplicate);
        CvCorePlan.Terminal terminal=value.plan.observe(41_000,value.plan.takeProfit-.01,value.plan.takeProfit,true);
        assertTrue(after.terminal(value,terminal,41_000).recorded);
        after.assertCounters(0,1,0);assertEquals(0L,after.summary.snapshot(false).get("openings"));
    }

    @Test public void diagnosticResetPreservesPlanWithoutInventingEconomics() {
        Harness h=new Harness();PlanAndSignal value=plan("reset-active",50_000);h.open(value,50_000);
        h.recorder.reset();h.summary.reset();assertEquals(0,h.recorder.summary().get("confirmedTrades"));
        assertEquals(0,h.events("CV_CORE_PLAN_PERSISTED"));
        CvCorePlan.Terminal terminal=value.plan.observe(51_000,value.plan.takeProfit-.01,value.plan.takeProfit,true);
        h.terminal(value,terminal,51_000);h.assertCounters(0,1,0);
    }

    @Test public void restoredV23448PlanKeepsLegacySingleTerminalSemantics() {
        MarketDiagnosticRecorder recorder=new MarketDiagnosticRecorder(MarketProfile.eth());
        recorder.record(60_000,"PLAN_RESTORED",ActivePlanPersistence.RESTORED,"restored","", "","",
                null,null,0,true,true,0,Map.of("engineId","NMC_SCALP_ACTION_V1"));
        recorder.record(61_000,"TP_TOUCHED","TP_TOUCHED","terminal","", "","",
                null,null,0,true,true,0,Map.of("engineId","NMC_SCALP_ACTION_V1"));
        assertEquals(1,recorder.summary().get("restoredActivePlans"));assertEquals(1,recorder.summary().get("tp"));
        assertEquals(0,recorder.summary().get("confirmedTrades"));
    }

    @Test public void persistentJournalHasOneCanonicalOpenAndTerminal() {
        Harness h=new Harness();PlanAndSignal value=plan("persistent",70_000);h.open(value,70_000);
        CvCorePlan.Terminal terminal=value.plan.observe(71_000,value.plan.stopLoss-.01,value.plan.stopLoss,true);
        h.terminal(value,terminal,71_000);h.terminal(value,terminal,71_001);h.open(value,71_002);
        assertEquals(1,h.events("CV_CORE_PLAN_PERSISTED"));assertEquals(1,h.events("CV_CORE_SL_TOUCHED"));
        assertEquals(2,h.recorder.eventMaps().size());
    }

    @Test public void twoPlansProduceExactIndexExportAndMarketSummaryText() {
        Harness h=new Harness();PlanAndSignal first=plan("first",80_000),second=plan("second",90_000);
        h.open(first,80_000);h.terminal(first,first.plan.observe(81_000,first.plan.takeProfit-.01,first.plan.takeProfit,true),81_000);
        h.open(second,90_000);h.terminal(second,second.plan.observe(91_000,second.plan.stopLoss-.01,second.plan.stopLoss,true),91_000);
        h.assertCounters(2,1,1);
        PersistentRecorderIndex index=new PersistentRecorderIndex();for(Map<String,Object> event:h.recorder.eventMaps())index.recordEvent(event,0,0);
        Map<String,Object> summary=index.snapshot();assertEquals(2L,summary.get("confirmedTrades"));assertEquals(1L,summary.get("tp"));assertEquals(1L,summary.get("sl"));
        String text=MarketSummaryText.format((Long)summary.get("eventCount"),(Long)summary.get("frameCount"),
                (Long)summary.get("confirmedTrades"),(Long)summary.get("restoredActivePlans"),(Long)summary.get("tp"),(Long)summary.get("sl"));
        assertTrue(text.contains("Trades confirmés : 2"));assertTrue(text.contains("TP : 1"));assertTrue(text.contains("SL : 1"));
        DiagnosticExportContract.ExportData export=DiagnosticExportContract.rebuild(h.recorder.eventMaps(),List.of());
        Map<String,Object> eth=export.summary.get(MarketProfile.ETH_SYMBOL);assertEquals(2,eth.get("confirmedTrades"));assertEquals(1,eth.get("tp"));assertEquals(1,eth.get("sl"));
    }

    @Test public void serviceUsesCanonicalMethodsAndSkipsLegacyTerminalForCvEngine() throws Exception {
        String source=new String(Files.readAllBytes(Path.of("src/main/java/com/ethscalper/cockpit/MarketWatchService.java")),StandardCharsets.UTF_8);
        assertFalse(source.contains("persistObservedSignalEvent(item,\"CV_CORE_PLAN_PERSISTED\""));
        assertFalse(source.contains("CV_CORE_TERMINAL_ECONOMICS"));
        assertTrue(source.contains("if(!CvCorePolicy.ENGINE_ID.equals(item.engineId))"));
        assertTrue(source.contains("recordCvEconomicOpen(result,plan,item,snapshot,now,persistedDetails)"));
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
        CvCoreEconomicEventJournal.Result open(PlanAndSignal value,long at){return journal.recordOpen(at,null,value.plan,value.signal,value.snapshot,true,true,Map.of());}
        CvCoreEconomicEventJournal.Result terminal(PlanAndSignal value,CvCorePlan.Terminal terminal,long at){return journal.recordTerminal(at,value.plan,terminal,value.signal,value.snapshot,true,true);}
        int events(String type){int count=0;for(Map<String,Object> event:recorder.eventMaps())if(type.equals(event.get("eventType")))count++;return count;}
        Map<String,Object> only(String type){List<Map<String,Object>> matches=new ArrayList<>();for(Map<String,Object> event:recorder.eventMaps())if(type.equals(event.get("eventType")))matches.add(event);assertEquals(1,matches.size());return matches.get(0);}
        void assertCounters(int confirmed,int tp,int sl){Map<String,Object> s=recorder.summary();assertEquals(confirmed,s.get("confirmedTrades"));assertEquals(tp,s.get("tp"));assertEquals(sl,s.get("sl"));}
    }
    private static final class PlanAndSignal{final CvCorePlan plan;final SignalDecision signal;final MarketSnapshot snapshot;
        PlanAndSignal(CvCorePlan plan,SignalDecision signal,MarketSnapshot snapshot){this.plan=plan;this.signal=signal;this.snapshot=snapshot;}}
}
