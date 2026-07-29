package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ordered public market-data endpoints. Futures remains authoritative; Binance's public
 * market-data-only Spot service is an availability fallback when the Futures route is blocked.
 * No private endpoint, account API or order endpoint is ever used.
 */
public final class MarketFeedEndpointPool {
    public static final String FUTURES_PRIMARY = "BINANCE_FUTURES_PRIMARY";
    public static final String FUTURES_ALTERNATE = "BINANCE_FUTURES_ALTERNATE";
    public static final String SPOT_PUBLIC_FALLBACK = "BINANCE_SPOT_PUBLIC_FALLBACK";

    private static final Endpoint[] WEBSOCKET_ENDPOINTS = {
            new Endpoint(FUTURES_PRIMARY, "wss://fstream.binance.com", false),
            new Endpoint(FUTURES_ALTERNATE, "wss://fstream-auth.binance.com", false),
            new Endpoint(SPOT_PUBLIC_FALLBACK, "wss://data-stream.binance.vision", true)
    };
    private static final String FUTURES_REST = "https://fapi.binance.com/fapi/v1";
    private static final String SPOT_REST = "https://data-api.binance.vision/api/v3";

    private MarketFeedEndpointPool() {}

    public static int webSocketCount() { return WEBSOCKET_ENDPOINTS.length; }

    public static Endpoint webSocket(int index) {
        return WEBSOCKET_ENDPOINTS[Math.floorMod(index, WEBSOCKET_ENDPOINTS.length)];
    }

    public static String combinedStreamUrl(int index, MarketRegistry registry) {
        Endpoint endpoint = webSocket(index);
        StringBuilder value = new StringBuilder(endpoint.baseUrl).append("/stream?streams=");
        for (MarketProfile profile : registry.tradedMarkets()) {
            if (value.charAt(value.length() - 1) != '=') value.append('/');
            String symbol = profile.symbol.toLowerCase(Locale.ROOT);
            value.append(symbol).append("@kline_1m/").append(symbol)
                    .append("@aggTrade/").append(symbol).append("@bookTicker");
        }
        value.append("/btcusdt@kline_1m/btcusdt@bookTicker");
        return value.toString();
    }

    public static List<RestEndpoint> klineEndpoints(String symbol, int limit) {
        String query = "?symbol=" + symbol + "&interval=1m&limit=" + limit;
        List<RestEndpoint> values = new ArrayList<>();
        values.add(new RestEndpoint(FUTURES_PRIMARY, FUTURES_REST + "/klines" + query, false));
        values.add(new RestEndpoint(SPOT_PUBLIC_FALLBACK, SPOT_REST + "/klines" + query, true));
        return Collections.unmodifiableList(values);
    }

    public static List<RestEndpoint> aggregateTradeEndpoints(String symbol, int limit) {
        String query = "?symbol=" + symbol + "&limit=" + limit;
        List<RestEndpoint> values = new ArrayList<>();
        values.add(new RestEndpoint(FUTURES_PRIMARY, FUTURES_REST + "/aggTrades" + query, false));
        values.add(new RestEndpoint(SPOT_PUBLIC_FALLBACK, SPOT_REST + "/aggTrades" + query, true));
        return Collections.unmodifiableList(values);
    }

    public static List<RestEndpoint> bookTickerEndpoints(String symbol) {
        String query = "?symbol=" + symbol;
        List<RestEndpoint> values = new ArrayList<>();
        values.add(new RestEndpoint(FUTURES_PRIMARY,
                FUTURES_REST + "/ticker/bookTicker" + query, false));
        values.add(new RestEndpoint(SPOT_PUBLIC_FALLBACK,
                SPOT_REST + "/ticker/bookTicker" + query, true));
        return Collections.unmodifiableList(values);
    }

    public static final class Endpoint {
        public final String name;
        public final String baseUrl;
        public final boolean spotFallback;

        private Endpoint(String name, String baseUrl, boolean spotFallback) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.spotFallback = spotFallback;
        }
    }

    public static final class RestEndpoint {
        public final String name;
        public final String url;
        public final boolean spotFallback;

        private RestEndpoint(String name, String url, boolean spotFallback) {
            this.name = name;
            this.url = url;
            this.spotFallback = spotFallback;
        }
    }
}
