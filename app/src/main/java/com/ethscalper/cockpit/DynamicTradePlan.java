package com.ethscalper.cockpit;

/** Pure v2.32.9 structural SL, market TP and risk-budget sizing calculation. */
public final class DynamicTradePlan {
    public static final String CONFIRMED = "V2329_DYNAMIC_PLAN_CONFIRMED";
    public static final String INVALID_DATA = "V2329_DYNAMIC_PLAN_INVALID";
    public static final String STOP_TOO_WIDE = "V2329_STRUCTURAL_STOP_TOO_WIDE";
    public static final String REWARD_RISK_INSUFFICIENT = "V2329_REWARD_RISK_INSUFFICIENT";
    public static final String RISK_BUDGET_TOO_SMALL = "V2329_RISK_BUDGET_TOO_SMALL";

    public static final double ESTIMATED_ROUND_TRIP_COST_PER_ETH = 1.43;
    public static final double DEFAULT_RISK_BUDGET_USDT = 10.00;
    public static final double DEFAULT_PRICE_TICK = 0.01;
    public static final int MAX_QUANTITY = 7;

    private DynamicTradePlan() {}

    public static double updateAdverseExcursion60(String side, double entry,
                                                   double observedBid, double observedAsk,
                                                   double currentMaximum) {
        double observed = "LONG".equals(side) ? entry - observedBid
                : "SHORT".equals(side) ? observedAsk - entry : 0.0;
        if (!finite(observed)) return Math.max(0.0, currentMaximum);
        return Math.max(Math.max(0.0, currentMaximum), Math.max(0.0, observed));
    }

    public static Result calculate(String side, double entry, double avgRange20,
                                   double e60, double recentHigh, double recentLow,
                                   int qualityCap) {
        return calculate(side, entry, avgRange20, e60, recentHigh, recentLow,
                qualityCap, ESTIMATED_ROUND_TRIP_COST_PER_ETH,
                DEFAULT_RISK_BUDGET_USDT, DEFAULT_PRICE_TICK);
    }

    public static Result calculate(String side, double entry, double avgRange20,
                                   double e60, double recentHigh, double recentLow,
                                   int qualityCap, double estimatedCostPerEth,
                                   double riskBudgetUsdt, double priceTick) {
        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (direction == 0 || !positive(entry) || !finite(avgRange20) || !finite(e60)
                || !positive(recentHigh) || !positive(recentLow)
                || qualityCap < 1 || !positive(estimatedCostPerEth)
                || !positive(riskBudgetUsdt) || !positive(priceTick)) {
            return Result.rejected(INVALID_DATA);
        }

        double a = Math.max(0.35, avgRange20);
        double adverseExcursion60 = Math.max(0.0, e60);
        double structuralRoom = direction > 0
                ? Math.max(0.0, recentHigh - entry)
                : Math.max(0.0, entry - recentLow);

        double stopRequired = Math.max(0.55,
                Math.max(0.70 * a, adverseExcursion60 + 0.20 * a));
        double stopMaximum = Math.min(2.50, 2.00 * a);
        double targetFloor = Math.max(2.80, 1.95 * estimatedCostPerEth);
        double targetRaw = 2.50 * a + 0.25 * structuralRoom;
        double targetDistance = clamp(targetRaw, targetFloor, 5.50);
        double rewardRisk = targetDistance / stopRequired;
        double riskPerEth = stopRequired + estimatedCostPerEth;
        int riskQuantity = (int) Math.floor(riskBudgetUsdt / riskPerEth);
        int boundedQualityCap = Math.min(MAX_QUANTITY, qualityCap);
        int finalQuantity = Math.min(Math.min(riskQuantity, boundedQualityCap), MAX_QUANTITY);

        double unroundedStop = direction > 0 ? entry - stopRequired : entry + stopRequired;
        double unroundedTarget = direction > 0 ? entry + targetDistance : entry - targetDistance;
        double stopLoss = direction > 0
                ? floorToTick(unroundedStop, priceTick) : ceilToTick(unroundedStop, priceTick);
        double takeProfit = direction > 0
                ? floorToTick(unroundedTarget, priceTick) : ceilToTick(unroundedTarget, priceTick);
        double roundedStopDistance = direction > 0 ? entry - stopLoss : stopLoss - entry;
        double roundedTargetDistance = direction > 0 ? takeProfit - entry : entry - takeProfit;

        Result calculated = new Result(false, CONFIRMED, a, adverseExcursion60,
                structuralRoom, stopRequired, stopMaximum, targetFloor, targetRaw,
                targetDistance, stopLoss, takeProfit, roundedStopDistance,
                roundedTargetDistance, rewardRisk, estimatedCostPerEth, riskBudgetUsdt,
                riskPerEth, riskQuantity, boundedQualityCap, finalQuantity,
                finalQuantity * riskPerEth, priceTick);

        if (stopRequired - stopMaximum > 1e-9) return calculated.withRejection(STOP_TOO_WIDE);
        if (!positive(stopLoss) || !positive(takeProfit)
                || !positive(roundedStopDistance) || !positive(roundedTargetDistance)
                || (direction > 0 && !(takeProfit > entry && stopLoss < entry))
                || (direction < 0 && !(takeProfit < entry && stopLoss > entry))) {
            return calculated.withRejection(INVALID_DATA);
        }
        if (!finite(rewardRisk) || rewardRisk < 1.40) {
            return calculated.withRejection(REWARD_RISK_INSUFFICIENT);
        }
        if (riskQuantity < 1 || finalQuantity < 1) {
            return calculated.withRejection(RISK_BUDGET_TOO_SMALL);
        }
        return calculated.withValidity();
    }

