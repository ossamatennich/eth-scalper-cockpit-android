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

    @Test public void publicAndResearchAggTradeReachRuntimeAndCaptureWithoutDoubleCount(){
        MarketRegistry registry=MarketRegistry.production();MultiMarketCoordinator coordinator=
                new MultiMarketCoordinator(registry);MarketDataRouter runtime=new MarketDataRouter(registry,
                coordinator,Collections.emptyMap());MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(
                record->true);capture.startSession("s","FUTURES",1_000,1);String payload=payload(
                "ethusdt@aggTrade","{\"a\":77,\"T\":900,\"p\":\"1900\",\"q\":\"2\",\"m\":false}");
        BinanceCombinedStreamRouter.Event publicEvent=router.parse(payload);assertTrue(runtime.routeAggTrade(
                publicEvent.symbol,new MarketRuntime.AggTrade(77,900,1900,2,false),1_010));
        assertTrue(capture.observeAggTrade(publicEvent.symbol,"PUBLIC_WS",1_010,2,77,900,1900,2,false));
        BinanceCombinedStreamRouter.Event researchEvent=router.parse(payload);assertFalse(capture.observeAggTrade(
                researchEvent.symbol,"RESEARCH_WS",1_011,3,77,900,1900,2,false));
        assertEquals(1,coordinator.runtime("ETHUSDT").aggTradeMessages);
        assertEquals(1,capture.stats().symbols.get("ETHUSDT").aggTradesAccepted);
        assertEquals(1,capture.stats().symbols.get("ETHUSDT").aggTradesDuplicate);
    }

    @Test public void researchUrlContainsAggTradeAndDepthForEverySymbolOnly(){String url=
            MarketFeedEndpointPool.researchCombinedStreamUrl(0,MarketRegistry.production());
        for(String symbol:new String[]{"ethusdt","solusdt","btcusdt"}){assertTrue(url.contains(
                symbol+"@aggTrade"));assertTrue(url.contains(symbol+"@kline_1m"));
            assertTrue(url.contains(symbol+"@depth20@100ms"));}
        assertFalse(url.contains("@bookTicker"));assertFalse(url.contains("/order"));}

    private BinanceCombinedStreamRouter.Event parse(String stream,String data){return router.parse(
            payload(stream,data));}
    private static String payload(String stream,String data){return "{\"stream\":\""+stream+
            "\",\"data\":"+data+"}";}
}
