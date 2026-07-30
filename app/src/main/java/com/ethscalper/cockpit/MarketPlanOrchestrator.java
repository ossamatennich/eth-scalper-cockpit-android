package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Per-runtime candidate and TP/SL-only coordinator. It has no Android dependency. */
public final class MarketPlanOrchestrator {
    public static final String BTC_STALE="V2340_BTC_REFERENCE_FEED_STALE";
    public static final String RANGE_DIAGNOSTIC=CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY;

    public Event evaluate(MarketRuntime runtime, SharedReferenceContext btc, long now,
                          boolean marketFeedFresh, boolean btcFeedFresh) {
        MarketSnapshot snapshot=MarketSnapshotFactory.build(runtime,btc,now);
        runtime.recorder.frame(now,runtime.lastDecision,snapshot,marketFeedFresh,btcFeedFresh);
        Event terminal=terminalIfTouched(runtime,snapshot,now);
        if(terminal!=null){record(runtime,snapshot,terminal.plan,null,now,terminal.status,
                terminal.reasonCode,"Plan terminé au "+terminal.status,"STRUCTURAL_SHARED","",
                "",0,marketFeedFresh,btcFeedFresh,0,Map.of("exitPrice",terminal.exitPrice));return terminal;}
        if(runtime.hasActivePlan())return Event.none(runtime.lastSignal);
        boolean fresh=marketFeedFresh&&btcFeedFresh;
        Event confirmed=advanceCandidates(runtime,snapshot,now,fresh);
        if(confirmed!=null)return confirmed;
        if(!fresh||!runtime.allowsNewPlan(now)) {
            runtime.lastDecision=SignalDecision.waiting(runtime.profile,
                    marketFeedFresh?BTC_STALE:runtime.profile.staleReasonCode,
                    "Nouveaux candidats bloqués; plans existants surveillés",0,"",false,0,0,0,false);
            record(runtime,snapshot,null,runtime.lastDecision,now,
                    runtime.rearmRemainingMs(now)>0?"REARM_BLOCKED":"ADMISSION_REJECTED",
                    runtime.lastDecision.reasonCode,runtime.lastDecision.reasonText,
                    "STRUCTURAL_SHARED","","",0,marketFeedFresh,btcFeedFresh,0,
                    Map.of("rearmRemainingMs",runtime.rearmRemainingMs(now)));
            return Event.none(runtime.lastDecision);
        }
        String setup=P02SleeveFilter.setupCandidateFor(snapshot,runtime.profile);
        if(runtime.p02SetupTracker.observe(setup)) addP02(runtime,snapshot,setup,now,
                marketFeedFresh,btcFeedFresh);
        SignalDecision raw=runtime.signalEngine.evaluate(snapshot,runtime.profile);
        runtime.lastDecision=raw;
        record(runtime,snapshot,null,raw,now,"RAW_DECISION",raw==null?"":raw.reasonCode,
                raw==null?"":raw.reasonText,"STRUCTURAL_SHARED","","",0,
                marketFeedFresh,btcFeedFresh,0,Collections.emptyMap());
        if(raw!=null&&raw.isSignal()) {
            if(raw.family.contains("RANGE_FADE")) {record(runtime,snapshot,null,raw,now,
                    "RANGE_FADE_DIAGNOSTIC_ONLY",RANGE_DIAGNOSTIC,
                    "RANGE_FADE conservé pour calibration — aucune publication finale.",
                    "STRUCTURAL_SHARED","","RANGE_FADE",0,marketFeedFresh,btcFeedFresh,0,
                    Collections.emptyMap());return Event.diagnostic(RANGE_DIAGNOSTIC,raw);}
            admitCandidate(runtime,snapshot,
                    new RuntimeCandidate(raw,CandidateLifecycle.SLEEVE_P01,now),now,
                    marketFeedFresh,btcFeedFresh);
        }
        return Event.none(raw);
    }

