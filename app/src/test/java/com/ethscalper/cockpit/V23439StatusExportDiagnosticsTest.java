package com.ethscalper.cockpit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

public class V23439StatusExportDiagnosticsTest {
    @Test public void recursivelyNormalizesNonFiniteNestedAndUnknownValues()throws Exception{
        LinkedHashMap<String,Object> raw=new LinkedHashMap<>();raw.put("nan",Double.NaN);raw.put("positive",Double.POSITIVE_INFINITY);
        raw.put("negative",Double.NEGATIVE_INFINITY);raw.put("map",Map.of("list",List.of(1,2,3)));
        raw.put("array",new Object[]{"ok",Double.NaN});raw.put("unknown",new Object(){@Override public String toString(){return null;}});
        JSONObject state=new JSONObject(){@Override public java.util.Iterator<String> keys(){return raw.keySet().iterator();}
            @Override public Object opt(String key){return raw.get(key);}};
        SafeJsonNormalizer.Result result=SafeJsonNormalizer.normalizeAndSerialize(state);
        assertTrue(SafeJsonNormalizer.isValidObject(result.serialized));assertTrue(result.issues.size()>=5);
        assertTrue(result.value.isNull("nan"));assertTrue(result.value.isNull("positive"));assertTrue(result.value.isNull("negative"));
        assertEquals(3,result.value.getJSONObject("map").getJSONArray("list").length());assertTrue(result.value.isNull("unknown"));
    }

    @Test public void nullReturningJsonToStringCannotBreakSizeCalculation()throws Exception{
        JSONObject broken=new JSONObject(){@Override public String toString(){return null;}};broken.put("connected",true);
        assertTrue(StatusPayloadPolicy.sizeBytes(broken)>0);
    }

    @Test public void diagnosticDetailsCannotReintroduceNaN()throws Exception{
        MarketRuntime runtime=new MarketRuntime(MarketProfile.eth());Map<String,Object> details=new LinkedHashMap<>();
        details.put("entry",Double.NaN);details.put("nested",Map.of("bad",Double.POSITIVE_INFINITY));
        runtime.recorder.record(1,"ENGINE_DIAGNOSTIC","V230_NO_EDGE","m1=0.2","STRUCTURAL_SHARED","","",
                null,null,0,true,true,0,details);
        JSONObject encoded=new JSONObject(runtime.recorder.eventMaps().get(0));String text=encoded.toString();
        assertNotNull(text);assertTrue(new JSONObject(text).isNull("entry"));
        JSONArray issues=encoded.getJSONArray("normalizationIssues");assertTrue(issues.length()>=2);
        assertEquals("$.details.entry",issues.getJSONObject(0).getString("path"));
        assertEquals("NON_FINITE_NUMBER",issues.getJSONObject(0).getString("code"));
    }

    @Test public void lastValidStatusIsNeverReplacedByNullOrInvalid(){
        String valid="{\"connected\":true,\"markets\":{},\"referenceMarket\":{}}";
        assertEquals("FULL",StatusPublicationPolicy.select(valid,"{}","{}").mode);
        assertEquals("MINIMAL",StatusPublicationPolicy.select(null,valid,"{}").mode);
        assertEquals("LAST_VALID",StatusPublicationPolicy.select(null,"not-json",valid).mode);
    }

    @Test public void exportHandshakeAcceptsOnlyMatchingCompletedAckAtAnyDelay(){
        for(long delay:new long[]{50,500,5_000}){DiagnosticExportHandshake value=new DiagnosticExportHandshake("request",1_000,10_000);
            assertFalse(value.acknowledge("stale",true));assertFalse(value.acknowledge("request",false));
            assertTrue(value.acknowledge("request",true));assertFalse(value.timeout(1_000+delay));}
        DiagnosticExportHandshake timeout=new DiagnosticExportHandshake("request",1_000,2_000);
        assertFalse(timeout.timeout(2_999));assertTrue(timeout.timeout(3_000));assertFalse(timeout.acknowledge("request",true));
    }

