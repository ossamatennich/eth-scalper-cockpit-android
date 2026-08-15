package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, causal capture core for Binance Futures public book tickers and aggregate trades.
 * It performs no I/O and never blocks. The supplied sink is expected to be bounded.
 */
public final class CausalMarketCapture {
    public static final long FLOW_BUCKET_MS=1_000L;
    public static final int MAX_EMPTY_BUCKET_FILL=5;
    private static final String[] SYMBOLS={"ETHUSDT","SOLUSDT","BTCUSDT"};

    public interface Sink { boolean offer(CausalMarketRecord record); }

    private final Sink sink;
    private final LinkedHashMap<String,FlowAccumulator> flows=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> nextBucket=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> lastTradeId=new LinkedHashMap<>();
    private String sessionId="",source="";
    private long sequence,lastReceivedAt,lastMonotonicAt;
    private long acceptedQuotes,acceptedTrades,emittedFlows,emittedGaps,droppedRecords;
    private long invalidInputs,lateTrades,clockRegressions,sinkErrors;

    public CausalMarketCapture(Sink sink){if(sink==null)throw new IllegalArgumentException("sink");
        this.sink=sink;for(String symbol:SYMBOLS){flows.put(symbol,new FlowAccumulator());
            nextBucket.put(symbol,Long.MIN_VALUE);lastTradeId.put(symbol,-1L);}}

    public synchronized boolean startSession(String id,String source,long receivedAt,long monotonicAt) {
        if(id==null||id.trim().isEmpty()||source==null||source.trim().isEmpty()
                ||receivedAt<0||monotonicAt<0){invalidInputs++;return false;}
        clearFlowState();this.sessionId=bounded(id,128);this.source=bounded(source,96);
        this.sequence=0;
        this.lastReceivedAt=receivedAt;this.lastMonotonicAt=monotonicAt;
        long bucket=bucket(receivedAt);for(String symbol:SYMBOLS)nextBucket.put(symbol,bucket);
        boolean emitted=emit(CausalMarketRecord.session(sessionId,nextSequence(),receivedAt,
                monotonicAt,this.source,"SESSION_STARTED"));
        if(!emitted){this.sessionId="";this.source="";}return emitted;
    }

    public synchronized boolean observeBookTicker(String symbol,long receivedAt,long monotonicAt,
                                                  long exchangeEventAt,long transactionAt,
                                                  long updateId,double bid,double bidQuantity,
                                                  double ask,double askQuantity) {
        if(!active()||!validClock(receivedAt,monotonicAt)||!CausalMarketRecord.supported(symbol)
                ||!positive(bid)||!positive(ask)||ask<bid||!nonNegative(bidQuantity)
                ||!nonNegative(askQuantity)||updateId<0){invalidInputs++;return false;}
        acceptedQuotes++;advanceClock(receivedAt,monotonicAt);
        try{return emit(CausalMarketRecord.quote(sessionId,nextSequence(),receivedAt,monotonicAt,
                symbol,source,exchangeEventAt,transactionAt,updateId,bid,bidQuantity,ask,
                askQuantity));}catch(RuntimeException error){sinkErrors++;return false;}
    }

    public synchronized boolean observeAggregateTrade(String symbol,long receivedAt,long monotonicAt,
                                                       long aggregateTradeId,long exchangeTradeAt,
                                                       double price,double quantity,
                                                       boolean buyerMaker) {
        if(!active()||!validClock(receivedAt,monotonicAt)||!CausalMarketRecord.supported(symbol)
                ||aggregateTradeId<0||exchangeTradeAt<0||!positive(price)||!positive(quantity)){
            invalidInputs++;return false;}
        long previous=lastTradeId.get(symbol);if(previous>=0&&aggregateTradeId<=previous){
            lateTrades++;return false;}
        double notional=price*quantity;if(!Double.isFinite(notional)){invalidInputs++;return false;}
        try{long target=bucket(receivedAt);FlowAccumulator flow=flows.get(symbol);
            if(flow.bucketStart==target&&!flow.canAdd(quantity,notional,buyerMaker)){
                invalidInputs++;return false;}
            // From here on advanceSymbol may emit records carrying this clock. Advance the
            // causal watermark first so no older observation can be accepted behind them.
            advanceClock(receivedAt,monotonicAt);
            advanceSymbol(symbol,target,receivedAt,monotonicAt);
            flow=flows.get(symbol);if(flow.bucketStart!=target)flow.reset(target);
            if(!flow.canAdd(quantity,notional,buyerMaker)){invalidInputs++;return false;}
            flow.add(aggregateTradeId,exchangeTradeAt,price,quantity,buyerMaker,previous,notional);
            lastTradeId.put(symbol,aggregateTradeId);acceptedTrades++;
            return true;
        }catch(RuntimeException error){sinkErrors++;return false;}
    }

