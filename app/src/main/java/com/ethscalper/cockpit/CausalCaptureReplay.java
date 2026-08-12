package com.ethscalper.cockpit;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Strict streaming/replay validation helpers for causal capture snapshots. */
public final class CausalCaptureReplay {
    private CausalCaptureReplay() {}

    public static Validation replay(List<File> files,Consumer<CausalMarketRecord> consumer)
            throws Exception {
        ValidationState state=new ValidationState(true);
        CausalCaptureStore.ScanResult scan=CausalCaptureStore.scan(files,true,record->{
            state.accept(record);if(consumer!=null)consumer.accept(record);});
        return state.result(scan.records,scan.corruptBlocks,scan.truncatedTails);
    }

    public static Validation validate(List<CausalMarketRecord> records) {
        ValidationState state=new ValidationState(false);if(records!=null)
            for(CausalMarketRecord record:records)state.accept(record);
        return state.result(records==null?0:records.size(),0,0);
    }

    /** Returns the first executable quote strictly after a completed observation. */
    public static CausalMarketRecord firstQuoteAfter(List<CausalMarketRecord> records,String symbol,
                                                     long observedAt) {
        if(records==null)return null;String session=null;
        for(CausalMarketRecord record:records){if(record.receivedAt>observedAt)break;
            if(record.kind==CausalMarketRecord.Kind.SESSION)session=record.sessionId;}
        if(session==null)return null;
        for(CausalMarketRecord record:records){if(record.receivedAt<=observedAt)continue;
            if(record.kind==CausalMarketRecord.Kind.SESSION)return null;
            if(!session.equals(record.sessionId))return null;
            if(record.kind==CausalMarketRecord.Kind.GAP
                    &&record.gapToAt>observedAt)return null;
            if((record.kind==CausalMarketRecord.Kind.QUOTE
                    ||record.kind==CausalMarketRecord.Kind.TOP_OF_BOOK_SAMPLE)
                    &&record.symbol.equals(symbol))return record;}
        return null;
    }

    /** True only when every one-second receive bucket exists and no explicit gap overlaps it. */
    public static boolean continuousFlow(List<CausalMarketRecord> records,String symbol,
                                         long fromBucketAt,long toExclusiveAt) {
        if(records==null||!CausalMarketRecord.supported(symbol)||fromBucketAt<0
                ||toExclusiveAt<=fromBucketAt||fromBucketAt%1_000L!=0
                ||toExclusiveAt%1_000L!=0)return false;Set<Long> buckets=new HashSet<>();
        String session=null;for(CausalMarketRecord record:records)
            if(record.kind==CausalMarketRecord.Kind.FLOW_1S&&symbol.equals(record.symbol)
                    &&record.bucketStartAt>=fromBucketAt&&record.bucketStartAt<toExclusiveAt){
                if(session==null)session=record.sessionId;else if(!session.equals(record.sessionId))return false;
                buckets.add(record.bucketStartAt);}
        if(session==null)return false;for(CausalMarketRecord record:records)
            if(record.kind==CausalMarketRecord.Kind.GAP&&session.equals(record.sessionId)
                    &&("*".equals(record.symbol)||symbol.equals(record.symbol))
                    &&record.gapFromAt<toExclusiveAt&&record.gapToAt>fromBucketAt)return false;
        for(long at=fromBucketAt;at<toExclusiveAt;at+=1_000L)if(!buckets.contains(at))return false;
        return true;
    }