    @Test public void blockedDiagnosticQueueBeyondTenSecondsExportsLastValid()throws Exception{
        DiagnosticExportHandshake blocked=new DiagnosticExportHandshake("blocked",1_000,10_000);
        assertFalse(blocked.acknowledge("blocked",false));
        assertFalse(blocked.timeout(10_999));assertTrue(blocked.timeout(11_001));
        File dir=Files.createTempDirectory("v23439-blocked-flush").toFile();
        File events=new File(dir,"events.jsonl"),frames=new File(dir,"frames.jsonl");
        Files.write(events.toPath(),new byte[0]);Files.write(frames.toPath(),new byte[0]);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        DiagnosticStreamingExporter.export(output,events,frames,Map.of("status.json","{\"connected\":true}"),
                "2.34.3.9",11_001,new DiagnosticStreamingExporter.ExportSnapshotMetadata(
                        11_001,"blocked",false,"LAST_VALID","status-sha"),null,()->false);
        JSONObject manifest=manifest(output.toByteArray());
        assertFalse(manifest.getBoolean("flushCompleted"));assertEquals("LAST_VALID",manifest.getString("statusMode"));
    }

    @Test public void variableNoEdgeTextCoalescesByStableIdentityWithoutLifecycleLoss(){
        DiagnosticEventCoalescer coalescer=new DiagnosticEventCoalescer();List<Map<String,Object>> emitted=new ArrayList<>();
        long start=1_000,baselineBytes=0;for(int i=0;i<3_000;i++){Map<String,Object> event=event("ENGINE_DIAGNOSTIC","V230_NO_EDGE",start+i*100,
                "m1="+i+" flow="+(i*.001));event.put("m1",i*.01);baselineBytes+=new JSONObject(event).toString().getBytes(StandardCharsets.UTF_8).length+1;
            emitted.addAll(coalescer.accept(event,start+i*100));}
        emitted.addAll(coalescer.flush(start+300_001));assertTrue(emitted.size()<=3);
        long syntheticBytes=0;for(Map<String,Object> item:emitted)syntheticBytes+=new JSONObject(item).toString().getBytes(StandardCharsets.UTF_8).length+1;
        System.out.println("V23439_COALESCENCE_BASELINE_BYTES="+baselineBytes);
        System.out.println("V23439_COALESCENCE_SYNTHETIC_BYTES="+syntheticBytes);
        System.out.println("V23439_COALESCENCE_REDUCTION_PERCENT="+(100.0-(100.0*syntheticBytes/baselineBytes)));
        Map<String,Object> summary=emitted.get(emitted.size()-1);assertTrue(((Number)summary.get("repeatCount")).longValue()>2_900);
        assertTrue(summary.containsKey("firstPayload"));assertTrue(summary.containsKey("lastPayload"));
        assertTrue(summary.containsKey("metricMinimums"));assertTrue(summary.containsKey("metricMaximums"));
        for(String type:List.of("CANDIDATE_CREATED","PLAN_CONFIRMED","PLAN_RESTORED","TP_TOUCHED","SL_TOUCHED",
                "SIGNAL_AUDIBLE_ALERT_POSTED","PERSISTENCE_FAILED","FEED_STATE_CHANGED"))
            assertFalse(DiagnosticEventCoalescer.coalescible(event(type,"CODE",1,"text")));
    }

