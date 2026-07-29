package com.ethscalper.cockpit;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class DiagnosticBoundsTest {
    @Test public void framesAreStrictlySeparatedFromLifecycleEvents() {
        MarketRuntime runtime=new MarketRuntime(MarketProfile.sol());MarketSnapshot snapshot=snapshot(runtime.profile);
        MarketDiagnosticRecorder.Record frame=runtime.recorder.frame(1,signal(runtime.profile),snapshot,true,true);
        runtime.recorder.record(2,"ADMISSION_ACCEPTED","OK","accepted","STRUCTURAL_SHARED","",
                "P01",signal(runtime.profile),snapshot,0,true,true,0,Collections.emptyMap());
        assertEquals(1,runtime.recorder.frameMaps().size());assertEquals(1,runtime.recorder.eventMaps().size());
        assertEquals("MARKET_FRAME",frame.values.get("eventType"));
        assertEquals("ADMISSION_ACCEPTED",runtime.recorder.eventMaps().get(0).get("eventType"));
        assertTrue(runtime.recorder.eventsAfter(0).stream().noneMatch(
                value->"MARKET_FRAME".equals(value.values.get("eventType"))));
    }

    @Test public void persistentEventJournalDefensivelyRejectsFrames() throws Exception {
        File dir=Files.createTempDirectory("market-events").toFile();File file=new File(dir,"events.jsonl");
        assertFalse(PersistentMarketLog.appendEvent(file,"MARKET_FRAME","{\"eventType\":\"MARKET_FRAME\"}"));
        assertTrue(PersistentMarketLog.appendEvent(file,"PLAN_CONFIRMED","{\"eventType\":\"PLAN_CONFIRMED\"}"));
        String value=PersistentMarketLog.readChronological(file);
        assertFalse(value.contains("MARKET_FRAME"));assertTrue(value.contains("PLAN_CONFIRMED"));
    }

    @Test public void frameCadenceIsAtMostOneEveryFiveSecondsPerSymbol() throws Exception {
        MarketRuntime runtime=new MarketRuntime(MarketProfile.sol());
        File file=new File(Files.createTempDirectory("market-frames").toFile(),"frames.jsonl");
        assertTrue(runtime.claimPersistentFrameSlot(10_000));PersistentMarketLog.appendFrame(file,"{\"at\":10000}");
        assertFalse(runtime.claimPersistentFrameSlot(14_999));
        assertTrue(runtime.claimPersistentFrameSlot(15_000));PersistentMarketLog.appendFrame(file,"{\"at\":15000}");
        assertFalse(runtime.claimPersistentFrameSlot(19_999));
        assertTrue(runtime.claimPersistentFrameSlot(20_000));PersistentMarketLog.appendFrame(file,"{\"at\":20000}");
        String[] lines=PersistentMarketLog.readChronological(file).trim().split("\\r?\\n");
        assertEquals(3,lines.length);assertTrue(lines[0].contains("10000"));
        assertTrue(lines[1].contains("15000"));assertTrue(lines[2].contains("20000"));
        assertEquals(5_000L,PersistentMarketLog.FRAME_INTERVAL_MS);
    }

    @Test public void twoHourEthSolStatusRemainsBounded() throws Exception {
        MarketRuntime eth=new MarketRuntime(MarketProfile.eth()),sol=new MarketRuntime(MarketProfile.sol());
        for(int second=0;second<7_200;second++){
            long at=1_000_000L+second*1_000L;eth.recorder.frame(at,signal(eth.profile),snapshot(eth.profile),true,true);
            sol.recorder.frame(at,signal(sol.profile),snapshot(sol.profile),true,true);
            if(second%60==0){eth.recorder.record(at,"ENGINE_DIAGNOSTIC","ETH_"+second,"minute",
                    "STRUCTURAL_SHARED","","P01",signal(eth.profile),snapshot(eth.profile),0,true,true,0,Collections.emptyMap());
                sol.recorder.record(at,"ENGINE_DIAGNOSTIC","SOL_"+second,"minute",
                        "STRUCTURAL_SHARED","","P02",signal(sol.profile),snapshot(sol.profile),0,true,true,0,Collections.emptyMap());}
        }
        Map<String,Object> state=new LinkedHashMap<>();state.put("connected",true);state.put("markets",new LinkedHashMap<>());
        List<Map<String,Object>> large=new ArrayList<>(eth.recorder.frameMaps());state.put("marketDiagnostics",large);
        state.put("marketCandidates",large);state.put("marketPlanHistory",large);
        state.put("multiMarketFrames",large);state.put("observedSignals",large);
        StatusPayloadPolicy.compactMap(state,List.of(eth,sol));int size=StatusPayloadPolicy.sizeBytes(state);
        System.out.println("TWO_HOUR_STATUS_BYTES="+size);
        assertTrue(size<StatusPayloadPolicy.MAX_STATUS_BYTES);assertTrue(size<100_000);
        for(String forbidden:StatusPayloadPolicy.FORBIDDEN_COLLECTIONS)assertFalse(state.containsKey(forbidden));
        assertTrue(((List<?>)state.get("diagnostics")).size()<=20);
    }

    @Test public void exportIsRebuiltFromPersistentEventsAndFrames() throws Exception {
        List<Map<String,Object>> events=new ArrayList<>();events.add(event("ADMISSION_ACCEPTED","SOLUSDT"));
        events.add(event("CANDIDATE_CREATED","SOLUSDT"));events.add(event("P02_OLS60","SOLUSDT"));
        events.add(event("PLAN_CONFIRMED","SOLUSDT"));events.add(event("PLAN_RESTORED","SOLUSDT"));
        events.add(event("TP_TOUCHED","SOLUSDT"));events.add(event("MARKET_FRAME","SOLUSDT"));
        List<Map<String,Object>> frames=List.of(event("MARKET_FRAME","SOLUSDT"));
        DiagnosticExportContract.ExportData out=DiagnosticExportContract.rebuild(events,frames);
        assertEquals(6,out.diagnostics.size());assertEquals(3,out.candidates.size());
        assertEquals(3,out.plans.size());assertEquals(1,out.frames.size());
        Map<String,Object> summary=out.summary.get("SOLUSDT");
        assertEquals(1,summary.get("confirmedTrades"));assertEquals(1,summary.get("restoredActivePlans"));
        assertEquals(1,summary.get("tp"));
    }

    @Test public void restoredPlanNeverInflatesConfirmedTradeCount() {
        MarketRuntime runtime=new MarketRuntime(MarketProfile.sol());SignalDecision signal=signal(runtime.profile);
        runtime.recorder.record(1,"PLAN_CONFIRMED","OK","confirmed","STRUCTURAL_SHARED","","P01",
                signal,snapshot(runtime.profile),0,true,true,0,Collections.emptyMap());
        runtime.recorder.record(2,"PLAN_RESTORED","OK","restored","STRUCTURAL_SHARED","","P01",
                signal,snapshot(runtime.profile),0,true,true,0,Collections.emptyMap());
        assertEquals(1,runtime.recorder.summary().get("confirmedTrades"));
        assertEquals(1,runtime.recorder.summary().get("restoredActivePlans"));
    }

    @Test public void engineDiagnosticsWithSameTimestampAndDifferentIdentitySurvive() {
        MarketRuntime runtime=new MarketRuntime(MarketProfile.sol());
        assertTrue(runtime.rememberEngineDiagnostic(10,"A","first"));
        assertTrue(runtime.rememberEngineDiagnostic(10,"B","second"));
        assertTrue(runtime.rememberEngineDiagnostic(10,"A","different message"));
        assertFalse(runtime.rememberEngineDiagnostic(10,"A","first"));
    }

    @Test public void rotationKeepsOnlyPreviousThenCurrentAndResetDeletesBoth() throws Exception {
        File dir=Files.createTempDirectory("market-rotation").toFile();File current=new File(dir,"events.jsonl");
        PersistentMarketLog.append(current,"{\"n\":1}",16);PersistentMarketLog.append(current,"{\"n\":2}",16);
        PersistentMarketLog.append(current,"{\"n\":3}",16);
        String ordered=PersistentMarketLog.readChronological(current);
        assertTrue(PersistentMarketLog.previous(current).exists());
        assertTrue(ordered.indexOf("\"n\":1")<ordered.indexOf("\"n\":3"));
        assertTrue(ordered.indexOf("\"n\":2")<ordered.indexOf("\"n\":3"));
        PersistentMarketLog.reset(current);assertFalse(current.exists());
        assertFalse(PersistentMarketLog.previous(current).exists());
        assertEquals(64L*1024L*1024L,PersistentMarketLog.MAX_CURRENT_BYTES);
    }

    @Test public void diagnosticMaintenanceCannotMutateTwoActivePlans() throws Exception {
        ActivePlanState eth=plan(MarketProfile.eth(),3),sol=plan(MarketProfile.sol(),70);
        int ethId=eth.notificationId,solId=sol.notificationId;double ethEntry=eth.entry,solEntry=sol.entry;
        File dir=Files.createTempDirectory("market-plan-safe").toFile();File file=new File(dir,"events.jsonl");
        PersistentMarketLog.append(file,"{\"eventType\":\"PLAN_CONFIRMED\"}",32);
        PersistentMarketLog.append(file,"{\"eventType\":\"PLAN_RESTORED\"}",32);
        PersistentMarketLog.readChronological(file);PersistentMarketLog.reset(file);
        assertEquals(ethId,eth.notificationId);assertEquals(solId,sol.notificationId);
        assertEquals(ethEntry,eth.entry,0);assertEquals(solEntry,sol.entry,0);
        assertEquals(3,eth.quantity);assertEquals(70,sol.quantity);
        assertEquals(eth.takeProfit,plan(MarketProfile.eth(),3).takeProfit,0);
        assertEquals(sol.stopLoss,plan(MarketProfile.sol(),70).stopLoss,0);
    }

    @Test public void serviceStatusAndExporterUseOnlyBoundedPersistentContracts() throws Exception {
        String service=new String(Files.readAllBytes(Path.of(
                "src/main/java/com/ethscalper/cockpit/MarketWatchService.java")),StandardCharsets.UTF_8);
        String activity=new String(Files.readAllBytes(Path.of(
                "src/main/java/com/ethscalper/cockpit/MainActivity.java")),StandardCharsets.UTF_8);
        for(String key:StatusPayloadPolicy.FORBIDDEN_COLLECTIONS)
            assertFalse(service.contains("state.put(\""+key+"\""));
        assertTrue(service.contains("StatusPayloadPolicy.compact(state"));
        assertTrue(activity.contains("DiagnosticStreamingExporter.export("));
        assertTrue(activity.contains("persistentEventsFile(this)"));
        assertTrue(activity.contains("persistentFramesFile(this)"));
        assertFalse(activity.contains("getPersistentObservationJournalJson(this)"));
        assertFalse(activity.contains("getPersistentMarketFramesJson(this)"));
    }

    private static Map<String,Object> event(String type,String symbol){Map<String,Object> out=new LinkedHashMap<>();
        out.put("symbol",symbol);out.put("asset",symbol.startsWith("SOL")?"SOL":"ETH");
        out.put("profileVersion","TEST");out.put("eventAt",1L);out.put("eventType",type);return out;}
    private static SignalDecision signal(MarketProfile profile){double entry=MarketProfile.ETH_SYMBOL.equals(profile.symbol)?1900:75.8;
        return SignalDecision.signal(profile,"LONG","CONTINUATION",90,3,entry,entry+profile.targetFloorReference,
                entry-profile.stopMinimumReference,profile.targetFloorReference,profile.stopMinimumReference,
                "P01",true,entry-1,entry+1,1);}
    private static MarketSnapshot snapshot(MarketProfile profile){double price=MarketProfile.ETH_SYMBOL.equals(profile.symbol)?1900:75.8;
        return MarketSnapshot.builder(100_000).market(profile,price,price-.01,price+.01)
                .btc(60_000,59_999,60_001).candleCounts(60,60).averages(profile.aMinimumReference,1)
                .movement(.1,.2,.3,price+1,price-1).move15(.4).flowWindows(.1,.2,.3,.4)
                .professionalFeatures(1,.5,.5,.2,.2,.2,.2,.2,.2,.6,1.2,1.5,0,0,.1,.1,0).build();}
    private static ActivePlanState plan(MarketProfile profile,int quantity){double entry=MarketProfile.ETH_SYMBOL.equals(profile.symbol)?1900:75.8;
        String signature=profile.symbol+"|stable";return ActivePlanState.builder().market(profile).side("LONG")
                .family("CONTINUATION · P01").reasonCode("OK").reasonText("active").score(90).quantity(quantity)
                .prices(entry,entry+profile.targetFloorReference,entry-profile.stopMinimumReference)
                .risk(profile.targetFloorReference,profile.stopMinimumReference).times(1,1,1)
                .notification(signature,3_000+Math.floorMod(signature.hashCode(),1_000_000))
                .lastMarket(entry,entry-.01,entry+.01,profile.aMinimumReference).lastP01ConfirmedAt(1)
                .movement("",true,entry,entry,0).unitRisk(profile.resultRoundTripCostReference,
                        profile.riskExecutionAllowanceReference,profile.finalRiskBudgetUsdt,10).build();}
}