    /** Emits all complete local-receive-time flow buckets up to {@code receivedAt}. */
    public synchronized void flushThrough(long receivedAt,long monotonicAt) {
        if(!active()||!validClock(receivedAt,monotonicAt)){invalidInputs++;return;}
        try{long exclusive=bucket(receivedAt);advanceClock(receivedAt,monotonicAt);
            for(String symbol:SYMBOLS)advanceSymbol(symbol,exclusive,receivedAt,monotonicAt);
        }catch(RuntimeException error){sinkErrors++;}
    }

    /** Records an explicit connectivity/capture gap and starts fresh one-second buckets. */
    public synchronized boolean gap(long fromAt,long toAt,long receivedAt,long monotonicAt,
                                    String reasonCode) {
        if(!active()||fromAt<0||toAt<=fromAt||toAt>receivedAt
                ||!validClock(receivedAt,monotonicAt)){
            invalidInputs++;return false;}
        try{clearFlowState();long next=bucket(Math.max(toAt,receivedAt));
            for(String symbol:SYMBOLS)nextBucket.put(symbol,next);
            advanceClock(receivedAt,monotonicAt);emittedGaps++;
            return emit(CausalMarketRecord.gap(sessionId,nextSequence(),receivedAt,monotonicAt,"*",
                    source,fromAt,toAt,bounded(reasonCode==null?"CAPTURE_GAP":reasonCode,160)));
        }catch(RuntimeException error){sinkErrors++;return false;}
    }

    public synchronized Stats stats(){return new Stats(sequence,acceptedQuotes,acceptedTrades,
            emittedFlows,emittedGaps,droppedRecords,invalidInputs,lateTrades,clockRegressions,
            sinkErrors,sessionId,source);}

    private void advanceSymbol(String symbol,long targetExclusive,long receivedAt,long monotonicAt) {
        long next=nextBucket.get(symbol);if(next==Long.MIN_VALUE){next=bucket(receivedAt);
            nextBucket.put(symbol,next);}
        if(targetExclusive<=next)return;
        long buckets=(targetExclusive-next)/FLOW_BUCKET_MS;
        if(buckets>MAX_EMPTY_BUCKET_FILL){FlowAccumulator active=flows.get(symbol);
            if(active.bucketStart==next)emitFlow(symbol,active,receivedAt,monotonicAt);
            long gapFrom=active.bucketStart==next?next+FLOW_BUCKET_MS:next;
            if(targetExclusive>gapFrom){emittedGaps++;emit(CausalMarketRecord.gap(sessionId,
                    nextSequence(),receivedAt,monotonicAt,symbol,source,gapFrom,targetExclusive,
                    "FLOW_RECEIVE_GAP"));}
            active.clear();nextBucket.put(symbol,targetExclusive);return;
        }
        while(next<targetExclusive){FlowAccumulator active=flows.get(symbol);
            if(active.bucketStart!=next)active.reset(next);
            emitFlow(symbol,active,receivedAt,monotonicAt);active.clear();
            next+=FLOW_BUCKET_MS;nextBucket.put(symbol,next);}
    }

    private void emitFlow(String symbol,FlowAccumulator flow,long receivedAt,long monotonicAt) {
        boolean has=flow.count>0;CausalMarketRecord record=CausalMarketRecord.flow(sessionId,
                nextSequence(),receivedAt,monotonicAt,symbol,source,flow.bucketStart,
                flow.bucketStart+FLOW_BUCKET_MS,has?flow.firstId:-1,has?flow.lastId:-1,
                has?flow.firstAt:0,has?flow.lastAt:0,flow.count,flow.idGaps,has,
                has?flow.open:0,has?flow.high:0,has?flow.low:0,has?flow.close:0,
                flow.buyerBase,flow.sellerBase,flow.buyerNotional,flow.sellerNotional);
        emittedFlows++;emit(record);
    }

