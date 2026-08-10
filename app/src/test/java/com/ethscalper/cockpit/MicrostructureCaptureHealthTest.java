package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class MicrostructureCaptureHealthTest {
    @Test public void depthAndRestTradesOnlyStayDegradedAndUnusable(){Fixture value=fixture(false);
        Map<String,Object> status=value.status(13_000,writer(0,0));assertEquals(
                MicrostructureCaptureHealth.DEGRADED,status.get("state"));assertFalse(bool(status,
                "marketWsAggTradeReady"));assertFalse(bool(status,"usableForMicrostructureResearch"));}

    @Test public void currentPublicDepthAndMarketAggForAllSymbolsBecomeHealthy(){Fixture value=fixture(true);
        Map<String,Object> status=value.status(13_000,writer(0,0));assertEquals(
                MicrostructureCaptureHealth.HEALTHY,status.get("state"));assertTrue(bool(status,
                "depth20WsReady"));assertTrue(bool(status,"marketWsAggTradeReady"));
        assertTrue(bool(status,"flowWsReady"));assertTrue(bool(status,"usableForMicrostructureResearch"));}

    @Test public void oneStaleMarketAggCannotBeMaskedByRest(){Fixture value=fixture(true);long now=29_100;
        for(String symbol:new String[]{"ETHUSDT","SOLUSDT","BTCUSDT"}){value.health.wsBook(symbol,
                MicrostructureCaptureHealth.PUBLIC_WS,now);value.health.wsDepth(symbol,
                MicrostructureCaptureHealth.PUBLIC_WS,now);}
        value.health.marketAggAccepted("ETHUSDT",now);value.health.marketAggAccepted("SOLUSDT",now);
        value.health.restRowsAccepted("BTCUSDT",500,now);Map<String,Object> status=value.status(now,
                writer(0,0));assertEquals(MicrostructureCaptureHealth.DEGRADED,status.get("state"));
        assertFalse(bool(status,"marketWsAggTradeReady"));assertFalse(bool(status,
                "usableForMicrostructureResearch"));}

    @Test public void writerFailureMakesCaptureUnusable(){Fixture value=fixture(true);
        Map<String,Object> status=value.status(13_000,writer(1,0));assertFalse(bool(status,
                "writerAvailable"));assertFalse(bool(status,"usableForMicrostructureResearch"));}

    @Test public void persistentQueueSaturationMakesCaptureUnusable(){Fixture value=fixture(true);
        Map<String,Object> status=value.status(13_000,writer(0,1));assertTrue(bool(status,
                "queueSaturated"));assertFalse(bool(status,"usableForMicrostructureResearch"));}

    @Test public void disconnectTelemetryIsSeparatedBoundedAndDetailed(){MicrostructureCaptureHealth health=
        new MicrostructureCaptureHealth();health.reset(1);health.socketConnected(
                MicrostructureCaptureHealth.PUBLIC_WS,"wss://fstream.binance.com/public/stream",2,101);
        health.socketMessage(MicrostructureCaptureHealth.PUBLIC_WS,3);health.socketFailure(
                MicrostructureCaptureHealth.PUBLIC_WS,"wss://fstream.binance.com/public/stream",4,503,
                "java.net.UnknownHostException","dns failure",0,1);health.socketReconnect(
                MicrostructureCaptureHealth.PUBLIC_WS,5,1);health.socketConnected(
                MicrostructureCaptureHealth.MARKET_WS,"wss://fstream.binance.com/market/stream",6,101);
        health.socketClosed(MicrostructureCaptureHealth.MARKET_WS,
                "wss://fstream.binance.com/market/stream",7,1006,"upstream close",0,1);
        for(int i=0;i<64;i++)health.socketFailure(MicrostructureCaptureHealth.MARKET_WS,"market",8+i,
                -1,"IOException","bounded",i,2);Map<String,Object> root=health.snapshot(100,null,null);
        assertEquals(1L,root.get("publicWsConnects"));assertEquals(1L,root.get("publicWsReconnects"));
        assertEquals(1L,root.get("publicWsFailures"));assertEquals(1L,root.get("marketWsConnects"));
        assertEquals(64L,root.get("marketWsFailures"));List<?> events=(List<?>)root.get(
                "socketDisconnectEvents");assertEquals(MicrostructureCaptureHealth.MAX_DISCONNECT_EVENTS,
                events.size());assertEquals(2L,root.get("socketDisconnectEventsEvicted"));Map<?,?> last=
                (Map<?,?>)events.get(events.size()-1);assertEquals("MARKET_WS",last.get("socketType"));
        assertEquals("IOException",last.get("exceptionClass"));assertTrue(last.containsKey("httpStatus"));
        assertTrue(last.containsKey("msSinceLastValidMessage"));}

    @SuppressWarnings("unchecked") private static boolean bool(Map<String,Object> value,String key){
        return Boolean.TRUE.equals(value.get(key));}
    private static CausalCaptureWriter.Stats writer(long failed,long rejected){return new
            CausalCaptureWriter.Stats(64,0,100,rejected,16,Collections.emptyMap(),100,failed,
                    1_024,1,0,true);}
    private static final class Fixture {final MicrostructureCaptureHealth health;
        final MicrostructureCaptureV2 capture;Fixture(MicrostructureCaptureHealth health,
                MicrostructureCaptureV2 capture){this.health=health;this.capture=capture;}
        Map<String,Object> status(long now,CausalCaptureWriter.Stats writer){return health.snapshot(
                now,capture.stats(),writer);}}
    private static Fixture fixture(boolean marketFlow){MicrostructureCaptureHealth health=
            new MicrostructureCaptureHealth();health.reset(1_000);health.socketConnected(
            MicrostructureCaptureHealth.PUBLIC_WS,"public",1_001,101);health.socketConnected(
            MicrostructureCaptureHealth.MARKET_WS,"market",1_001,101);
        MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(record->true);
        capture.startSession("s","FUTURES",1_000,1);long id=10;for(String symbol:new String[]{
                "ETHUSDT","SOLUSDT","BTCUSDT"}){health.wsBook(symbol,
                MicrostructureCaptureHealth.PUBLIC_WS,12_000);capture.observeTopBook(symbol,
                "BINANCE_FUTURES_PUBLIC_WS",12_000,2,0,0,id,100,1,101,1);
            boolean depth=capture.observeDepth20(symbol,"BINANCE_FUTURES_PUBLIC_WS",12_000,2,
                    0,0,id,id+1,0,levels(100,false),levels(101,true));health.wsDepth(symbol,
                    MicrostructureCaptureHealth.PUBLIC_WS,12_000,depth);if(marketFlow){health.wsAgg(
                    symbol,MicrostructureCaptureHealth.MARKET_WS,12_000);boolean accepted=
                    capture.observeAggTrade(symbol,"BINANCE_FUTURES_MARKET_WS",12_000,2,id,
                            11_900,100,1,false);if(accepted)health.marketAggAccepted(symbol,12_000);}
            else{boolean accepted=capture.observeAggTrade(symbol,"BINANCE_FUTURES_REST",12_000,
                    2,id,11_900,100,1,false);health.restRowsSeen(symbol,1);health.restRowsAccepted(
                    symbol,accepted?1:0,12_000);}id+=10;}capture.flushThrough(12_500,3);
        return new Fixture(health,capture);}
    private static double[][] levels(double start,boolean ascending){double[][] out=new double[20][2];
        for(int i=0;i<20;i++){out[i][0]=start+(ascending?i:-i)*.01;out[i][1]=i+1;}return out;}
}
