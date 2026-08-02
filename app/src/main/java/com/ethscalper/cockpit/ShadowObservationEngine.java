package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared, fail-open shadow observation layer used by both the legacy ETH engine and the
 * generic per-market orchestrator. It owns no public plan state and has no Android dependency.
 */
public final class ShadowObservationEngine {
    public static final String ETH_FLOW_EXPANSION_EXTENDED="ETH_FLOW_EXPANSION_EXTENDED";
    public static final String ETH_BTC_LED_BREAKOUT_RESEARCH="ETH_BTC_LED_BREAKOUT_RESEARCH";
    private static final String DUPLICATE_HIGHER="SHADOW_DUPLICATE_HIGHER_PRIORITY_LANE";
    private static final int MAX_MISSED_MOVEMENTS=160;
    private static final long MISSED_MOVEMENT_BUCKET_MS=15_000L;

    @FunctionalInterface public interface OperationRunner {
        void execute(String operation,Runnable action);
    }
    @FunctionalInterface public interface EventSink {
        void record(ShadowEvent event);
    }

    public static final class Context {
        public final MarketRuntime runtime;
        public final MarketSnapshot snapshot;
        public final List<MarketRuntime.MarketBar> structuralBars;
        public final long now;
        public final boolean marketFresh,btcFresh,productionActivePlan,rearmComplete,tombstoned;
        public Context(MarketRuntime runtime,MarketSnapshot snapshot,long now,
                       boolean marketFresh,boolean btcFresh,boolean productionActivePlan,
                       boolean rearmComplete,boolean tombstoned,
                       List<MarketRuntime.MarketBar> structuralBars){
            if(runtime==null)throw new IllegalArgumentException("runtime");
            this.runtime=runtime;this.snapshot=snapshot;this.now=now;
            this.marketFresh=marketFresh;this.btcFresh=btcFresh;
            this.productionActivePlan=productionActivePlan;this.rearmComplete=rearmComplete;
            this.tombstoned=tombstoned;
            this.structuralBars=structuralBars==null?Collections.emptyList():structuralBars;
        }
    }

    /** Mutable diagnostic adapter; synchronization back to the public candidate is explicit. */
    public static final class Candidate {
        public final SignalDecision signal;
        public final String sleeve,signature,historicalDiagnosticCode;
        public final long createdAt;
        public final boolean historicalReplayRiskVeto;
        public double adverse,favorable;
        public String lastLaneReason="";
        public long extendedQualificationAt,extendedFirstExecutableAt;
        public Candidate(SignalDecision signal,String sleeve,String signature,long createdAt,
                         double adverse,double favorable,boolean replayVeto,String historicalCode){
            this.signal=signal;this.sleeve=sleeve==null?"":sleeve;
            this.signature=signature==null?"":signature;this.createdAt=createdAt;
            this.adverse=adverse;this.favorable=favorable;
            historicalReplayRiskVeto=replayVeto;
            historicalDiagnosticCode=historicalCode==null?"":historicalCode;
        }
    }

    public static final class ShadowEvent {
        final Context context;final SignalDecision signal;final String type,code,text,sleeve;
        final long age;final double adverse;final Map<String,Object> details;
        ShadowEvent(Context context,SignalDecision signal,String type,String code,String text,
                    String sleeve,long age,double adverse,Map<String,Object> details){
            this.context=context;this.signal=signal;this.type=type;this.code=code;this.text=text;
            this.sleeve=sleeve;this.age=age;this.adverse=adverse;this.details=details;
        }
    }

    private final OperationRunner runner;
    private final EventSink sink;
    private final Deque<String> missedOrder=new ArrayDeque<>();
    private final Set<String> missedMovements=new HashSet<>();

    public ShadowObservationEngine(){this((operation,action)->action.run(),defaultSink());}
    public ShadowObservationEngine(OperationRunner runner){this(runner,defaultSink());}
    public ShadowObservationEngine(OperationRunner runner,EventSink sink){
        this.runner=runner==null?(operation,action)->action.run():runner;
        this.sink=sink==null?defaultSink():sink;
    }
    public static OperationRunner noOpRunner(){return (operation,action)->{};}
    public static EventSink defaultSink(){return event->event.context.runtime.recorder.record(
            event.context.now,event.type,event.code,event.text,"SHADOW_OBSERVABILITY","",
            event.sleeve,event.signal,event.context.snapshot,event.age,event.context.marketFresh,
            event.context.btcFresh,event.adverse,event.details);}

