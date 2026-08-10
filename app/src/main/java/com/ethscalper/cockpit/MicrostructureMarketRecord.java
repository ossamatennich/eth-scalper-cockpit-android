package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable V2 research record. V1 records remain readable through {@link CausalMarketRecord}. */
public final class MicrostructureMarketRecord extends CausalMarketRecord {
    public static final String SCHEMA = "NMC_CAUSAL_MARKET_CAPTURE_V2";
    public static final int FORMAT_VERSION = 2;

    public final long firstReceivedAt;
    public final long lastReceivedAt;
    public final long firstUpdateId;
    public final long finalUpdateId;
    public final long previousFinalUpdateId;
    public final double[][] bids;
    public final double[][] asks;
    public final Map<String,Long> droppedByKind;

    private MicrostructureMarketRecord(Kind kind,String sessionId,long sequence,long receivedAt,
            long monotonicAt,String symbol,String source,long exchangeEventAt,long transactionAt,
            long updateId,double bid,double bidQuantity,double ask,double askQuantity,
            long bucketStartAt,long bucketEndAt,long firstTradeId,long lastTradeId,
            long firstTradeAt,long lastTradeAt,long aggregateCount,long aggregateIdGaps,
            boolean hasTrades,double open,double high,double low,double close,double buyerBase,
            double sellerBase,double buyerNotional,double sellerNotional,long gapFromAt,
            long gapToAt,String reasonCode,long firstReceivedAt,long lastReceivedAt,
            long firstUpdateId,long finalUpdateId,long previousFinalUpdateId,double[][] bids,
            double[][] asks,Map<String,Long> droppedByKind) {
        super(kind,sessionId,sequence,receivedAt,monotonicAt,symbol,source,exchangeEventAt,
                transactionAt,updateId,bid,bidQuantity,ask,askQuantity,bucketStartAt,bucketEndAt,
                firstTradeId,lastTradeId,firstTradeAt,lastTradeAt,aggregateCount,aggregateIdGaps,
                hasTrades,open,high,low,close,buyerBase,sellerBase,buyerNotional,sellerNotional,
                gapFromAt,gapToAt,reasonCode);
        this.firstReceivedAt=firstReceivedAt;this.lastReceivedAt=lastReceivedAt;
        this.firstUpdateId=firstUpdateId;this.finalUpdateId=finalUpdateId;
        this.previousFinalUpdateId=previousFinalUpdateId;
        this.bids=copyLevels(bids);this.asks=copyLevels(asks);
        this.droppedByKind=immutableCounts(droppedByKind);
        validateV2();
    }

    public static MicrostructureMarketRecord session(String sessionId,long sequence,long receivedAt,
            long monotonicAt,String source,String reasonCode) {
        return base(Kind.SESSION,sessionId,sequence,receivedAt,monotonicAt,"*",source,
                reasonCode,0,0,null,null,null);
    }

    public static MicrostructureMarketRecord topBook(String sessionId,long sequence,long receivedAt,
            long monotonicAt,String symbol,String source,long exchangeEventAt,long transactionAt,
            long updateId,double bid,double bidQuantity,double ask,double askQuantity) {
        return new MicrostructureMarketRecord(Kind.TOP_OF_BOOK_SAMPLE,sessionId,sequence,
                receivedAt,monotonicAt,symbol,source,exchangeEventAt,transactionAt,updateId,bid,
                bidQuantity,ask,askQuantity,0,0,-1,-1,0,0,0,0,false,0,0,0,0,0,0,0,0,
                0,0,"",receivedAt,receivedAt,0,0,0,null,null,null);
    }

    public static MicrostructureMarketRecord flow100(String sessionId,long sequence,long receivedAt,
            long monotonicAt,String symbol,String source,long bucketStartAt,long firstReceivedAt,
            long lastReceivedAt,long firstTradeId,long lastTradeId,long firstTradeAt,
            long lastTradeAt,long aggregateCount,long aggregateIdGaps,double open,double high,
            double low,double close,double buyerBase,double sellerBase,double buyerNotional,
            double sellerNotional) {
        return new MicrostructureMarketRecord(Kind.FLOW_100MS,sessionId,sequence,receivedAt,
                monotonicAt,symbol,source,0,0,-1,0,0,0,0,bucketStartAt,bucketStartAt+100L,
                firstTradeId,lastTradeId,firstTradeAt,lastTradeAt,aggregateCount,aggregateIdGaps,
                true,open,high,low,close,buyerBase,sellerBase,buyerNotional,sellerNotional,0,0,
                "",firstReceivedAt,lastReceivedAt,0,0,0,null,null,null);
    }

