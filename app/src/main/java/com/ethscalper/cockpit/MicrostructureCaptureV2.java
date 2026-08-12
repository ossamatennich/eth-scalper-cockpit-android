package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Non-blocking V3 capture core. Quotes and depth are coalesced before the bounded sink,
 * aggregate trades are deduplicated across WS/REST, and sparse liquidation snapshots remain
 * event records. The class name is retained to avoid needless runtime churn.
 */
public final class MicrostructureCaptureV2 {
    public static final long FLOW_BUCKET_MS=100L;
    public static final long SAMPLE_BUCKET_MS=250L;
    public static final int RECENT_TRADE_IDS=4_096;
    private static final String[] SYMBOLS={"ETHUSDT","SOLUSDT","BTCUSDT"};

    private final CausalMarketCapture.Sink sink;
    private final LinkedHashMap<String,TopPending> top=new LinkedHashMap<>();
    private final LinkedHashMap<String,DepthPending> depth=new LinkedHashMap<>();
    private final LinkedHashMap<String,FlowPending> flow=new LinkedHashMap<>();
    private final LinkedHashMap<String,TradeDedup> dedup=new LinkedHashMap<>();
    private final LinkedHashMap<String,MutableSymbolStats> symbols=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> pendingDrops=new LinkedHashMap<>();
    private String sessionId="",sessionSource="";private long sequence;
    private long lastObservedAt,lastMonotonicAt,lastEmittedAt;
    private long droppedFromAt,droppedToAt,invalidInputs,clockRegressions,sinkErrors;

    public MicrostructureCaptureV2(CausalMarketCapture.Sink sink){if(sink==null)
        throw new IllegalArgumentException("sink");this.sink=sink;for(String symbol:SYMBOLS){
        top.put(symbol,new TopPending());depth.put(symbol,new DepthPending());
        flow.put(symbol,new FlowPending());dedup.put(symbol,new TradeDedup());
        symbols.put(symbol,new MutableSymbolStats());}}

    public synchronized boolean startSession(String id,String source,long receivedAt,long monotonicAt){
        if(blank(id)||blank(source)||receivedAt<0||monotonicAt<0){invalidInputs++;return false;}
        clearTransient(true);sessionId=bounded(id,128);sessionSource=bounded(source,96);
        sequence=0;lastObservedAt=receivedAt;lastMonotonicAt=monotonicAt;lastEmittedAt=receivedAt;
        return emitDirect(MicrostructureMarketRecord.session(sessionId,nextSequence(),receivedAt,
                monotonicAt,sessionSource,"SESSION_STARTED"),false);
    }

    public synchronized boolean observeTopBook(String symbol,String source,long receivedAt,
            long monotonicAt,long exchangeEventAt,long transactionAt,long updateId,double bid,
            double bidQuantity,double ask,double askQuantity){
        if(!validBase(symbol,source,receivedAt,monotonicAt)||!positive(bid)||!positive(ask)
                ||ask<bid||!nonNegative(bidQuantity)||!nonNegative(askQuantity)||updateId<0){
            invalidInputs++;return false;}
        advanceCompleted(receivedAt);advanceClock(receivedAt,monotonicAt);
        long bucket=bucket(receivedAt,SAMPLE_BUCKET_MS);TopPending value=top.get(symbol);
        value.set(bucket,receivedAt,monotonicAt,source,exchangeEventAt,transactionAt,updateId,
                bid,bidQuantity,ask,askQuantity);symbols.get(symbol).topMessagesAccepted++;
        return true;
    }