    public void safeObserveTerminal(Context context){safe(context,"SHADOW_LIFECYCLE",
            "OBSERVE_TERMINAL",()->observeTerminal(context));}
    public void safeConsiderAddedPlan(Context context,Candidate candidate){safe(context,
            "SHADOW_ADDED_LANES","CONSIDER_OPEN",()->considerAddedPlan(context,candidate));}
    public void safeObserveProductionConfirmation(Context context,Candidate candidate,
                                                  CandidateLifecycle.FillResult result,
                                                  String entryRevalidationCode){safe(context,
            "SHADOW_AB_GUARD","OBSERVE_CONFIRMATION",()->observeProductionConfirmation(
                    context,candidate,result,entryRevalidationCode));}
    public void safeObserveMissedMove(Context context,Candidate candidate,
                                      Map<String,Object> creationContext){safe(context,
            ETH_BTC_LED_BREAKOUT_RESEARCH,"OBSERVE_MISSED_MOVE",()->observeMissedMove(
                    context,candidate,creationContext));}

    public synchronized void resetResearchMemory(){missedOrder.clear();missedMovements.clear();}

    private void safe(Context c,String component,String operation,Runnable action){
        try{runner.execute(operation,action);}catch(RuntimeException failure){
            safeRecordInternalError(c,component,operation,failure);
        }
    }

    public void safeRecordInternalError(Context c,String component,String operation,
                                        RuntimeException failure) {
        try{
            LinkedHashMap<String,Object>d=base(c,null,component);
            d.put("operation",operation);d.put("exceptionClass",failure.getClass().getName());
            String message=failure.getMessage()==null?"":failure.getMessage();
            d.put("message",message.substring(0,Math.min(256,message.length())));
            write(c,null,"SHADOW_INTERNAL_ERROR","SHADOW_INTERNAL_ERROR",
                    "Erreur shadow isolée; moteur public poursuivi.","",0,0,d);
        }catch(RuntimeException ignored){/* the shadow recorder is isolated too */}
    }

    private void observeTerminal(Context c){
        ShadowPlanState active=c.runtime.shadowResearch.active();
        ShadowPlanState.Terminal terminal=c.runtime.shadowResearch.observe(c.now,
                c.snapshot==null?Double.NaN:c.snapshot.marketBid,
                c.snapshot==null?Double.NaN:c.snapshot.marketAsk,c.marketFresh);
        if(active==null||terminal==null)return;
        LinkedHashMap<String,Object>d=base(c,null,active.component);
        d.put("decision","TERMINAL");d.put("shadowReasonCode",terminal.status);
        addPlan(d,active);d.put("candidateSignature",active.candidateSignature);
        d.put("sourceCandidateCreatedAt",active.sourceCandidateCreatedAt);
        d.put("side",active.side);d.put("sleeve",active.sleeve);
        d.put("openedAt",active.openedAt);d.put("terminalAt",terminal.at);
        d.put("durationMs",terminal.at-active.openedAt);d.put("exitQuote",terminal.exitQuote);
        d.put("plannedGrossStopUsdt",terminal.plannedGrossStopUsdt);
        d.put("plannedFeesUsdt",terminal.plannedFeesUsdt);
        d.put("plannedNetStopUsdt",terminal.plannedNetStopUsdt);
        d.put("grossResultUsdt",terminal.grossResultUsdt);
        d.put("estimatedFeesUsdt",terminal.estimatedFeesUsdt);
        d.put("netResultUsdt",terminal.netResultUsdt);
        d.put("resultR",finiteOrNull(terminal.resultR));d.put("terminalStatus",terminal.status);
        write(c,null,terminal.status,terminal.status,"Terminal shadow observé; compteurs publics inchangés.",
                active.sleeve,c.now-active.sourceCandidateCreatedAt,0,d);
    }

