package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-open orchestrator for the frozen future-holdout protocol. */
public final class FrozenProfitabilityShadowObserver {
    @FunctionalInterface public interface OperationRunner { void run(String operation,Runnable action); }
    private final FrozenProfitabilityShadowPortfolio portfolio=new FrozenProfitabilityShadowPortfolio();
    private final FrozenMovementRegistry movements=new FrozenMovementRegistry();
    private final FrozenProfitabilityShadowSummary summary=new FrozenProfitabilityShadowSummary();
    private final LinkedHashMap<String,Map<String,Object>> openedTelemetry=new LinkedHashMap<>();
    private final OperationRunner runner;private final AtomicLong ids=new AtomicLong();

    public FrozenProfitabilityShadowObserver(){this((operation,action)->action.run());}
    public FrozenProfitabilityShadowObserver(OperationRunner runner){this.runner=runner==null?(o,a)->a.run():runner;}

    public void safeObserveTerminal(MarketRuntime runtime,MarketSnapshot snapshot,long now,
                                    boolean marketFresh,boolean btcFresh) {
        safeObserveTerminal(runtime,snapshot,now,marketFresh,btcFresh,runtime.hasActivePlan());
    }
    public void safeObserveTerminal(MarketRuntime runtime,MarketSnapshot snapshot,long now,
                                    boolean marketFresh,boolean btcFresh,boolean productionActive) {
        safely(runtime,"terminal",now,marketFresh,btcFresh,()->observeTerminal(runtime,snapshot,now,marketFresh,btcFresh,productionActive));
    }
    public void safeObserveRaw(MarketRuntime runtime,MarketSnapshot snapshot,SignalDecision raw,long now,
                               boolean marketFresh,boolean btcFresh,boolean productionActive,
                               boolean rearmComplete) {
        safely(runtime,"qualification",now,marketFresh,btcFresh,()->observeRaw(runtime,snapshot,raw,now,
                marketFresh,btcFresh,productionActive,rearmComplete));
    }
    public void safeReset(MarketRuntime runtime,long now,boolean publicPlanActive) {
        safely(runtime,"reset",now,false,false,()->{
            for(FrozenProfitabilityShadowPlan p:portfolio.reset()){LinkedHashMap<String,Object>d=openDetails(p);d.putAll(p.details());record(runtime,null,null,now,
                    "FROZEN_SHADOW_RESET_ABORTED","FROZEN_RESET_ACTIVE_BRANCH",d,false,false);}
            movements.reset();summary.reset();openedTelemetry.clear();
        });
    }
    public Map<String,Object> safeSnapshot(long now){try{return snapshotInternal(now);}catch(RuntimeException ignored){return Collections.singletonMap("available",false);}}
    public Map<String,Object> safeSnapshot(MarketRuntime runtime,long now){try{return snapshotInternal(now);}catch(RuntimeException error){
        try{summary.internalError("summary");LinkedHashMap<String,Object>d=metadata();d.put("component","FROZEN_PROTOCOL");d.put("operation","summary");d.put("exceptionClass",error.getClass().getSimpleName());record(runtime,null,null,now,"FROZEN_SHADOW_INTERNAL_ERROR","FROZEN_INTERNAL_ERROR",d,false,false);}catch(RuntimeException ignored){}
        return Collections.singletonMap("available",false);}}
    public FrozenProfitabilityShadowPortfolio portfolio(){return portfolio;}
    public FrozenMovementRegistry movements(){return movements;}
    public FrozenProfitabilityShadowSummary summary(){return summary;}

    private Map<String,Object> snapshotInternal(long now){AtomicReference<Map<String,Object>> box=new AtomicReference<>(Collections.emptyMap());runner.run("summary",()->box.set(summary.snapshot(now,portfolio.activeGroups(),portfolio.unresolvedBranches())));return box.get();}

