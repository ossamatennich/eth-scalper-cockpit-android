package com.ethscalper.cockpit;

/** Pure, deterministic presentation math for an immutable published plan. */
public final class PlanMetricsCalculator {
    private PlanMetricsCalculator() {}

    public static Result calculate(String side, int quantity, double entry, double takeProfit,
                                   double stopLoss, double currentPrice, double bid, double ask,
                                   double resultCostPerUnit, double riskAllowancePerUnit,
                                   double riskBudgetUsdt, int leverage) {
        boolean complete = ("LONG".equals(side) || "SHORT".equals(side)) && quantity > 0
                && positive(entry) && positive(takeProfit) && positive(stopLoss)
                && positive(resultCostPerUnit) && positive(riskAllowancePerUnit)
                && positive(riskBudgetUsdt) && leverage > 0;
        if (!complete) return Result.incomplete();
        double targetDistance=Math.abs(takeProfit-entry), stopDistance=Math.abs(entry-stopLoss);
        if (!(targetDistance>0)||!(stopDistance>0)) return Result.incomplete();
        double grossProfit=targetDistance*quantity;
        double grossLoss=stopDistance*quantity;
        double estimatedFees=resultCostPerUnit*quantity;
        double netProfit=grossProfit-estimatedFees;
        double netLoss=grossLoss+estimatedFees;
        // The public 14.55 USDT budget is the gross market movement to the SL.
        // Fees and the execution allowance remain separate information.
        double theoreticalLoss=grossLoss;
        double rewardRisk=targetDistance/stopDistance;
        double progress=Double.NaN,distanceToTarget=Double.NaN,distanceToStop=Double.NaN;
        if(positive(currentPrice)){
            int d="LONG".equals(side)?1:-1;
            progress=d*(currentPrice-entry)/targetDistance*100.0;
            distanceToTarget=d*(takeProfit-currentPrice);
            distanceToStop=d*(currentPrice-stopLoss);
        }
        boolean executable="LONG".equals(side)?positive(ask)&&ask<=entry:positive(bid)&&bid>=entry;
        double notional=entry*quantity;
        return new Result(true,grossProfit,grossLoss,estimatedFees,netProfit,netLoss,
                theoreticalLoss,rewardRisk,progress,distanceToTarget,distanceToStop,
                executable,notional,notional/leverage);
    }

    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}

    public static final class Result {
        public final boolean complete;
        public final double grossProfit,grossLoss,estimatedFees,netProfit,netLoss;
        public final double theoreticalMaximumLoss,rewardRisk,progressPercent;
        public final double distanceToTarget,distanceToStop,notional,estimatedMargin;
        public final boolean currentlyExecutable;
        Result(boolean complete,double grossProfit,double grossLoss,double estimatedFees,
               double netProfit,double netLoss,double theoreticalMaximumLoss,double rewardRisk,
               double progressPercent,double distanceToTarget,double distanceToStop,
               boolean currentlyExecutable,double notional,double estimatedMargin){
            this.complete=complete;this.grossProfit=grossProfit;this.grossLoss=grossLoss;
            this.estimatedFees=estimatedFees;this.netProfit=netProfit;this.netLoss=netLoss;
            this.theoreticalMaximumLoss=theoreticalMaximumLoss;this.rewardRisk=rewardRisk;
            this.progressPercent=progressPercent;this.distanceToTarget=distanceToTarget;
            this.distanceToStop=distanceToStop;this.currentlyExecutable=currentlyExecutable;
            this.notional=notional;this.estimatedMargin=estimatedMargin;
        }
        static Result incomplete(){return new Result(false,Double.NaN,Double.NaN,Double.NaN,
                Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,
                Double.NaN,false,Double.NaN,Double.NaN);}
    }
}