    private void observeProductionConfirmation(Context c,Candidate candidate,
                                               CandidateLifecycle.FillResult result,
                                               String revalidation){
        if(candidate==null||result==null||!result.confirmed||result.publishedSignal==null)return;
        NormalizedSignalMetrics.Result metrics=result.normalizedMetrics;
        ShadowCalibrationPolicy.Decision decision=CandidateLifecycle.SLEEVE_P02.equals(candidate.sleeve)
                ?ShadowCalibrationPolicy.p02Symbolic(c.runtime.profile,candidate.signal.score,metrics)
                :ShadowCalibrationPolicy.p01FinalGuard(candidate.signal.score,metrics,
                        result.p01SleeveFilter,revalidation);
        String component=CandidateLifecycle.SLEEVE_P02.equals(candidate.sleeve)
                ?ShadowCalibrationPolicy.P02_GUARD:ShadowCalibrationPolicy.P01_GUARD;
        LinkedHashMap<String,Object>d=base(c,candidate,component);
        d.put("decision",decision.decision);d.put("shadowReasonCode",decision.reasonCode);
        d.put("productionConfirmed",true);d.put("score",candidate.signal.score);
        putMetrics(d,metrics,candidate.adverse);d.put("entryRevalidationCode",safe(revalidation));
        d.put("entryRevalidationAlreadyBlocksProduction",revalidation!=null&&!revalidation.isEmpty());
        d.put("entryRevalidationHistoricalDiagnostic",false);
        d.put("recordedHistoricalDiagnosticCode",candidate.historicalDiagnosticCode);
        if(result.p01SleeveFilter!=null){d.put("p01Phase",result.p01SleeveFilter.phase);
            d.put("flowBacked",result.p01SleeveFilter.flowBacked);
            d.put("priceLed",result.p01SleeveFilter.priceLed);
        }else{d.put("p01Phase","");d.put("flowBacked",false);d.put("priceLed",false);}
        SignalDecision published=result.publishedSignal;
        d.put("entry",published.entry);d.put("tp",published.takeProfit);
        d.put("sl",published.stopLoss);d.put("quantity",published.quantity);
        write(c,published,"SHADOW_AB_DECISION",decision.reasonCode,
                "Décision A/B shadow sur confirmation publique.",candidate.sleeve,
                c.now-candidate.createdAt,candidate.adverse,d);
        ShadowPlanFactory.Result geometry=ShadowPlanFactory.fromProduction(c.runtime.profile,
                candidate.signature,component,candidate.sleeve,candidate.createdAt,c.now,result);
        if(geometry.valid)recordFeeAware(c,published,geometry.state,geometry.economics,true);
    }

