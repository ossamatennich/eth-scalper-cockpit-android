package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure per-symbol USD-M diff-depth synchronization state; it never builds a trading book. */
public final class IncrementalDepthContinuity {
    public static final long STALE_MS=5_000L;
    public static final long RETRY_BASE_MS=5_000L;
    public static final long RETRY_MAX_MS=60_000L;
    public static final int MAX_BUFFERED_DIFFS=4_096;
    private static final String[] SYMBOLS={"ETHUSDT","SOLUSDT","BTCUSDT"};
    private final LinkedHashMap<String,State> states=new LinkedHashMap<>();
    private boolean socketConnected;
    private long rawSocketMessages,unknownParserRejections;

    public IncrementalDepthContinuity(){for(String symbol:SYMBOLS)states.put(symbol,new State());}

    public synchronized void reset(){socketConnected=false;rawSocketMessages=unknownParserRejections=0;
        for(State state:states.values())state.clear();}
    public synchronized void socketConnected(boolean value){socketConnected=value;}
    public synchronized void rawSocketMessage(){rawSocketMessages++;}
    public synchronized void unknownParserRejected(){unknownParserRejections++;}

    /** A reconnect invalidates reconstruction once; it does not create one gap per buffered diff. */
    public synchronized void requireRebootstrapAll(){for(State state:states.values())state.invalidateForReconnect();}

    /** Atomically enforces one REST request in flight and bounded per-symbol retry/backoff. */
    public synchronized boolean tryBeginBootstrap(String symbol,long now){State state=state(symbol);
        if(state.bootstrapInFlight||now<state.nextBootstrapAllowedAt)return false;
        state.bootstrapInFlight=true;state.bootstrapAttempts++;state.resyncsRequested++;
        return true;}
    /** Compatibility helper for pure tests; production uses tryBeginBootstrap. */
    public synchronized void bootstrapAttempt(String symbol){State state=state(symbol);
        if(!state.bootstrapInFlight){state.bootstrapInFlight=true;state.bootstrapAttempts++;
            state.resyncsRequested++;}}
    public synchronized long retryDelayMs(String symbol,long now){State state=state(symbol);
        return Math.max(0,state.nextBootstrapAllowedAt-now);}

    public synchronized void bootstrapFailure(String symbol){bootstrapFailure(symbol,System.currentTimeMillis());}
    public synchronized void bootstrapFailure(String symbol,long now){State state=state(symbol);
        state.bootstrapInFlight=false;state.bootstrapFailures++;state.consecutiveBootstrapFailures++;
        state.phase=Phase.INVALID_WAITING_RESYNC;state.bootstrapValid=false;state.reconstructible=false;
        state.nextBootstrapAllowedAt=now+backoff(state.consecutiveBootstrapFailures);}

    /**
     * Applies Binance's documented bootstrap contract to the buffered causal events:
     * discard u < lastUpdateId, then require U <= lastUpdateId <= u, then chain pu == previous u.
     */
    public synchronized Result bootstrapSuccess(String symbol,long updateId,long at){State state=state(symbol);
        if(updateId<0||at<0)throw new IllegalArgumentException("bootstrap");
        state.bootstrapInFlight=false;state.bootstrapSuccesses++;state.consecutiveBootstrapFailures=0;
        state.nextBootstrapAllowedAt=0;state.lastBootstrapAt=at;state.lastBootstrapUpdateId=updateId;
        state.bootstrapValid=true;state.reconstructible=false;state.lastFinalUpdateId=-1;
        state.phase=Phase.SYNCING;Result result=applyBufferedAfterBootstrap(state,updateId,at);
        if(result.restored)state.resyncsSucceeded++;return result;}

    public synchronized Result observe(String symbol,long first,long last,long previous,long receivedAt){State state=
            state(symbol);state.incrementalDepthMessages++;
        state.lastDepthDiffReceivedAt=receivedAt;state.lastFirstUpdateId=first;
        state.lastPreviousFinalUpdateId=previous;
        Pending event=new Pending(first,last,previous,receivedAt);
        if(state.phase==Phase.WAITING_BOOTSTRAP||state.phase==Phase.BUFFERING
                ||state.phase==Phase.INVALID_WAITING_RESYNC){state.remember(event);
            if(state.phase==Phase.WAITING_BOOTSTRAP)state.phase=Phase.BUFFERING;
            return Result.waiting(state.canBootstrap(receivedAt),"DEPTH_DIFF_BUFFERING");}
        if(state.phase==Phase.SYNCING)return synchronizeLive(state,event);
        if(last<=state.lastFinalUpdateId)return Result.old("DEPTH_DIFF_OLD_OR_DUPLICATE");
        boolean continuous=previous>=0?previous==state.lastFinalUpdateId
                :first<=state.lastFinalUpdateId+1&&last>=state.lastFinalUpdateId+1;
        if(continuous){state.lastFinalUpdateId=last;state.lastAcceptedReceiveAt=receivedAt;
            return Result.applied("DEPTH_DIFF_CONTINUOUS",false);}
        state.puMismatches++;return invalidate(state,event,"DEPTH_DIFF_CONTINUITY_BREAK");}

