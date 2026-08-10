package com.ethscalper.cockpit;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.*;

public final class MicrostructureCaptureHealthTest {
    @Test public void healthRequiresTopDepthAggAndWriterNotJustBookTicker()throws Exception{
        long start=1_000;MicrostructureCaptureHealth health=new MicrostructureCaptureHealth();
        health.reset(start);CausalCaptureQueue queue=new CausalCaptureQueue(64);MicrostructureCaptureV2
                capture=new MicrostructureCaptureV2(queue);capture.startSession("s","WS",start,1);
        CausalCaptureWriter writer=writer(queue);for(String symbol:new String[]{"ETHUSDT","SOLUSDT","BTCUSDT"}){
            health.wsBook(symbol,"PUBLIC_WS",12_000);capture.observeTopBook(symbol,"WS",12_000,2,
                    0,0,1,100,1,101,1);}capture.flushThrough(12_250,3);
        Map<String,Object> degraded=health.snapshot(12_250,capture.stats(),writer.stats());
        assertEquals(MicrostructureCaptureHealth.DEGRADED,degraded.get("state"));
        for(String symbol:new String[]{"ETHUSDT","SOLUSDT","BTCUSDT"}){health.wsAgg(symbol,
                "RESEARCH_WS",12_300);health.wsDepth(symbol,"RESEARCH_WS",12_300);
            capture.observeAggTrade(symbol,"WS",12_300,4,1,12_000,100,1,false);
            capture.observeDepth20(symbol,"WS",12_300,4,0,0,1,2,0,levels(100,false),
                    levels(101,true));}capture.flushThrough(12_750,5);writer.flush(2_000);
        assertEquals(MicrostructureCaptureHealth.HEALTHY,health.snapshot(12_750,capture.stats(),
                writer.stats()).get("state"));writer.close();}

    @Test public void perSourceCountersSeparateWsAndRest()throws Exception{MicrostructureCaptureHealth
        health=new MicrostructureCaptureHealth();health.reset(0);health.wsAgg("ETHUSDT","RESEARCH_WS",1);
        health.restRowsSeen("ETHUSDT",500);health.restRowsAccepted("ETHUSDT",2,2);
        MicrostructureCaptureV2 capture=new MicrostructureCaptureV2(record->true);
        capture.startSession("s","WS",0,0);Map<String,Object> root=health.snapshot(1,capture.stats(),null);
        Map<?,?> eth=(Map<?,?>)((Map<?,?>)root.get("symbols")).get("ETHUSDT");Map<?,?> sources=
                (Map<?,?>)eth.get("sources");assertEquals(1L,((Map<?,?>)sources.get("RESEARCH_WS"))
                .get("wsAggTradeMessages"));assertEquals(1L,((Map<?,?>)sources.get("RESEARCH_WS"))
                .get("lastSuccessfulAggTradeAt"));assertEquals(500L,((Map<?,?>)sources.get("REST"))
                .get("restAggTradeRowsSeen"));assertEquals(2L,((Map<?,?>)sources.get("REST"))
                .get("lastSuccessfulRestAggTradeAt"));}

    private static CausalCaptureWriter writer(CausalCaptureQueue queue)throws Exception{
        CausalCaptureWriter value=new CausalCaptureWriter(queue,new CausalCaptureStore(
                Files.createTempDirectory("health-store").toFile(),"health",4,64_000));value.start();return value;}
    private static double[][] levels(double start,boolean ascending){double[][] out=new double[20][2];
        for(int i=0;i<20;i++){out[i][0]=start+(ascending?i:-i)*.01;out[i][1]=i+1;}return out;}
}