    /** Evaluates one raw quote in recorded arrival order. */
    public static Terminal terminal(String side,double takeProfit,double stopLoss,
                                    CausalMarketRecord quote) {
        if(quote==null||(quote.kind!=CausalMarketRecord.Kind.QUOTE
                &&quote.kind!=CausalMarketRecord.Kind.TOP_OF_BOOK_SAMPLE)
                ||!("LONG".equals(side)||"SHORT".equals(side))||!Double.isFinite(takeProfit)
                ||!Double.isFinite(stopLoss))return null;double touch="LONG".equals(side)
                ?quote.bid:quote.ask;boolean sl="LONG".equals(side)?touch<=stopLoss:touch>=stopLoss;
        boolean tp="LONG".equals(side)?touch>=takeProfit:touch<=takeProfit;
        if(sl)return new Terminal("SL_TOUCHED",touch,stopLoss,quote.receivedAt,quote.sequence);
        if(tp)return new Terminal("TP_TOUCHED",touch,takeProfit,quote.receivedAt,quote.sequence);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean finiteOrNull(Object value){if(value==null)return true;
        if(value instanceof Number)return Double.isFinite(((Number)value).doubleValue());
        if(value instanceof Map)for(Object child:((Map<Object,Object>)value).values())
            if(!finiteOrNull(child))return false;
        if(value instanceof Iterable)for(Object child:(Iterable<?>)value)if(!finiteOrNull(child))return false;
        return true;}

    private static final class ValidationState {
        final boolean rejectSequenceGaps;long lastSequence,sequenceGaps,lastReceived,lastMonotonic;
        String session="";int sessions,quotes,flows,depthSamples,liquidationSnapshots,
                dropSummaries,healthMarkers,gaps;
        final Map<String,Long> lastFlow=new java.util.HashMap<>();
        ValidationState(boolean rejectSequenceGaps){this.rejectSequenceGaps=rejectSequenceGaps;}
        void accept(CausalMarketRecord record){if(record==null)throw new IllegalArgumentException("record");
            if(record.kind==CausalMarketRecord.Kind.SESSION){if(record.sessionId.equals(session))
                throw new IllegalStateException("duplicate session marker");session=record.sessionId;
                sessions++;lastReceived=record.receivedAt;lastMonotonic=record.monotonicAt;
                lastFlow.clear();lastSequence=0;if(record.sequence!=1)
                    throw new IllegalStateException("session sequence");}
            else{if(session.isEmpty()||!session.equals(record.sessionId))
                    throw new IllegalStateException("record outside session");
                if(record.receivedAt<lastReceived||record.monotonicAt<lastMonotonic)
                    throw new IllegalStateException("future reordered into past");
                lastReceived=record.receivedAt;lastMonotonic=record.monotonicAt;}
            if(record.sequence<=lastSequence)throw new IllegalStateException(
                    "non-monotonic capture sequence");
            if(lastSequence>0&&record.sequence>lastSequence+1){
                sequenceGaps+=record.sequence-lastSequence-1;if(rejectSequenceGaps)
                    throw new IllegalStateException("incomplete capture sequence");}
            if(record.kind==CausalMarketRecord.Kind.QUOTE
                    ||record.kind==CausalMarketRecord.Kind.TOP_OF_BOOK_SAMPLE)quotes++;
            else if(record.kind==CausalMarketRecord.Kind.FLOW_1S
                    ||record.kind==CausalMarketRecord.Kind.FLOW_100MS){flows++;
                long prior=lastFlow.getOrDefault(record.symbol,Long.MIN_VALUE);
                if(prior!=Long.MIN_VALUE&&record.bucketStartAt<=prior)
                    throw new IllegalStateException("non-monotonic flow bucket");
                if(record.kind==CausalMarketRecord.Kind.FLOW_1S
                        &&record.bucketEndAt>record.receivedAt)
                    throw new IllegalStateException("future flow bucket");
                lastFlow.put(record.symbol,record.bucketStartAt);}
            else if(record.kind==CausalMarketRecord.Kind.DEPTH20_SAMPLE)depthSamples++;
            else if(record.kind==CausalMarketRecord.Kind.LIQUIDATION_SNAPSHOT)liquidationSnapshots++;
            else if(record.kind==CausalMarketRecord.Kind.DROP_SUMMARY){dropSummaries++;gaps++;}
            else if(record.kind==CausalMarketRecord.Kind.HEALTH)healthMarkers++;
            else if(record.kind==CausalMarketRecord.Kind.GAP)gaps++;
            if(!finiteOrNull(record.toMap()))throw new IllegalStateException("unsafe json value");
            lastSequence=record.sequence;}
        Validation result(long count,int corruptBlocks,int truncatedTails){return new Validation(
                count,sessions,quotes,flows,depthSamples,liquidationSnapshots,dropSummaries,
                healthMarkers,gaps,
                sequenceGaps,lastSequence,corruptBlocks,truncatedTails);}
    }

    public static final class Validation {
        public final long records;public final int sessions,quotes,flows,depthSamples,
                liquidationSnapshots,dropSummaries,healthMarkers,gaps;
        public final long missingSequences,lastSequence;
        public final int corruptBlocks,truncatedTails;
        public final boolean complete;
        Validation(long records,int sessions,int quotes,int flows,int depthSamples,
                   int liquidationSnapshots,int dropSummaries,int healthMarkers,int gaps,long missingSequences,
                   long lastSequence,int corruptBlocks,int truncatedTails){this.records=records;
            this.sessions=sessions;this.quotes=quotes;
            this.flows=flows;this.depthSamples=depthSamples;
            this.liquidationSnapshots=liquidationSnapshots;this.dropSummaries=dropSummaries;
            this.healthMarkers=healthMarkers;this.gaps=gaps;this.missingSequences=missingSequences;
            this.lastSequence=lastSequence;this.corruptBlocks=corruptBlocks;
            this.truncatedTails=truncatedTails;
            this.complete=missingSequences==0&&corruptBlocks==0&&truncatedTails==0;}
    }
    public static final class Terminal {
        public final String status;public final double touchQuote,fillPrice;
        public final long terminalAt,sourceSequence;
        Terminal(String status,double touchQuote,double fillPrice,long terminalAt,long sourceSequence){
            this.status=status;this.touchQuote=touchQuote;this.fillPrice=fillPrice;
            this.terminalAt=terminalAt;this.sourceSequence=sourceSequence;}
    }
}
