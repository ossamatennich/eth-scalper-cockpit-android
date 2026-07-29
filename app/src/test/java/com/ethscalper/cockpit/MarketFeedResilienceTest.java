package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class MarketFeedResilienceTest {
    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(
                Path.of("src/main/java/com/ethscalper/cockpit/" + name)),
                StandardCharsets.UTF_8);
    }

    @Test public void websocketPoolIsOrderedAndRegistryDriven() {
        MarketRegistry registry = MarketRegistry.production();
        assertEquals(3, MarketFeedEndpointPool.webSocketCount());
        assertEquals(MarketFeedEndpointPool.FUTURES_PRIMARY,
                MarketFeedEndpointPool.webSocket(0).name);
        assertEquals(MarketFeedEndpointPool.SPOT_PUBLIC_FALLBACK,
                MarketFeedEndpointPool.webSocket(2).name);
        String url = MarketFeedEndpointPool.combinedStreamUrl(0, registry);
        for (MarketProfile profile : registry.tradedMarkets()) {
            assertTrue(url.contains(profile.symbol.toLowerCase() + "@bookTicker"));
            assertTrue(url.contains(profile.symbol.toLowerCase() + "@kline_1m"));
            assertTrue(url.contains(profile.symbol.toLowerCase() + "@aggTrade"));
        }
        assertTrue(url.contains("btcusdt@bookTicker"));
    }

    @Test public void everyRestFeedHasPublicFallback() {
        for (String symbol : new String[]{"ETHUSDT", "SOLUSDT", "BTCUSDT"}) {
            List<MarketFeedEndpointPool.RestEndpoint> books =
                    MarketFeedEndpointPool.bookTickerEndpoints(symbol);
            List<MarketFeedEndpointPool.RestEndpoint> klines =
                    MarketFeedEndpointPool.klineEndpoints(symbol, 180);
            assertEquals(2, books.size());
            assertEquals(2, klines.size());
            assertTrue(books.get(0).url.startsWith("https://fapi.binance.com/"));
            assertTrue(books.get(1).url.startsWith("https://data-api.binance.vision/"));
            assertTrue(klines.get(1).url.contains("symbol=" + symbol));
        }
    }

    @Test public void serviceAutomaticallyRecoversAndKeepsRunning() throws Exception {
        String service = source("MarketWatchService.java");
        String compact = service.replaceAll("\\s+", "");
        assertTrue(service.contains("return START_STICKY"));
        assertTrue(service.contains("registerDefaultNetworkCallback"));
        assertTrue(service.contains("scheduleServiceRestart(2_000L)"));
        assertTrue(service.contains("websocketEndpointIndex = (endpointIndex + 1)"));
        assertTrue(compact.contains("fetchRuntimeBookTicker(runtime,0)"));
        assertTrue(compact.contains("fetchReferenceBookTicker(0)"));
        assertTrue(service.contains("wakeLock.acquire()"));
        assertTrue(service.contains("releaseWakeLock()"));
    }

    @Test public void operationalStatusUsesFreshMarketDataNotOnlyWebsocket() throws Exception {
        String service = source("MarketWatchService.java");
        assertTrue(service.contains("boolean connected = marketFeedsOperational(now)"));
        assertTrue(service.contains("state.put(\"websocketConnected\""));
        assertTrue(service.contains("state.put(\"marketDataSource\""));
        assertTrue(service.contains("state.put(\"lastFeedError\""));
        assertTrue(service.contains("state.put(\"executionFeedAuthoritative\""));
        assertTrue(service.contains("if (!executionFeedAuthoritative) return false"));
    }

    @Test public void spotFallbackRestoresVisibilityButCannotPublishPlans() throws Exception {
        String service = source("MarketWatchService.java");
        assertTrue(service.contains("executionFeedAuthoritative = !endpoint.spotFallback"));
        assertTrue(service.contains("boolean marketFresh=executionFeedAuthoritative"));
        assertTrue(MarketFeedEndpointPool.bookTickerEndpoints("ETHUSDT").get(1).spotFallback);
        assertFalse(MarketFeedEndpointPool.bookTickerEndpoints("ETHUSDT").get(0).spotFallback);
    }

    @Test public void fallbackContainsNoTradingOrPrivateApi() throws Exception {
        String pool = source("MarketFeedEndpointPool.java").toLowerCase();
        assertFalse(pool.contains("api-key"));
        assertFalse(pool.contains("/order"));
        assertFalse(pool.contains("/account"));
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
    }
}
