package com.ethscalper.cockpit;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class MultiMarketDiagnosticsTest {
    @Test public void acceptedAndRejectedSolAdmissionsAreExportable() {
        MarketRuntime r=new MarketRuntime(MarketProfile.sol());SignalDecision s=signal(r.profile,"LONG");
        MarketSnapshot snap=snapshot(r.profile,"LONG");
        r.recorder.record(1,"ADMISSION_ACCEPTED",MarketAdmissionPolicy.ACCEPTED,"accepted",
                "STRUCTURAL_SHARED",MarketAdmissionPolicy.MARKET_REPLAY_UNAVAILABLE,"P01",s,snap,
                0,true,true,0,Collections.emptyMap());
        r.recorder.record(2,"ADMISSION_REJECTED",MarketAdmissionPolicy.FLOW_CONFLICT,"rejected",
                "STRUCTURAL_SHARED",MarketAdmissionPolicy.MARKET_REPLAY_UNAVAILABLE,"P01",s,snap,
                1,true,true,0,Collections.emptyMap());
        assertEquals("ADMISSION_ACCEPTED",r.recorder.eventMaps().get(0).get("eventType"));
        assertEquals(MarketAdmissionPolicy.FLOW_CONFLICT,r.recorder.eventMaps().get(1).get("reasonCode"));
    }

    @Test public void unavailableHistoricalModelIncludesSymbolProfileAndClassification() {
        MarketRuntime r=new MarketRuntime(MarketProfile.sol());
        Map<String,Object> e=r.recorder.record(1,"HISTORICAL_DIAGNOSTIC",
                MarketAdmissionPolicy.MARKET_REPLAY_UNAVAILABLE,"unavailable",
                "ETH_HISTORICAL_ONLY",MarketAdmissionPolicy.MARKET_REPLAY_UNAVAILABLE,"P01",
                signal(r.profile,"LONG"),snapshot(r.profile,"LONG"),0,true,true,0,
                Collections.emptyMap()).values;
        assertEquals("SOLUSDT",e.get("symbol"));assertEquals("SOL_V1_20260727",e.get("profileVersion"));
        assertEquals("ETH_HISTORICAL_ONLY",e.get("classification"));
    }

    @Test public void solP01AndP02WithOlsAndAllFlowsAreRecorded() {
        MarketRuntime r=new MarketRuntime(MarketProfile.sol());MarketSnapshot snap=snapshot(r.profile,"LONG");
        r.recorder.record(1,"CANDIDATE_CREATED","P01","p01","STRUCTURAL_SHARED","","P01",
                signal(r.profile,"LONG"),snap,10,true,true,.01,Map.of("earlyP01Mode","STRUCTURE_LED",
                        "earlyP01StabilityMs",1000,"earlyP01ReasonCode","EARLY_OK"));
        r.recorder.record(2,"P02_OLS60","P02_OK","p02","STRUCTURAL_SHARED","","P02",
                signal(r.profile,"LONG"),snap,21_000,true,true,.01,Map.of("p02Mode","TREND",
                        "olsCount",60,"olsSlope",.01,"olsT60",3.0,"p02ReasonCode","P02_OK"));
        Map<String,Object> p01=r.recorder.eventMaps().get(0),p02=r.recorder.eventMaps().get(1);
        assertEquals("P01",p01.get("sleeve"));assertEquals("P02",p02.get("sleeve"));
        assertEquals(60,p02.get("olsCount"));assertNotNull(p02.get("f15"));
        assertNotNull(p02.get("f30"));assertNotNull(p02.get("f60"));assertNotNull(p02.get("f120"));
    }

    @Test public void solPlanConfirmationAndTpSlAreRecordedAsTradesAndTerminalsOnly() {
        MarketRuntime r=new MarketRuntime(MarketProfile.sol());SignalDecision s=signal(r.profile,"LONG");
        MarketSnapshot snap=snapshot(r.profile,"LONG");Map<String,Object> risk=Map.of(
                "riskBudgetUsdt",14.55,"resultCostPerUnit",.06,"riskAllowancePerUnit",.10,
                "theoreticalMaximumLoss",14.0);
        r.recorder.record(1,"PLAN_CONFIRMED","CONFIRMED","confirmed","STRUCTURAL_SHARED","","P01",s,snap,20_000,true,true,.01,risk);
        r.recorder.record(2,"TP_TOUCHED","TP_TOUCHED","tp","STRUCTURAL_SHARED","","P01",s,snap,0,true,true,0,risk);
        r.recorder.record(3,"SL_TOUCHED","SL_TOUCHED","sl","STRUCTURAL_SHARED","","P01",s,snap,0,true,true,0,risk);
        assertEquals(1,r.recorder.summary().get("confirmedTrades"));
        assertEquals("TP_TOUCHED",r.recorder.eventMaps().get(1).get("terminalStatus"));
        assertEquals("SL_TOUCHED",r.recorder.eventMaps().get(2).get("terminalStatus"));
    }

    @Test public void pendingAndRejectedCandidatesNeverCountAsTrades() {
        MarketRuntime r=new MarketRuntime(MarketProfile.sol());SignalDecision s=signal(r.profile,"SHORT");
        r.recorder.record(1,"CANDIDATE_CREATED","PENDING","pending","STRUCTURAL_SHARED","","P01",s,snapshot(r.profile,"SHORT"),0,true,true,0,Collections.emptyMap());
        r.recorder.record(2,"ADMISSION_REJECTED","REJECT","rejected","STRUCTURAL_SHARED","","P01",s,snapshot(r.profile,"SHORT"),1,true,true,0,Collections.emptyMap());
        assertEquals(0,r.recorder.summary().get("confirmedTrades"));
        assertEquals("",r.recorder.eventMaps().get(1).get("terminalStatus"));
    }

    @Test public void resetAndRestoreReinsertEthAndSolPlansWithoutMutation() {
        MultiMarketCoordinator c=new MultiMarketCoordinator(MarketRegistry.production());
        ActivePlanState eth=state(MarketProfile.eth(),3),sol=state(MarketProfile.sol(),70);
        c.publish("ETHUSDT",eth,1);c.publish("SOLUSDT",sol,1);
        for(MarketRuntime r:c.runtimes().values()){r.resetDiagnosticsPreservingActivePlan();
            assertEquals(2,r.recorder.eventMaps().size());assertTrue(r.hasActivePlan());
            assertEquals(r.profile.symbol,r.recorder.eventMaps().get(1).get("symbol"));}
        assertEquals(eth.notificationId,c.runtime("ETHUSDT").activePlan.notificationId);
        assertEquals(sol.notificationId,c.runtime("SOLUSDT").activePlan.notificationId);
    }

    @Test public void twoActivePlansAndDistinctNotificationIdsRemainExportable() {
        ActivePlanState eth=state(MarketProfile.eth(),3),sol=state(MarketProfile.sol(),70);
        List<Map<String,String>> plans=List.of(eth.toMap(),sol.toMap());
        assertEquals(2,plans.size());assertNotEquals(eth.notificationId,sol.notificationId);
        assertEquals("ETHUSDT",plans.get(0).get("symbol"));assertEquals("SOLUSDT",plans.get(1).get("symbol"));
    }

    @Test public void fakeThirdProfileRecordsAndExportsWithoutBranch() {
        MarketProfile fake=MarketProfile.builder("TESTUSDT","TEST","TEST_V1").referencePrice(10)
                .priceTick(.01).quantity(1,1,20).researchCandidate(true).adaptivePriceScale(true)
                .detection(.01,.02,.02).stops(.02,.10).targets(.05,.20).p02Seed(.05,.03)
                .revalidation(.01,.02).lateDistances(.02,.02).costs(.01,.02).riskBudgets(1,2)
                .qualityBudgets(1,1.2,1.4,1.6,2).staleReasonCode("TEST_STALE").build();
        MarketRuntime runtime=new MarketRuntime(fake);runtime.recorder.record(1,"RAW_DECISION","WAIT",
                "test","STRUCTURAL_SHARED",MarketAdmissionPolicy.MARKET_REPLAY_UNAVAILABLE,"",null,null,
                0,true,true,0,Collections.emptyMap());
        assertEquals("TESTUSDT",runtime.recorder.eventMaps().get(0).get("symbol"));
    }

    @Test public void completeZipContractUsesOnlyCurrentVersion() {
        assertEquals(19,DiagnosticExportContract.REQUIRED_FILES.size());
        assertTrue(DiagnosticExportContract.REQUIRED_FILES.contains("market_diagnostics.json"));
        assertTrue(DiagnosticExportContract.REQUIRED_FILES.contains("persistent_market_events.jsonl"));
        assertEquals("ETH_SOL_Scalper_Diagnostic_v2_34_0_3_",
                DiagnosticExportContract.zipPrefix("2.34.0.3"));
        assertFalse(DiagnosticExportContract.instructions("2.34.0.3").contains("v2.34.0\n"));
    }

    @Test public void applicationExporterContainsEveryRequiredZipEntry() throws Exception {
        String source=new String(Files.readAllBytes(Path.of("src/main/java/com/ethscalper/cockpit/MainActivity.java")),StandardCharsets.UTF_8);
        for(String file:DiagnosticExportContract.REQUIRED_FILES)
            assertTrue("missing "+file,source.contains("\""+file+"\""));
        assertTrue(source.contains("BuildConfig.VERSION_NAME"));
        assertTrue(source.contains("DiagnosticExportContract.rebuild("));
        assertFalse(source.contains("state.optJSONArray(\"marketDiagnostics\")"));
    }

    @Test public void publishedLifecycleStillHasOnlyTpAndSlTerminals() throws Exception {
        String source=new String(Files.readAllBytes(Path.of("src/main/java/com/ethscalper/cockpit/MarketPlanOrchestrator.java")),StandardCharsets.UTF_8);
        assertTrue(source.contains("TP_TOUCHED"));assertTrue(source.contains("SL_TOUCHED"));
        assertFalse(source.contains("SCENARIO_INVALIDATED"));assertFalse(source.contains("TIMEOUT_45M"));
    }

    private static SignalDecision signal(MarketProfile p,String side){boolean l="LONG".equals(side);
        return SignalDecision.signal(p,side,"CONTINUATION",90,3,75.8,l?75.92:75.68,
                l?75.77:75.83,.12,.03,"P01",true,75,76,1);}
    private static MarketSnapshot snapshot(MarketProfile p,String side){int d="LONG".equals(side)?1:-1;
        return MarketSnapshot.builder(100_000).market(p,75.8,75.79,75.80).btc(60_000,59_999,60_001)
                .candleCounts(60,60).averages(.02,1).movement(d*.012,d*.024,d*.030,76,75)
                .move15(d*.04).flowWindows(d*.04,d*.1,d*.2,d*.3)
                .professionalFeatures(1,.5,.5,.2,.2,.2,.2,.2,.2,.6,1.2,1.5,0,0,.1,.1,0).build();}
    private static ActivePlanState state(MarketProfile p,int q){double e=MarketProfile.ETH_SYMBOL.equals(p.symbol)?1900:75.8;
        String sig=p.symbol+"|diag";return ActivePlanState.builder().market(p).side("LONG")
                .family("CONTINUATION · P01").reasonCode("OK").reasonText("active").score(90)
                .quantity(q).prices(e,e+p.targetFloorReference,e-p.stopMinimumReference)
                .risk(p.targetFloorReference,p.stopMinimumReference).times(1,1,1)
                .notification(sig,3000+Math.floorMod(sig.hashCode(),1_000_000))
                .lastMarket(e,e-.01,e+.01,p.aMinimumReference).lastP01ConfirmedAt(1)
                .movement("",true,e,e,0).unitRisk(p.resultRoundTripCostReference,
                        p.riskExecutionAllowanceReference,p.finalRiskBudgetUsdt,10).build();}
}