    public static MicrostructureMarketRecord depth20(String sessionId,long sequence,long receivedAt,
            long monotonicAt,String symbol,String source,long exchangeEventAt,long transactionAt,
            long firstUpdateId,long finalUpdateId,long previousFinalUpdateId,double[][] bids,
            double[][] asks) {
        return new MicrostructureMarketRecord(Kind.DEPTH20_SAMPLE,sessionId,sequence,receivedAt,
                monotonicAt,symbol,source,exchangeEventAt,transactionAt,finalUpdateId,0,0,0,0,
                0,0,-1,-1,0,0,0,0,false,0,0,0,0,0,0,0,0,0,0,"",receivedAt,receivedAt,
                firstUpdateId,finalUpdateId,previousFinalUpdateId,bids,asks,null);
    }

    public static MicrostructureMarketRecord gap(String sessionId,long sequence,long receivedAt,
            long monotonicAt,String symbol,String source,long fromAt,long toAt,String reasonCode) {
        return base(Kind.GAP,sessionId,sequence,receivedAt,monotonicAt,symbol,source,reasonCode,
                fromAt,toAt,null,null,null);
    }

    public static MicrostructureMarketRecord dropSummary(String sessionId,long sequence,
            long receivedAt,long monotonicAt,String source,long fromAt,long toAt,
            Map<String,Long> droppedByKind) {
        return base(Kind.DROP_SUMMARY,sessionId,sequence,receivedAt,monotonicAt,"*",source,
                "BOUNDED_QUEUE_DROP",fromAt,toAt,null,null,droppedByKind);
    }

    public static MicrostructureMarketRecord health(String sessionId,long sequence,long receivedAt,
            long monotonicAt,String source,String reasonCode) {
        return base(Kind.HEALTH,sessionId,sequence,receivedAt,monotonicAt,"*",source,
                reasonCode,0,0,null,null,null);
    }

    private static MicrostructureMarketRecord base(Kind kind,String sessionId,long sequence,
            long receivedAt,long monotonicAt,String symbol,String source,String reasonCode,
            long fromAt,long toAt,double[][] bids,double[][] asks,Map<String,Long> drops) {
        return new MicrostructureMarketRecord(kind,sessionId,sequence,receivedAt,monotonicAt,
                symbol,source,0,0,-1,0,0,0,0,0,0,-1,-1,0,0,0,0,false,0,0,0,0,0,0,0,0,
                fromAt,toAt,reasonCode,receivedAt,receivedAt,0,0,0,bids,asks,drops);
    }

    @Override public Map<String,Object> toMap() {
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("schema",SCHEMA);out.put("formatVersion",FORMAT_VERSION);
        out.put("kind",kind.name());out.put("sessionId",sessionId);out.put("sequence",sequence);
        out.put("receivedAt",receivedAt);out.put("monotonicAt",monotonicAt);
        out.put("symbol",symbol);out.put("source",source);
        if(kind==Kind.TOP_OF_BOOK_SAMPLE){out.put("exchangeEventAt",nullable(exchangeEventAt));
            out.put("transactionAt",nullable(transactionAt));out.put("updateId",updateId);
            finite(out,"bid",bid);finite(out,"bidQuantity",bidQuantity);finite(out,"ask",ask);
            finite(out,"askQuantity",askQuantity);}
        else if(kind==Kind.FLOW_100MS){out.put("bucketStartAt",bucketStartAt);
            out.put("bucketEndAt",bucketEndAt);out.put("firstReceivedAt",firstReceivedAt);
            out.put("lastReceivedAt",lastReceivedAt);out.put("firstTradeId",firstTradeId);
            out.put("lastTradeId",lastTradeId);out.put("firstTradeAt",firstTradeAt);
            out.put("lastTradeAt",lastTradeAt);out.put("aggregateCount",aggregateCount);
            out.put("aggregateIdGaps",aggregateIdGaps);finite(out,"open",open);
            finite(out,"high",high);finite(out,"low",low);finite(out,"close",close);
            finite(out,"buyerBase",buyerBase);finite(out,"sellerBase",sellerBase);
            finite(out,"buyerNotional",buyerNotional);finite(out,"sellerNotional",sellerNotional);}
        else if(kind==Kind.DEPTH20_SAMPLE){out.put("exchangeEventAt",nullable(exchangeEventAt));
            out.put("transactionAt",nullable(transactionAt));out.put("firstUpdateId",firstUpdateId);
            out.put("finalUpdateId",finalUpdateId);out.put("previousFinalUpdateId",previousFinalUpdateId);
            out.put("bids",levelList(bids));out.put("asks",levelList(asks));}
        else if(kind==Kind.GAP){out.put("gapFromAt",gapFromAt);out.put("gapToAt",gapToAt);
            out.put("reasonCode",reasonCode);}
        else if(kind==Kind.DROP_SUMMARY){out.put("gapFromAt",gapFromAt);
            out.put("gapToAt",gapToAt);out.put("reasonCode",reasonCode);
            out.put("droppedByKind",droppedByKind);}
        else out.put("reasonCode",reasonCode);
        return Collections.unmodifiableMap(out);
    }