    public synchronized boolean observeAggTrade(String symbol,String source,long receivedAt,
            long monotonicAt,long aggregateTradeId,long exchangeTradeAt,double price,double quantity,
            boolean buyerMaker){
        if(!validBase(symbol,source,receivedAt,monotonicAt)||aggregateTradeId<0
                ||exchangeTradeAt<0||!positive(price)||!positive(quantity)){
            invalidInputs++;return false;}
        double notional=price*quantity;if(!Double.isFinite(notional)){invalidInputs++;return false;}
        TradeDedup ids=dedup.get(symbol);MutableSymbolStats stats=symbols.get(symbol);
        if(aggregateTradeId<=ids.lastId){if(ids.recent.containsKey(aggregateTradeId))
            stats.aggTradesDuplicate++;else stats.aggTradesLate++;return false;}
        String previousSource=ids.lastSource;boolean transition=!previousSource.isEmpty()
                &&!previousSource.equals(source);advanceCompleted(receivedAt);
        advanceClock(receivedAt,monotonicAt);
        long gaps=ids.lastId>=0&&aggregateTradeId>ids.lastId+1
                ?aggregateTradeId-ids.lastId-1:0;
        long bucket=bucket(receivedAt,FLOW_BUCKET_MS);FlowPending value=flow.get(symbol);
        if(value.bucketStart!=bucket)value.reset(bucket);
        if(!value.add(receivedAt,monotonicAt,source,aggregateTradeId,exchangeTradeAt,price,quantity,
                buyerMaker,gaps,notional)){invalidInputs++;return false;}
        if(gaps>0){stats.aggregateIdGaps+=gaps;stats.causalGaps++;emit(
                MicrostructureMarketRecord.health(sessionId,nextSequence(),receivedAt,monotonicAt,
                        source,"AGGTRADE_ID_GAP_"+gaps));}
        if(transition){stats.sourceTransitions++;emit(MicrostructureMarketRecord.health(sessionId,
                nextSequence(),receivedAt,monotonicAt,source,"AGGTRADE_SOURCE_TRANSITION_"
                        +bounded(previousSource,32)+"_TO_"+bounded(source,32)));}
        ids.accept(aggregateTradeId,source,receivedAt);
        stats.aggTradesAccepted++;return true;
    }

    public synchronized boolean observeDepth20(String symbol,String source,long receivedAt,
            long monotonicAt,long exchangeEventAt,long transactionAt,long firstUpdateId,
            long finalUpdateId,long previousFinalUpdateId,double[][] bids,double[][] asks){
        if(!validBase(symbol,source,receivedAt,monotonicAt)||finalUpdateId<0
                ||!validDepth(bids,true)||!validDepth(asks,false)){
            invalidInputs++;if(CausalMarketRecord.supported(symbol))symbols.get(symbol).depthInvalid++;
            return false;}
        advanceCompleted(receivedAt);advanceClock(receivedAt,monotonicAt);
        long bucket=bucket(receivedAt,SAMPLE_BUCKET_MS);depth.get(symbol).set(bucket,receivedAt,
                monotonicAt,source,exchangeEventAt,transactionAt,firstUpdateId,finalUpdateId,
                previousFinalUpdateId,bids,asks);symbols.get(symbol).depthMessagesAccepted++;
        return true;
    }

    /** Writes one validated Binance forceOrder snapshot without coalescing or heuristic dedup. */
    public synchronized boolean observeLiquidation(String symbol,String source,long receivedAt,
            long monotonicAt,long exchangeEventAt,long tradeAt,String orderSide,String orderType,
            String timeInForce,double originalQuantity,double price,double averagePrice,
            String orderStatus,double lastFilledQuantity,double accumulatedFilledQuantity){
        MutableSymbolStats stats=CausalMarketRecord.supported(symbol)?symbols.get(symbol):null;
        if(!validBase(symbol,source,receivedAt,monotonicAt)||exchangeEventAt<0||tradeAt<0
                ||tradeAt>exchangeEventAt||blank(orderSide)||!nonNegative(originalQuantity)
                ||!nonNegative(price)||!nonNegative(averagePrice)||!nonNegative(lastFilledQuantity)
                ||!nonNegative(accumulatedFilledQuantity)){
            invalidInputs++;if(stats!=null)stats.liquidationSnapshotsRejected++;return false;}
        advanceCompleted(receivedAt);advanceClock(receivedAt,monotonicAt);
        boolean accepted=emit(MicrostructureMarketRecord.liquidation(sessionId,nextSequence(),
                receivedAt,monotonicAt,symbol,source,exchangeEventAt,tradeAt,orderSide,orderType,
                timeInForce,originalQuantity,price,averagePrice,orderStatus,lastFilledQuantity,
                accumulatedFilledQuantity));
        if(accepted){stats.liquidationSnapshotsAccepted++;
            if("BUY".equalsIgnoreCase(orderSide))stats.liquidationBuyOrderSide++;
            else if("SELL".equalsIgnoreCase(orderSide))stats.liquidationSellOrderSide++;
            stats.liquidationOriginalQuantity+=originalQuantity;
            stats.liquidationAccumulatedFilledQuantity+=accumulatedFilledQuantity;
            double notional=averagePrice*accumulatedFilledQuantity;
            if(Double.isFinite(notional))stats.liquidationEstimatedNotional+=notional;
            stats.lastLiquidationReceivedAt=Math.max(stats.lastLiquidationReceivedAt,receivedAt);
            stats.lastLiquidationExchangeEventAt=Math.max(stats.lastLiquidationExchangeEventAt,
                    exchangeEventAt);
        }else stats.liquidationSnapshotsRejected++;
        return accepted;
    }