    private void considerAddedPlan(Context c,Candidate candidate){
        if(candidate==null||candidate.signal==null||c.snapshot==null
                ||!CandidateLifecycle.SLEEVE_P01.equals(candidate.sleeve))return;
        NormalizedSignalMetrics.Result metrics=NormalizedSignalMetrics.calculate(c.runtime.profile,
                candidate.signal.side,candidate.signal,c.snapshot,candidate.adverse);
        ShadowCalibrationPolicy.Decision pullback=ShadowCalibrationPolicy.pullback(
                candidate.signal.score,metrics);
        ShadowCalibrationPolicy.Decision mid=ShadowCalibrationPolicy.ethMidVol(c.runtime.profile,
                candidate.signal.score,metrics);
        ShadowCalibrationPolicy.Decision extended=ShadowCalibrationPolicy.ethFlowExpansionExtended(
                c.runtime.profile,candidate.signal.score,metrics);
        if(!pullback.keep&&!mid.keep&&!extended.keep)return;
        if(extended.keep&&candidate.extendedQualificationAt<=0)candidate.extendedQualificationAt=c.now;
        boolean executable=CandidateLifecycle.currentlyExecutable(candidate.signal,c.snapshot);
        if(extended.keep&&executable&&candidate.extendedFirstExecutableAt<=0)
            candidate.extendedFirstExecutableAt=c.now;
        String selected=pullback.keep?ShadowCalibrationPolicy.PULLBACK
                :mid.keep?ShadowCalibrationPolicy.ETH_MID_VOL:ETH_FLOW_EXPANSION_EXTENDED;
        if(pullback.keep&&mid.keep)recordWouldQualify(c,candidate,metrics,
                ShadowCalibrationPolicy.ETH_MID_VOL);
        if((pullback.keep||mid.keep)&&extended.keep)recordWouldQualify(c,candidate,metrics,
                ETH_FLOW_EXPANSION_EXTENDED);
        if(!c.marketFresh||!c.btcFresh){recordSkipOnce(c,candidate,metrics,selected,
                "SHADOW_FEED_STALE","SKIP");return;}
        if(c.productionActivePlan){recordSkipOnce(c,candidate,metrics,selected,
                "SHADOW_PRODUCTION_PLAN_ACTIVE","SKIP");return;}
        if(!c.rearmComplete){recordSkipOnce(c,candidate,metrics,selected,
                "SHADOW_PRODUCTION_REARM_ACTIVE","SKIP");return;}
        if(c.tombstoned){recordSkipOnce(c,candidate,metrics,selected,
                "SHADOW_CANDIDATE_TOMBSTONED","SKIP");return;}
        if(!executable){recordSkipOnce(c,candidate,metrics,selected,
                "SHADOW_LIMIT_NOT_EXECUTABLE","SKIP");return;}
        if(!ShadowCalibrationPolicy.targetUntouchedBeforeOpen(candidate.signal,candidate.favorable)){
            recordSkipOnce(c,candidate,metrics,selected,
                    "SHADOW_TARGET_ALREADY_TOUCHED_BEFORE_OPEN","SKIP");return;}
        if(!c.runtime.shadowResearch.canOpen(candidate.signature,c.now)){
            recordSkipOnce(c,candidate,metrics,selected,c.runtime.shadowResearch.active()!=null
                    ?"SHADOW_PLAN_ALREADY_ACTIVE":"SHADOW_DEDUP_OR_COOLDOWN","SKIP");return;}
        ShadowPlanFactory.Result built=ShadowPlanFactory.build(c.runtime.profile,candidate.signal,
                candidate.sleeve,candidate.signature,selected,c.snapshot,candidate.adverse,
                candidate.historicalReplayRiskVeto,c.structuralBars,candidate.createdAt,c.now);
        if(!built.valid){recordSkipOnce(c,candidate,metrics,selected,built.reasonCode,"SKIP");return;}
        if((ShadowCalibrationPolicy.ETH_MID_VOL.equals(selected)||ETH_FLOW_EXPANSION_EXTENDED.equals(selected))
                &&(!built.economics.valid||!(built.economics.netTargetUsdt>0)
                ||!(built.economics.netStopUsdt>0)||built.economics.netRewardRisk+1e-12<.40)){
            recordSkipOnce(c,candidate,metrics,selected,"SHADOW_NET_REWARD_RISK_TOO_LOW","SKIP");return;}
        if(!c.runtime.shadowResearch.open(built.state)){recordSkipOnce(c,candidate,metrics,selected,
                "SHADOW_DEDUP_OR_COOLDOWN","SKIP");return;}
        LinkedHashMap<String,Object>d=base(c,candidate,selected);
        d.put("decision","OPEN");d.put("shadowReasonCode","SHADOW_PLAN_OPENED");
        addPlan(d,built.state);putMetrics(d,metrics,candidate.adverse);
        if(ETH_FLOW_EXPANSION_EXTENDED.equals(selected))putLatency(d,candidate,c.now);
        write(c,candidate.signal,"SHADOW_PLAN_OPENED","SHADOW_PLAN_OPENED",
                "Plan shadow ouvert sans publication ni alerte.",candidate.sleeve,
                c.now-candidate.createdAt,candidate.adverse,d);
        recordFeeAware(c,candidate.signal,built.state,built.economics,false);
    }

    private void recordWouldQualify(Context c,Candidate candidate,NormalizedSignalMetrics.Result metrics,
                                    String component){
        recordSkipOnce(c,candidate,metrics,component,DUPLICATE_HIGHER,"WOULD_QUALIFY");
    }

