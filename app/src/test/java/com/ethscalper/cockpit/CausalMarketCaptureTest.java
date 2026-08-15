package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class CausalMarketCaptureTest {
    @Test public void quoteRetainsCausalAndExchangeClocks() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        assertTrue(capture.startSession("s1","BINANCE_FUTURES_WS",1_000,50));
        assertTrue(capture.observeBookTicker("ETHUSDT",1_010,60,900,905,7,
                100,2,101,3));
        CausalMarketRecord quote=sink.last(CausalMarketRecord.Kind.QUOTE,"ETHUSDT");
        assertEquals(1_010,quote.receivedAt);assertEquals(900,quote.exchangeEventAt);
        assertEquals(100,quote.bid,0);assertEquals(3,quote.askQuantity,0);
        assertTrue(quote.sequence>sink.values.get(0).sequence);
    }

    @Test public void oneSecondFlowUsesReceiveBucketAndExactMakerSplit() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",1_000,1);
        assertTrue(capture.observeAggregateTrade("SOLUSDT",1_100,2,10,900,100,2,false));
        assertTrue(capture.observeAggregateTrade("SOLUSDT",1_900,3,12,1_850,101,3,true));
        capture.flushThrough(2_000,4);CausalMarketRecord flow=
                sink.last(CausalMarketRecord.Kind.FLOW_1S,"SOLUSDT");
        assertEquals(1_000,flow.bucketStartAt);assertEquals(2,flow.aggregateCount);
        assertEquals(1,flow.aggregateIdGaps);assertEquals(2,flow.buyerBase,0);
        assertEquals(3,flow.sellerBase,0);assertEquals(200,flow.buyerNotional,0);
        assertEquals(303,flow.sellerNotional,0);assertEquals(100,flow.open,0);
        assertEquals(101,flow.close,0);assertEquals(503d/5d,flow.vwap(),1e-12);
    }

    @Test public void absentTradesAreExplicitZeroFlowNotNan() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",1_000,1);capture.flushThrough(2_000,2);
        CausalMarketRecord eth=sink.last(CausalMarketRecord.Kind.FLOW_1S,"ETHUSDT");
        assertFalse(eth.hasTrades);assertEquals(0,eth.aggregateCount);
        assertNull(eth.toMap().get("open"));assertEquals(0d,(Double)eth.toMap().get("totalBase"),0);
    }

    @Test public void largeSilenceBecomesGapRatherThanUnboundedZeroFill() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",0,0);capture.flushThrough(20_000,20_000);
        assertNotNull(sink.last(CausalMarketRecord.Kind.GAP,"ETHUSDT"));
        assertTrue(capture.stats().emittedFlows<=3);
    }

    @Test public void clockRegressionAndLateTradeAreRejected() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",1_000,10);
        assertTrue(capture.observeAggregateTrade("ETHUSDT",1_100,11,5,1_000,100,1,false));
        assertFalse(capture.observeAggregateTrade("ETHUSDT",1_200,12,5,1_100,100,1,false));
        assertFalse(capture.observeBookTicker("ETHUSDT",999,13,0,0,1,100,1,101,1));
        assertEquals(1,capture.stats().lateTrades);assertEquals(1,capture.stats().clockRegressions);
    }

    @Test public void invalidAndNonFiniteInputsNeverReachSink() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",0,0);int initial=sink.values.size();
        assertFalse(capture.observeBookTicker("ETHUSDT",1,1,0,0,1,
                Double.NaN,1,101,1));
        assertFalse(capture.observeBookTicker("UNKNOWN",1,1,0,0,1,100,1,101,1));
        assertFalse(capture.observeAggregateTrade("SOLUSDT",1,1,1,1,100,-1,false));
        assertEquals(initial,sink.values.size());assertEquals(3,capture.stats().invalidInputs);
    }

    @Test public void overflowingNotionalIsRejectedWithoutThrowing() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",0,0);assertFalse(capture.observeAggregateTrade(
                "BTCUSDT",1,1,1,1,Double.MAX_VALUE,2,false));
        assertEquals(1,capture.stats().invalidInputs);
    }

    @Test public void exchangeTimestampRegressionDoesNotCorruptLocalCausalFlow(){
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",1_000,1);
        assertTrue(capture.observeAggregateTrade("ETHUSDT",1_100,2,1,2_000,100,1,false));
        assertTrue(capture.observeAggregateTrade("ETHUSDT",1_200,3,2,1_000,101,1,false));
        capture.flushThrough(2_000,4);CausalMarketRecord flow=
                sink.last(CausalMarketRecord.Kind.FLOW_1S,"ETHUSDT");
        assertEquals(2_000,flow.firstTradeAt);assertEquals(1_000,flow.lastTradeAt);
        assertEquals(2,flow.aggregateCount);
    }

    @Test public void overflowingTotalsProduceNullVwapNotNan(){
        CausalMarketRecord record=CausalMarketRecord.flow("s",1,2_000,2_000,"ETHUSDT","WS",
                1_000,2_000,1,2,1_100,1_900,2,0,true,100,101,100,101,
                Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE);
        assertNull(record.vwap());assertNull(record.toMap().get("vwap"));
        assertNull(record.toMap().get("totalBase"));assertNull(record.toMap().get("totalNotional"));
    }

    @Test public void newSessionResetsLocalSequenceForRestartSafeReplay() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s1","WS",0,0);capture.observeBookTicker("ETHUSDT",1,1,
                0,0,1,100,1,101,1);capture.startSession("s2","WS",2,2);
        assertEquals(1,sink.values.get(2).sequence);
        assertEquals(2,CausalCaptureReplay.validate(sink.values).sessions);
    }

    @Test public void boundedQueueNeverBlocksAndCountsDrops() {
        CausalCaptureQueue queue=new CausalCaptureQueue(2);
        CausalMarketCapture capture=new CausalMarketCapture(queue);
        capture.startSession("s","WS",0,0);
        assertTrue(capture.observeBookTicker("ETHUSDT",1,1,0,0,1,100,1,101,1));
        assertFalse(capture.observeBookTicker("ETHUSDT",2,2,0,0,2,100,1,101,1));
        assertEquals(2,queue.size());assertEquals(1,queue.rejected());
        assertEquals(1,capture.stats().droppedRecords);assertEquals(2,queue.drain(10).size());
    }

    @Test public void sinkFailureIsFailOpenAndBoundedInStats() {
        CausalMarketCapture capture=new CausalMarketCapture(record->{throw new IllegalStateException("disk");});
        assertFalse(capture.startSession("s","WS",0,0));
        assertFalse(capture.observeBookTicker("ETHUSDT",1,1,0,0,1,100,1,101,1));
        assertEquals(1,capture.stats().sinkErrors);assertEquals(1,capture.stats().droppedRecords);
    }

    @Test public void mapsContainOnlyFiniteNumbersOrNull() {
        Sink sink=new Sink();CausalMarketCapture capture=new CausalMarketCapture(sink);
        capture.startSession("s","WS",0,0);capture.flushThrough(1_000,1_000);
        for(CausalMarketRecord record:sink.values)for(Object value:record.toMap().values())
            if(value instanceof Number)assertTrue(Double.isFinite(((Number)value).doubleValue()));
    }

    private static final class Sink implements CausalMarketCapture.Sink {
        final List<CausalMarketRecord> values=new ArrayList<>();
        @Override public boolean offer(CausalMarketRecord record){values.add(record);return true;}
        CausalMarketRecord last(CausalMarketRecord.Kind kind,String symbol){for(int i=values.size()-1;i>=0;i--){
            CausalMarketRecord value=values.get(i);if(value.kind==kind&&symbol.equals(value.symbol))return value;}
            return null;}
    }
}