    public synchronized void accepted(String symbol,long exchangeAt){State state=state(symbol);
        state.acceptedDepthDiffRecords++;state.rawDepthDiffPersisted++;
        state.lastDepthExchangeEventAt=Math.max(state.lastDepthExchangeEventAt,exchangeAt);}
    public synchronized void parsed(String symbol){state(symbol).parserAcceptedMessages++;}
    public synchronized void rejected(String symbol){State state=state(symbol);
        state.rejectedDepthDiffRecords++;state.parserRejectedMessages++;}
    public synchronized Result dropped(String symbol,long at){State state=state(symbol);
        state.rejectedDepthDiffRecords++;state.queueDrops++;return invalidate(state,null,"DEPTH_DIFF_QUEUE_DROP",at);}

    public synchronized Map<String,Object> snapshot(long now,boolean writerHealthy,boolean saturated){
        LinkedHashMap<String,Object> perSymbol=new LinkedHashMap<>();boolean all=true;
        for(Map.Entry<String,State> entry:states.entrySet()){State state=entry.getValue();boolean recent=
                state.lastDepthDiffReceivedAt>0&&now-state.lastDepthDiffReceivedAt<=STALE_MS;
            boolean ready=recent&&state.bootstrapValid&&state.reconstructible
                    &&state.phase==Phase.RECONSTRUCTIBLE;all&=ready;
            LinkedHashMap<String,Object> item=state.map();item.put("recent",recent);item.put("ready",ready);
            perSymbol.put(entry.getKey(),Collections.unmodifiableMap(item));}
        LinkedHashMap<String,Object> root=new LinkedHashMap<>();root.put("incrementalDepthConfigured",true);
        root.put("incrementalDepthNaturallyHighFrequency",true);root.put("incrementalDepthSocketConnected",
                socketConnected);root.put("rawSocketMessages",rawSocketMessages);root.put(
                "unknownParserRejections",unknownParserRejections);root.put("incrementalDepthReadyBySymbol",perSymbol);
        root.put("usableForIncrementalDepthResearch",socketConnected&&all&&writerHealthy&&!saturated);
        return Collections.unmodifiableMap(root);}

    private Result applyBufferedAfterBootstrap(State state,long updateId,long at){
        boolean anchored=false;long previousFinal=-1;
        while(!state.pending.isEmpty()){Pending event=state.pending.removeFirst();
            if(event.last<updateId)continue;
            if(!anchored){if(event.first<=updateId&&event.last>=updateId){anchored=true;
                    previousFinal=event.last;state.lastFinalUpdateId=event.last;}
                else {state.pending.clear();state.nextBootstrapAllowedAt=at+RETRY_BASE_MS;
                    return invalidate(state,event,"DEPTH_DIFF_BOOTSTRAP_TOO_OLD",at);}}
            else if(event.last>previousFinal){boolean continuous=event.previous>=0?event.previous==previousFinal
                        :event.first<=previousFinal+1&&event.last>=previousFinal+1;
                if(!continuous){state.puMismatches++;return invalidate(state,event,
                                "DEPTH_DIFF_BUFFERED_CONTINUITY_BREAK",at);}
                previousFinal=event.last;state.lastFinalUpdateId=event.last;}}
        if(anchored){state.phase=Phase.RECONSTRUCTIBLE;state.reconstructible=true;
            state.lastAcceptedReceiveAt=at;
            return Result.applied("DEPTH_DIFF_ANCHORED",true);}
        // Snapshot is newer than every buffered u. Keep buffering until a future event covers lastUpdateId.
        state.phase=Phase.SYNCING;return Result.waiting(false,"DEPTH_DIFF_WAITING_FOR_SNAPSHOT_RANGE");}