    private void observeTerminal(MarketRuntime runtime,MarketSnapshot snapshot,long now,
                                 boolean marketFresh,boolean btcFresh,boolean productionActive) {
        summary.observeTime(now,marketFresh,btcFresh);
        if(productionActive){
            FrozenProfitabilityShadowPortfolio.Group overlap=portfolio.markPublicOverlap(runtime.profile.symbol);
            if(overlap!=null){FrozenProfitabilityShadowPlan p=overlap.branches.get(0);summary.publicOverlap(p.component);
                LinkedHashMap<String,Object> d=openDetails(p);d.putAll(p.details());d.put("branchId","GROUP");
                d.put("branchCount",overlap.branches.size());d.put("productionActivePlan",true);
                record(runtime,snapshot,null,now,"FROZEN_SHADOW_PUBLIC_OVERLAP","FROZEN_PUBLIC_PLAN_AFTER_OPEN",d,marketFresh,btcFresh);}
        }
        if(snapshot==null)return;
        List<FrozenProfitabilityShadowPortfolio.TerminalEvent> terminals=portfolio.observe(runtime.profile.symbol,
                now,snapshot.marketBid,snapshot.marketAsk,marketFresh);
        for(FrozenProfitabilityShadowPortfolio.TerminalEvent event:terminals){
            FrozenProfitabilityShadowPlan p=event.plan;FrozenProfitabilityShadowPlan.Terminal t=event.terminal;
            LinkedHashMap<String,Object> d=openDetails(p);d.putAll(p.details());d.put("touchQuote",t.touchQuote);
            d.put("fillPrice",t.fillPrice);d.put("terminalStatus",t.terminalStatus);d.put("terminalAt",t.terminalAt);
            d.put("durationMs",t.durationMs);d.put("grossResultUsdt",t.grossResultUsdt);
            d.put("estimatedFeesUsdt",t.estimatedFeesUsdt);d.put("netResultUsdt",t.netResultUsdt);
            d.put("resultR",t.resultR);d.put("plannedNetRewardRisk",p.plannedNetRewardRisk);
            d.put("productionActivePlan",productionActive);d.put("publicOverlap",p.publicOverlap());
            String type="TP_TOUCHED".equals(t.terminalStatus)?"FROZEN_SHADOW_TP_TOUCHED":"FROZEN_SHADOW_SL_TOUCHED";
            record(runtime,snapshot,null,now,type,type,d,marketFresh,btcFresh);summary.terminal(p.component,t);
            openedTelemetry.remove(planKey(p));
        }
    }