    private Event advanceCandidates(MarketRuntime runtime,MarketSnapshot snapshot,long now,
                                    boolean fresh) {
        Iterator<Object> iterator=runtime.observedSignals.iterator();
        while(iterator.hasNext()) {
            Object value=iterator.next(); if(!(value instanceof RuntimeCandidate))continue;
            RuntimeCandidate c=(RuntimeCandidate)value;
            c.adverse=DynamicTradePlan.updateAdverseExcursion60(c.signal.side,c.signal.entry,
                    snapshot.marketBid,snapshot.marketAsk,c.adverse);
            c.favorable=DynamicTradePlan.updateFavorableExcursionBeforeFill(c.signal.side,
                    c.signal.entry,snapshot.marketBid,snapshot.marketAsk,c.favorable);
            if(CandidateLifecycle.targetReachedBeforeConfirmedFill(c.signal,snapshot)) {
                iterator.remove();runtime.candidateTombstones.markMissed(c.signature);
                record(runtime,snapshot,null,c.signal,now,"CANDIDATE_MISSED_NO_FILL",
                        CandidateLifecycle.TARGET_BEFORE_FILL,"Cible atteinte avant fill confirmé.",
                        "STRUCTURAL_SHARED",c.historicalDiagnosticCode,c.sleeve,now-c.createdAt,
                        fresh,fresh,c.adverse,Collections.emptyMap());
                return Event.diagnostic(CandidateLifecycle.TARGET_BEFORE_FILL,c.signal);
            }
            long age=now-c.createdAt;
            long max=CandidateLifecycle.SLEEVE_P02.equals(c.sleeve)?45_000L:90_000L;
            if(age>max){iterator.remove();runtime.candidateTombstones.markMissed(c.signature);
                record(runtime,snapshot,null,c.signal,now,"CANDIDATE_EXPIRED",
                        "V23402_PENDING_CANDIDATE_EXPIRED","Candidat silencieux expiré.",
                        "STRUCTURAL_SHARED",c.historicalDiagnosticCode,c.sleeve,age,fresh,fresh,
                        c.adverse,Collections.emptyMap());continue;}
            if(!fresh||!CandidateLifecycle.currentlyExecutable(c.signal,snapshot)) {
                c.resetEarly();continue;
            }
            CandidateLifecycle.FillResult result;
            if(CandidateLifecycle.SLEEVE_P01.equals(c.sleeve)&&age<15_000L) {
                CandidateLifecycle.FillResult quality=CandidateLifecycle.processEarlyP01Candidate(
                        runtime.profile,c.signal,snapshot,true,c.createdAt,now,progress(c),c.adverse,
                        c.historicalReplayRiskVeto,!runtime.hasActivePlan(),runtime.rearmRemainingMs(now)==0,
                        c.signal.entry,false);
                P01EarlyConfirmation.StabilityResult stability=P01EarlyConfirmation.advance(now,
                        c.earlySince,c.earlyMode,quality.earlyP01);
                c.earlySince=stability.qualitySince;c.earlyMode=stability.mode;
                if(!stability.confirmed){if(!"EARLY_P01_SHADOW_REJECTED".equals(c.lastEarlyShadowEventType)){
                    c.lastEarlyShadowEventType="EARLY_P01_SHADOW_REJECTED";
                    record(runtime,snapshot,null,c.signal,now,"EARLY_P01_SHADOW_REJECTED",
                            quality.reasonCode,"Évaluation anticipée conservée en recherche fantôme; aucune publication.",
                            "STRUCTURAL_SHARED",c.historicalDiagnosticCode,c.sleeve,age,fresh,fresh,
                            c.adverse,Map.of("earlyP01Mode",c.earlyMode,"earlyP01StabilityMs",
                                    c.earlySince==0?0:now-c.earlySince,"earlyP01ReasonCode",quality.reasonCode,
                                    "scope","SHADOW_RESEARCH"));}continue;}
                CandidateLifecycle.FillResult shadow=CandidateLifecycle.processEarlyP01Candidate(
                        runtime.profile,c.signal,snapshot,true,c.createdAt,now,progress(c),c.adverse,
                        c.historicalReplayRiskVeto,!runtime.hasActivePlan(),
                        runtime.rearmRemainingMs(now)==0,c.signal.entry,true);
                String shadowEvent=shadow.confirmed?"EARLY_P01_SHADOW_WOULD_CONFIRM":"EARLY_P01_SHADOW_REJECTED";
                if(!shadowEvent.equals(c.lastEarlyShadowEventType)){c.lastEarlyShadowEventType=shadowEvent;
                    record(runtime,snapshot,null,c.signal,now,shadowEvent,shadow.reasonCode,
                            shadow.confirmed
                                    ?"La branche anticipée aurait confirmé; publication publique interdite avant 15 s."
                                    :"La branche anticipée a rejeté; aucune publication publique.",
                            "STRUCTURAL_SHARED",c.historicalDiagnosticCode,c.sleeve,age,fresh,fresh,
                            c.adverse,Map.of("earlyP01Mode",c.earlyMode,"earlyP01StabilityMs",
                                    c.earlySince==0?0:now-c.earlySince,"earlyP01ReasonCode",shadow.reasonCode,
                                    "scope","SHADOW_RESEARCH"));}
                // v2.34.2 restores the validated v2.33.1 public timing. The early
                // branch remains observability-only and can never publish.
                continue;
            } else {
                TrendRegime60.Result trend=CandidateLifecycle.SLEEVE_P02.equals(c.sleeve)
                        ? trend(runtime,c,snapshot,now):null;
                if(trend!=null)record(runtime,snapshot,null,c.signal,now,"P02_OLS60",
                        trend.reasonCode,"Évaluation OLS60 P02.","STRUCTURAL_SHARED",
                        c.historicalDiagnosticCode,c.sleeve,age,fresh,fresh,c.adverse,
                        trendDetails(trend));
                result=CandidateLifecycle.processPendingCandidate(runtime.profile,c.signal,
                        snapshot,true,c.createdAt,now,progress(c),c.adverse,
                        c.historicalReplayRiskVeto,c.sleeve,trend,runtime.candles);
            }
            if(result.confirmed) {
                iterator.remove();
                if(CandidateLifecycle.SLEEVE_P01.equals(c.sleeve))
                    runtime.lastP01ConfirmedAt=now;
                ActivePlanState state=state(runtime,result,now,snapshot);
                runtime.activePlan=state;runtime.lastSignal=result.publishedSignal;
                record(runtime,snapshot,state,result.publishedSignal,now,"CONFIRMATION_READY",
                        result.publishedSignal.reasonCode,result.publishedSignal.reasonText,
                        "STRUCTURAL_SHARED",c.historicalDiagnosticCode,c.sleeve,age,fresh,fresh,
                        c.adverse,planDetails(result.dynamicPlan));
                return Event.confirmed(result.publishedSignal,state,result);
            }
            record(runtime,snapshot,null,c.signal,now,"CONFIRMATION_REJECTED",
                    result.reasonCode,"Confirmation refusée.","STRUCTURAL_SHARED",
                    c.historicalDiagnosticCode,c.sleeve,age,fresh,fresh,c.adverse,
                    result.dynamicPlan==null?Collections.emptyMap():planDetails(result.dynamicPlan));
        }
        return null;
    }