    @Test public void exportManifestBindsEverySmallFileToOneAcknowledgedSnapshot()throws Exception{
        File dir=Files.createTempDirectory("v23439-export").toFile();File events=new File(dir,"events.jsonl"),frames=new File(dir,"frames.jsonl");
        Files.write(events.toPath(),new byte[0]);Files.write(frames.toPath(),new byte[0]);
        String status="{\"connected\":true,\"markets\":{\"ETHUSDT\":{\"last\":1900}},\"referenceMarket\":{\"last\":100000},\"activePlans\":[]}";
        Map<String,String> small=new LinkedHashMap<>();for(String name:List.of("status.json","markets.json","active_plans.json",
                "profiles_manifest.json","market_summary.json","market_summary.txt","feed_health.json","health_check.txt","instructions.txt"))small.put(name,name.equals("status.json")?status:"{\"snapshot\":1234}");
        ByteArrayOutputStream output=new ByteArrayOutputStream();DiagnosticStreamingExporter.export(output,events,frames,small,
                "2.34.3.9",2_000,new DiagnosticStreamingExporter.ExportSnapshotMetadata(1_234,"req-1",true,"FULL","abc"),null,()->false);
        String manifest="";try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null)if("export_manifest.json".equals(entry.getName()))manifest=new String(zip.readAllBytes(),StandardCharsets.UTF_8);}
        JSONObject value=new JSONObject(manifest);assertEquals(1_234,value.getLong("snapshotAt"));assertEquals("req-1",value.getString("requestId"));
        assertTrue(value.getBoolean("flushCompleted"));assertEquals("FULL",value.getString("statusMode"));assertEquals("abc",value.getString("statusSha256"));
    }

    @Test public void sourceUsesAckAndAddsSoundObservabilityWithoutTouchingMarketLogic()throws Exception{
        String activity=new String(Files.readAllBytes(java.nio.file.Path.of("src/main/java/com/ethscalper/cockpit/MainActivity.java")),StandardCharsets.UTF_8);
        String service=new String(Files.readAllBytes(java.nio.file.Path.of("src/main/java/com/ethscalper/cockpit/MarketWatchService.java")),StandardCharsets.UTF_8);
        assertFalse(activity.contains("Thread.sleep(150)"));assertTrue(activity.contains("DiagnosticExportHandshake"));
        assertTrue(service.contains("EXTRA_FLUSH_COMPLETED"));assertTrue(service.contains("soundResourceOpenable"));
        assertTrue(service.contains("alarmVolume"));assertTrue(service.contains("interruptionFilter"));
        assertTrue(service.contains("backgroundRestricted"));assertTrue(service.contains("batteryOptimizationExempt"));
        assertTrue(service.contains("postAudibleFinalSignalAlert"));assertTrue(service.contains("nmc_final_signal_loud_v2"));
        assertTrue(service.contains("boolean flushCompleted=flushDiagnosticsBlocking(10_000L)"));
        assertTrue(service.contains("requestId==null?\"\":requestId,false"));
        assertTrue(service.contains("requestId==null?\"\":requestId,true"));
    }

    @Test public void hotStatusReadsOnlyCachedSoundResourcePrimitives()throws Exception{
        String service=new String(Files.readAllBytes(java.nio.file.Path.of(
                "src/main/java/com/ethscalper/cockpit/MarketWatchService.java")),StandardCharsets.UTF_8);
        String hot=method(service,"private synchronized void broadcastStatus","private JSONObject marketStatusJson");
        String channel=method(service,"private JSONObject audibleChannelJson","private JSONObject audibleObservabilityJson");
        String observability=method(service,"private JSONObject audibleObservabilityJson",
                "/** Schedules the expensive sound-resource probe");
        for(String forbidden:List.of("openAssetFileDescriptor","MediaMetadataRetriever","probeAudibleSoundResource")){
            assertFalse(hot.contains(forbidden));assertFalse(channel.contains(forbidden));assertFalse(observability.contains(forbidden));
        }
        String probe=method(service,"private AudibleSoundResourceSnapshot probeAudibleSoundResource",
                "private void recordAudibleAlertDiagnostic");
        assertTrue(probe.contains("openAssetFileDescriptor"));assertTrue(probe.contains("MediaMetadataRetriever"));
        String scheduler=method(service,"private void scheduleAudibleSoundResourceProbe",
                "/** Runs only on nmc-diagnostic-io");
        assertTrue(scheduler.contains("diagnosticIoExecutor.execute"));
    }

    private static JSONObject manifest(byte[] zipBytes)throws Exception{try(ZipInputStream zip=new ZipInputStream(
            new ByteArrayInputStream(zipBytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)
            if("export_manifest.json".equals(entry.getName()))return new JSONObject(
                    new String(zip.readAllBytes(),StandardCharsets.UTF_8));}throw new AssertionError("manifest missing");}

    private static String method(String source,String startToken,String endToken){int start=source.indexOf(startToken);
        int end=source.indexOf(endToken,start+startToken.length());assertTrue(start>=0);assertTrue(end>start);
        return source.substring(start,end);}

    private static Map<String,Object> event(String type,String code,long at,String text){Map<String,Object> out=new LinkedHashMap<>();
        out.put("symbol","SOLUSDT");out.put("eventType",type);out.put("reasonCode",code);out.put("classification","STRUCTURAL_SHARED");
        out.put("sleeve","P01");out.put("side","LONG");out.put("family","CONTINUATION");out.put("reasonText",text);out.put("eventAt",at);return out;}
}