    private boolean emit(CausalMarketRecord record) {
        try{boolean accepted=sink.offer(record);if(!accepted)droppedRecords++;return accepted;}
        catch(RuntimeException error){sinkErrors++;droppedRecords++;return false;}
    }

    private boolean validClock(long receivedAt,long monotonicAt) {
        if(receivedAt<0||monotonicAt<0)return false;
        if(receivedAt<lastReceivedAt||monotonicAt<lastMonotonicAt){clockRegressions++;return false;}
        return true;
    }
    private void advanceClock(long receivedAt,long monotonicAt){lastReceivedAt=receivedAt;
        lastMonotonicAt=monotonicAt;}
    private boolean active(){return !sessionId.isEmpty();}
    private long nextSequence(){return ++sequence;}
    private static long bucket(long at){return at-at%FLOW_BUCKET_MS;}
    private void clearFlowState(){for(FlowAccumulator value:flows.values())value.clear();
        for(String symbol:SYMBOLS){nextBucket.put(symbol,Long.MIN_VALUE);lastTradeId.put(symbol,-1L);}}
    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private static boolean nonNegative(double value){return Double.isFinite(value)&&value>=0;}
    private static String bounded(String value,int maximum){String out=value.trim();return out.length()
            <=maximum?out:out.substring(0,maximum);}

    private static final class FlowAccumulator {
        long bucketStart=Long.MIN_VALUE,firstId,lastId,firstAt,lastAt,count,idGaps;
        double open,high,low,close,buyerBase,sellerBase,buyerNotional,sellerNotional;
        void reset(long bucket){clear();bucketStart=bucket;}
        void clear(){bucketStart=Long.MIN_VALUE;firstId=lastId=-1;firstAt=lastAt=count=idGaps=0;
            open=high=low=close=buyerBase=sellerBase=buyerNotional=sellerNotional=0;}
        boolean canAdd(double quantity,double notional,boolean buyerMaker){return buyerMaker
                ?Double.isFinite(sellerBase+quantity)&&Double.isFinite(sellerNotional+notional)
                :Double.isFinite(buyerBase+quantity)&&Double.isFinite(buyerNotional+notional);}
        void add(long id,long at,double price,double quantity,boolean buyerMaker,long previous,
                 double notional){
            if(count==0){firstId=id;firstAt=at;open=high=low=close=price;}
            else{high=Math.max(high,price);low=Math.min(low,price);close=price;}
            lastId=id;lastAt=at;count++;if(previous>=0&&id>previous+1)idGaps+=id-previous-1;
            if(buyerMaker){sellerBase+=quantity;
                sellerNotional+=notional;}else{buyerBase+=quantity;buyerNotional+=notional;}}
    }

    public static final class Stats {
        public final long sequence,acceptedQuotes,acceptedTrades,emittedFlows,emittedGaps;
        public final long droppedRecords,invalidInputs,lateTrades,clockRegressions,sinkErrors;
        public final String sessionId,source;
        Stats(long sequence,long acceptedQuotes,long acceptedTrades,long emittedFlows,
              long emittedGaps,long droppedRecords,long invalidInputs,long lateTrades,
              long clockRegressions,long sinkErrors,String sessionId,String source){
            this.sequence=sequence;this.acceptedQuotes=acceptedQuotes;
            this.acceptedTrades=acceptedTrades;this.emittedFlows=emittedFlows;
            this.emittedGaps=emittedGaps;this.droppedRecords=droppedRecords;
            this.invalidInputs=invalidInputs;this.lateTrades=lateTrades;
            this.clockRegressions=clockRegressions;this.sinkErrors=sinkErrors;
            this.sessionId=sessionId;this.source=source;}
        public Map<String,Object> toMap(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            out.put("schema",CausalMarketRecord.SCHEMA);out.put("sessionId",sessionId);
            out.put("source",source);out.put("sequence",sequence);
            out.put("acceptedQuotes",acceptedQuotes);out.put("acceptedTrades",acceptedTrades);
            out.put("emittedFlows",emittedFlows);out.put("emittedGaps",emittedGaps);
            out.put("droppedRecords",droppedRecords);out.put("invalidInputs",invalidInputs);
            out.put("lateTrades",lateTrades);out.put("clockRegressions",clockRegressions);
            out.put("sinkErrors",sinkErrors);return java.util.Collections.unmodifiableMap(out);}
    }
}