    private void addP02(MarketRuntime runtime,MarketSnapshot s,String setup,long now,
                        boolean marketFeedFresh,boolean btcFeedFresh) {
        String side=P02SleeveFilter.sideFor(setup);if(side.isEmpty())return;
        int d="LONG".equals(side)?1:-1;
        double entry=d>0?s.marketAsk:s.marketBid;if(!(entry>0))return;
        double tp=runtime.profile.scaledMinimum(runtime.profile.p02SeedTargetReference,entry);
        double sl=runtime.profile.scaledMinimum(runtime.profile.p02SeedStopReference,entry);
        SignalDecision seed=SignalDecision.signal(runtime.profile,side,
                "v2.34 P02_CONTINUATION",80,ConfirmedSizing.BASE_QUANTITY,
                runtime.profile.floorToTick(entry),runtime.profile.roundPriceConservative(entry+d*tp,d>0),
                runtime.profile.roundPriceConservative(entry-d*sl,d>0),tp,sl,"P02",true,entry,entry,0);
        NormalizedSignalMetrics.Result metrics=NormalizedSignalMetrics.calculate(runtime.profile,
                side,seed,s,0);
        if(P02SleeveFilter.prefilter(metrics).accepted)admitCandidate(runtime,s,
                new RuntimeCandidate(seed,CandidateLifecycle.SLEEVE_P02,now),now,
                marketFeedFresh,btcFeedFresh);
    }

