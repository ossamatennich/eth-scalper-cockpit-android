package com.ethscalper.cockpit;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

public class CausalCaptureExportTest {
    @Test public void v2ManifestProvesFlowDepthSymbolsSourcesAndIntegrity()throws Exception{
        Path directory=Files.createTempDirectory("causal-export-v2");CausalCaptureStore store=
                new CausalCaptureStore(directory.toFile(),"v2",4,64*1024);store.appendBatch(List.of(
                MicrostructureMarketRecord.session("s",1,1_000,1,"RESEARCH_WS","START"),
                MicrostructureMarketRecord.flow100("s",2,1_150,2,"ETHUSDT","RESEARCH_WS",
                        1_100,1_110,1_150,1,1,900,900,1,0,100,100,100,100,1,0,100,0),
                MicrostructureMarketRecord.depth20("s",3,1_200,3,"ETHUSDT","RESEARCH_WS",
                        1_190,1_195,1,2,0,levels(100,false),levels(101,true))));
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){ByteArrayOutputStream bytes=
                new ByteArrayOutputStream();DiagnosticStreamingExporter.export(bytes,empty(directory,
                "events.jsonl"),empty(directory,"frames.jsonl"),smallEntries(),"2.34.5.1",2_000,
                new DiagnosticStreamingExporter.ExportSnapshotMetadata(1_500,"v2",true,"FULL","sha"),
                snapshot.files,null,()->false);Map<String,String> zip=entries(bytes.toByteArray());
            JSONObject manifest=new JSONObject(zip.get("causal_market_manifest.json"));
            assertEquals(MicrostructureMarketRecord.SCHEMA,manifest.getString("schema"));
            assertEquals(2,manifest.getInt("formatVersion"));assertTrue(manifest.getBoolean(
                    "usableForMicrostructureResearch"));assertEquals(1,manifest.getJSONObject(
                    "recordCountByKind").getInt("FLOW_100MS"));assertEquals(2,manifest.getJSONObject(
                    "recordCountBySymbol").getInt("ETHUSDT"));assertEquals(3,manifest.getJSONObject(
                    "recordCountBySource").getInt("RESEARCH_WS"));String stream=
                    zip.get("causal_market_stream.jsonl");assertTrue(stream.contains("\"bids\":[["));
            assertFalse(stream.contains("NaN"));assertFalse(stream.contains("Infinity"));}}
    @Test public void snapshotStreamsEveryRecordAndPublishesBoundedManifest()throws Exception{
        Path directory=Files.createTempDirectory("causal-export-store");
        CausalCaptureStore store=new CausalCaptureStore(directory.toFile(),"export",4,64*1024);
        store.appendBatch(List.of(
                CausalMarketRecord.session("session-1",1,1_000,10,"FUTURES_WS","CONNECTED"),
                CausalMarketRecord.quote("session-1",2,2_000,20,"ETHUSDT","FUTURES_WS",
                        1_995,1_996,12,1_900.0,4.0,1_900.01,5.0),
                CausalMarketRecord.flow("session-1",3,3_000,30,"SOLUSDT","FUTURES_WS",
                        2_000,3_000,-1,-1,0,0,0,0,false,
                        0,0,0,0,0,0,0,0)));
        try(CausalCaptureStore.Snapshot snapshot=store.checkpoint()){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DiagnosticStreamingExporter.Result result=DiagnosticStreamingExporter.export(bytes,
                    empty(directory,"events.jsonl"),empty(directory,"frames.jsonl"),smallEntries(),
                    "test",4_000,new DiagnosticStreamingExporter.ExportSnapshotMetadata(
                            3_000,"capture-request",true,"FULL","status-sha"),snapshot.files,
                    null,()->false);
            Map<String,String> zip=entries(bytes.toByteArray());
            assertEquals(DiagnosticStreamingExporter.ENTRY_NAMES.size(),zip.size());
            assertEquals(DiagnosticStreamingExporter.ENTRY_NAMES,result.names);
            assertTrue(zip.containsKey("causal_market_stream.jsonl"));
            assertTrue(zip.containsKey("causal_market_manifest.json"));
            String[] lines=zip.get("causal_market_stream.jsonl").strip().split("\\R");
            assertEquals(3,lines.length);
            assertEquals("SESSION",new JSONObject(lines[0]).getString("kind"));
            assertEquals("ETHUSDT",new JSONObject(lines[1]).getString("symbol"));
            JSONObject flow=new JSONObject(lines[2]);
            assertEquals("FLOW_1S",flow.getString("kind"));
            assertFalse(flow.getBoolean("hasTrades"));assertTrue(flow.isNull("vwap"));
            JSONObject manifest=new JSONObject(zip.get("causal_market_manifest.json"));
            assertEquals(CausalMarketRecord.SCHEMA,manifest.getString("schema"));
            assertEquals(3,manifest.getLong("recordCount"));
            assertEquals(1,manifest.getInt("sourceFileCount"));
            assertTrue(manifest.getLong("sourceBytes")>0);
            assertEquals(0,manifest.getInt("corruptBlocks"));
            assertEquals(0,manifest.getInt("truncatedTails"));
            assertTrue(manifest.getBoolean("strictCrc"));
            assertFalse(zip.get("causal_market_stream.jsonl").contains("NaN"));
            assertFalse(zip.get("causal_market_stream.jsonl").contains("Infinity"));
            String exportManifest=zip.get("export_manifest.json");
            assertTrue(exportManifest.contains("causal_market_stream.jsonl"));
            assertTrue(exportManifest.contains("causal_market_manifest.json"));
        }
    }

    @Test public void legacyExporterOverloadKeepsContractWithEmptyCausalDataset()throws Exception{
        Path directory=Files.createTempDirectory("causal-export-legacy");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DiagnosticStreamingExporter.export(bytes,empty(directory,"events.jsonl"),
                empty(directory,"frames.jsonl"),smallEntries(),"test",1,null,()->false);
        Map<String,String> zip=entries(bytes.toByteArray());
        assertEquals("",zip.get("causal_market_stream.jsonl"));
        JSONObject manifest=new JSONObject(zip.get("causal_market_manifest.json"));
        assertEquals(0,manifest.getLong("recordCount"));
        assertEquals(0,manifest.getInt("sourceFileCount"));
        assertEquals(0,manifest.getLong("sourceBytes"));
    }

    @Test public void activityReleasesPinnedSnapshotBeforeOptionalReset()throws Exception{
        Path activity=Path.of("src/main/java/com/ethscalper/cockpit/MainActivity.java");
        if(!Files.exists(activity))activity=Path.of("app").resolve(activity);
        String source=new String(Files.readAllBytes(activity),StandardCharsets.UTF_8);
        int method=source.indexOf("private void startDiagnosticExport");
        int release=source.indexOf("MarketWatchService.releaseCausalCaptureSnapshot();snapshotReleased=true",method);
        int reset=source.indexOf("MarketWatchService.ACTION_RESET_DIAGNOSTICS",method);
        assertTrue(release>method);assertTrue(reset>release);
        assertTrue(source.substring(method,reset).contains("MarketWatchService.causalCaptureSnapshotFiles()"));
        assertTrue(source.contains("if(!usableSnapshot(snapshot)){MarketWatchService.releaseCausalCaptureSnapshot()"));
    }

    private static File empty(Path directory,String name)throws Exception{
        Path value=directory.resolve(name);Files.write(value,new byte[0]);return value.toFile();}
    private static Map<String,String> smallEntries(){LinkedHashMap<String,String> out=new LinkedHashMap<>();
        for(String name:List.of("status.json","markets.json","active_plans.json","profiles_manifest.json",
                "market_summary.json","market_summary.txt","feed_health.json","health_check.txt","instructions.txt"))
            out.put(name,name.endsWith(".json")?"{}":"");return out;}
    private static Map<String,String> entries(byte[] bytes)throws Exception{
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){assertNull("duplicate ZIP entry",out.put(entry.getName(),
                    new String(zip.readAllBytes(),StandardCharsets.UTF_8)));}}
        return out;}
    private static double[][] levels(double start,boolean ascending){double[][] out=new double[20][2];
        for(int i=0;i<20;i++){out[i][0]=start+(ascending?i:-i)*.01;out[i][1]=i+1;}return out;}
}
