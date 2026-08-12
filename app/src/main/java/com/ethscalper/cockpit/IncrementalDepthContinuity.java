package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure per-symbol USD-M diff-depth synchronization state; it never builds a trading book. */
public final class IncrementalDepthContinuity {
    public static final long STALE_MS=5_000L;private static final String[] SYMBOLS={"ETHUSDT","SOLUSDT","BTCUSDT"};
    private final LinkedHashMap<String,State> states=new LinkedHashMap<>();private boolean socketConnected;
    public IncrementalDepthContinuity(){for(String symbol:SYMBOLS)states.put(symbol,new State());}
    public synchronized void reset(){socketConnected=false;for(State state:states.values())state.clear();}
    public synchronized void socketConnected(boolean value){socketConnected=value;}
    public synchronized void requireRebootstrapAll(){for(State state:states.values()){
        state.bootstrapValid=false;state.reconstructible=false;state.lastFinalUpdateId=-1;
        state.pending.clear();}}
    public synchronized void bootstrapAttempt(String symbol){state(symbol).bootstrapAttempts++;}
    public synchronized void bootstrapFailure(String symbol){State state=state(symbol);state.bootstrapFailures++;
        state.reconstructible=false;state.bootstrapValid=false;}
    public synchronized void bootstrapSuccess(String symbol,long updateId,long at){State state=state(symbol);
        if(updateId<0||at<0)throw new IllegalArgumentException("bootstrap");state.bootstrapSuccesses++;
        state.lastBootstrapAt=at;state.lastBootstrapUpdateId=updateId;state.bootstrapValid=true;
        state.reconstructible=false;state.lastFinalUpdateId=-1;while(!state.pending.isEmpty()){
            Pending event=state.pending.removeFirst();if(event.last<updateId)continue;
            if(!state.reconstructible){if(event.first<=updateId&&event.last>=updateId
                    ||event.previous==updateId){state.reconstructible=true;state.lastFinalUpdateId=event.last;}
                else {state.continuityBreaks++;state.lastContinuityBreakAt=event.at;
                    state.bootstrapValid=false;state.pending.clear();break;}}
            else if(event.last>state.lastFinalUpdateId){boolean continuous=event.previous>=0
                    ?event.previous==state.lastFinalUpdateId:event.first<=state.lastFinalUpdateId+1
                    &&event.last>=state.lastFinalUpdateId+1;if(continuous)state.lastFinalUpdateId=event.last;
                else {state.continuityBreaks++;state.lastContinuityBreakAt=event.at;
                    state.bootstrapValid=false;state.reconstructible=false;state.pending.clear();break;}}}}
    public synchronized Result observe(String symbol,long first,long last,long previous,long receivedAt){State state=
        state(symbol);state.incrementalDepthMessages++;state.lastDepthDiffReceivedAt=receivedAt;
        state.lastFirstUpdateId=first;state.lastPreviousFinalUpdateId=previous;
        if(!state.bootstrapValid){state.remember(first,last,previous,receivedAt);
            return new Result(false,false,true,"DEPTH_DIFF_UNANCHORED");}
        if(!state.reconstructible){if(last<state.lastBootstrapUpdateId)return new Result(false,true,false,
                    "DEPTH_DIFF_OLD_BEFORE_BOOTSTRAP");if(first<=state.lastBootstrapUpdateId
                    &&last>=state.lastBootstrapUpdateId){state.reconstructible=true;
                state.lastFinalUpdateId=last;return new Result(true,false,false,"DEPTH_DIFF_ANCHORED");}
            return breakChain(state,receivedAt,"DEPTH_DIFF_BOOTSTRAP_RANGE_MISS");}
        if(last<=state.lastFinalUpdateId)return new Result(false,true,false,"DEPTH_DIFF_OLD_OR_DUPLICATE");
        boolean continuous=previous>=0?previous==state.lastFinalUpdateId
                :first<=state.lastFinalUpdateId+1&&last>=state.lastFinalUpdateId+1;
        if(!continuous){state.remember(first,last,previous,receivedAt);
            return breakChain(state,receivedAt,"DEPTH_DIFF_CONTINUITY_BREAK");}
        state.lastFinalUpdateId=last;return new Result(true,false,false,"DEPTH_DIFF_CONTINUOUS");}
    public synchronized void accepted(String symbol,long exchangeAt){State state=state(symbol);
        state.acceptedDepthDiffRecords++;state.lastDepthExchangeEventAt=Math.max(
                state.lastDepthExchangeEventAt,exchangeAt);}
    public synchronized void rejected(String symbol){state(symbol).rejectedDepthDiffRecords++;}
    public synchronized void dropped(String symbol,long at){State state=state(symbol);state.rejectedDepthDiffRecords++;
        breakChain(state,at,"DEPTH_DIFF_DROPPED");}
    public synchronized Map<String,Object> snapshot(long now,boolean writerHealthy,boolean saturated){
        LinkedHashMap<String,Object> perSymbol=new LinkedHashMap<>();boolean all=true;
        for(Map.Entry<String,State> entry:states.entrySet()){State state=entry.getValue();boolean recent=
                state.lastDepthDiffReceivedAt>0&&now-state.lastDepthDiffReceivedAt<=STALE_MS;
            boolean ready=recent&&state.bootstrapValid&&state.reconstructible;all&=ready;
            LinkedHashMap<String,Object> item=state.map();item.put("recent",recent);item.put("ready",ready);
            perSymbol.put(entry.getKey(),Collections.unmodifiableMap(item));}
        LinkedHashMap<String,Object> root=new LinkedHashMap<>();root.put("incrementalDepthConfigured",true);
        root.put("incrementalDepthNaturallyHighFrequency",true);root.put("incrementalDepthSocketConnected",
                socketConnected);root.put("incrementalDepthReadyBySymbol",perSymbol);
        root.put("usableForIncrementalDepthResearch",socketConnected&&all&&writerHealthy&&!saturated);
        return Collections.unmodifiableMap(root);}
    private Result breakChain(State state,long at,String reason){state.continuityBreaks++;
        state.lastContinuityBreakAt=at;state.reconstructible=false;state.bootstrapValid=false;
        return new Result(false,false,true,reason);}
    private State state(String symbol){State state=states.get(symbol);if(state==null)
        throw new IllegalArgumentException("symbol");return state;}
    public static final class Result {public final boolean applied,old,needsBootstrap;public final String reasonCode;
        Result(boolean applied,boolean old,boolean needsBootstrap,String reason){this.applied=applied;
            this.old=old;this.needsBootstrap=needsBootstrap;reasonCode=reason;}}
    private static final class State {long incrementalDepthMessages,acceptedDepthDiffRecords,
        rejectedDepthDiffRecords,lastDepthDiffReceivedAt,lastDepthExchangeEventAt,lastFirstUpdateId=-1,
        lastFinalUpdateId=-1,lastPreviousFinalUpdateId=-1,continuityBreaks,lastContinuityBreakAt,
        bootstrapAttempts,bootstrapSuccesses,bootstrapFailures,lastBootstrapAt,lastBootstrapUpdateId=-1;
        final ArrayDeque<Pending> pending=new ArrayDeque<>();boolean bootstrapValid,reconstructible;
        void remember(long first,long last,long previous,long at){pending.addLast(new Pending(first,last,
                previous,at));while(pending.size()>2_000)pending.removeFirst();}
        void clear(){incrementalDepthMessages=
            acceptedDepthDiffRecords=rejectedDepthDiffRecords=lastDepthDiffReceivedAt=
            lastDepthExchangeEventAt=continuityBreaks=lastContinuityBreakAt=bootstrapAttempts=
            bootstrapSuccesses=bootstrapFailures=lastBootstrapAt=0;lastFirstUpdateId=
            lastFinalUpdateId=lastPreviousFinalUpdateId=lastBootstrapUpdateId=-1;
            bootstrapValid=reconstructible=false;pending.clear();}
        LinkedHashMap<String,Object> map(){LinkedHashMap<String,Object> out=new LinkedHashMap<>();
            out.put("incrementalDepthMessages",incrementalDepthMessages);out.put("acceptedDepthDiffRecords",
                    acceptedDepthDiffRecords);out.put("rejectedDepthDiffRecords",rejectedDepthDiffRecords);
            out.put("lastDepthDiffReceivedAt",nullable(lastDepthDiffReceivedAt));out.put(
                    "lastDepthExchangeEventAt",nullable(lastDepthExchangeEventAt));out.put("lastFirstUpdateId",
                    nullableId(lastFirstUpdateId));out.put("lastFinalUpdateId",nullableId(lastFinalUpdateId));
            out.put("lastPreviousFinalUpdateId",nullableId(lastPreviousFinalUpdateId));out.put(
                    "continuityBreaks",continuityBreaks);out.put("lastContinuityBreakAt",
                    nullable(lastContinuityBreakAt));out.put("bootstrapAttempts",bootstrapAttempts);
            out.put("bootstrapSuccesses",bootstrapSuccesses);out.put("bootstrapFailures",bootstrapFailures);
            out.put("lastBootstrapAt",nullable(lastBootstrapAt));out.put("lastBootstrapUpdateId",
                    nullableId(lastBootstrapUpdateId));out.put("anchored",bootstrapValid);
            out.put("reconstructible",reconstructible);return out;}}
    private static final class Pending {final long first,last,previous,at;Pending(long first,long last,
            long previous,long at){this.first=first;this.last=last;this.previous=previous;this.at=at;}}
    private static Long nullable(long value){return value>0?value:null;}
    private static Long nullableId(long value){return value>=0?value:null;}
}
