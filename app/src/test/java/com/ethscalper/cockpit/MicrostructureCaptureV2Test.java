package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class MicrostructureCaptureV2Test {
    @Test public void aggTradesUseReceiveBucketsAndExactAggressorConvention(){
        Sink sink=new Sink();MicrostructureCaptureV2 capture=capture(sink);
        assertTrue(capture.observeAggTrade("ETHUSDT","WS",1_110,2,10,9_000,100,2,false));
        assertTrue(capture.observeAggTrade("ETHUSDT","WS",1_190,3,12,8_000,101,3,true));
        capture.flushThrough(1_200,4);MicrostructureMarketRecord flow=sink.last(
                CausalMarketRecord.Kind.FLOW_100MS,"ETHUSDT");assertNotNull(flow);
        assertEquals(1_100,flow.bucketStartAt);assertEquals(2,flow.aggregateCount);
        assertEquals(1,flow.aggregateIdGaps);assertEquals(2,flow.buyerBase,0);
        assertEquals(3,flow.sellerBase,0);assertEquals(200,flow.buyerNotional,0);
        assertEquals(303,flow.sellerNotional,0);assertEquals(9_000,flow.firstTradeAt);
        assertEquals(8_000,flow.lastTradeAt); // exchange time never moves the local bucket.
    }

    @Test public void wsAndRestIdsAreDeduplicatedAndGapsRemainVisible(){
        Sink sink=new Sink();MicrostructureCaptureV2 capture=capture(sink);
        assertTrue(capture.observeAggTrade("SOLUSDT","REST",1_010,2,100,900,75,1,false));
        assertFalse(capture.observeAggTrade("SOLUSDT","WS",1_020,3,100,901,75,1,false));
        assertTrue(capture.observeAggTrade("SOLUSDT","WS",1_030,4,103,902,75,1,true));
        assertFalse(capture.observeAggTrade("SOLUSDT","REST",1_040,5,99,903,75,1,true));
        MicrostructureCaptureV2.SymbolStats stats=capture.stats().symbols.get("SOLUSDT");
        assertEquals(2,stats.aggTradesAccepted);assertEquals(1,stats.aggTradesDuplicate);
        assertEquals(1,stats.aggTradesLate);assertEquals(2,stats.aggregateIdGaps);
        assertEquals(1,stats.sourceTransitions);assertEquals(1,stats.causalGaps);
        assertEquals(2,sink.count(CausalMarketRecord.Kind.HEALTH));
    }

    @Test public void quoteCoalescingPersistsLatestOnlyAt250msCadence(){
        Sink sink=new Sink();MicrostructureCaptureV2 capture=capture(sink);
        for(int i=0;i<200;i++)assertTrue(capture.observeTopBook("BTCUSDT","WS",1_000+i,
                2+i,900+i,900+i,i,60_000+i,1,60_001+i,2));
        capture.flushThrough(1_250,300);assertEquals(1,sink.count(
                CausalMarketRecord.Kind.TOP_OF_BOOK_SAMPLE));MicrostructureMarketRecord value=
                sink.last(CausalMarketRecord.Kind.TOP_OF_BOOK_SAMPLE,"BTCUSDT");
        assertEquals(1_199,value.receivedAt);assertEquals(60_199,value.bid,0);
        assertEquals(200,capture.stats().symbols.get("BTCUSDT").topMessagesAccepted);
    }

    @Test public void depthCoalescesLatestAndNeverFabricatesMissingBuckets(){
        Sink sink=new Sink();MicrostructureCaptureV2 capture=capture(sink);double[][] first=depth(100),
                second=depth(101);assertTrue(capture.observeDepth20("ETHUSDT","WS",1_010,2,
                900,901,1,2,0,first,asks(101)));
        assertTrue(capture.observeDepth20("ETHUSDT","WS",1_200,3,902,903,2,3,2,
                second,asks(102)));capture.flushThrough(1_250,4);
        assertEquals(1,sink.count(CausalMarketRecord.Kind.DEPTH20_SAMPLE));
        MicrostructureMarketRecord value=sink.last(CausalMarketRecord.Kind.DEPTH20_SAMPLE,"ETHUSDT");
        assertEquals(101,value.bids[0][0],0);capture.flushThrough(2_000,5);
        assertEquals(1,sink.count(CausalMarketRecord.Kind.DEPTH20_SAMPLE));
    }

    @Test public void malformedShortDepthIsRejectedWithoutThrowing(){
        Sink sink=new Sink();MicrostructureCaptureV2 capture=capture(sink);
        assertFalse(capture.observeDepth20("ETHUSDT","WS",1_010,2,0,0,1,2,0,
                new double[19][2],new double[19][2]));assertEquals(1,
                capture.stats().symbols.get("ETHUSDT").depthInvalid);
    }

    @Test public void boundedOverflowBecomesExplicitDropSummary(){
        CausalCaptureQueue queue=new CausalCaptureQueue(1);MicrostructureCaptureV2 capture=
                new MicrostructureCaptureV2(queue);assertTrue(capture.startSession("s","WS",0,0));
        capture.observeTopBook("ETHUSDT","WS",10,1,0,0,1,100,1,101,1);
        capture.flushThrough(250,2);assertEquals(2,queue.rejected());assertEquals(1L,
                queue.droppedByKind().get("TOP_OF_BOOK_SAMPLE").longValue());queue.drain(1);
        capture.health(300,3,"WS","PROBE");List<CausalMarketRecord> records=queue.drain(2);
        assertEquals(1,records.size());assertEquals(CausalMarketRecord.Kind.DROP_SUMMARY,
                records.get(0).kind);assertEquals(1L,((MicrostructureMarketRecord)records.get(0))
                .droppedByKind.get("TOP_OF_BOOK_SAMPLE").longValue());
    }

    @Test public void v2MapsContainOnlyFiniteNumbersOrNull(){Sink sink=new Sink();
        MicrostructureCaptureV2 capture=capture(sink);capture.observeDepth20("BTCUSDT","WS",1_010,
                2,0,0,1,2,0,depth(100),asks(101));capture.flushThrough(1_250,3);
        for(CausalMarketRecord record:sink.values)assertFinite(record.toMap());}

    private static MicrostructureCaptureV2 capture(Sink sink){MicrostructureCaptureV2 value=
            new MicrostructureCaptureV2(sink);assertTrue(value.startSession("s","FUTURES",1_000,1));
        return value;}
    private static double[][] depth(double best){double[][] out=new double[20][2];for(int i=0;i<20;i++){
        out[i][0]=best-i*.01;out[i][1]=i+1;}return out;}
    private static double[][] asks(double best){double[][] out=new double[20][2];for(int i=0;i<20;i++){
        out[i][0]=best+i*.01;out[i][1]=i+1;}return out;}
    @SuppressWarnings("unchecked") private static void assertFinite(Object value){if(value==null)return;
        if(value instanceof Number){assertTrue(Double.isFinite(((Number)value).doubleValue()));return;}
        if(value instanceof Map)for(Object child:((Map<?,?>)value).values())assertFinite(child);
        if(value instanceof Iterable)for(Object child:(Iterable<?>)value)assertFinite(child);}
    private static final class Sink implements CausalMarketCapture.Sink{final List<CausalMarketRecord>
        values=new ArrayList<>();public boolean offer(CausalMarketRecord value){values.add(value);return true;}
        int count(CausalMarketRecord.Kind kind){int count=0;for(CausalMarketRecord value:values)
            if(value.kind==kind)count++;return count;}MicrostructureMarketRecord last(
                CausalMarketRecord.Kind kind,String symbol){for(int i=values.size()-1;i>=0;i--){
            CausalMarketRecord value=values.get(i);if(value.kind==kind&&symbol.equals(value.symbol))
                return (MicrostructureMarketRecord)value;}return null;}}
}
