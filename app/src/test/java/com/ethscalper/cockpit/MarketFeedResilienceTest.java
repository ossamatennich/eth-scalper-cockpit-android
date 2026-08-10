package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class MarketFeedResilienceTest {

    private static String source(String name) throws Exception {
        return new String(
                Files.readAllBytes(
                        Path.of(
                                "src/main/java/com/ethscalper/cockpit/"
                                        + name
                        )
                ),
                StandardCharsets.UTF_8
        );
    }

    @Test
    public void websocketPoolIsFuturesOnlyAndRegistryDriven() {
        MarketRegistry registry = MarketRegistry.production();

        assertEquals(
                1,
                MarketFeedEndpointPool.webSocketCount()
        );

        MarketFeedEndpointPool.Endpoint endpoint =
                MarketFeedEndpointPool.webSocket(0);

        assertEquals(
                MarketFeedEndpointPool.FUTURES_PRIMARY,
                endpoint.name
        );

        assertEquals(
                "wss://fstream.binance.com",
                endpoint.baseUrl
        );

        assertFalse(endpoint.spotFallback);

        String publicUrl=MarketFeedEndpointPool.publicCombinedStreamUrl(0,registry);
        String marketUrl=MarketFeedEndpointPool.marketCombinedStreamUrl(0,registry);

        for (MarketProfile profile : registry.tradedMarkets()) {
            String symbol = profile.symbol.toLowerCase();
            assertTrue(publicUrl.contains(symbol+"@bookTicker"));
            assertTrue(publicUrl.contains(symbol+"@depth20@100ms"));
            assertTrue(marketUrl.contains(symbol+"@kline_1m"));
            assertTrue(marketUrl.contains(symbol+"@aggTrade"));
        }
        assertTrue(publicUrl.contains("btcusdt@bookTicker"));
        assertTrue(publicUrl.contains("btcusdt@depth20@100ms"));
        assertTrue(marketUrl.contains("btcusdt@kline_1m"));
        assertTrue(marketUrl.contains("btcusdt@aggTrade"));
        assertTrue(publicUrl.startsWith("wss://fstream.binance.com/public/stream?streams="));
        assertTrue(marketUrl.startsWith("wss://fstream.binance.com/market/stream?streams="));
        assertFalse(publicUrl.contains("@aggTrade"));assertFalse(publicUrl.contains("@kline_1m"));
        assertFalse(marketUrl.contains("@bookTicker"));assertFalse(marketUrl.contains("@depth20"));
        assertFalse(publicUrl.contains("fstream-auth"));assertFalse(marketUrl.contains("binance.vision"));
    }

    @Test
    public void everyRestFeedIsFuturesOnly() {
        for (String symbol :
                new String[]{"ETHUSDT", "SOLUSDT", "BTCUSDT"}) {

            List<MarketFeedEndpointPool.RestEndpoint> books =
                    MarketFeedEndpointPool
                            .bookTickerEndpoints(symbol);

            List<MarketFeedEndpointPool.RestEndpoint> klines =
                    MarketFeedEndpointPool
                            .klineEndpoints(symbol, 180);

            List<MarketFeedEndpointPool.RestEndpoint> trades =
                    MarketFeedEndpointPool
                            .aggregateTradeEndpoints(symbol, 500);

            assertEquals(1, books.size());
            assertEquals(1, klines.size());
            assertEquals(1, trades.size());

            assertTrue(
                    books.get(0).url.startsWith(
                            "https://fapi.binance.com/fapi/v1/"
                    )
            );

            assertTrue(
                    klines.get(0).url.startsWith(
                            "https://fapi.binance.com/fapi/v1/"
                    )
            );

            assertTrue(
                    trades.get(0).url.startsWith(
                            "https://fapi.binance.com/fapi/v1/"
                    )
            );

            assertTrue(
                    books.get(0).url.contains(
                            "symbol=" + symbol
                    )
            );

            assertTrue(
                    klines.get(0).url.contains(
                            "symbol=" + symbol
                    )
            );

            assertTrue(
                    trades.get(0).url.contains(
                            "symbol=" + symbol
                    )
            );

            assertFalse(books.get(0).spotFallback);
            assertFalse(klines.get(0).spotFallback);
            assertFalse(trades.get(0).spotFallback);
        }
    }

    @Test
    public void serviceAutomaticallyRecoversAndKeepsRunning()
            throws Exception {

        String service = source("MarketWatchService.java");
        String compact = service.replaceAll("\\s+", "");

        assertTrue(service.contains("return START_STICKY"));
        assertTrue(
                service.contains(
                        "registerDefaultNetworkCallback"
                )
        );
        assertTrue(
                service.contains(
                        "scheduleServiceRestart(2_000L)"
                )
        );
        assertTrue(
                service.contains(
                        "websocketEndpointIndex = "
                                + "(endpointIndex + 1)"
                )
        );
        assertTrue(
                compact.contains(
                        "fetchRuntimeBookTicker(runtime,0)"
                )
        );
        assertTrue(
                compact.contains(
                        "fetchReferenceBookTicker(0)"
                )
        );
        assertTrue(service.contains("wakeLock.acquire()"));
        assertTrue(service.contains("releaseWakeLock()"));
    }

    @Test
    public void operationalStatusRequiresAuthoritativeMarketData()
            throws Exception {

        String service = source("MarketWatchService.java");

        assertTrue(
                service.contains(
                        "boolean connected = "
                                + "marketFeedsOperational(now)"
                )
        );

        assertTrue(
                service.contains(
                        "state.put(\"websocketConnected\""
                )
        );

        assertTrue(
                service.contains(
                        "state.put(\"marketDataSource\""
                )
        );

        assertTrue(
                service.contains(
                        "state.put(\"lastFeedError\""
                )
        );

        assertTrue(
                service.contains(
                        "state.put("
                                + "\"executionFeedAuthoritative\""
                )
        );

        assertTrue(
                service.contains(
                        "if (!executionFeedAuthoritative) "
                                + "return false"
                )
        );
    }

    @Test
    public void productionEndpointsAreAlwaysAuthoritativeFutures()
            throws Exception {

        String service = source("MarketWatchService.java");

        assertTrue(
                service.contains(
                        "executionFeedAuthoritative = "
                                + "!endpoint.spotFallback"
                )
        );

        assertTrue(
                service.contains(
                        "boolean marketFresh="
                                + "executionFeedAuthoritative"
                )
        );

        assertFalse(
                MarketFeedEndpointPool
                        .webSocket(0)
                        .spotFallback
        );

        assertFalse(
                MarketFeedEndpointPool
                        .bookTickerEndpoints("ETHUSDT")
                        .get(0)
                        .spotFallback
        );

        assertFalse(
                MarketFeedEndpointPool
                        .bookTickerEndpoints("SOLUSDT")
                        .get(0)
                        .spotFallback
        );
    }

    @Test
    public void feedContainsNoTradingPrivateOrSpotApi()
            throws Exception {

        String pool =
                source("MarketFeedEndpointPool.java")
                        .toLowerCase();

        assertFalse(pool.contains("api-key"));
        assertFalse(pool.contains("/order"));
        assertFalse(pool.contains("/account"));

        assertFalse(
                pool.contains(
                        "data-stream.binance.vision"
                )
        );

        assertFalse(
                pool.contains(
                        "data-api.binance.vision"
                )
        );

        assertFalse(
                SignalSafetyPolicies.realTradingAllowed()
        );
    }
}