    private static double floorToTick(double value, double tick) {
        return Math.floor((value + 1e-9) / tick) * tick;
    }

    private static double ceilToTick(double value, double tick) {
        return Math.ceil((value - 1e-9) / tick) * tick;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static boolean positive(double value) {
        return finite(value) && value > 0.0;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    public static final class Result {
        public final boolean valid;
        public final String reasonCode;
        public final double a;
        public final double adverseExcursion60;
        public final double structuralRoom;
        public final double stopRequired;
        public final double stopMaximum;
        public final double targetFloor;
        public final double targetRaw;
        public final double targetDistance;
        public final double stopLoss;
        public final double takeProfit;
        public final double roundedStopDistance;
        public final double roundedTargetDistance;
        public final double grossRewardRisk;
        public final double estimatedRoundTripCostPerEth;
        public final double riskBudgetUsdt;
        public final double riskPerEth;
        public final int riskQuantity;
        public final int qualityCap;
        public final int finalQuantity;
        public final double theoreticalMaximumLoss;
        public final double priceTick;

        private Result(boolean valid, String reasonCode, double a,
                       double adverseExcursion60, double structuralRoom,
                       double stopRequired, double stopMaximum, double targetFloor,
                       double targetRaw, double targetDistance, double stopLoss,
                       double takeProfit, double roundedStopDistance,
                       double roundedTargetDistance, double grossRewardRisk,
                       double estimatedRoundTripCostPerEth, double riskBudgetUsdt,
                       double riskPerEth, int riskQuantity, int qualityCap,
                       int finalQuantity, double theoreticalMaximumLoss,
                       double priceTick) {
            this.valid = valid;
            this.reasonCode = reasonCode;
            this.a = a;
            this.adverseExcursion60 = adverseExcursion60;
            this.structuralRoom = structuralRoom;
            this.stopRequired = stopRequired;
            this.stopMaximum = stopMaximum;
            this.targetFloor = targetFloor;
            this.targetRaw = targetRaw;
            this.targetDistance = targetDistance;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.roundedStopDistance = roundedStopDistance;
            this.roundedTargetDistance = roundedTargetDistance;
            this.grossRewardRisk = grossRewardRisk;
            this.estimatedRoundTripCostPerEth = estimatedRoundTripCostPerEth;
            this.riskBudgetUsdt = riskBudgetUsdt;
            this.riskPerEth = riskPerEth;
            this.riskQuantity = riskQuantity;
            this.qualityCap = qualityCap;
            this.finalQuantity = finalQuantity;
            this.theoreticalMaximumLoss = theoreticalMaximumLoss;
            this.priceTick = priceTick;
        }

        private static Result rejected(String code) {
            return new Result(false, code, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, Double.NaN,
                    Double.NaN);
        }

        private Result withRejection(String code) {
            return copy(false, code);
        }

        private Result withValidity() {
            return copy(true, CONFIRMED);
        }

        private Result copy(boolean newValid, String code) {
            return new Result(newValid, code, a, adverseExcursion60, structuralRoom,
                    stopRequired, stopMaximum, targetFloor, targetRaw, targetDistance,
                    stopLoss, takeProfit, roundedStopDistance, roundedTargetDistance,
                    grossRewardRisk, estimatedRoundTripCostPerEth, riskBudgetUsdt,
                    riskPerEth, riskQuantity, qualityCap, finalQuantity,
                    theoreticalMaximumLoss, priceTick);
        }
    }
}