    private void recordSkipOnce(Context c,Candidate candidate,NormalizedSignalMetrics.Result metrics,
                                String component,String reason,String decision){
        String key=decision+"|"+component+"|"+reason;if(key.equals(candidate.lastLaneReason))return;
        candidate.lastLaneReason=key;LinkedHashMap<String,Object>d=base(c,candidate,component);
        d.put("decision",decision);d.put("shadowReasonCode",reason);
        putMetrics(d,metrics,candidate.adverse);
        if(ETH_FLOW_EXPANSION_EXTENDED.equals(component))putLatency(d,candidate,0);
        write(c,candidate.signal,"SHADOW_PLAN_SKIPPED",reason,
                "Voie shadow observée sans effet public.",candidate.sleeve,
                c.now-candidate.createdAt,candidate.adverse,d);
    }

    private synchronized void observeMissedMove(Context c,Candidate candidate,
                                                Map<String,Object> creationContext){
        if(candidate==null||candidate.signal==null
                ||!MarketProfile.ETH_SYMBOL.equals(c.runtime.profile.symbol))return;
        String movementKey=candidate.signal.side+"|"+(candidate.createdAt/MISSED_MOVEMENT_BUCKET_MS)
                +"|"+Math.round(candidate.signal.movementOrigin/c.runtime.profile.priceTick);
        if(missedMovements.contains(movementKey))return;
        missedMovements.add(movementKey);missedOrder.addLast(movementKey);
        while(missedOrder.size()>MAX_MISSED_MOVEMENTS)missedMovements.remove(missedOrder.removeFirst());
        LinkedHashMap<String,Object>d=base(c,candidate,ETH_BTC_LED_BREAKOUT_RESEARCH);
        d.put("decision","OBSERVE");d.put("shadowReasonCode","SHADOW_TARGET_REACHED_BEFORE_CONFIRMATION");
        NormalizedSignalMetrics.Result metrics=NormalizedSignalMetrics.calculate(c.runtime.profile,
                candidate.signal.side,candidate.signal,c.snapshot,candidate.adverse);
        putMetrics(d,metrics,candidate.adverse);
        if(c.snapshot!=null){d.put("btcMove1",finiteOrNull(c.snapshot.btcMove1));
            d.put("btcMove3",finiteOrNull(c.snapshot.btcMove3));d.put("btcMove8",finiteOrNull(c.snapshot.btcMove8));}
        if(creationContext!=null)d.putAll(creationContext);
        write(c,candidate.signal,"SHADOW_MISSED_MOVE_OBSERVATION",
                "SHADOW_TARGET_REACHED_BEFORE_CONFIRMATION",
                "Mouvement ETH manqué observé sans ouvrir de plan shadow.",candidate.sleeve,
                c.now-candidate.createdAt,candidate.adverse,d);
    }

    private void recordFeeAware(Context c,SignalDecision signal,ShadowPlanState state,
                                ShadowNetEconomics.Result e,boolean productionConfirmed){
        if(e==null||!e.valid)return;LinkedHashMap<String,Object>d=base(c,null,state.component);
        d.put("decision","MEASURE");d.put("shadowReasonCode","SHADOW_FEE_AWARE_SIZING");
        addPlan(d,state);d.put("candidateSignature",state.candidateSignature);
        d.put("sourceCandidateCreatedAt",state.sourceCandidateCreatedAt);
        d.put("productionConfirmed",productionConfirmed);
        d.put("activeQuantity",state.quantity);d.put("feeAwareQuantity",e.feeAwareQuantity);
        d.put("riskBudgetUsdt",state.riskBudgetUsdt);d.put("roundedStopDistance",state.stopDistance);
        d.put("estimatedRoundTripCostPerUnit",e.estimatedRoundTripCostPerUnit);
        d.put("activeGrossStopLossUsdt",e.activeGrossStopLossUsdt);
        d.put("activeEstimatedFeesUsdt",e.activeEstimatedFeesUsdt);
        d.put("activeTotalStopLossUsdt",e.activeTotalStopLossUsdt);
        d.put("feeAwareGrossStopLossUsdt",e.feeAwareGrossStopLossUsdt);
        d.put("feeAwareEstimatedFeesUsdt",e.feeAwareEstimatedFeesUsdt);
        d.put("feeAwareTotalStopLossUsdt",e.feeAwareTotalStopLossUsdt);
        d.put("netTargetUsdt",e.netTargetUsdt);d.put("netStopUsdt",e.netStopUsdt);
        d.put("netRewardRisk",e.netRewardRisk);
        d.put("activeExceedsBudgetAfterFees",e.activeExceedsBudgetAfterFees);
        write(c,signal,"SHADOW_FEE_AWARE_SIZING","SHADOW_FEE_AWARE_SIZING",
                "Sonde de sizing frais inclus; quantité publique inchangée.",state.sleeve,
                c.now-state.sourceCandidateCreatedAt,0,d);
    }

