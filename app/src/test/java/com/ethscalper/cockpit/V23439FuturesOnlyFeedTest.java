package com.ethscalper.cockpit;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class V23439FuturesOnlyFeedTest {

    @Test
    public void websocketUsesOnlyTheProvenFuturesPath() {
        assertEquals(
                1,
                MarketFeedEndpointPool.webSocketCount()
        );

        MarketFeedEndpointPool.Endpoint endpoint =
                MarketFeedEndpointPool.webSocket(0);

        assertEquals(
                "wss://fstream.binance.com",
                endpoint.baseUrl
        );

        assertFalse(endpoint.spotFallback);

        String publicUrl=MarketFeedEndpointPool.publicCombinedStreamUrl(0,MarketRegistry.production());
        String marketUrl=MarketFeedEndpointPool.marketCombinedStreamUrl(0,MarketRegistry.production());
        for(String symbol:new String[]{"ethusdt","solusdt","btcusdt"}){
            assertTrue(publicUrl.contains(symbol+"@bookTicker"));
            assertTrue(publicUrl.contains(symbol+"@depth20@100ms"));
            assertTrue(marketUrl.contains(symbol+"@kline_1m"));
            assertTrue(marketUrl.contains(symbol+"@aggTrade"));}
        assertFalse(publicUrl.contains("@aggTrade"));assertFalse(publicUrl.contains("@kline_1m"));
        assertFalse(marketUrl.contains("@bookTicker"));assertFalse(marketUrl.contains("@depth20"));
        assertFalse(publicUrl.contains("fstream-auth"));assertFalse(marketUrl.contains("binance.vision"));
    }

    @Test
    public void everyRestEndpointUsesFuturesOnly() {
        assertFuturesOnly(
                MarketFeedEndpointPool.klineEndpoints(
                        "ETHUSDT",
                        180
                )
        );

        assertFuturesOnly(
                MarketFeedEndpointPool.klineEndpoints(
                        "SOLUSDT",
                        180
                )
        );

        assertFuturesOnly(
                MarketFeedEndpointPool.aggregateTradeEndpoints(
                        "ETHUSDT",
                        500
                )
        );

        assertFuturesOnly(
                MarketFeedEndpointPool.aggregateTradeEndpoints(
                        "SOLUSDT",
                        500
                )
        );

        assertFuturesOnly(
                MarketFeedEndpointPool.bookTickerEndpoints(
                        "ETHUSDT"
                )
        );

        assertFuturesOnly(
                MarketFeedEndpointPool.bookTickerEndpoints(
                        "SOLUSDT"
                )
        );

        assertFuturesOnly(
                MarketFeedEndpointPool.bookTickerEndpoints(
                        "BTCUSDT"
                )
        );
    }

    @Test
    public void jsonNormalizerPreservesBooleans()
            throws Exception {
        JSONObject source = new JSONObject();

        source.put("connected", true);
        source.put("executionFeedAuthoritative", true);
        source.put("realTradingAllowed", false);

        JSONObject normalized =
                SafeJsonNormalizer
                        .normalizeAndSerialize(source)
                        .value;

        assertTrue(
                normalized.get("connected")
                        instanceof Boolean
        );

        assertTrue(
                normalized.get(
                        "executionFeedAuthoritative"
                ) instanceof Boolean
        );

        assertTrue(
                normalized.get("realTradingAllowed")
                        instanceof Boolean
        );

        assertTrue(normalized.getBoolean("connected"));

        assertTrue(
                normalized.getBoolean(
                        "executionFeedAuthoritative"
                )
        );

        assertFalse(
                normalized.getBoolean(
                        "realTradingAllowed"
                )
        );
    }

    private static void assertFuturesOnly(
            List<MarketFeedEndpointPool.RestEndpoint>
                    endpoints
    ) {
        assertEquals(1, endpoints.size());

        MarketFeedEndpointPool.RestEndpoint endpoint =
                endpoints.get(0);

        assertFalse(endpoint.spotFallback);

        assertTrue(
                endpoint.url.startsWith(
                        "https://fapi.binance.com/fapi/v1/"
                )
        );

        assertFalse(
                endpoint.url.contains("binance.vision")
        );
    }
}