    private void observeRaw(MarketRuntime runtime,MarketSnapshot snapshot,SignalDecision raw,long now,
                            boolean marketFresh,boolean btcFresh,boolean productionActive,
                            boolean rearmComplete) {
        summary.observeTime(now,marketFresh,btcFresh);
        if(raw==null||!raw.isSignal()||snapshot==null)return;
        FrozenProfitabilityShadowPolicy.Evaluation policy=FrozenProfitabilityShadowPolicy.evaluate(runtime.profile,raw,snapshot);
        if(policy.component.isEmpty())return;
        FrozenMovementRegistry.Resolution resolution=movements.resolve(runtime.profile,raw,now);
        FrozenMovementRegistry.Record movement=resolution.record;summary.evicted(movements.evicted());
        if(!resolution.duplicate){summary.bucket(FrozenProfitabilityShadowPolicy.sensitivityBucket(runtime.profile,
                raw,policy.a,policy.accel38Directional),raw.side);}
        else {summary.duplicate(policy.component);if(movement.duplicates==1)emitDuplicate(runtime,snapshot,raw,now,
                marketFresh,btcFresh,policy,movement);}
        if(policy.nearMiss){if(transition(movement,"NEAR|"+policy.reasonCode)){summary.nearMiss(policy.component,movement.movementSignature);
            record(runtime,snapshot,raw,now,"FROZEN_SHADOW_NEAR_MISS",policy.reasonCode,
                    details(runtime,snapshot,raw,policy,movement,now,productionActive,rearmComplete),marketFresh,btcFresh);}return;}
        if(!policy.qualified)return;
        if(movement.qualified)return;movement.qualified=true;summary.qualified(policy.component,movement.movementSignature);
        record(runtime,snapshot,raw,now,"FROZEN_SHADOW_QUALIFIED","FROZEN_POLICY_QUALIFIED",
                details(runtime,snapshot,raw,policy,movement,now,productionActive,rearmComplete),marketFresh,btcFresh);
        String skip=operationalSkip(runtime,snapshot,marketFresh,btcFresh,productionActive,rearmComplete);
        if(!skip.isEmpty()){summary.skipped(policy.component,skip);LinkedHashMap<String,Object> d=details(runtime,snapshot,raw,policy,movement,now,productionActive,rearmComplete);d.put("skipReason",skip);
            record(runtime,snapshot,raw,now,"FROZEN_SHADOW_SKIPPED",skip,d,marketFresh,btcFresh);return;}
        if(!portfolio.canOpen(runtime.profile.symbol,now)){String reason=portfolio.active(runtime.profile.symbol)!=null?
                "FROZEN_ACTIVE_GROUP_EXISTS":"FROZEN_COOLDOWN_ACTIVE";summary.skipped(policy.component,reason);
            LinkedHashMap<String,Object> d=details(runtime,snapshot,raw,policy,movement,now,productionActive,rearmComplete);
            d.put("skipReason",reason);d.put("cooldownRemainingMs",portfolio.cooldownRemaining(runtime.profile.symbol,now));
            record(runtime,snapshot,raw,now,"FROZEN_SHADOW_SKIPPED",reason,d,marketFresh,btcFresh);return;}
        String opportunityId="FROZEN-"+runtime.profile.symbol+"-"+now+"-"+ids.incrementAndGet();
        boolean legacyOverlap=runtime.shadowResearch.active()!=null;List<FrozenProfitabilityShadowPlan> plans=new ArrayList<>();
        if(MarketProfile.ETH_SYMBOL.equals(runtime.profile.symbol)){
            FrozenProfitabilityShadowPlan.BuildResult b=FrozenProfitabilityShadowPlan.build(runtime.profile,raw,now,
                    policy.a,opportunityId,"ETH_RANGE",FrozenProfitabilityShadowPolicy.ETH_RANGE,
                    movement.movementSignature,movement.signatureMode,3.0,1.5,snapshot.marketBid,snapshot.marketAsk,legacyOverlap);
            if(!b.valid()){skipBuild(runtime,snapshot,raw,now,marketFresh,btcFresh,policy,movement,b.reasonCode,productionActive,rearmComplete);return;}plans.add(b.plan);
        } else {
            FrozenProfitabilityShadowPlan.BuildResult a=FrozenProfitabilityShadowPlan.build(runtime.profile,raw,now,
                    policy.a,opportunityId,"CANONICAL",FrozenProfitabilityShadowPolicy.SOL_CANONICAL,
                    movement.movementSignature,movement.signatureMode,4.0,1.5,snapshot.marketBid,snapshot.marketAsk,legacyOverlap);
            FrozenProfitabilityShadowPlan.BuildResult b=FrozenProfitabilityShadowPlan.build(runtime.profile,raw,now,
                    policy.a,opportunityId,"ROBUST",FrozenProfitabilityShadowPolicy.SOL_ROBUST,
                    movement.movementSignature,movement.signatureMode,4.5,1.75,snapshot.marketBid,snapshot.marketAsk,legacyOverlap);
            if(!a.valid()||!b.valid()){skipBuild(runtime,snapshot,raw,now,marketFresh,btcFresh,policy,movement,
                    !a.valid()?a.reasonCode:b.reasonCode,productionActive,rearmComplete);return;}plans.add(a.plan);plans.add(b.plan);
        }
        FrozenProfitabilityShadowPortfolio.Group group=new FrozenProfitabilityShadowPortfolio.Group(opportunityId,
                movement.movementSignature,runtime.profile.symbol,now,plans);
        if(!portfolio.open(group)){skipBuild(runtime,snapshot,raw,now,marketFresh,btcFresh,policy,movement,
                "FROZEN_GROUP_OPEN_RACE",productionActive,rearmComplete);return;}
        movement.opened=true;summary.opportunity(policy.component,movement.movementSignature);
        summary.groupOpened(policy.component,movement.movementSignature,legacyOverlap);
        LinkedHashMap<String,Object> groupDetails=details(runtime,snapshot,raw,policy,movement,now,productionActive,rearmComplete);
        groupDetails.put("opportunityId",opportunityId);groupDetails.put("branchCount",plans.size());groupDetails.put("legacyShadowOverlap",legacyOverlap);
        record(runtime,snapshot,raw,now,"FROZEN_SHADOW_GROUP_OPENED","FROZEN_GROUP_OPENED",groupDetails,marketFresh,btcFresh);
        for(FrozenProfitabilityShadowPlan p:plans){summary.branchOpened(p.component);LinkedHashMap<String,Object> branch=new LinkedHashMap<>(groupDetails);branch.putAll(p.details());openedTelemetry.put(planKey(p),Collections.unmodifiableMap(new LinkedHashMap<>(branch)));record(runtime,snapshot,raw,now,
                "FROZEN_SHADOW_BRANCH_OPENED","FROZEN_BRANCH_OPENED",branch,marketFresh,btcFresh);}
    }