    private static LinkedHashMap<String,Object> base(Context c,Candidate candidate,String component){
        LinkedHashMap<String,Object>d=new LinkedHashMap<>();
        d.put("shadowPolicyVersion",ShadowCalibrationPolicy.VERSION);
        d.put("shadowSchemaVersion",ShadowCalibrationPolicy.SCHEMA_VERSION);
        d.put("component",component);d.put("observedAt",c.now);
        d.put("productionActivePlan",c.productionActivePlan);d.put("productionConfirmed",false);
        d.put("marketFeedFresh",c.marketFresh);d.put("btcFeedFresh",c.btcFresh);
        d.put("symbol",c.runtime.profile.symbol);d.put("asset",c.runtime.profile.asset);
        d.put("profileVersion",c.runtime.profile.profileVersion);
        if(candidate!=null){d.put("candidateSignature",candidate.signature);
            d.put("sourceCandidateCreatedAt",candidate.createdAt);d.put("side",candidate.signal.side);
            d.put("sleeve",candidate.sleeve);}
        return d;
    }

    private static void addPlan(Map<String,Object>d,ShadowPlanState p){
        d.put("shadowPlanId",p.shadowPlanId);d.put("entry",p.entry);d.put("tp",p.tp);
        d.put("sl",p.sl);d.put("quantity",p.quantity);d.put("roundedStopDistance",p.stopDistance);
        d.put("roundedTargetDistance",p.targetDistance);d.put("A",finiteOrNull(p.a));
        d.put("E60",finiteOrNull(p.adverseExcursion60));d.put("eNormalized",finiteOrNull(p.eNormalized));
    }
    static void putMetrics(Map<String,Object>d,NormalizedSignalMetrics.Result m,double adverse){
        if(m==null)return;d.put("A",finiteOrNull(m.a));d.put("E60",finiteOrNull(adverse));
        d.put("eNormalized",finiteOrNull(m.e));d.put("room",finiteOrNull(m.room));
        d.put("m1",finiteOrNull(m.m1));d.put("m3",finiteOrNull(m.m3));d.put("m8",finiteOrNull(m.m8));
        d.put("f30",finiteOrNull(m.f30));d.put("f60",finiteOrNull(m.f60));
        d.put("volumeRatio",finiteOrNull(m.volumeRatio));d.put("directionalEdge",finiteOrNull(m.directionalEdge));
    }
    private static void putLatency(Map<String,Object>d,Candidate c,long openedAt){
        d.put("qualificationAt",c.extendedQualificationAt>0?c.extendedQualificationAt:null);
        d.put("firstExecutableAt",c.extendedFirstExecutableAt>0?c.extendedFirstExecutableAt:null);
        d.put("shadowOpenedAt",openedAt>0?openedAt:null);
        d.put("executableDelayMs",c.extendedQualificationAt>0&&c.extendedFirstExecutableAt>0
                ?Math.max(0,c.extendedFirstExecutableAt-c.extendedQualificationAt):null);
    }
    private void write(Context c,SignalDecision signal,String type,String code,String text,
                       String sleeve,long age,double adverse,Map<String,Object> details){
        LinkedHashMap<String,Object> normalized=new LinkedHashMap<>(details);
        normalized.put("shadowPolicyVersion",ShadowCalibrationPolicy.VERSION);
        normalized.put("shadowSchemaVersion",ShadowCalibrationPolicy.SCHEMA_VERSION);
        sink.record(new ShadowEvent(c,signal,type,code,text,sleeve,Math.max(0,age),adverse,normalized));
    }
    private static Object finiteOrNull(double value){return Double.isFinite(value)?value:null;}
    private static String safe(String value){return value==null?"":value;}
}
