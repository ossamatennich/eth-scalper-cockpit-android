package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Delivery regression lock for the 5.2 transport-only namespace correction. */
public final class V23452WebSocketNamespaceFixTest {
    @Test public void publicAndMarketUrlsAreStrictlySeparatedForEverySymbol(){String publicUrl=
            MarketFeedEndpointPool.publicCombinedStreamUrl(0,MarketRegistry.production());String marketUrl=
            MarketFeedEndpointPool.marketCombinedStreamUrl(0,MarketRegistry.production());assertTrue(
            publicUrl.startsWith("wss://fstream.binance.com/public/stream?streams="));assertTrue(
            marketUrl.startsWith("wss://fstream.binance.com/market/stream?streams="));for(String symbol:
            new String[]{"ethusdt","solusdt","btcusdt"}){assertTrue(publicUrl.contains(symbol+
                    "@bookTicker"));assertTrue(publicUrl.contains(symbol+"@depth20@100ms"));assertTrue(
                    marketUrl.contains(symbol+"@aggTrade"));assertTrue(marketUrl.contains(symbol+
                    "@kline_1m"));}assertFalse(publicUrl.contains("@aggTrade"));assertFalse(publicUrl.contains(
                    "@kline_1m"));assertFalse(marketUrl.contains("@bookTicker"));assertFalse(marketUrl.contains(
                    "@depth20@100ms"));assertFalse(publicUrl.startsWith(
                    "wss://fstream.binance.com/stream?streams="));assertFalse(marketUrl.startsWith(
                    "wss://fstream.binance.com/stream?streams="));}

    @Test public void socketFamiliesAcceptOnlyTheirDeclaredEvents(){assertTrue(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,
                    BinanceCombinedStreamRouter.Type.BOOK_TICKER));assertTrue(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,
                    BinanceCombinedStreamRouter.Type.DEPTH20));assertFalse(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,
                    BinanceCombinedStreamRouter.Type.AGG_TRADE));assertFalse(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,
                    BinanceCombinedStreamRouter.Type.KLINE_1M));assertTrue(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.MARKET_WS,
                    BinanceCombinedStreamRouter.Type.AGG_TRADE));assertTrue(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.MARKET_WS,
                    BinanceCombinedStreamRouter.Type.KLINE_1M));assertFalse(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.MARKET_WS,
                    BinanceCombinedStreamRouter.Type.BOOK_TICKER));assertFalse(
            BinanceCombinedStreamRouter.accepts(BinanceCombinedStreamRouter.SocketType.MARKET_WS,
                    BinanceCombinedStreamRouter.Type.DEPTH20));}

    @Test public void releaseIdentityAndPublicEngineRemainFrozen()throws Exception{String gradle=read(
                "app/build.gradle");assertTrue(gradle.contains("versionCode 23460"));assertTrue(gradle.contains(
                "versionName '2.34.6.0'"));assertTrue(gradle.contains("NMC Stable 6.0"));assertEquals(
                    "NMC_SCALP_CV_CORE_V1",CvCorePolicy.ENGINE_ID);assertEquals(1,
                    CvCorePolicy.DUAL_EXHAUSTION_SHORT.priority);assertRoute(
                    CvCorePolicy.DUAL_EXHAUSTION_SHORT,4,1.75,14.55);assertRoute(
                    CvCorePolicy.CAPITULATION_LONG,2.5,1.5,14.55);assertRoute(
                    CvCorePolicy.P02_BALANCED_SHORT,3,1.25,7.275);assertEquals(1.43,
                    CvCorePolicy.RESULT_COST_PER_UNIT,0);assertFalse(SignalSafetyPolicies.realTradingAllowed());}

    private static void assertRoute(CvCorePolicy.Route route,double target,double stop,double budget){
        assertEquals(target,route.targetMultiple,0);assertEquals(stop,route.stopMultiple,0);assertEquals(
                budget,route.riskBudgetUsdt,0);}
    private static String read(String value)throws Exception{Path root=Paths.get(System.getProperty(
            "user.dir")).toAbsolutePath(),file=root.resolve(value);if(!Files.exists(file)&&root.getParent()!=null)
        file=root.getParent().resolve(value);return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);}
}
