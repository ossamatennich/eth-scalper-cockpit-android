package com.ethscalper.cockpit;

/** Pure, diagnostic-only fee-aware quantity and net reward/risk calculation. */
public final class ShadowNetEconomics {
    private static final double EPS=1e-12;
    private ShadowNetEconomics() {}

    public static Result calculate(MarketProfile profile,double entry,double stopDistance,
                                   double targetDistance,int activeQuantity,
                                   double riskBudgetUsdt,int qualityCap) {
        if(profile==null||!positive(entry)||!positive(stopDistance)||!positive(targetDistance)
                ||activeQuantity<1||!positive(riskBudgetUsdt))return Result.invalid();
        double cost=profile.scaledMinimum(profile.resultRoundTripCostReference,entry);
        double riskPerUnit=stopDistance+cost;
        int raw=(int)Math.floor((riskBudgetUsdt+EPS)/riskPerUnit);
        int stepped=(raw/profile.quantityStep)*profile.quantityStep;
        int cap=qualityCap>0?Math.min(qualityCap,profile.maximumQuantity):profile.maximumQuantity;
        int quantity=Math.min(stepped,cap);
        if(quantity<profile.minimumQuantity)quantity=0;
        double activeGross=activeQuantity*stopDistance;
        double activeFees=activeQuantity*cost;
        double feeGross=quantity*stopDistance;
        double feeFees=quantity*cost;
        double netTarget=activeQuantity*(targetDistance-cost);
        double netStop=activeQuantity*(stopDistance+cost);
        double netR=netTarget>0&&netStop>0?netTarget/netStop:Double.NaN;
        return new Result(true,cost,riskPerUnit,quantity,activeGross,activeFees,
                activeGross+activeFees,feeGross,feeFees,feeGross+feeFees,
                netTarget,netStop,netR,activeGross+activeFees>riskBudgetUsdt+1e-9);
    }

    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
    public static final class Result {
        public final boolean valid,activeExceedsBudgetAfterFees;
        public final double estimatedRoundTripCostPerUnit,feeAwareRiskPerUnit;
        public final int feeAwareQuantity;
        public final double activeGrossStopLossUsdt,activeEstimatedFeesUsdt;
        public final double activeTotalStopLossUsdt,feeAwareGrossStopLossUsdt;
        public final double feeAwareEstimatedFeesUsdt,feeAwareTotalStopLossUsdt;
        public final double netTargetUsdt,netStopUsdt,netRewardRisk;
        private Result(boolean valid,double cost,double risk,int quantity,double activeGross,
                       double activeFees,double activeTotal,double feeGross,double feeFees,
                       double feeTotal,double netTarget,double netStop,double netR,boolean exceeds){
            this.valid=valid;estimatedRoundTripCostPerUnit=cost;feeAwareRiskPerUnit=risk;
            feeAwareQuantity=quantity;activeGrossStopLossUsdt=activeGross;
            activeEstimatedFeesUsdt=activeFees;activeTotalStopLossUsdt=activeTotal;
            feeAwareGrossStopLossUsdt=feeGross;feeAwareEstimatedFeesUsdt=feeFees;
            feeAwareTotalStopLossUsdt=feeTotal;netTargetUsdt=netTarget;netStopUsdt=netStop;
            netRewardRisk=netR;activeExceedsBudgetAfterFees=exceeds;
        }
        private static Result invalid(){return new Result(false,Double.NaN,Double.NaN,0,
                Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,
                Double.NaN,Double.NaN,Double.NaN,false);}
    }
}
