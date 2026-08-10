package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public final class BinanceCombinedStreamRouterTest {
    private final BinanceCombinedStreamRouter router=new BinanceCombinedStreamRouter();

    @Test public void parsesAllCombinedFuturesEventClassesCaseSafely(){
        assertEquals(BinanceCombinedStreamRouter.Type.BOOK_TICKER,parse("ETHUSDT@BOOKTICKER",
                "{\"b\":\"1\",\"a\":\"2\"}").type);
        assertEquals(BinanceCombinedStreamRouter.Type.AGG_TRADE,parse("ethusdt@aggTrade",
                "{\"a\":1}").type);
        assertEquals(BinanceCombinedStreamRouter.Type.KLINE_1M,parse("solusdt@kline_1m",
                "{\"k\":{}}").type);
        BinanceCombinedStreamRouter.Event depth=parse("btcusdt@depth20@100ms","{\"b\":[],\"a\":[]}");
        assertEquals(BinanceCombinedStreamRouter.Type.DEPTH20,depth.type);
        assertEquals("BTCUSDT",depth.symbol);
    }

    @Test public void malformedAndUnknownStreamsFailClosed(){assertNull(router.parse("{}"));
        assertNull(router.parse("{\"stream\":\"ethusdt@markPrice\",\"data\":{}}"));
        final int[] malformed={0};assertFalse(router.dispatch("not-json",new BinanceCombinedStreamRouter.Listener(){
            public void onEvent(BinanceCombinedStreamRouter.Event event){fail();}
            public void onMalformed(String reason){malformed[0]++;}}));assertEquals(1,malformed[0]);}

    @Test public void marketAggTradeReachesRuntimeAndCaptureWhilePublicRejectsIt(){
        MarketRegistry registry=MarketRegistry.production();MultiMarketCoordinator coordinator=
                new MultiMarketCoordinator(registry);MarketDataRouter runtime=new MarketDataRouter(registry,
                coordinator,Collections.emptyMap());MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(
                record->true);capture.startSession("s","FUTURES",1_000,1);String payload=payload(
                "ethusdt@aggTrade","{\"a\":77,\"T\":900,\"p\":\"1900\",\"q\":\"2\",\"m\":false}");
        BinanceCombinedStreamRouter.Event event=router.parse(payload);assertTrue(runtime.routeAggTrade(
                event.symbol,new MarketRuntime.AggTrade(77,900,1900,2,false),1_010));
        assertTrue(capture.observeAggTrade(event.symbol,"MARKET_WS",1_010,2,77,900,1900,2,false));
        final int[] delivered={0},malformed={0};BinanceCombinedStreamRouter.Listener listener=
                new BinanceCombinedStreamRouter.Listener(){public void onEvent(
                        BinanceCombinedStreamRouter.Event value){delivered[0]++;}
                    public void onMalformed(String reason){malformed[0]++;}};
        assertTrue(router.dispatch(payload,BinanceCombinedStreamRouter.SocketType.MARKET_WS,listener));
        assertFalse(router.dispatch(payload,BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,listener));
        assertEquals(1,delivered[0]);assertEquals(1,malformed[0]);
        assertEquals(1,coordinator.runtime("ETHUSDT").aggTradeMessages);
        assertEquals(1,capture.stats().symbols.get("ETHUSDT").aggTradesAccepted);
    }

    @Test public void endpointsUseStrictPublicAndMarketNamespaces(){String publicUrl=
            MarketFeedEndpointPool.publicCombinedStreamUrl(0,MarketRegistry.production());
        String marketUrl=MarketFeedEndpointPool.marketCombinedStreamUrl(0,MarketRegistry.production());
        assertTrue(publicUrl.startsWith("wss://fstream.binance.com/public/stream?streams="));
        assertTrue(marketUrl.startsWith("wss://fstream.binance.com/market/stream?streams="));
        for(String symbol:new String[]{"ethusdt","solusdt","btcusdt"}){assertTrue(marketUrl.contains(
                symbol+"@aggTrade"));assertTrue(marketUrl.contains(symbol+"@kline_1m"));
            assertTrue(publicUrl.contains(symbol+"@bookTicker"));assertTrue(publicUrl.contains(
                    symbol+"@depth20@100ms"));}
        assertFalse(publicUrl.contains("@aggTrade"));assertFalse(publicUrl.contains("@kline_1m"));
        assertFalse(marketUrl.contains("@bookTicker"));assertFalse(marketUrl.contains("@depth20"));
        assertFalse(publicUrl.contains("/order"));assertFalse(marketUrl.contains("/order"));}

    @Test public void allThreeSymbolsReachOnlyTheirExpectedSocketHandler(){for(String symbol:
            new String[]{"ethusdt","solusdt","btcusdt"}){assertSocket(symbol+"@bookTicker",
                    "{\"b\":\"1\",\"a\":\"2\"}",BinanceCombinedStreamRouter.SocketType.PUBLIC_WS);
            assertSocket(symbol+"@depth20@100ms","{\"b\":[],\"a\":[]}",
                    BinanceCombinedStreamRouter.SocketType.PUBLIC_WS);
            assertSocket(symbol+"@aggTrade","{\"a\":1}",
                    BinanceCombinedStreamRouter.SocketType.MARKET_WS);
            assertSocket(symbol+"@kline_1m","{\"k\":{}}",
                    BinanceCombinedStreamRouter.SocketType.MARKET_WS);}}

    private void assertSocket(String stream,String data,BinanceCombinedStreamRouter.SocketType expected){
        final int[] delivered={0},rejected={0};BinanceCombinedStreamRouter.Listener listener=
                new BinanceCombinedStreamRouter.Listener(){public void onEvent(
                        BinanceCombinedStreamRouter.Event value){delivered[0]++;}
                    public void onMalformed(String reason){rejected[0]++;}};
        assertTrue(router.dispatch(payload(stream,data),expected,listener));assertFalse(router.dispatch(
                payload(stream,data),expected==BinanceCombinedStreamRouter.SocketType.PUBLIC_WS
                        ?BinanceCombinedStreamRouter.SocketType.MARKET_WS
                        :BinanceCombinedStreamRouter.SocketType.PUBLIC_WS,listener));
        assertEquals(1,delivered[0]);assertEquals(1,rejected[0]);}

    private BinanceCombinedStreamRouter.Event parse(String stream,String data){return router.parse(
            payload(stream,data));}
    private static String payload(String stream,String data){return "{\"stream\":\""+stream+
            "\",\"data\":"+data+"}";}
}
