package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable causal market-capture record.
 *
 * <p>The capture clock is {@link #receivedAt}: exchange timestamps are retained as evidence but
 * never used to move information into an earlier local bucket. Numeric values exposed by
 * {@link #toMap()} are always finite or {@code null}.</p>
 */
public class CausalMarketRecord {
    public static final String SCHEMA = "NMC_CAUSAL_MARKET_CAPTURE_V1";
    public static final int FORMAT_VERSION = 1;

    public enum Kind {
        SESSION, QUOTE, FLOW_1S, GAP,
        TOP_OF_BOOK_SAMPLE, FLOW_100MS, DEPTH20_SAMPLE, DROP_SUMMARY, HEALTH,
        LIQUIDATION_SNAPSHOT, DEPTH_DIFF, DEPTH_BOOTSTRAP
    }

    public final Kind kind;
    public final String sessionId;
    public final long sequence;
    public final long receivedAt;
    public final long monotonicAt;
    public final String symbol;
    public final String source;

    // Quote fields.
    public final long exchangeEventAt, transactionAt, updateId;
    public final double bid, bidQuantity, ask, askQuantity;

    // One-second flow fields. Buckets are based on local receive time.
    public final long bucketStartAt, bucketEndAt, firstTradeId, lastTradeId;
    public final long firstTradeAt, lastTradeAt, aggregateCount, aggregateIdGaps;
    public final boolean hasTrades;
    public final double open, high, low, close;
    public final double buyerBase, sellerBase, buyerNotional, sellerNotional;

    // Session/gap details.
    public final long gapFromAt, gapToAt;
    public final String reasonCode;

    protected CausalMarketRecord(Kind kind, String sessionId, long sequence, long receivedAt,
                               long monotonicAt, String symbol, String source,
                               long exchangeEventAt, long transactionAt, long updateId,
                               double bid, double bidQuantity, double ask, double askQuantity,
                               long bucketStartAt, long bucketEndAt, long firstTradeId,
                               long lastTradeId, long firstTradeAt, long lastTradeAt,
                               long aggregateCount, long aggregateIdGaps, boolean hasTrades,
                               double open, double high, double low, double close,
                               double buyerBase, double sellerBase, double buyerNotional,
                               double sellerNotional, long gapFromAt, long gapToAt,
                               String reasonCode) {
        this.kind=required(kind,"kind");
        this.sessionId=bounded(sessionId,"sessionId",128);
        if(this.sessionId.isEmpty())throw new IllegalArgumentException("sessionId");
        if(sequence<=0)throw new IllegalArgumentException("sequence");
        if(receivedAt<0||monotonicAt<0)throw new IllegalArgumentException("clock");
        this.sequence=sequence;this.receivedAt=receivedAt;this.monotonicAt=monotonicAt;
        this.symbol=bounded(symbol,"symbol",24);this.source=bounded(source,"source",96);
        if(this.source.isEmpty())throw new IllegalArgumentException("source");
        this.exchangeEventAt=exchangeEventAt;this.transactionAt=transactionAt;
        this.updateId=updateId;this.bid=bid;this.bidQuantity=bidQuantity;this.ask=ask;
        this.askQuantity=askQuantity;this.bucketStartAt=bucketStartAt;
        this.bucketEndAt=bucketEndAt;this.firstTradeId=firstTradeId;
        this.lastTradeId=lastTradeId;this.firstTradeAt=firstTradeAt;
        this.lastTradeAt=lastTradeAt;this.aggregateCount=aggregateCount;
        this.aggregateIdGaps=aggregateIdGaps;this.hasTrades=hasTrades;this.open=open;
        this.high=high;this.low=low;this.close=close;this.buyerBase=buyerBase;
        this.sellerBase=sellerBase;this.buyerNotional=buyerNotional;
        this.sellerNotional=sellerNotional;this.gapFromAt=gapFromAt;
        this.gapToAt=gapToAt;this.reasonCode=bounded(reasonCode,"reasonCode",160);
        validate();
    }

    public static CausalMarketRecord session(String sessionId,long sequence,long receivedAt,
                                             long monotonicAt,String source,String reasonCode) {
        return new CausalMarketRecord(Kind.SESSION,sessionId,sequence,receivedAt,monotonicAt,
                "*",source,0,0,-1,0,0,0,0,0,0,-1,-1,0,0,0,0,false,
                0,0,0,0,0,0,0,0,0,0,reasonCode);
    }

    public static CausalMarketRecord quote(String sessionId,long sequence,long receivedAt,
                                           long monotonicAt,String symbol,String source,
                                           long exchangeEventAt,long transactionAt,long updateId,
                                           double bid,double bidQuantity,double ask,
                                           double askQuantity) {
        return new CausalMarketRecord(Kind.QUOTE,sessionId,sequence,receivedAt,monotonicAt,
                symbol,source,exchangeEventAt,transactionAt,updateId,bid,bidQuantity,ask,
                askQuantity,0,0,-1,-1,0,0,0,0,false,0,0,0,0,0,0,0,0,0,0,"");
    }

    public static CausalMarketRecord flow(String sessionId,long sequence,long receivedAt,
                                          long monotonicAt,String symbol,String source,
                                          long bucketStartAt,long bucketEndAt,
                                          long firstTradeId,long lastTradeId,long firstTradeAt,
                                          long lastTradeAt,long aggregateCount,long aggregateIdGaps,
                                          boolean hasTrades,double open,double high,double low,
                                          double close,double buyerBase,double sellerBase,
                                          double buyerNotional,double sellerNotional) {
        return new CausalMarketRecord(Kind.FLOW_1S,sessionId,sequence,receivedAt,monotonicAt,
                symbol,source,0,0,-1,0,0,0,0,bucketStartAt,bucketEndAt,firstTradeId,
                lastTradeId,firstTradeAt,lastTradeAt,aggregateCount,aggregateIdGaps,hasTrades,
                open,high,low,close,buyerBase,sellerBase,buyerNotional,sellerNotional,0,0,"");
    }

    public static CausalMarketRecord gap(String sessionId,long sequence,long receivedAt,
                                         long monotonicAt,String symbol,String source,
                                         long gapFromAt,long gapToAt,String reasonCode) {
        return new CausalMarketRecord(Kind.GAP,sessionId,sequence,receivedAt,monotonicAt,
                symbol,source,0,0,-1,0,0,0,0,0,0,-1,-1,0,0,0,0,false,
                0,0,0,0,0,0,0,0,gapFromAt,gapToAt,reasonCode);
    }

    public double totalBase(){return buyerBase+sellerBase;}
    public double totalNotional(){return buyerNotional+sellerNotional;}
    public Double vwap(){double base=totalBase(),notional=totalNotional();
        if(!hasTrades||!Double.isFinite(base)||base<=0||!Double.isFinite(notional))return null;
        double value=notional/base;return Double.isFinite(value)?value:null;}

    public Map<String,Object> toMap() {
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("schema",SCHEMA);out.put("formatVersion",FORMAT_VERSION);
        out.put("kind",kind.name());out.put("sessionId",sessionId);
        out.put("sequence",sequence);out.put("receivedAt",receivedAt);
        out.put("monotonicAt",monotonicAt);out.put("symbol",symbol);out.put("source",source);
        if(kind==Kind.QUOTE){out.put("exchangeEventAt",nullableTime(exchangeEventAt));
            out.put("transactionAt",nullableTime(transactionAt));out.put("updateId",updateId);
            put(out,"bid",bid);put(out,"bidQuantity",bidQuantity);put(out,"ask",ask);
            put(out,"askQuantity",askQuantity);}
        else if(kind==Kind.FLOW_1S){out.put("bucketStartAt",bucketStartAt);
            out.put("bucketEndAt",bucketEndAt);out.put("hasTrades",hasTrades);
            out.put("firstTradeId",hasTrades?firstTradeId:null);
            out.put("lastTradeId",hasTrades?lastTradeId:null);
            out.put("firstTradeAt",hasTrades?firstTradeAt:null);
            out.put("lastTradeAt",hasTrades?lastTradeAt:null);
            out.put("aggregateCount",aggregateCount);out.put("aggregateIdGaps",aggregateIdGaps);
            putNullable(out,"open",open,hasTrades);putNullable(out,"high",high,hasTrades);
            putNullable(out,"low",low,hasTrades);putNullable(out,"close",close,hasTrades);
            put(out,"buyerBase",buyerBase);put(out,"sellerBase",sellerBase);
            put(out,"buyerNotional",buyerNotional);put(out,"sellerNotional",sellerNotional);
            put(out,"totalBase",totalBase());put(out,"totalNotional",totalNotional());
            out.put("vwap",vwap());}
        else if(kind==Kind.GAP){out.put("gapFromAt",gapFromAt);out.put("gapToAt",gapToAt);
            out.put("reasonCode",reasonCode);}
        else out.put("reasonCode",reasonCode);
        return Collections.unmodifiableMap(out);
    }

    private void validate() {
        if(kind==Kind.QUOTE){if(!supported(symbol)||!positive(bid)||!positive(ask)||ask<bid
                    ||!nonNegative(bidQuantity)||!nonNegative(askQuantity)||updateId<0)
                throw new IllegalArgumentException("quote");}
        else if(kind==Kind.FLOW_1S){if(!supported(symbol)||bucketStartAt<0
                    ||bucketEndAt!=bucketStartAt+1_000L||aggregateCount<0||aggregateIdGaps<0
                    ||!nonNegative(buyerBase)||!nonNegative(sellerBase)
                    ||!nonNegative(buyerNotional)||!nonNegative(sellerNotional))
                throw new IllegalArgumentException("flow");
            if(hasTrades){if(aggregateCount<1||firstTradeId<0||lastTradeId<firstTradeId
                        ||firstTradeAt<0||lastTradeAt<0||!positive(open)
                        ||!positive(high)||!positive(low)||!positive(close)
                        ||high<Math.max(open,close)||low>Math.min(open,close))
                    throw new IllegalArgumentException("trades");}
            else if(aggregateCount!=0||buyerBase!=0||sellerBase!=0||buyerNotional!=0
                    ||sellerNotional!=0)throw new IllegalArgumentException("empty flow");}
        else if(kind==Kind.GAP){if(!("*".equals(symbol)||supported(symbol))
                    ||gapFromAt<0||gapToAt<=gapFromAt)
            throw new IllegalArgumentException("gap");}
    }

    public static boolean supported(String symbol){return "ETHUSDT".equals(symbol)
            ||"SOLUSDT".equals(symbol)||"BTCUSDT".equals(symbol);}
    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private static boolean nonNegative(double value){return Double.isFinite(value)&&value>=0;}
    private static Long nullableTime(long value){return value>0?value:null;}
    private static void put(Map<String,Object> out,String key,double value){out.put(key,
            Double.isFinite(value)?value:null);}
    private static void putNullable(Map<String,Object> out,String key,double value,boolean present){
        out.put(key,present&&Double.isFinite(value)?value:null);}
    private static <T> T required(T value,String name){if(value==null)
        throw new IllegalArgumentException(name);return value;}
    private static String bounded(String value,String name,int maximum){if(value==null)
        throw new IllegalArgumentException(name);String out=value.trim();if(out.length()>maximum)
        out=out.substring(0,maximum);return out;}
}