    private Result synchronizeLive(State state,Pending event){long anchor=state.lastBootstrapUpdateId;
        if(event.last<anchor)return Result.old("DEPTH_DIFF_OLD_BEFORE_BOOTSTRAP");
        if(event.first<=anchor&&event.last>=anchor){state.reconstructible=true;
            state.phase=Phase.RECONSTRUCTIBLE;state.lastFinalUpdateId=event.last;
            state.lastAcceptedReceiveAt=event.at;state.resyncsSucceeded++;
            return Result.applied("DEPTH_DIFF_ANCHORED",true);}
        // The first relevant U is already beyond the snapshot: the snapshot is too old.
        state.nextBootstrapAllowedAt=event.at+RETRY_BASE_MS;
        return invalidate(state,event,"DEPTH_DIFF_BOOTSTRAP_TOO_OLD");}

    private Result invalidate(State state,Pending event,String reason){return invalidate(state,event,reason,
            event==null?0:event.at);}
    private Result invalidate(State state,Pending event,String reason,long at){boolean transitioned=
            state.phase!=Phase.INVALID_WAITING_RESYNC;long previousFinal=state.lastFinalUpdateId;
        state.phase=Phase.INVALID_WAITING_RESYNC;state.bootstrapValid=false;state.reconstructible=false;
        state.bootstrapInFlight=false;state.lastFinalUpdateId=-1;state.pending.clear();
        if(event!=null)state.remember(event);
        if(transitioned){state.continuityBreaks++;state.lastContinuityBreakAt=at;
            state.lastBreakPreviousFinalUpdateId=previousFinal;state.lastBreakFirstUpdateId=event==null?-1:event.first;
            state.lastBreakFinalUpdateId=event==null?-1:event.last;state.lastBreakPreviousUpdateId=
                    event==null?-1:event.previous;state.lastBreakReceiveDeltaMs=state.lastAcceptedReceiveAt>0
                    ?Math.max(0,at-state.lastAcceptedReceiveAt):null;}
        state.lastAcceptedReceiveAt=Math.max(state.lastAcceptedReceiveAt,at);
        return new Result(false,false,true,reason,transitioned,false,previousFinal);}

    private static long backoff(int failures){int exponent=Math.max(0,Math.min(4,failures-1));
        return Math.min(RETRY_MAX_MS,RETRY_BASE_MS<<exponent);}
    private State state(String symbol){State state=states.get(symbol);if(state==null)
        throw new IllegalArgumentException("symbol");return state;}

    public enum Phase {WAITING_BOOTSTRAP,BUFFERING,SYNCING,RECONSTRUCTIBLE,INVALID_WAITING_RESYNC}
    public static final class Result {
        public final boolean applied,old,needsBootstrap,gapTransition,restored;public final String reasonCode;
        public final long previousFinalUpdateId;
        Result(boolean applied,boolean old,boolean needsBootstrap,String reason,boolean gap,boolean restored){
            this.applied=applied;this.old=old;this.needsBootstrap=needsBootstrap;reasonCode=reason;
            gapTransition=gap;this.restored=restored;previousFinalUpdateId=-1;}
        Result(boolean applied,boolean old,boolean needsBootstrap,String reason,boolean gap,boolean restored,
               long previousFinalUpdateId){this.applied=applied;this.old=old;this.needsBootstrap=needsBootstrap;
            reasonCode=reason;gapTransition=gap;this.restored=restored;
            this.previousFinalUpdateId=previousFinalUpdateId;}
        static Result applied(String reason,boolean restored){return new Result(true,false,false,reason,false,restored);}
        static Result old(String reason){return new Result(false,true,false,reason,false,false);}
        static Result waiting(boolean request,String reason){return new Result(false,false,request,reason,false,false);}
    }