    private void admitCandidate(MarketRuntime runtime,MarketSnapshot snapshot,
                                RuntimeCandidate candidate,long now,
                                boolean marketFeedFresh,boolean btcFeedFresh) {
        boolean duplicate=false,opposite=false;
        for(Object value:runtime.observedSignals)if(value instanceof RuntimeCandidate) {
            RuntimeCandidate existing=(RuntimeCandidate)value;
            if(existing.signature.equals(candidate.signature))duplicate=true;
            if(!existing.signal.side.equals(candidate.signal.side))opposite=true;
        }
        MarketAdmissionPolicy.Context context=new MarketAdmissionPolicy.Context(
                marketFeedFresh,btcFeedFresh,runtime.hasActivePlan(),
                runtime.rearmRemainingMs(now)==0,opposite,duplicate,
                runtime.candidateTombstones.blocks(candidate.signature),false);
        MarketAdmissionPolicy.Result result=MarketAdmissionPolicy.evaluate(runtime.profile,
                candidate.signal,snapshot,context);
        runtime.signalEngine.recordExternalDiagnostic(now,result.reasonCode,
                result.classification+" | "+result.historicalDiagnosticCode);
        record(runtime,snapshot,null,candidate.signal,now,
                result.accepted?"ADMISSION_ACCEPTED":"ADMISSION_REJECTED",result.reasonCode,
                result.accepted?"Candidat admis silencieusement.":"Candidat rejeté silencieusement.",
                result.classification.name(),result.historicalDiagnosticCode,candidate.sleeve,0,
                marketFeedFresh,btcFeedFresh,0,Map.of("candidateSignature",candidate.signature));
        if(!result.historicalDiagnosticCode.isEmpty())record(runtime,snapshot,null,candidate.signal,
                now,"HISTORICAL_DIAGNOSTIC",result.historicalDiagnosticCode,
                "Aucun modèle replay historique certifié pour ce marché.",
                MarketAdmissionPolicy.Classification.ETH_HISTORICAL_ONLY.name(),
                result.historicalDiagnosticCode,candidate.sleeve,0,marketFeedFresh,btcFeedFresh,0,
                Collections.emptyMap());
        if(!result.accepted)return;
        candidate.historicalReplayRiskVeto=result.historicalReplayRiskVeto;
        candidate.historicalDiagnosticCode=result.historicalDiagnosticCode;
        addCandidate(runtime,candidate);
        record(runtime,snapshot,null,candidate.signal,now,"CANDIDATE_CREATED",result.reasonCode,
                "Candidat silencieux créé.",result.classification.name(),
                result.historicalDiagnosticCode,candidate.sleeve,0,marketFeedFresh,btcFeedFresh,0,
                Map.of("candidateSignature",candidate.signature));
    }

    private void addCandidate(MarketRuntime runtime,RuntimeCandidate candidate) {
        if(runtime.candidateTombstones.blocks(candidate.signature))return;
        for(Object value:runtime.observedSignals) if(value instanceof RuntimeCandidate
                &&((RuntimeCandidate)value).signature.equals(candidate.signature))return;
        runtime.observedSignals.addLast(candidate);
    }

    private static TrendRegime60.Result trend(MarketRuntime r,RuntimeCandidate c,
                                               MarketSnapshot s,long now) {
        List<TrendRegime60.MinuteClose> closes=new ArrayList<>();
        for(MarketRuntime.MarketBar b:r.candles)closes.add(new TrendRegime60.MinuteClose(b.openTime,b.close));
        List<TrendRegime60.Point> points=TrendRegime60.pointsFromMinuteCloses(closes,now,s.marketLast);
        NormalizedSignalMetrics.Result metrics=NormalizedSignalMetrics.calculate(r.profile,
                c.signal.side,c.signal,s,c.adverse);
        return TrendRegime60.evaluate(c.signal.side,metrics.a,metrics,points,now);
    }

    private static Event terminalIfTouched(MarketRuntime r,MarketSnapshot s,long now) {
        if(!r.hasActivePlan())return null;ActivePlanState p=r.activePlan;
        double exitQuote="LONG".equals(p.side)?s.marketBid:s.marketAsk;
        String status="";
        if("LONG".equals(p.side)){if(exitQuote>=p.takeProfit)status="TP_TOUCHED";else if(exitQuote<=p.stopLoss)status="SL_TOUCHED";}
        else {if(exitQuote<=p.takeProfit)status="TP_TOUCHED";else if(exitQuote>=p.stopLoss)status="SL_TOUCHED";}
        if(status.isEmpty())return null;r.terminal(now,status);return Event.terminal(status,p,exitQuote);
    }