    /** Counts a malformed socket payload without ever passing it to the bounded writer. */
    public synchronized void rejectLiquidation(String symbol){invalidInputs++;
        if(CausalMarketRecord.supported(symbol))symbols.get(symbol).liquidationSnapshotsRejected++;}

    /** Emits only buckets that are complete at this local time; no missing sample is fabricated. */
    public synchronized void flushThrough(long receivedAt,long monotonicAt){
        if(!active()||receivedAt<lastObservedAt||monotonicAt<lastMonotonicAt){
            if(receivedAt<lastObservedAt||monotonicAt<lastMonotonicAt)clockRegressions++;
            else invalidInputs++;return;}
        advanceCompleted(receivedAt);advanceClock(receivedAt,monotonicAt);
        flushDropSummary(receivedAt,monotonicAt);
    }

    public synchronized boolean gap(long fromAt,long toAt,long receivedAt,long monotonicAt,
            String source,String reasonCode){
        if(!validBase("*",source,receivedAt,monotonicAt)||fromAt<0||toAt<=fromAt||toAt>receivedAt){
            invalidInputs++;return false;}
        advanceCompleted(receivedAt);advanceClock(receivedAt,monotonicAt);
        for(MutableSymbolStats value:symbols.values())value.causalGaps++;
        clearPending();return emit(MicrostructureMarketRecord.gap(sessionId,nextSequence(),
                receivedAt,monotonicAt,"*",source,fromAt,toAt,bounded(reasonCode,160)));
    }

    public synchronized boolean health(long receivedAt,long monotonicAt,String source,String code){
        if(!validBase("*",source,receivedAt,monotonicAt)){invalidInputs++;return false;}
        advanceCompleted(receivedAt);advanceClock(receivedAt,monotonicAt);
        return emit(MicrostructureMarketRecord.health(sessionId,nextSequence(),receivedAt,
                monotonicAt,source,bounded(code,160)));
    }

    public synchronized Stats stats(){LinkedHashMap<String,SymbolStats> copy=new LinkedHashMap<>();
        for(Map.Entry<String,MutableSymbolStats> entry:symbols.entrySet())copy.put(entry.getKey(),
                entry.getValue().snapshot());return new Stats(sessionId,sessionSource,sequence,
                invalidInputs,clockRegressions,sinkErrors,pendingDropTotal(),copy);}

    private void advanceCompleted(long now){ArrayList<Emission> due=new ArrayList<>();
        for(String symbol:SYMBOLS){TopPending t=top.get(symbol);if(t.present&&t.bucketStart
                +SAMPLE_BUCKET_MS<=now)due.add(t.emission(symbol));DepthPending d=depth.get(symbol);
            if(d.present&&d.bucketStart+SAMPLE_BUCKET_MS<=now)due.add(d.emission(symbol));
            FlowPending f=flow.get(symbol);if(f.count>0&&f.bucketStart+FLOW_BUCKET_MS<=now)
                due.add(f.emission(symbol));}
        due.sort(Comparator.comparingLong((Emission value)->value.receivedAt)
                .thenComparingInt(value->value.order).thenComparing(value->value.symbol));
        for(Emission value:due){MicrostructureMarketRecord record=value.create(nextSequence());
            boolean accepted=emit(record);MutableSymbolStats stats=symbols.get(value.symbol);
            if(accepted){if(record.kind==CausalMarketRecord.Kind.TOP_OF_BOOK_SAMPLE)
                    {stats.topSamples++;stats.lastTopAt=record.receivedAt;}
                else if(record.kind==CausalMarketRecord.Kind.DEPTH20_SAMPLE)
                    {stats.depthSamples++;stats.lastDepthAt=record.receivedAt;}
                else if(record.kind==CausalMarketRecord.Kind.FLOW_100MS)
                    {stats.flowBuckets++;stats.lastAggTradeAt=record.lastReceivedAt;}}
            value.clear.run();}
    }

