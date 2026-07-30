package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Regression coverage for the reset-dependent blank-feed failure. */
public final class MarketStatusIsolationTest {
    @Test public void liveStatusReadsOnlyBoundedDiagnosticTails() {
        MarketRuntime eth=new MarketRuntime(MarketProfile.eth());
        MarketRuntime sol=new MarketRuntime(MarketProfile.sol());
        for(int i=0;i<5_000;i++){
            record(eth,i,"ETH_"+i);record(sol,i,"SOL_"+i);
        }
        List<Map<String,Object>> recent=StatusPayloadPolicy.recentDiagnosticMaps(
                List.of(eth,sol),StatusPayloadPolicy.MAX_RECENT_DIAGNOSTICS);
        assertEquals(20,recent.size());
        assertEquals(0,eth.recorder.fullEventMapReads());
        assertEquals(0,sol.recorder.fullEventMapReads());
        assertEquals(1,eth.recorder.recentEventMapReads());
        assertEquals(1,sol.recorder.recentEventMapReads());
        assertEquals(4_990L,((Number)recent.get(0).get("eventAt")).longValue());
        assertEquals(4_999L,((Number)recent.get(19).get("eventAt")).longValue());
    }

    @Test public void summaryIsIncrementalAndRemainsExactAfterEvictionAndReset() {
        MarketRuntime runtime=new MarketRuntime(MarketProfile.sol());
        for(int i=0;i<MarketDiagnosticRecorder.MAX_EVENTS+25;i++)
            runtime.recorder.record(i,"CANDIDATE_REJECTED","R","rejected",
                    "STRUCTURAL_SHARED","","P01",null,null,0,true,true,0,
                    Collections.emptyMap());
        Map<String,Object> summary=runtime.recorder.summary();
        assertEquals(MarketDiagnosticRecorder.MAX_EVENTS,summary.get("events"));
        assertEquals(MarketDiagnosticRecorder.MAX_EVENTS,summary.get("candidates"));
        assertEquals(MarketDiagnosticRecorder.MAX_EVENTS,summary.get("rejectedCandidates"));
        assertEquals(0,runtime.recorder.fullEventMapReads());
        runtime.recorder.reset();
        summary=runtime.recorder.summary();
        assertEquals(0,summary.get("events"));assertEquals(0,summary.get("candidates"));
        assertEquals(0,summary.get("rejectedCandidates"));
    }

    @Test public void concurrentRecordingAndStatusTailReadsCannotLoseStatus() throws Exception {
        MarketRuntime runtime=new MarketRuntime(MarketProfile.eth());
        CountDownLatch start=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread writer=new Thread(()->run(start,failure,()->{
            for(int i=0;i<20_000;i++)record(runtime,i,"W"+i);
        }));
        Thread reader=new Thread(()->run(start,failure,()->{
            for(int i=0;i<20_000;i++){
                assertTrue(StatusPayloadPolicy.recentDiagnosticMaps(List.of(runtime),20).size()<=20);
                runtime.recorder.summary();
            }
        }));
        writer.start();reader.start();start.countDown();writer.join();reader.join();
        if(failure.get()!=null)throw new AssertionError(failure.get());
        assertEquals(MarketDiagnosticRecorder.MAX_EVENTS,runtime.recorder.summary().get("events"));
    }

    @Test public void serviceStatusIsSerializedCachedAndNeverSilentlyDiscarded() throws Exception {
        String service=new String(Files.readAllBytes(Path.of(
                "src/main/java/com/ethscalper/cockpit/MarketWatchService.java")),
                StandardCharsets.UTF_8);
        int start=service.indexOf("private synchronized void broadcastStatus");
        int end=service.indexOf("private JSONObject marketStatusJson",start);
        assertTrue(start>0&&end>start);String hot=service.substring(start,end);
        assertTrue(hot.contains("new JSONObject(LAST_MARKET_SUMMARY_JSON)"));
        assertTrue(hot.contains("new JSONObject(LAST_OBSERVATION_SUMMARY_JSON)"));
        assertTrue(hot.contains("new JSONObject(LAST_CALIBRATION_SUMMARY_JSON)"));
        assertFalse(hot.contains("marketRecorderSummaryJson()"));
        assertFalse(hot.contains("observationSummaryJson()"));
        assertFalse(hot.contains("calibrationSummaryJson()"));
        assertTrue(hot.contains("publishMinimalStatus(type,message,error,requestId,flushCompleted)"));
        assertTrue(service.contains("private synchronized void handleMessage"));
        assertTrue(service.contains("private void postMarketState(Runnable task)"));
        int frameStart=service.indexOf("private void recordMarketFrame");
        int frameEnd=service.indexOf("private String setupCandidateFor",frameStart);
        String frameHot=service.substring(frameStart,frameEnd);
        assertFalse(frameHot.contains("marketFramesJson()"));
        assertTrue(frameHot.contains("updateLegacyFrameCounters"));
    }

    @Test public void futuresPublicPrimaryRemainsTheFirstSource() {
        assertEquals("wss://fstream.binance.com",MarketFeedEndpointPool.webSocket(0).baseUrl);
        assertEquals(MarketFeedEndpointPool.FUTURES_PRIMARY,
                MarketFeedEndpointPool.webSocket(0).name);
        assertFalse(MarketFeedEndpointPool.webSocket(0).spotFallback);
    }

    private static void record(MarketRuntime runtime,long at,String code){
        runtime.recorder.record(at,"ENGINE_DIAGNOSTIC",code,"message",
                "STRUCTURAL_SHARED","","",null,null,0,true,true,0,
                Collections.emptyMap());
    }

    private static void run(CountDownLatch start,AtomicReference<Throwable> failure,Runnable task){
        try{start.await();task.run();}catch(Throwable error){failure.compareAndSet(null,error);}
    }
}
