package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flux public de marché fidèle au chemin de connexion éprouvé
 * de l'ancienne application :
 *
 * - Binance USD-M Futures uniquement ;
 * - deux WebSockets combinés et séparés par namespace PUBLIC/MARKET ;
 * - REST Futures uniquement ;
 * - ETHUSDT et SOLUSDT comme marchés analysés ;
 * - BTCUSDT comme contexte partagé.
 *
 * Aucun flux Spot, compte, ordre ou API privée.
 */
public final class MarketFeedEndpointPool {
    public static final String FUTURES_PRIMARY =
            "BINANCE_FUTURES_PRIMARY";

    /*
     * Conservées uniquement pour compatibilité source avec les anciens
     * diagnostics/tests. Elles ne sont utilisées par aucun endpoint.
     */
    public static final String FUTURES_ALTERNATE =
            "BINANCE_FUTURES_ALTERNATE";
    public static final String SPOT_PUBLIC_FALLBACK =
            "BINANCE_SPOT_PUBLIC_FALLBACK";

    private static final Endpoint[] WEBSOCKET_ENDPOINTS = {
            new Endpoint(
                    FUTURES_PRIMARY,
                    "wss://fstream.binance.com",
                    false
            )
    };

    private static final String FUTURES_REST =
            "https://fapi.binance.com/fapi/v1";

    private MarketFeedEndpointPool() {}

    public static int webSocketCount() {
        return WEBSOCKET_ENDPOINTS.length;
    }

    public static Endpoint webSocket(int index) {
        return WEBSOCKET_ENDPOINTS[
                Math.floorMod(index, WEBSOCKET_ENDPOINTS.length)
        ];
    }

    /** Public namespace: executable top-of-book plus partial depth snapshots only. */
    public static String publicCombinedStreamUrl(int index,MarketRegistry registry){
        Endpoint endpoint=webSocket(index);StringBuilder value=new StringBuilder(endpoint.baseUrl)
                .append("/public/stream?streams=");
        for(MarketProfile profile:registry.tradedMarkets())appendPublicStreams(value,
                profile.symbol.toLowerCase(Locale.ROOT));
        appendPublicStreams(value,"btcusdt");return value.toString();
    }

    /** Market namespace: aggressive trades, one-minute klines and sparse liquidation snapshots. */
    public static String marketCombinedStreamUrl(int index,MarketRegistry registry){
        Endpoint endpoint=webSocket(index);StringBuilder value=new StringBuilder(endpoint.baseUrl)
                .append("/market/stream?streams=");
        for(MarketProfile profile:registry.tradedMarkets())appendMarketStreams(value,
                profile.symbol.toLowerCase(Locale.ROOT));
        appendMarketStreams(value,"btcusdt");return value.toString();
    }

    /** Dedicated public namespace connection for raw incremental diff-depth updates only. */
    public static String incrementalDepthCombinedStreamUrl(int index,MarketRegistry registry){
        Endpoint endpoint=webSocket(index);StringBuilder value=new StringBuilder(endpoint.baseUrl)
                .append("/public/stream?streams=");for(MarketProfile profile:registry.tradedMarkets())
            appendIncrementalDepthStream(value,profile.symbol.toLowerCase(Locale.ROOT));
        appendIncrementalDepthStream(value,"btcusdt");return value.toString();}

    private static void appendPublicStreams(StringBuilder value,String symbol){
        if(value.charAt(value.length()-1)!='=')value.append('/');
        value.append(symbol).append("@bookTicker/").append(symbol).append("@depth20@100ms");
    }

    private static void appendMarketStreams(StringBuilder value,String symbol){
        if(value.charAt(value.length()-1)!='=')value.append('/');
        value.append(symbol).append("@aggTrade/").append(symbol).append("@kline_1m/")
                .append(symbol).append("@forceOrder");
    }

    private static void appendIncrementalDepthStream(StringBuilder value,String symbol){
        if(value.charAt(value.length()-1)!='=')value.append('/');
        // Binance USD-M default diff-depth cadence is 250 ms. It is sufficient for the
        // 500 ms+ research horizons and materially reduces transport/storage pressure.
        value.append(symbol).append("@depth");}

    /** Unsigned USD-M Futures order-book snapshot used only to anchor diff-depth replay. */
    public static List<RestEndpoint> depthSnapshotEndpoints(String symbol,int limit){
        if(!CausalMarketRecord.supported(symbol)||limit!=500)throw new IllegalArgumentException("depth");
        return Collections.singletonList(new RestEndpoint(FUTURES_PRIMARY,FUTURES_REST+
                "/depth?symbol="+symbol+"&limit="+limit,false));}

    public static List<RestEndpoint> klineEndpoints(
            String symbol,
            int limit
    ) {
        String query =
                "?symbol=" + symbol
                        + "&interval=1m&limit=" + limit;

        return Collections.singletonList(
                new RestEndpoint(
                        FUTURES_PRIMARY,
                        FUTURES_REST + "/klines" + query,
                        false
                )
        );
    }

    public static List<RestEndpoint> aggregateTradeEndpoints(
            String symbol,
            int limit
    ) {
        String query =
                "?symbol=" + symbol + "&limit=" + limit;

        return Collections.singletonList(
                new RestEndpoint(
                        FUTURES_PRIMARY,
                        FUTURES_REST + "/aggTrades" + query,
                        false
                )
        );
    }

    public static List<RestEndpoint> bookTickerEndpoints(
            String symbol
    ) {
        String query = "?symbol=" + symbol;

        return Collections.singletonList(
                new RestEndpoint(
                        FUTURES_PRIMARY,
                        FUTURES_REST
                                + "/ticker/bookTicker"
                                + query,
                        false
                )
        );
    }

    public static final class Endpoint {
        public final String name;
        public final String baseUrl;
        public final boolean spotFallback;

        private Endpoint(
                String name,
                String baseUrl,
                boolean spotFallback
        ) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.spotFallback = spotFallback;
        }
    }

    public static final class RestEndpoint {
        public final String name;
        public final String url;
        public final boolean spotFallback;

        private RestEndpoint(
                String name,
                String url,
                boolean spotFallback
        ) {
            this.name = name;
            this.url = url;
            this.spotFallback = spotFallback;
        }
    }
}