    private boolean emit(MicrostructureMarketRecord record){flushDropSummary(record.receivedAt,
            record.monotonicAt);return emitDirect(record,true);}
    private boolean emitDirect(MicrostructureMarketRecord record,boolean countDrop){
        try{boolean accepted=sink.offer(record);if(accepted){lastEmittedAt=Math.max(lastEmittedAt,
                record.receivedAt);return true;}if(countDrop)rememberDrop(record);return false;}
        catch(RuntimeException error){sinkErrors++;if(countDrop)rememberDrop(record);return false;}}
    private void rememberDrop(MicrostructureMarketRecord record){pendingDrops.put(record.kind.name(),
            pendingDrops.getOrDefault(record.kind.name(),0L)+1L);if(droppedFromAt==0)
        droppedFromAt=Math.max(0,record.receivedAt);droppedToAt=Math.max(droppedFromAt+1,
                record.receivedAt+1);}
    private void flushDropSummary(long at,long monotonicAt){if(pendingDrops.isEmpty())return;
        long from=droppedFromAt,to=Math.max(from+1,Math.max(droppedToAt,at));
        MicrostructureMarketRecord summary=MicrostructureMarketRecord.dropSummary(sessionId,
                nextSequence(),Math.max(at,lastEmittedAt),Math.max(monotonicAt,lastMonotonicAt),
                sessionSource,from,to,pendingDrops);try{if(sink.offer(summary)){pendingDrops.clear();
            droppedFromAt=droppedToAt=0;lastEmittedAt=Math.max(lastEmittedAt,summary.receivedAt);}}
        catch(RuntimeException error){sinkErrors++;}}

    private boolean validBase(String symbol,String source,long at,long monotonic){if(!active()
            ||!("*".equals(symbol)||CausalMarketRecord.supported(symbol))||blank(source)||at<0
            ||monotonic<0)return false;if(at<lastObservedAt||monotonic<lastMonotonicAt){
            clockRegressions++;return false;}return true;}
    private void advanceClock(long at,long monotonic){lastObservedAt=at;lastMonotonicAt=monotonic;}
    private void clearTransient(boolean dedupToo){clearPending();pendingDrops.clear();
        droppedFromAt=droppedToAt=0;for(MutableSymbolStats value:symbols.values())value.clear();
        if(dedupToo)for(TradeDedup value:dedup.values())value.clear();}
    private void clearPending(){for(TopPending value:top.values())value.clear();
        for(DepthPending value:depth.values())value.clear();for(FlowPending value:flow.values())value.clear();}
    private long pendingDropTotal(){long out=0;for(long value:pendingDrops.values())out+=value;return out;}
    private boolean active(){return !sessionId.isEmpty();}private long nextSequence(){return ++sequence;}
    private static long bucket(long at,long width){return at-at%width;}
    private static boolean validDepth(double[][] levels,boolean bid){if(levels==null||levels.length<20)
        return false;double prior=bid?Double.POSITIVE_INFINITY:0;for(int i=0;i<20;i++){double[] level=
                levels[i];if(level==null||level.length<2||!positive(level[0])||!nonNegative(level[1]))
            return false;if(bid&&level[0]>prior||!bid&&level[0]<prior)return false;prior=level[0];}return true;}
    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private static boolean nonNegative(double value){return Double.isFinite(value)&&value>=0;}
    private static boolean blank(String value){return value==null||value.trim().isEmpty();}
    private static String bounded(String value,int maximum){String out=value==null?"":value.trim();
        return out.length()<=maximum?out:out.substring(0,maximum);}

    private interface Factory { MicrostructureMarketRecord create(long sequence); }
    private static final class Emission {final long receivedAt;final int order;final String symbol;
        final Factory factory;final Runnable clear;Emission(long receivedAt,int order,String symbol,
        Factory factory,Runnable clear){this.receivedAt=receivedAt;this.order=order;this.symbol=symbol;
            this.factory=factory;this.clear=clear;}MicrostructureMarketRecord create(long seq){return factory.create(seq);}}