    private void validateV2() {
        if(kind==Kind.TOP_OF_BOOK_SAMPLE&&(!supported(symbol)||!positive(bid)||!positive(ask)
                ||ask<bid||!nonNegative(bidQuantity)||!nonNegative(askQuantity)||updateId<0))
            throw new IllegalArgumentException("top book");
        if(kind==Kind.FLOW_100MS&&(!supported(symbol)||bucketStartAt<0
                ||bucketEndAt!=bucketStartAt+100L||aggregateCount<1||firstTradeId<0
                ||lastTradeId<firstTradeId||firstReceivedAt<bucketStartAt
                ||lastReceivedAt<firstReceivedAt||lastReceivedAt>=bucketEndAt
                ||!positive(open)||!positive(high)||!positive(low)||!positive(close)
                ||high<Math.max(open,close)||low>Math.min(open,close)
                ||!nonNegative(buyerBase)||!nonNegative(sellerBase)
                ||!nonNegative(buyerNotional)||!nonNegative(sellerNotional)))
            throw new IllegalArgumentException("flow100");
        if(kind==Kind.DEPTH20_SAMPLE&&(!supported(symbol)||bids.length!=20||asks.length!=20
                ||finalUpdateId<0||!validLevels(bids,true)||!validLevels(asks,false)))
            throw new IllegalArgumentException("depth20");
        if((kind==Kind.GAP||kind==Kind.DROP_SUMMARY)&&(gapFromAt<0||gapToAt<=gapFromAt))
            throw new IllegalArgumentException("gap");
        if(kind==Kind.DROP_SUMMARY&&droppedByKind.isEmpty())
            throw new IllegalArgumentException("drops");
    }

    private static boolean validLevels(double[][] levels,boolean bids) {
        double prior=bids?Double.POSITIVE_INFINITY:0d;
        for(double[] level:levels){if(level==null||level.length!=2||!positive(level[0])
                ||!nonNegative(level[1]))return false;
            if(bids&&level[0]>prior||!bids&&level[0]<prior)return false;prior=level[0];}
        return true;
    }
    private static double[][] copyLevels(double[][] value){if(value==null)return new double[0][0];
        double[][] out=new double[value.length][2];for(int i=0;i<value.length;i++){
            if(value[i]==null||value[i].length<2)throw new IllegalArgumentException("levels");
            out[i][0]=value[i][0];out[i][1]=value[i][1];}return out;}
    private static Map<String,Long> immutableCounts(Map<String,Long> value){LinkedHashMap<String,Long> out=
            new LinkedHashMap<>();if(value!=null)for(Map.Entry<String,Long> entry:value.entrySet())
            if(entry.getKey()!=null&&entry.getValue()!=null&&entry.getValue()>0)
                out.put(entry.getKey(),entry.getValue());return Collections.unmodifiableMap(out);}
    private static List<List<Double>> levelList(double[][] levels){ArrayList<List<Double>> out=
            new ArrayList<>(levels.length);for(double[] level:levels)out.add(List.of(level[0],level[1]));
        return Collections.unmodifiableList(out);}
    private static Long nullable(long value){return value>0?value:null;}
    private static void finite(Map<String,Object> out,String key,double value){out.put(key,
            Double.isFinite(value)?value:null);}
    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private static boolean nonNegative(double value){return Double.isFinite(value)&&value>=0;}
}