    private void skipBuild(MarketRuntime r,MarketSnapshot s,SignalDecision raw,long now,boolean mf,boolean bf,
                           FrozenProfitabilityShadowPolicy.Evaluation p,FrozenMovementRegistry.Record m,String reason,
                           boolean active,boolean rearm){summary.skipped(p.component,reason);LinkedHashMap<String,Object>d=details(r,s,raw,p,m,now,active,rearm);d.put("skipReason",reason);record(r,s,raw,now,"FROZEN_SHADOW_SKIPPED",reason,d,mf,bf);}
    private static String operationalSkip(MarketRuntime r,MarketSnapshot s,boolean mf,boolean bf,boolean active,boolean rearm){
        if(!mf)return "FROZEN_MARKET_FEED_STALE";if(!bf)return "FROZEN_BTC_FEED_STALE";
        if(active||r.hasActivePlan())return "FROZEN_PUBLIC_PLAN_ACTIVE";if(!rearm)return "FROZEN_PUBLIC_REARM_ACTIVE";
        if(!positive(s.marketBid)||!positive(s.marketAsk))return "FROZEN_INVALID_EXECUTABLE_QUOTE";return "";}
    private void emitDuplicate(MarketRuntime r,MarketSnapshot s,SignalDecision raw,long now,boolean mf,boolean bf,
                               FrozenProfitabilityShadowPolicy.Evaluation p,FrozenMovementRegistry.Record m){LinkedHashMap<String,Object>d=details(r,s,raw,p,m,now,r.hasActivePlan(),r.rearmRemainingMs(now)==0);d.put("duplicateGrouped",m.duplicates);record(r,s,raw,now,"FROZEN_SHADOW_DUPLICATE_GROUPED","FROZEN_DUPLICATE_GROUPED",d,mf,bf);}
    private static boolean transition(FrozenMovementRegistry.Record r,String state){if(state.equals(r.lastEventState))return false;r.lastEventState=state;return true;}