    private final class TopPending {boolean present;long bucketStart,at,monotonic,eventAt,
        transactionAt,updateId;String source="";double bid,bidQty,ask,askQty;
        void set(long bucket,long at,long mono,String source,long event,long transaction,long update,
                double bid,double bidQty,double ask,double askQty){present=true;bucketStart=bucket;
            this.at=at;monotonic=mono;this.source=source;eventAt=event;transactionAt=transaction;
            updateId=update;this.bid=bid;this.bidQty=bidQty;this.ask=ask;this.askQty=askQty;}
        Emission emission(String symbol){long a=at,m=monotonic,e=eventAt,t=transactionAt,u=updateId;
            String s=source;double b=bid,bq=bidQty,x=ask,xq=askQty;return new Emission(a,1,
                    symbol,seq->MicrostructureMarketRecord.topBook(sessionId,seq,a,m,symbol,s,
                            e,t,u,b,bq,x,xq),this::clear);}
        void clear(){present=false;source="";}}

    private final class DepthPending {boolean present;long bucketStart,at,monotonic,eventAt,
        transactionAt,firstId,finalId,previousId;String source="";double[][] bids,asks;
        void set(long bucket,long at,long mono,String source,long event,long transaction,long first,
                long last,long previous,double[][] bids,double[][] asks){present=true;bucketStart=bucket;
            this.at=at;monotonic=mono;this.source=source;eventAt=event;transactionAt=transaction;
            firstId=first;finalId=last;previousId=previous;this.bids=copy20(bids);this.asks=copy20(asks);}
        Emission emission(String symbol){long a=at,m=monotonic,e=eventAt,t=transactionAt,f=firstId,
            l=finalId,p=previousId;String s=source;double[][] b=bids,x=asks;return new Emission(a,2,
                    symbol,seq->MicrostructureMarketRecord.depth20(sessionId,seq,a,m,symbol,s,e,t,
                    f,l,p,b,x),this::clear);}
        void clear(){present=false;source="";bids=asks=null;}}

    private final class FlowPending {long bucketStart=Long.MIN_VALUE,firstReceived,lastReceived,
        firstMonotonic,lastMonotonic,firstId,lastId,firstTradeAt,lastTradeAt,count,idGaps;
        String source="";double open,high,low,close,buyerBase,sellerBase,buyerNotional,sellerNotional;
        void reset(long bucket){clear();bucketStart=bucket;}
        boolean add(long received,long monotonic,String nextSource,long id,long tradeAt,double price,
                double quantity,boolean buyerMaker,long gaps,double notional){double nextBase=
                buyerMaker?sellerBase+quantity:buyerBase+quantity;double nextNotional=buyerMaker
                ?sellerNotional+notional:buyerNotional+notional;if(!Double.isFinite(nextBase)
                ||!Double.isFinite(nextNotional))return false;if(count==0){firstReceived=received;
                firstMonotonic=monotonic;firstId=id;firstTradeAt=tradeAt;open=high=low=close=price;
                source=nextSource;}else{high=Math.max(high,price);low=Math.min(low,price);close=price;
                if(!source.equals(nextSource))source="MIXED_WS_REST";}lastReceived=received;
            lastMonotonic=monotonic;lastId=id;lastTradeAt=tradeAt;count++;idGaps+=gaps;
            if(buyerMaker){sellerBase+=quantity;sellerNotional+=notional;}else{buyerBase+=quantity;
                buyerNotional+=notional;}return true;}
        Emission emission(String symbol){long b=bucketStart,fr=firstReceived,lr=lastReceived,
            mono=lastMonotonic,fi=firstId,li=lastId,ft=firstTradeAt,lt=lastTradeAt,c=count,g=idGaps;
            String s=source;double o=open,h=high,l=low,cl=close,bb=buyerBase,sb=sellerBase,
            bn=buyerNotional,sn=sellerNotional;return new Emission(lr,0,symbol,seq->
                    MicrostructureMarketRecord.flow100(sessionId,seq,lr,mono,symbol,s,b,fr,lr,
                            fi,li,ft,lt,c,g,o,h,l,cl,bb,sb,bn,sn),this::clear);}
        void clear(){bucketStart=Long.MIN_VALUE;firstReceived=lastReceived=firstMonotonic=
            lastMonotonic=firstId=lastId=firstTradeAt=lastTradeAt=count=idGaps=0;source="";
            open=high=low=close=buyerBase=sellerBase=buyerNotional=sellerNotional=0;}}

