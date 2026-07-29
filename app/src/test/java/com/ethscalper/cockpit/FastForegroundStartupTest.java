package com.ethscalper.cockpit;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

public class FastForegroundStartupTest {
    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(
                Path.of("src/main/java/com/ethscalper/cockpit/" + name)),
                StandardCharsets.UTF_8);
    }

    @Test public void foregroundNotificationPrecedesRecorderAndPersistenceWork() throws Exception {
        String service=source("MarketWatchService.java");
        int start=service.indexOf("@Override public void onCreate()");
        int end=service.indexOf("@Override public int onStartCommand",start);
        String onCreate=service.substring(start,end);
        int foreground=onCreate.indexOf("startForeground(");
        assertTrue(foreground>=0);
        assertTrue(foreground<onCreate.indexOf("PersistentRecorderIndex.loadFast"));
        assertTrue(foreground<onCreate.indexOf("restoreActiveFinalPlan"));
        assertFalse(onCreate.contains("migratePersistentFramesToSymbolAwareFormat()"));
        assertFalse(onCreate.contains("loadOrRebuild("));
    }

    @Test public void fastIndexLoaderNeverScansLargeJournals() throws Exception {
        Path dir=Files.createTempDirectory("nmc-fast-index");
        File events=dir.resolve("events.jsonl").toFile();
        File frames=dir.resolve("frames.jsonl").toFile();
        Files.write(events.toPath(),("{\"eventAt\":1,\"eventType\":\"PLAN_CONFIRMED\"}\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(frames.toPath(),("{\"eventAt\":2,\"eventType\":\"MARKET_FRAME\"}\n")
                .getBytes(StandardCharsets.UTF_8));
        PersistentRecorderIndex index=PersistentRecorderIndex.loadFast(
                dir.resolve("missing.properties").toFile(),events,frames);
        Map<String,Object> snapshot=index.snapshot();
        assertEquals(0L,snapshot.get("startupJsonlDiskReads"));
        assertEquals(events.length(),snapshot.get("eventFileBytes"));
        assertEquals(frames.length(),snapshot.get("frameFileBytes"));
    }

    @Test public void futuresPrimaryRemainsFirstAndAuthoritative() {
        assertEquals(MarketFeedEndpointPool.FUTURES_PRIMARY,
                MarketFeedEndpointPool.webSocket(0).name);
        assertEquals("wss://fstream.binance.com",
                MarketFeedEndpointPool.webSocket(0).baseUrl);
        assertFalse(MarketFeedEndpointPool.webSocket(0).spotFallback);
        assertFalse(MarketFeedEndpointPool.bookTickerEndpoints("ETHUSDT").get(0).spotFallback);
    }

    @Test public void startupFixDoesNotChangeTradingSafety() {
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
        assertTrue(SignalSafetyPolicies.isTerminalStatus("TP_TOUCHED"));
        assertTrue(SignalSafetyPolicies.isTerminalStatus("SL_TOUCHED"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT"));
    }
}
