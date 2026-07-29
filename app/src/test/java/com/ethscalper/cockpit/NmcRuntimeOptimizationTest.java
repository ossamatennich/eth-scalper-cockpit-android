package com.ethscalper.cockpit;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static org.junit.Assert.*;

public class NmcRuntimeOptimizationTest {
    @Test public void brandingIsNativeOnlyAndRegistryDriven()throws Exception{
        String activity=source("src/main/java/com/ethscalper/cockpit/MainActivity.java");
        String manifest=source("src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:label=\"NMC\""));
        assertTrue(activity.contains("Native Market Cockpit"));assertTrue(activity.contains("Multi-Market Research Engine"));
        assertTrue(activity.contains("MarketUiCatalog.cards(MarketRegistry.production())"));
        assertTrue(activity.contains("WindowInsetsCompat.Type.systemBars()"));assertTrue(activity.contains("WindowInsetsCompat.Type.displayCutout()"));
        assertTrue(activity.contains("WindowInsetsCompat.Type.ime()"));assertTrue(activity.contains("addNav(\"Cockpit\""));
        assertTrue(activity.contains("addNav(\"Plans\""));assertTrue(activity.contains("addNav(\"Diagnostic\""));assertTrue(activity.contains("addNav(\"Outils\""));
        assertTrue(activity.contains("dp(48)"));assertFalse(activity.contains("WebView"));assertFalse(activity.contains("android_asset"));
        assertFalse(activity.contains("ByteArrayOutputStream"));assertFalse(source("src/main/java/com/ethscalper/cockpit/DiagnosticStreamingExporter.java").contains("ByteArrayOutputStream"));
        assertFalse(activity.contains("statusRow"));assertFalse(activity.contains("GÉRER LE PLAN ACTIF"));assertFalse(activity.contains("Quantité ETH"));
        assertFalse(activity.contains("showLegacyCockpit"));assertFalse(activity.contains("buildLegacyFooter"));assertFalse(activity.contains("showingLegacyCockpit"));
        assertFalse(Files.exists(Path.of("src/main/assets/www")));assertTrue(Files.exists(Path.of("src/main/res/drawable/nmc_logo.xml")));
        String logo=source("src/main/res/drawable/nmc_logo.xml").toLowerCase();assertFalse(logo.contains("eth"));assertFalse(logo.contains("sol"));assertFalse(logo.contains("btc"));
        assertTrue(Files.exists(Path.of("src/main/res/drawable/ic_nmc_monochrome.xml")));assertTrue(Files.exists(Path.of("src/main/res/drawable/ic_stat_nmc.xml")));
    }

    @Test public void fakeThirdProfileAutomaticallyProducesUiCard(){MarketProfile fake=MarketProfile.builder("XRPUSDT","XRP","TEST")
            .referencePrice(1).priceTick(.01).quantity(1,1,10).researchCandidate(true).adaptivePriceScale(true)
            .detection(.01,.01,.01).stops(.01,.1).targets(.02,.2).p02Seed(.02,.01).revalidation(.01,.02)
            .lateDistances(.01,.01).costs(.01,.01).riskBudgets(1,2).qualityBudgets(1,1,1,1,1).staleReasonCode("STALE").build();
        MarketRegistry registry=new MarketRegistry(List.of(MarketProfile.eth(),MarketProfile.sol(),fake));
        List<MarketUiCatalog.CardDescriptor> cards=MarketUiCatalog.cards(registry);assertEquals(3,cards.size());assertEquals("XRPUSDT",cards.get(2).symbol);}

    @Test public void recorderIndexIsIncrementalAndHotPathHasZeroReads()throws Exception{Path dir=Files.createTempDirectory("nmc-index");
        File events=dir.resolve("events.jsonl").toFile(),frames=dir.resolve("frames.jsonl").toFile(),indexFile=dir.resolve("index.properties").toFile();
        PersistentRecorderIndex index=PersistentRecorderIndex.loadOrRebuild(indexFile,events,frames);
        for(int i=0;i<100;i++){Map<String,Object> event=event("RAW_DECISION","ETHUSDT",i*1000L);index.recordEvent(event,i*90L,0);}
        for(int i=0;i<50;i++)index.recordFrame(event("MARKET_FRAME",i%2==0?"ETHUSDT":"SOLUSDT",i*5000L),9000,i*100L);
        index.saveAtomic(indexFile);Map<String,Object> snapshot=index.snapshot();assertEquals(100L,snapshot.get("eventCount"));assertEquals(50L,snapshot.get("frameCount"));
        assertEquals(0L,((Number)snapshot.get("hotPathJsonlDiskReads")).longValue());long startup=((Number)snapshot.get("startupJsonlDiskReads")).longValue();
        for(int i=0;i<10_000;i++)index.snapshot();assertEquals(startup,((Number)index.snapshot().get("startupJsonlDiskReads")).longValue());}

    @Test public void eightHourStatusRemainsBelowOneHundredKilobytes(){MarketRegistry registry=MarketRegistry.production();MultiMarketCoordinator coordinator=new MultiMarketCoordinator(registry);
        long start=1_000_000L;for(int second=0;second<8*60*60;second++){long now=start+second*1000L;for(MarketRuntime runtime:coordinator.runtimes().values()){
            if(second%5==0)runtime.recorder.frame(now,null,null,true,true);if(second%17==0)runtime.recorder.record(now,"RAW_DECISION","NO_EDGE","No edge","STRUCTURAL_SHARED","","",null,null,0,true,true,0,Collections.emptyMap());}}
        Map<String,Object> state=new LinkedHashMap<>();state.put("connected",true);state.put("markets",Map.of("ETHUSDT",Map.of("last",1900),"SOLUSDT",Map.of("last",75)));
        state.put("activePlans",List.of());state.put("marketDiagnostics",new ArrayList<>(Collections.nCopies(20_000,"large")));StatusPayloadPolicy.compactMap(state,coordinator.runtimes().values());
        int bytes=StatusPayloadPolicy.sizeBytes(state);System.out.println("NMC_8H_STATUS_BYTES="+bytes);
        assertTrue(bytes<100_000);assertFalse(state.containsKey("marketDiagnostics"));}

    @Test public void tenThousandNoEdgeEventsAreCoalescedWithoutLifecycleLoss(){DiagnosticEventCoalescer value=new DiagnosticEventCoalescer();long start=1_000;
        List<Map<String,Object>> emitted=new ArrayList<>();for(int i=0;i<10_000;i++)emitted.addAll(value.accept(event("RAW_DECISION","ETHUSDT",start+i*720L),start+i*720L));emitted.addAll(value.flush(start+7_200_000L));
        System.out.println("NMC_10000_NO_EDGE_WRITES="+emitted.size());assertTrue(emitted.size()<=26);assertTrue(emitted.size()>=24);long repeats=0;for(Map<String,Object> item:emitted)if(item.get("repeatCount") instanceof Number)repeats+=((Number)item.get("repeatCount")).longValue();assertTrue(repeats>9_900);
        for(int i=0;i<100;i++)assertEquals(1,value.accept(event("PLAN_CONFIRMED","ETHUSDT",start+i),start+i).size());}

    @Test public void feedTransitionsDoNotSpam(){FeedObservabilityTracker value=new FeedObservabilityTracker();Map<String,Object> details=Map.of("lastTickerAgeMs",1L);
        assertFalse(value.observe("ETHUSDT","ETH","ETH",1,true,"WEBSOCKET",details).isEmpty());
        for(int i=2;i<1000;i++)assertTrue(value.observe("ETHUSDT","ETH","ETH",i,true,"WEBSOCKET",details).isEmpty());
        assertEquals("V23410_FEED_STALE_OBSERVED",value.observe("ETHUSDT","ETH","ETH",1001,false,"REST_FALLBACK",details).get("reasonCode"));}

    @Test public void framesRemainIndependentAtFiveSecondCadence(){MarketRuntime eth=new MarketRuntime(MarketProfile.eth()),sol=new MarketRuntime(MarketProfile.sol());
        assertTrue(eth.claimPersistentFrameSlot(10_000));assertFalse(eth.claimPersistentFrameSlot(14_999));assertTrue(eth.claimPersistentFrameSlot(15_000));
        assertTrue(sol.claimPersistentFrameSlot(12_000));assertFalse(sol.claimPersistentFrameSlot(16_999));assertTrue(sol.claimPersistentFrameSlot(17_000));}

    @Test public void streamingExportHasCanonicalUniqueEntriesAndBoundedBuffer()throws Exception{Path dir=Files.createTempDirectory("nmc-export");File events=dir.resolve("events.jsonl").toFile(),frames=dir.resolve("frames.jsonl").toFile();
        try(FileOutputStream out=new FileOutputStream(events)){out.write((json(event("CANDIDATE_CREATED","ETHUSDT",1))+"\n"+json(event("PLAN_CONFIRMED","SOLUSDT",2))+"\n").getBytes(StandardCharsets.UTF_8));}
        try(FileOutputStream out=new FileOutputStream(frames)){out.write((json(event("MARKET_FRAME","ETHUSDT",5))+"\n"+json(event("MARKET_FRAME","SOLUSDT",5))+"\n").getBytes(StandardCharsets.UTF_8));}
        Map<String,String> small=new LinkedHashMap<>();int n=0;for(String name:List.of("status.json","markets.json","active_plans.json","profiles_manifest.json","market_summary.json","market_summary.txt","feed_health.json","health_check.txt","instructions.txt"))small.put(name,"{\"entry\":"+(++n)+"}");
        ByteArrayOutputStream destination=new ByteArrayOutputStream();DiagnosticStreamingExporter.Result result=DiagnosticStreamingExporter.export(destination,events,frames,small,"2.34.1.0",10,null,()->false);
        assertEquals(8192,result.maximumBufferBytes);Set<String> names=new HashSet<>();Map<String,String> contents=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new java.io.ByteArrayInputStream(destination.toByteArray()))){ZipEntry entry;byte[] buffer=new byte[2048];while((entry=zip.getNextEntry())!=null){assertTrue("duplicate "+entry.getName(),names.add(entry.getName()));ByteArrayOutputStream bytes=new ByteArrayOutputStream();int read;while((read=zip.read(buffer))>=0)bytes.write(buffer,0,read);contents.put(entry.getName(),bytes.toString(StandardCharsets.UTF_8.name()));}}
        assertEquals(new HashSet<>(DiagnosticStreamingExporter.ENTRY_NAMES),names);assertTrue(names.contains("export_manifest.json"));
        assertTrue(contents.get("market_frames.jsonl").contains("ETHUSDT"));assertTrue(contents.get("market_frames.jsonl").contains("SOLUSDT"));assertFalse(contents.get("market_events.jsonl").contains("MARKET_FRAME"));
        Set<String> digests=new HashSet<>();for(DiagnosticStreamingExporter.EntryDigest digest:result.entries.values())assertTrue("strictly duplicated ZIP content",digests.add(digest.sha256));}

    @Test public void serviceHotStatusNeverReadsJsonl()throws Exception{String source=source("src/main/java/com/ethscalper/cockpit/MarketWatchService.java");int start=source.indexOf("private void broadcastStatus");int end=source.indexOf("private JSONObject marketStatusJson",start);String hot=source.substring(start,end);
        assertFalse(hot.contains("readChronological"));assertFalse(hot.contains("getPersistentObservationJournalJson"));assertTrue(hot.contains("recorderIndex.snapshot()"));}

    @Test public void sixtyFourMiBJournalExportsWithBoundedMemory()throws Exception{Path dir=Files.createTempDirectory("nmc-64m");File events=dir.resolve("events.jsonl").toFile(),frames=dir.resolve("frames.jsonl").toFile(),zipFile=dir.resolve("diagnostic.zip").toFile();
        String padding=String.join("",Collections.nCopies(900,"x"));byte[] line=(json(event("RAW_DECISION","ETHUSDT",1)).replace("No edge","No edge "+padding)+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(events)){long written=0;while(written<64L*1024L*1024L){out.write(line);written+=line.length;}}
        Map<String,String> small=new LinkedHashMap<>();int n=0;for(String name:List.of("status.json","markets.json","active_plans.json","profiles_manifest.json","market_summary.json","market_summary.txt","feed_health.json","health_check.txt","instructions.txt"))small.put(name,"{\"largeExportEntry\":"+(++n)+"}");
        DiagnosticStreamingExporter.Result result;try(FileOutputStream destination=new FileOutputStream(zipFile)){result=DiagnosticStreamingExporter.export(destination,events,frames,small,"2.34.1.0",10,null,()->false);}
        assertEquals(8192,result.maximumBufferBytes);assertTrue(zipFile.length()>0);assertTrue(events.length()>=64L*1024L*1024L);System.out.println("NMC_64M_EXPORT_ZIP_BYTES="+zipFile.length());}

    @Test public void historicalReplayArtifactsAreNotPartOfCurrentCandidate(){assertFalse(Files.exists(Path.of("src/test/resources/eth_v23321_golden_manifest.properties")));assertFalse(Files.exists(Path.of("../tools/validate_eth_v2331_replay.py")));}

    private static Map<String,Object> event(String type,String symbol,long at){LinkedHashMap<String,Object> out=new LinkedHashMap<>();out.put("symbol",symbol);out.put("asset",symbol.replace("USDT",""));out.put("profileVersion","TEST");out.put("eventAt",at);out.put("eventType",type);out.put("reasonCode","NO_EDGE");out.put("classification","STRUCTURAL_SHARED");out.put("sleeve","");out.put("side","");out.put("reasonText","No edge");return out;}
    private static String json(Map<String,Object> value){StringBuilder out=new StringBuilder("{");boolean first=true;for(Map.Entry<String,Object> e:value.entrySet()){if(!first)out.append(',');first=false;out.append('"').append(e.getKey()).append("\":");if(e.getValue() instanceof Number||e.getValue() instanceof Boolean)out.append(e.getValue());else out.append('"').append(String.valueOf(e.getValue())).append('"');}return out.append('}').toString();}
    private static String source(String path)throws Exception{return new String(Files.readAllBytes(Path.of(path)),StandardCharsets.UTF_8);}
}