    private static final class TradeDedup {long lastId=-1,lastReceivedAt;String lastSource="";
        final LinkedHashMap<Long,Boolean> recent=new LinkedHashMap<>();void accept(long id,String source,
                long at){lastId=id;lastSource=source;lastReceivedAt=at;recent.put(id,Boolean.TRUE);
            while(recent.size()>RECENT_TRADE_IDS)recent.remove(recent.keySet().iterator().next());}
        void clear(){lastId=-1;lastReceivedAt=0;lastSource="";recent.clear();}}
    private static double[][] copy20(double[][] value){double[][] out=new double[20][2];
        for(int i=0;i<20;i++){out[i][0]=value[i][0];out[i][1]=value[i][1];}return out;}

    private static final class MutableSymbolStats {long topMessagesAccepted,topSamples,
        depthMessagesAccepted,depthInvalid,depthSamples,aggTradesAccepted,aggTradesDuplicate,
        aggTradesLate,aggregateIdGaps,flowBuckets,causalGaps,sourceTransitions,lastTopAt,
        lastDepthAt,lastAggTradeAt,liquidationSnapshotsAccepted,liquidationSnapshotsRejected,
        liquidationBuyOrderSide,liquidationSellOrderSide,lastLiquidationReceivedAt,
        lastLiquidationExchangeEventAt;double liquidationOriginalQuantity,
        liquidationAccumulatedFilledQuantity,liquidationEstimatedNotional;
        void clear(){topMessagesAccepted=topSamples=depthMessagesAccepted=
            depthInvalid=depthSamples=aggTradesAccepted=aggTradesDuplicate=aggTradesLate=
            aggregateIdGaps=flowBuckets=causalGaps=sourceTransitions=lastTopAt=lastDepthAt=
            lastAggTradeAt=liquidationSnapshotsAccepted=liquidationSnapshotsRejected=
            liquidationBuyOrderSide=liquidationSellOrderSide=lastLiquidationReceivedAt=
            lastLiquidationExchangeEventAt=0;liquidationOriginalQuantity=
            liquidationAccumulatedFilledQuantity=liquidationEstimatedNotional=0;}
        SymbolStats snapshot(){return new SymbolStats(this);}}

