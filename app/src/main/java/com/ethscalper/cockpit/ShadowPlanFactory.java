package com.ethscalper.cockpit;

import java.util.Collections;

/** Builds shadow geometry exclusively through the production pure planning primitives. */
public final class ShadowPlanFactory {
    private ShadowPlanFactory() {}

    public static Result build(MarketProfile profile,SignalDecision candidate,String sleeve,
                               String signature,String component,MarketSnapshot snapshot,
                               double adverse,boolean historicalReplayRiskVeto,
                               Iterable<MarketRuntime.MarketBar> candles,long createdAt,long now) {
        if(profile==null||candidate==null||snapshot==null)return Result.rejected("SHADOW_PLAN_DATA_INVALID");
        ContinuationConfirmation.Result confirmation=ContinuationConfirmation.evaluate(profile,
                candidate.side,snapshot,true,createdAt,0.0);
        ConfirmedSizing.Result sizing=ConfirmedSizing.computeConfirmedSizingQuantity(candidate,
                snapshot,confirmation,confirmation.premium15m,historicalReplayRiskVeto);
        StructuralStopPlanner.Result stop=StructuralStopPlanner.calculate(profile,candidate.side,
                candidate.entry,snapshot.avgRange20,adverse,snapshot.recentHigh,snapshot.recentLow,
                snapshot.marketBid,snapshot.marketAsk,candles==null?Collections.emptyList():candles,now);
        if(!stop.valid)return Result.rejected(stop.reasonCode);
        AdaptiveRiskSizing.Evidence evidence=new AdaptiveRiskSizing.Evidence(true,true,
                confirmation.premium15m,sizing.cleanContextBonus,historicalReplayRiskVeto,true,
                true,true,sizing.cleanContextBonus&&sizing.move1Bonus&&sizing.move3Bonus,
                sizing.finalQuantity);
        DynamicTradePlan.Result plan=DynamicTradePlan.calculateStructural(profile,candidate.side,
                candidate.entry,snapshot.avgRange20,adverse,snapshot.recentHigh,snapshot.recentLow,
                sizing.finalQuantity,stop,evidence);
        if(!plan.valid)return Result.rejected(plan.reasonCode);
        ShadowNetEconomics.Result probe=ShadowNetEconomics.calculate(profile,candidate.entry,
                plan.roundedStopDistance,plan.roundedTargetDistance,plan.finalQuantity,
                plan.qualityRiskBudget,plan.qualityCap);
        if(!probe.valid||probe.feeAwareQuantity<profile.minimumQuantity)
            return Result.rejected("SHADOW_FEE_AWARE_QUANTITY_UNAVAILABLE");
        int shadowQuantity=probe.feeAwareQuantity;
        String id=profile.symbol+"|"+component+"|"+Integer.toUnsignedString(signature.hashCode())+"|"+now;
        ShadowPlanState state=new ShadowPlanState(id,component,signature,profile,candidate.side,
                sleeve,createdAt,now,candidate.entry,plan.takeProfit,plan.stopLoss,
                plan.roundedStopDistance,plan.roundedTargetDistance,shadowQuantity,
                plan.qualityCap,plan.qualityRiskBudget,plan.resultCostPerUnit,
                plan.a,plan.adverseExcursion60);
        ShadowNetEconomics.Result economics=ShadowNetEconomics.calculate(profile,candidate.entry,
                plan.roundedStopDistance,plan.roundedTargetDistance,shadowQuantity,
                plan.qualityRiskBudget,plan.qualityCap);
        return new Result(true,"SHADOW_PLAN_READY",state,plan,sizing,economics,plan.finalQuantity);
    }

    /** Re-anchors research geometry to the executable quote without mutating the source candidate. */
    public static Result buildReanchored(MarketProfile profile,SignalDecision candidate,String sleeve,
            String signature,String component,MarketSnapshot snapshot,double adverse,
            boolean historicalReplayRiskVeto,Iterable<MarketRuntime.MarketBar> candles,
            long createdAt,long now) {
        if(profile==null||candidate==null||snapshot==null)return Result.rejected("SHADOW_PLAN_DATA_INVALID");
        double quote="LONG".equals(candidate.side)?snapshot.marketAsk:snapshot.marketBid;
        if(!Double.isFinite(quote)||quote<=0)return Result.rejected("SHADOW_EXECUTABLE_QUOTE_INVALID");
        double entry="LONG".equals(candidate.side)?profile.ceilToTick(quote):profile.floorToTick(quote);
        SignalDecision reanchored=SignalDecision.signal(profile,candidate.side,candidate.family,
                candidate.score,candidate.quantity,entry,candidate.takeProfit,candidate.stopLoss,
                candidate.targetMove,candidate.stopDistance,candidate.impulse,candidate.resetConfirmed,
                candidate.movementOrigin,candidate.movementExtreme,candidate.movementDistance);
        return build(profile,reanchored,sleeve,signature,component,snapshot,adverse,
                historicalReplayRiskVeto,candles,createdAt,now);
    }

    public static Result fromProduction(MarketProfile profile,String signature,String component,
                                        String sleeve,long createdAt,long now,
                                        CandidateLifecycle.FillResult fill) {
        if(profile==null||fill==null||!fill.confirmed||fill.publishedSignal==null
                ||fill.dynamicPlan==null)return Result.rejected("SHADOW_PLAN_DATA_INVALID");
        DynamicTradePlan.Result p=fill.dynamicPlan;SignalDecision d=fill.publishedSignal;
        int quality=p.qualityCap;
        ShadowPlanState state=new ShadowPlanState(profile.symbol+"|PUBLIC|"+now,component,signature,
                profile,d.side,sleeve,createdAt,now,d.entry,d.takeProfit,d.stopLoss,
                d.stopDistance,d.targetMove,d.quantity,quality,p.qualityRiskBudget,p.resultCostPerUnit,
                p.a,p.adverseExcursion60);
        ShadowNetEconomics.Result economics=ShadowNetEconomics.calculate(profile,d.entry,
                d.stopDistance,d.targetMove,d.quantity,p.qualityRiskBudget,quality);
        return new Result(true,"SHADOW_PRODUCTION_GEOMETRY_READY",state,p,fill.sizing,economics,d.quantity);
    }

    public static final class Result {
        public final boolean valid;public final String reasonCode;public final ShadowPlanState state;
        public final DynamicTradePlan.Result plan;public final ConfirmedSizing.Result sizing;
        public final ShadowNetEconomics.Result economics;
        public final int baselineQuantity;
        private Result(boolean valid,String reason,ShadowPlanState state,DynamicTradePlan.Result plan,
                       ConfirmedSizing.Result sizing,ShadowNetEconomics.Result economics,int baselineQuantity){
            this.valid=valid;reasonCode=reason;this.state=state;this.plan=plan;
            this.sizing=sizing;this.economics=economics;this.baselineQuantity=baselineQuantity;
        }
        private static Result rejected(String reason){return new Result(false,reason,null,null,null,null,0);}
    }
}