    private static ActivePlanState state(MarketRuntime r,CandidateLifecycle.FillResult fill,
                                         long now,MarketSnapshot s) {
        SignalDecision d=fill.publishedSignal;DynamicTradePlan.Result p=fill.dynamicPlan;
        String signature=signature(d,now);
        return ActivePlanState.builder().market(r.profile).side(d.side).family(d.family)
                .reasonCode(d.reasonCode).reasonText(d.reasonText).score(d.score).quantity(d.quantity)
                .prices(d.entry,d.takeProfit,d.stopLoss).risk(d.targetMove,d.stopDistance)
                .times(now,now,now).premium15m(fill.premium15m)
                .notification(signature,3000+Math.floorMod(signature.hashCode(),1_000_000))
                .lastMarket(s.marketLast,s.marketBid,s.marketAsk,s.avgRange20)
                .lastP01ConfirmedAt(r.lastP01ConfirmedAt).movement(d.impulse,d.resetConfirmed,d.movementOrigin,
                        d.movementExtreme,d.movementDistance)
                .unitRisk(p.resultCostPerUnit,p.riskAllowancePerUnit,p.qualityRiskBudget,
                        p.theoreticalMaximumLoss)
                .structural(p.a,p.adverseExcursion60,p.baseStop,p.structuralAnchor,
                        p.structuralWindowMinutes,p.structuralBuffer,p.stopCalculationType,
                        p.stopReasonCode,p.selectedBudgetReason,p.riskPerUnit,p.riskQuantity,
                        p.qualityCap).sizingDiagnostic(sizing(p)).build();
    }

    private static String sizing(DynamicTradePlan.Result p){return String.format(Locale.US,
            "costPerUnit=%.4f|allowancePerUnit=%.4f|grossBudgetExcludingFees=%.2f|budgetReason=%s|grossRiskPerUnit=%.4f|riskQuantity=%d|qualityCap=%d|quantity=%d|grossLossAtSl=%.4f|fees=%.4f|totalLossAtSl=%.4f|A=%.4f|E=%.4f|baseStop=%.4f|anchor=%.4f|window=%d|buffer=%.4f|stop=%.4f|stopType=%s",
            p.resultCostPerUnit,p.riskAllowancePerUnit,p.qualityRiskBudget,p.selectedBudgetReason,
            p.grossRiskPerUnit,p.riskQuantity,p.qualityCap,p.finalQuantity,p.grossLossAtSl,
            p.estimatedRoundTripFees,p.estimatedTotalLossAtSl,p.a,p.adverseExcursion60,
            p.baseStop,p.structuralAnchor,p.structuralWindowMinutes,
            p.structuralBuffer,p.roundedStopDistance,p.stopCalculationType);}
    private static double progress(RuntimeCandidate c){return c.signal.targetMove>0?c.favorable/c.signal.targetMove:0;}
    public static String signature(SignalDecision d,long now){return d.symbol+"|"+d.side+"|"+d.family+"|"+d.entry+"|"+d.takeProfit+"|"+d.stopLoss+"|"+(now/60_000L);}