    public static final class SymbolStats {public final long topMessagesAccepted,topSamples,
        depthMessagesAccepted,depthInvalid,depthSamples,aggTradesAccepted,aggTradesDuplicate,
        aggTradesLate,aggregateIdGaps,flowBuckets,causalGaps,sourceTransitions,lastTopAt,
        lastDepthAt,lastAggTradeAt,liquidationSnapshotsAccepted,liquidationSnapshotsRejected,
        liquidationBuyOrderSide,liquidationSellOrderSide,lastLiquidationReceivedAt,
        lastLiquidationExchangeEventAt;public final double liquidationOriginalQuantity,
        liquidationAccumulatedFilledQuantity,liquidationEstimatedNotional;
        SymbolStats(MutableSymbolStats v){topMessagesAccepted=v.topMessagesAccepted;
            topSamples=v.topSamples;depthMessagesAccepted=v.depthMessagesAccepted;depthInvalid=v.depthInvalid;
            depthSamples=v.depthSamples;aggTradesAccepted=v.aggTradesAccepted;
            aggTradesDuplicate=v.aggTradesDuplicate;aggTradesLate=v.aggTradesLate;
            aggregateIdGaps=v.aggregateIdGaps;flowBuckets=v.flowBuckets;causalGaps=v.causalGaps;
            sourceTransitions=v.sourceTransitions;lastTopAt=v.lastTopAt;lastDepthAt=v.lastDepthAt;
            lastAggTradeAt=v.lastAggTradeAt;liquidationSnapshotsAccepted=v.liquidationSnapshotsAccepted;
            liquidationSnapshotsRejected=v.liquidationSnapshotsRejected;
            liquidationBuyOrderSide=v.liquidationBuyOrderSide;
            liquidationSellOrderSide=v.liquidationSellOrderSide;
            lastLiquidationReceivedAt=v.lastLiquidationReceivedAt;
            lastLiquidationExchangeEventAt=v.lastLiquidationExchangeEventAt;
            liquidationOriginalQuantity=v.liquidationOriginalQuantity;
            liquidationAccumulatedFilledQuantity=v.liquidationAccumulatedFilledQuantity;
            liquidationEstimatedNotional=v.liquidationEstimatedNotional;}
        public Map<String,Object> toMap(){LinkedHashMap<String,Object> out=
                new LinkedHashMap<>();out.put("topBookMessagesAccepted",topMessagesAccepted);
            out.put("causalTopBookSamples",topSamples);out.put("depth20MessagesAccepted",depthMessagesAccepted);
            out.put("causalDepthInvalid",depthInvalid);out.put("causalDepthSamples",depthSamples);
            out.put("causalAggTradesAccepted",aggTradesAccepted);out.put("causalAggTradesDuplicate",aggTradesDuplicate);
            out.put("causalAggTradesLate",aggTradesLate);out.put("aggregateTradeIdGaps",aggregateIdGaps);
            out.put("causalFlow100msBuckets",flowBuckets);out.put("causalGaps",causalGaps);
            out.put("sourceTransitions",sourceTransitions);out.put("lastTopBookAt",lastTopAt);
            out.put("lastDepth20At",lastDepthAt);out.put("lastAggTradeAt",lastAggTradeAt);
            out.put("acceptedLiquidationSnapshots",liquidationSnapshotsAccepted);
            out.put("rejectedLiquidationSnapshots",liquidationSnapshotsRejected);
            out.put("liquidationBuyOrderSideCount",liquidationBuyOrderSide);
            out.put("liquidationSellOrderSideCount",liquidationSellOrderSide);
            out.put("liquidationOriginalQuantity",liquidationOriginalQuantity);
            out.put("liquidationAccumulatedFilledQuantity",liquidationAccumulatedFilledQuantity);
            out.put("liquidationEstimatedNotional",liquidationEstimatedNotional);
            out.put("lastLiquidationReceivedAt",lastLiquidationReceivedAt<=0?null:lastLiquidationReceivedAt);
            out.put("lastLiquidationExchangeEventAt",lastLiquidationExchangeEventAt<=0?null:
                    lastLiquidationExchangeEventAt);
            return Collections.unmodifiableMap(out);}}

    public static final class Stats {public final String sessionId,source;public final long sequence,
        invalidInputs,clockRegressions,sinkErrors,pendingDroppedRecords;public final Map<String,SymbolStats> symbols;
        Stats(String sessionId,String source,long sequence,long invalidInputs,long clockRegressions,
                long sinkErrors,long pendingDroppedRecords,Map<String,SymbolStats> symbols){this.sessionId=sessionId;
            this.source=source;this.sequence=sequence;this.invalidInputs=invalidInputs;
            this.clockRegressions=clockRegressions;this.sinkErrors=sinkErrors;
            this.pendingDroppedRecords=pendingDroppedRecords;this.symbols=Collections.unmodifiableMap(symbols);}
        public Map<String,Object> toMap(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            out.put("schema",MicrostructureMarketRecord.SCHEMA);
            out.put("formatVersion",MicrostructureMarketRecord.FORMAT_VERSION);
            out.put("sessionId",sessionId);out.put("source",source);out.put("sequence",sequence);
            out.put("invalidInputs",invalidInputs);out.put("clockRegressions",clockRegressions);
            out.put("sinkErrors",sinkErrors);out.put("pendingDroppedRecords",pendingDroppedRecords);
            LinkedHashMap<String,Object> perSymbol=new LinkedHashMap<>();for(Map.Entry<String,SymbolStats> e:
                    symbols.entrySet())perSymbol.put(e.getKey(),e.getValue().toMap());out.put("symbols",perSymbol);
            long total=0;int observed=0;for(SymbolStats value:symbols.values()){
                total+=value.liquidationSnapshotsAccepted;if(value.liquidationSnapshotsAccepted>0)observed++;}
            out.put("totalLiquidationSnapshots",total);out.put("liquidationSymbolsObserved",observed);
            out.put("liquidationStreamConfigured",true);
            out.put("liquidationStreamNaturallySparse",true);
            return Collections.unmodifiableMap(out);}}
}