    private static LinkedHashMap<String,Object> details(MarketRuntime runtime,MarketSnapshot snapshot,
            SignalDecision raw,FrozenProfitabilityShadowPolicy.Evaluation policy,FrozenMovementRegistry.Record movement,
            long now,boolean productionActive,boolean rearmComplete){LinkedHashMap<String,Object>d=metadata();
        d.put("component",policy.component);d.put("movementSignature",movement.movementSignature);d.put("signatureMode",movement.signatureMode);
        d.put("signatureOrigin",finite(movement.origin));d.put("signatureExtreme",finite(movement.lastExtreme));d.put("signatureDistance",finite(movement.distance));
        d.put("symbol",runtime.profile.symbol);d.put("side",raw.side);d.put("family",raw.family);d.put("sourceObservedAt",now);
        d.put("marketBid",finite(snapshot.marketBid));d.put("marketAsk",finite(snapshot.marketAsk));d.put("marketLast",finite(snapshot.marketLast));
        d.put("spread",finite(snapshot.marketAsk-snapshot.marketBid));d.put("A",finite(policy.a));
        NormalizedSignalMetrics.Result m=NormalizedSignalMetrics.calculate(runtime.profile,raw.side,raw,snapshot,0);
        d.put("m1",finite(m.m1));d.put("m3",finite(m.m3));d.put("m8",finite(m.m8));d.put("accel38Directionnelle",finite(policy.accel38Directional));
        d.put("f30",finite(m.f30));d.put("f60",finite(m.f60));d.put("volumeRatio",finite(m.volumeRatio));d.put("directionalEdge",finite(m.directionalEdge));
        d.put("marketFeedFresh",true);d.put("btcFeedFresh",true);d.put("productionActivePlan",productionActive);
        d.put("productionRearmComplete",rearmComplete);d.put("legacyShadowOverlap",runtime.shadowResearch.active()!=null);
        d.put("publicOverlap",false);d.put("duplicateGrouped",movement.duplicates);return d;}
    private static LinkedHashMap<String,Object> metadata(){LinkedHashMap<String,Object>d=new LinkedHashMap<>();
        d.put("shadowPolicyVersion",FrozenProfitabilityShadowPolicy.POLICY_VERSION);d.put("shadowSchemaVersion",FrozenProfitabilityShadowPolicy.SCHEMA_VERSION);
        d.put("protocolId",FrozenProfitabilityShadowPolicy.PROTOCOL_ID);d.put("protocolSchema",FrozenProfitabilityShadowPolicy.PROTOCOL_SCHEMA);
        d.put("historicalCorpusId",FrozenProfitabilityShadowPolicy.HISTORICAL_CORPUS_ID);d.put("historicalFrames",FrozenProfitabilityShadowPolicy.HISTORICAL_FRAMES);d.put("historicalMarketHours",FrozenProfitabilityShadowPolicy.HISTORICAL_MARKET_HOURS);d.put("futureHoldoutOnly",true);
        d.put("publicActivationAllowed",false);d.put("automaticPromotionAllowed",false);return d;}
    private void safely(MarketRuntime runtime,String operation,long now,boolean mf,boolean bf,Runnable action){try{runner.run(operation,action);}catch(RuntimeException error){try{summary.internalError(operation);LinkedHashMap<String,Object>d=metadata();d.put("component","FROZEN_PROTOCOL");d.put("operation",operation);d.put("exceptionClass",error.getClass().getSimpleName());String msg=error.getMessage();d.put("message",msg==null?"":msg.substring(0,Math.min(160,msg.length())));record(runtime,null,null,now,"FROZEN_SHADOW_INTERNAL_ERROR","FROZEN_INTERNAL_ERROR",d,mf,bf);}catch(RuntimeException ignored){}}}
    private static void record(MarketRuntime runtime,MarketSnapshot snapshot,SignalDecision signal,long now,String type,String reason,Map<String,Object> details,boolean mf,boolean bf){LinkedHashMap<String,Object> merged=metadata();if(details!=null)merged.putAll(details);merged.put("marketFeedFresh",mf);merged.put("btcFeedFresh",bf);runtime.recorder.record(now,type,reason,"Frozen profitability shadow only.","FROZEN_HOLDOUT","","",signal,snapshot,0,mf,bf,0,merged);}
    private static Object finite(double v){return Double.isFinite(v)?v:null;}
    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
    private static String planKey(FrozenProfitabilityShadowPlan p){return p.opportunityId+"|"+p.branchId;}
    private LinkedHashMap<String,Object> openDetails(FrozenProfitabilityShadowPlan p){Map<String,Object> stored=openedTelemetry.get(planKey(p));return new LinkedHashMap<>(stored==null?Collections.emptyMap():stored);}
}