    private static Map<String,Object> trendDetails(TrendRegime60.Result trend){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();out.put("p02Mode",trend.mode);
        out.put("olsCount",trend.count);out.put("olsSlope",trend.slope);
        out.put("olsT60",trend.t60);out.put("p02ReasonCode",trend.reasonCode);return out;
    }
    private static Map<String,Object> planDetails(DynamicTradePlan.Result p){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();if(p==null)return out;
        out.put("A",p.a);out.put("E60",p.adverseExcursion60);out.put("R",p.structuralRoom);
        out.put("riskBudgetUsdt",p.qualityRiskBudget);out.put("resultCostPerUnit",p.resultCostPerUnit);
        out.put("riskAllowancePerUnit",p.riskAllowancePerUnit);
        out.put("theoreticalMaximumLoss",p.theoreticalMaximumLoss);out.put("quantity",p.finalQuantity);
        out.put("tp",p.takeProfit);out.put("sl",p.stopLoss);out.put("baseStop",p.baseStop);
        out.put("structuralAnchor",p.structuralAnchor);out.put("structuralWindowMinutes",p.structuralWindowMinutes);
        out.put("structuralBuffer",p.structuralBuffer);out.put("structuralStop",p.structuralStop);
        out.put("requiredStop",p.stopRequired);out.put("sanityEnvelope",p.sanityEnvelope);
        out.put("stopCalculationType",p.stopCalculationType);out.put("stopReasonCode",p.stopReasonCode);
        out.put("selectedBudgetReason",p.selectedBudgetReason);out.put("riskPerUnit",p.riskPerUnit);
        out.put("riskQuantity",p.riskQuantity);out.put("qualityCap",p.qualityCap);
        out.put("selectedStructuralLevel",p.structuralAnchor);
        out.put("structuralInvalidationDistance",p.structureDistance);
        out.put("volatilityProtectionDistance",p.volatilityProtectionDistance);
        out.put("adverseExcursionProtectionDistance",p.adverseExcursionProtectionDistance);
        out.put("spread",p.spread);out.put("tick",p.priceTick);
        out.put("technicalBuffer",p.structuralBuffer);
        out.put("finalStopDistance",p.roundedStopDistance);
        out.put("grossRiskPerUnit",p.grossRiskPerUnit);
        out.put("riskBudgetExcludingFees",p.riskBudgetExcludingFees);
        out.put("grossLossAtSl",p.grossLossAtSl);
        out.put("estimatedRoundTripFees",p.estimatedRoundTripFees);
        out.put("estimatedTotalLossAtSl",p.estimatedTotalLossAtSl);
        out.put("stopDecisionSource",p.stopCalculationType);
        out.put("rejectionReason",p.valid?"":p.reasonCode);return out;
    }
    private static void record(MarketRuntime runtime,MarketSnapshot snapshot,ActivePlanState plan,
            SignalDecision signal,long now,String type,String code,String text,String classification,
            String historical,String sleeve,long age,boolean marketFresh,boolean btcFresh,
            double adverse,Map<String,Object> details){
        if(signal==null&&plan!=null)signal=plan.toSignalDecision();
        LinkedHashMap<String,Object> merged=new LinkedHashMap<>();if(details!=null)merged.putAll(details);
        if(snapshot!=null){if(Double.isFinite(snapshot.recentLow))merged.put("recentLow",snapshot.recentLow);
            if(Double.isFinite(snapshot.recentHigh))merged.put("recentHigh",snapshot.recentHigh);
            if(signal!=null&&Double.isFinite(signal.entry))merged.put("entry",signal.entry);}
        if(plan!=null){merged.put("quantity",plan.quantity);merged.put("entry",plan.entry);
            merged.put("tp",plan.takeProfit);merged.put("sl",plan.stopLoss);
            merged.put("riskBudgetUsdt",plan.qualityRiskBudget);
            merged.put("resultCostPerUnit",plan.resultCostPerUnit);
            merged.put("riskAllowancePerUnit",plan.riskAllowancePerUnit);
            merged.put("theoreticalMaximumLoss",plan.theoreticalMaximumLoss);}
        runtime.recorder.record(now,type,code,text,classification,historical,sleeve,signal,
                snapshot,age,marketFresh,btcFresh,adverse,merged);
    }

    public static final class RuntimeCandidate {
        public final SignalDecision signal;public final String sleeve,signature;public final long createdAt;
        public double adverse,favorable;public long earlySince;public String earlyMode="",lastEarlyShadowEventType="";
        public boolean historicalReplayRiskVeto;
        public String historicalDiagnosticCode="";
        RuntimeCandidate(SignalDecision signal,String sleeve,long createdAt){this.signal=signal;this.sleeve=sleeve;this.createdAt=createdAt;this.signature=signal.symbol+"|"+signal.side+"|"+signal.family+"|"+signal.entry+"|"+signal.takeProfit+"|"+signal.stopLoss;}
        void resetEarly(){earlySince=0;earlyMode="";}
    }

    public static final class Event {
        public final String type,reasonCode,status;public final SignalDecision signal;public final ActivePlanState plan;public final CandidateLifecycle.FillResult fill;public final double exitPrice;
        private Event(String type,String reason,String status,SignalDecision signal,ActivePlanState plan,CandidateLifecycle.FillResult fill,double exitPrice){this.type=type;this.reasonCode=reason;this.status=status;this.signal=signal;this.plan=plan;this.fill=fill;this.exitPrice=exitPrice;}
        static Event none(SignalDecision d){return new Event("NONE","","",d,null,null,Double.NaN);}
        static Event diagnostic(String r,SignalDecision d){return new Event("DIAGNOSTIC",r,"",d,null,null,Double.NaN);}
        static Event confirmed(SignalDecision d,ActivePlanState p,CandidateLifecycle.FillResult f){return new Event("CONFIRMED",d.reasonCode,"",d,p,f,Double.NaN);}
        static Event terminal(String s,ActivePlanState p,double x){return new Event("TERMINAL",s,s,null,p,null,x);}
    }
}