    private static final class State {
        long incrementalDepthMessages,parserAcceptedMessages,parserRejectedMessages,rawDepthDiffPersisted,
                acceptedDepthDiffRecords,rejectedDepthDiffRecords,lastDepthDiffReceivedAt,lastDepthExchangeEventAt,
                lastFirstUpdateId=-1,lastFinalUpdateId=-1,lastPreviousFinalUpdateId=-1,continuityBreaks,
                puMismatches,lastContinuityBreakAt,bootstrapAttempts,bootstrapSuccesses,bootstrapFailures,
                resyncsRequested,resyncsSucceeded,queueDrops,lastBootstrapAt,lastBootstrapUpdateId=-1,
                nextBootstrapAllowedAt,lastAcceptedReceiveAt,lastBreakPreviousFinalUpdateId=-1,
                lastBreakFirstUpdateId=-1,lastBreakFinalUpdateId=-1,lastBreakPreviousUpdateId=-1;
        Long lastBreakReceiveDeltaMs;int consecutiveBootstrapFailures;boolean bootstrapValid,reconstructible,
                bootstrapInFlight;Phase phase=Phase.WAITING_BOOTSTRAP;final ArrayDeque<Pending> pending=new ArrayDeque<>();
        void remember(Pending value){pending.addLast(value);while(pending.size()>MAX_BUFFERED_DIFFS)pending.removeFirst();}
        boolean canBootstrap(long now){return !bootstrapInFlight&&now>=nextBootstrapAllowedAt;}
        void invalidateForReconnect(){bootstrapValid=reconstructible=bootstrapInFlight=false;
            lastFinalUpdateId=-1;pending.clear();phase=Phase.WAITING_BOOTSTRAP;nextBootstrapAllowedAt=0;}
        void clear(){incrementalDepthMessages=parserAcceptedMessages=parserRejectedMessages=rawDepthDiffPersisted=
                acceptedDepthDiffRecords=rejectedDepthDiffRecords=lastDepthDiffReceivedAt=lastDepthExchangeEventAt=
                continuityBreaks=puMismatches=lastContinuityBreakAt=bootstrapAttempts=bootstrapSuccesses=
                bootstrapFailures=resyncsRequested=resyncsSucceeded=queueDrops=lastBootstrapAt=
                nextBootstrapAllowedAt=lastAcceptedReceiveAt=0;lastFirstUpdateId=lastFinalUpdateId=
                lastPreviousFinalUpdateId=lastBootstrapUpdateId=lastBreakPreviousFinalUpdateId=
                lastBreakFirstUpdateId=lastBreakFinalUpdateId=lastBreakPreviousUpdateId=-1;
            lastBreakReceiveDeltaMs=null;consecutiveBootstrapFailures=0;bootstrapValid=reconstructible=
                    bootstrapInFlight=false;phase=Phase.WAITING_BOOTSTRAP;pending.clear();}
        LinkedHashMap<String,Object> map(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            out.put("phase",phase.name());out.put("incrementalDepthMessages",incrementalDepthMessages);
            out.put("parserAcceptedMessages",parserAcceptedMessages);out.put("parserRejectedMessages",
                    parserRejectedMessages);out.put("rawDepthDiffPersisted",rawDepthDiffPersisted);
            out.put("acceptedDepthDiffRecords",acceptedDepthDiffRecords);out.put("rejectedDepthDiffRecords",
                    rejectedDepthDiffRecords);out.put("lastDepthDiffReceivedAt",nullable(lastDepthDiffReceivedAt));
            out.put("lastDepthExchangeEventAt",nullable(lastDepthExchangeEventAt));out.put("lastFirstUpdateId",
                    nullableId(lastFirstUpdateId));out.put("lastFinalUpdateId",nullableId(lastFinalUpdateId));
            out.put("lastPreviousFinalUpdateId",nullableId(lastPreviousFinalUpdateId));out.put("continuityBreaks",
                    continuityBreaks);out.put("puMismatches",puMismatches);out.put("lastContinuityBreakAt",
                    nullable(lastContinuityBreakAt));out.put("bootstrapAttempts",bootstrapAttempts);
            out.put("bootstrapSuccesses",bootstrapSuccesses);out.put("bootstrapFailures",bootstrapFailures);
            out.put("resyncsRequested",resyncsRequested);out.put("resyncsSucceeded",resyncsSucceeded);
            out.put("queueDrops",queueDrops);out.put("lastBootstrapAt",nullable(lastBootstrapAt));
            out.put("lastBootstrapUpdateId",nullableId(lastBootstrapUpdateId));out.put("bootstrapInFlight",
                    bootstrapInFlight);out.put("bufferedDiffs",pending.size());out.put("anchored",bootstrapValid);
            out.put("reconstructible",reconstructible);out.put("lastBreakPreviousFinalUpdateId",
                    nullableId(lastBreakPreviousFinalUpdateId));out.put("lastBreakFirstUpdateId",
                    nullableId(lastBreakFirstUpdateId));out.put("lastBreakFinalUpdateId",
                    nullableId(lastBreakFinalUpdateId));out.put("lastBreakPreviousUpdateId",
                    nullableId(lastBreakPreviousUpdateId));out.put("lastBreakReceiveDeltaMs",lastBreakReceiveDeltaMs);
            return out;}}

    private static final class Pending {final long first,last,previous,at;Pending(long first,long last,
            long previous,long at){this.first=first;this.last=last;this.previous=previous;this.at=at;}}
    private static Long nullable(long value){return value>0?value:null;}
    private static Long nullableId(long value){return value>=0?value:null;}
}
