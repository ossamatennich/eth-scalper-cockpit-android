package com.ethscalper.cockpit;

/** Pure v2.33.2 structural SL, market TP and deterministic one-step quantity uplift. */
public final class DynamicTradePlan {
    public static final String CONFIRMED = "V2330_DYNAMIC_PLAN_CONFIRMED";
    public static final String INVALID_DATA = "V2330_DYNAMIC_PLAN_INVALID";
    public static final String STOP_TOO_WIDE = "V2330_STRUCTURAL_STOP_TOO_WIDE";
    public static final String REWARD_RISK_INSUFFICIENT = "V2330_REWARD_RISK_INSUFFICIENT";
    public static final String RISK_BUDGET_TOO_SMALL = "V2330_RISK_BUDGET_TOO_SMALL";
    public static final String QUANTITY_UPLIFT_APPLIED = "V2332_QUANTITY_UPLIFT_APPLIED";
    public static final String QUANTITY_UPLIFT_RISK_REJECTED =
            "V2332_QUANTITY_UPLIFT_RISK_REJECTED";

    public static final double RESULT_ROUND_TRIP_COST_PER_ETH = 1.43;
    public static final double RISK_EXECUTION_ALLOWANCE_PER_ETH = 2.35;
    /** Compatibility alias; result accounting uses 1.43, never the risk allowance. */
    public static final double ESTIMATED_ROUND_TRIP_COST_PER_ETH =
            RESULT_ROUND_TRIP_COST_PER_ETH;
    public static final double LEGACY_RISK_BUDGET_USDT = 10.00;
    public static final double DEFAULT_RISK_BUDGET_USDT = 14.55;
    public static final double DEFAULT_PRICE_TICK = 0.01;
    public static final int MAX_QUANTITY = 7;
    public static final String MARKET_QUANTITY_INVALID = "V2340_MARKET_QUANTITY_INVALID";

    private DynamicTradePlan() {}

    public static double updateAdverseExcursion60(String side, double entry,
                                                   double observedBid, double observedAsk,
                                                   double currentMaximum) {
        double baseline = finite(currentMaximum) ? Math.max(0.0, currentMaximum) : 0.0;
        if (!positive(entry)) return baseline;
        double observed;
        if ("LONG".equals(side)) {
            if (!positive(observedBid)) return baseline;
            observed = entry - observedBid;
        } else if ("SHORT".equals(side)) {
            if (!positive(observedAsk)) return baseline;
            observed = observedAsk - entry;
        } else {
            return baseline;
        }
        return Math.max(Math.max(0.0, currentMaximum), Math.max(0.0, observed));
    }

    public static double updateFavorableExcursionBeforeFill(
            String side, double entry, double observedBid, double observedAsk,
            double currentMaximum) {
        double baseline = finite(currentMaximum) ? Math.max(0.0, currentMaximum) : 0.0;
        if (!positive(entry)) return baseline;
        double observed;
        if ("LONG".equals(side)) {
            if (!positive(observedBid)) return baseline;
            observed = observedBid - entry;
        } else if ("SHORT".equals(side)) {
            if (!positive(observedAsk)) return baseline;
            observed = entry - observedAsk;
        } else {
            return baseline;
        }
        return Math.max(baseline, Math.max(0.0, observed));
    }

    public static Result calculate(String side, double entry, double avgRange20,
                                   double e60, double recentHigh, double recentLow,
                                   int qualityCap) {
        return calculate(side, entry, avgRange20, e60, recentHigh, recentLow,
                qualityCap, RESULT_ROUND_TRIP_COST_PER_ETH,
                RISK_EXECUTION_ALLOWANCE_PER_ETH, LEGACY_RISK_BUDGET_USDT,
                DEFAULT_RISK_BUDGET_USDT, DEFAULT_PRICE_TICK, true);
    }

    /** Profile-aware calculation. The ETH branch is intentionally the historical path. */
    public static Result calculate(MarketProfile profile, String side, double entry,
                                   double avgRange20, double e60, double recentHigh,
                                   double recentLow, int qualityLevel) {
        if (profile == null) return Result.rejected(INVALID_DATA);
        if (MarketProfile.ETH_SYMBOL.equals(profile.symbol)) {
            return calculate(side, entry, avgRange20, e60, recentHigh, recentLow, qualityLevel);
        }
        return calculateAdaptive(profile, side, entry, avgRange20, e60,
                recentHigh, recentLow, qualityLevel);
    }

    private static Result calculateAdaptive(MarketProfile profile, String side, double entry,
                                            double avgRange20, double e60,
                                            double recentHigh, double recentLow,
                                            int qualityLevel) {
        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (direction == 0 || !positive(entry) || !finite(avgRange20) || !finite(e60)
                || !positive(recentHigh) || !positive(recentLow)) {
            return Result.rejected(INVALID_DATA);
        }
        double aMin = profile.scaledMinimum(profile.aMinimumReference, entry);
        double a = Math.max(aMin, avgRange20);
        double adverse = Math.max(0.0, e60);
        double room = direction > 0 ? Math.max(0.0, recentHigh - entry)
                : Math.max(0.0, entry - recentLow);
        double stopMin = profile.scaledMinimum(profile.stopMinimumReference, entry);
        double stopCap = profile.scaledMaximum(profile.stopMaximumReference, entry, stopMin);
        double stopRequired = Math.max(stopMin, Math.max(a, adverse + .20 * a));
        double stopMaximum = Math.min(stopCap, 2.00 * a);
        double resultCost = profile.scaledMinimum(profile.resultRoundTripCostReference, entry);
        double allowance = profile.scaledMinimum(profile.riskExecutionAllowanceReference, entry);
        double scaledTargetFloor = profile.scaledMinimum(profile.targetFloorReference, entry);
        double scaledTargetCap = profile.scaledMaximum(profile.targetMaximumReference, entry,
                scaledTargetFloor);
        double targetFloor = Math.max(scaledTargetFloor, 1.95 * resultCost);
        double targetRaw = 2.70 * a + .20 * room;
        double targetDistance = clamp(targetRaw, targetFloor, scaledTargetCap);
        double rr = targetDistance / stopRequired;
        double stopLoss = direction > 0
                ? floorToTick(entry - stopRequired, profile.priceTick)
                : ceilToTick(entry + stopRequired, profile.priceTick);
        double takeProfit = direction > 0
                ? floorToTick(entry + targetDistance, profile.priceTick)
                : ceilToTick(entry - targetDistance, profile.priceTick);
        double roundedStop = direction > 0 ? entry - stopLoss : stopLoss - entry;
        double roundedTarget = direction > 0 ? takeProfit - entry : entry - takeProfit;
        double riskPerUnit = roundedStop + allowance;
        int level = Math.max(3, Math.min(7, qualityLevel));
        double qualityBudget = profile.qualityRiskBudget(level);
        int rawQuantity = (int) Math.floor((qualityBudget + 1e-12) / riskPerUnit);
        int steppedQuantity = (rawQuantity / profile.quantityStep) * profile.quantityStep;
        double loss = steppedQuantity * riskPerUnit;
        Result calculated = new Result(false, CONFIRMED, a, adverse, room, stopRequired,
                stopMaximum, targetFloor, targetRaw, targetDistance, stopLoss, takeProfit,
                roundedStop, roundedTarget, rr, resultCost, allowance, qualityBudget,
                riskPerUnit, rawQuantity, level, steppedQuantity, loss, profile.priceTick,
                profile.legacyRiskBudgetUsdt, rawQuantity, steppedQuantity, false,
                steppedQuantity, qualityBudget, rawQuantity, loss, loss);
        if (stopRequired - stopMaximum > 1e-9) return calculated.withRejection(STOP_TOO_WIDE);
        if (!positive(stopLoss) || !positive(takeProfit) || !positive(roundedStop)
                || !positive(roundedTarget) || !finite(rr)) {
            return calculated.withRejection(INVALID_DATA);
        }
        if (rr < 1.40) return calculated.withRejection(REWARD_RISK_INSUFFICIENT);
        if (rawQuantity < profile.minimumQuantity || steppedQuantity < profile.minimumQuantity
                || steppedQuantity > profile.maximumQuantity
                || loss > qualityBudget + 1e-9) {
            return calculated.withRejection(MARKET_QUANTITY_INVALID);
        }
        return calculated.withValidity(CONFIRMED);
    }

    /** Preserves the exact v2.33.1 sizing path for the unchanged P02 sleeve. */
    public static Result calculateLegacy(String side, double entry, double avgRange20,
                                         double e60, double recentHigh, double recentLow,
                                         int qualityCap) {
        return calculate(side, entry, avgRange20, e60, recentHigh, recentLow,
                qualityCap, RESULT_ROUND_TRIP_COST_PER_ETH,
                RISK_EXECUTION_ALLOWANCE_PER_ETH, LEGACY_RISK_BUDGET_USDT,
                LEGACY_RISK_BUDGET_USDT, DEFAULT_PRICE_TICK, false);
    }

    public static Result calculate(String side, double entry, double avgRange20,
                                   double e60, double recentHigh, double recentLow,
                                   int qualityCap, double estimatedCostPerEth,
                                   double riskBudgetUsdt, double priceTick) {
        return calculate(side, entry, avgRange20, e60, recentHigh, recentLow,
                qualityCap, estimatedCostPerEth, RISK_EXECUTION_ALLOWANCE_PER_ETH,
                riskBudgetUsdt, riskBudgetUsdt, priceTick, true);
    }

    public static Result calculate(String side, double entry, double avgRange20,
                                   double e60, double recentHigh, double recentLow,
                                   int qualityCap, double resultCostPerEth,
                                   double riskExecutionAllowancePerEth,
                                   double riskBudgetUsdt, double priceTick) {
        return calculate(side, entry, avgRange20, e60, recentHigh, recentLow,
                qualityCap, resultCostPerEth, riskExecutionAllowancePerEth,
                riskBudgetUsdt, riskBudgetUsdt, priceTick, true);
    }

    private static Result calculate(String side, double entry, double avgRange20,
                                    double e60, double recentHigh, double recentLow,
                                    int qualityCap, double resultCostPerEth,
                                    double riskExecutionAllowancePerEth,
                                    double legacyRiskBudgetUsdt,
                                    double upliftedRiskBudgetUsdt, double priceTick,
                                    boolean applyUplift) {
        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (direction == 0 || !positive(entry) || !finite(avgRange20) || !finite(e60)
                || !positive(recentHigh) || !positive(recentLow)
                || qualityCap < 1 || !positive(resultCostPerEth)
                || !positive(riskExecutionAllowancePerEth)
                || !positive(legacyRiskBudgetUsdt) || !positive(upliftedRiskBudgetUsdt)
                || !positive(priceTick)) {
            return Result.rejected(INVALID_DATA);
        }

        double a = Math.max(0.35, avgRange20);
        double adverseExcursion60 = Math.max(0.0, e60);
        double structuralRoom = direction > 0
                ? Math.max(0.0, recentHigh - entry)
                : Math.max(0.0, entry - recentLow);

        double stopRequired = Math.max(0.55,
                Math.max(1.00 * a, adverseExcursion60 + 0.20 * a));
        double stopMaximum = Math.min(2.50, 2.00 * a);
        double targetFloor = Math.max(2.80, 1.95 * resultCostPerEth);
        double targetRaw = 2.70 * a + 0.20 * structuralRoom;
        double targetDistance = clamp(targetRaw, targetFloor, 5.50);
        double rewardRisk = targetDistance / stopRequired;

        double unroundedStop = direction > 0 ? entry - stopRequired : entry + stopRequired;
        double unroundedTarget = direction > 0 ? entry + targetDistance : entry - targetDistance;
        double stopLoss = direction > 0
                ? floorToTick(unroundedStop, priceTick) : ceilToTick(unroundedStop, priceTick);
        double takeProfit = direction > 0
                ? floorToTick(unroundedTarget, priceTick) : ceilToTick(unroundedTarget, priceTick);
        double roundedStopDistance = direction > 0 ? entry - stopLoss : stopLoss - entry;
        double roundedTargetDistance = direction > 0 ? takeProfit - entry : entry - takeProfit;
        double riskPerEth = roundedStopDistance + riskExecutionAllowancePerEth;
        int legacyRiskQuantity = (int) Math.floor(
                (legacyRiskBudgetUsdt + 1e-12) / riskPerEth);
        int boundedQualityCap = Math.min(MAX_QUANTITY, qualityCap);
        int baselineFinalQuantity = Math.min(
                Math.min(legacyRiskQuantity, boundedQualityCap), MAX_QUANTITY);
        int upliftedQuantity = applyUplift
                ? upliftQuantity(baselineFinalQuantity)
                : baselineFinalQuantity;
        int upliftedRiskQuantity = (int) Math.floor(
                (upliftedRiskBudgetUsdt + 1e-12) / riskPerEth);
        double theoreticalMaximumLossBeforeUplift = baselineFinalQuantity * riskPerEth;
        double theoreticalMaximumLossAfterUplift = upliftedQuantity * riskPerEth;

        Result calculated = new Result(false, CONFIRMED, a, adverseExcursion60,
                structuralRoom, stopRequired, stopMaximum, targetFloor, targetRaw,
                targetDistance, stopLoss, takeProfit, roundedStopDistance,
                roundedTargetDistance, rewardRisk, resultCostPerEth,
                riskExecutionAllowancePerEth, upliftedRiskBudgetUsdt,
                riskPerEth, legacyRiskQuantity, boundedQualityCap, upliftedQuantity,
                theoreticalMaximumLossAfterUplift, priceTick,
                legacyRiskBudgetUsdt, legacyRiskQuantity, baselineFinalQuantity,
                applyUplift && upliftedQuantity != baselineFinalQuantity,
                upliftedQuantity, upliftedRiskBudgetUsdt, upliftedRiskQuantity,
                theoreticalMaximumLossBeforeUplift,
                theoreticalMaximumLossAfterUplift);

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
        if (legacyRiskQuantity < 1 || baselineFinalQuantity < 1) {
            return calculated.withRejection(RISK_BUDGET_TOO_SMALL);
        }
        if (upliftedQuantity > upliftedRiskQuantity
                || theoreticalMaximumLossAfterUplift > upliftedRiskBudgetUsdt + 1e-9) {
            return calculated.withRejection(applyUplift
                    ? QUANTITY_UPLIFT_RISK_REJECTED : INVALID_DATA);
        }
        return calculated.withValidity(applyUplift ? QUANTITY_UPLIFT_APPLIED : CONFIRMED);
    }

    private static double floorToTick(double value, double tick) {
        return Math.floor((value + 1e-9) / tick) * tick;
    }

    /** Exact v2.33.2 mapping applied after the untouched v2.33.1 baseline calculation. */
    public static int upliftQuantity(int baselineFinalQuantity) {
        return Math.min(MAX_QUANTITY, Math.max(3, baselineFinalQuantity + 1));
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
        public final double riskExecutionAllowancePerEth;
        public final double riskBudgetUsdt;
        public final double riskPerEth;
        public final int riskQuantity;
        public final int qualityCap;
        public final int finalQuantity;
        public final double theoreticalMaximumLoss;
        public final double priceTick;
        public final double legacyRiskBudgetUsdt;
        public final int legacyRiskQuantity;
        public final int baselineFinalQuantity;
        public final boolean quantityUpliftApplied;
        public final int upliftedQuantity;
        public final double upliftedRiskBudgetUsdt;
        public final int upliftedRiskQuantity;
        public final double theoreticalMaximumLossBeforeUplift;
        public final double theoreticalMaximumLossAfterUplift;
        /** Generic per-unit aliases; historical PerEth fields remain byte-compatible. */
        public final double resultCostPerUnit;
        public final double riskAllowancePerUnit;
        public final double qualityRiskBudget;
        public final double riskPerUnit;
        public final int rawQuantity;

        private Result(boolean valid, String reasonCode, double a,
                       double adverseExcursion60, double structuralRoom,
                       double stopRequired, double stopMaximum, double targetFloor,
                       double targetRaw, double targetDistance, double stopLoss,
                       double takeProfit, double roundedStopDistance,
                       double roundedTargetDistance, double grossRewardRisk,
                       double estimatedRoundTripCostPerEth,
                       double riskExecutionAllowancePerEth, double riskBudgetUsdt,
                       double riskPerEth, int riskQuantity, int qualityCap,
                       int finalQuantity, double theoreticalMaximumLoss,
                       double priceTick, double legacyRiskBudgetUsdt,
                       int legacyRiskQuantity, int baselineFinalQuantity,
                       boolean quantityUpliftApplied, int upliftedQuantity,
                       double upliftedRiskBudgetUsdt, int upliftedRiskQuantity,
                       double theoreticalMaximumLossBeforeUplift,
                       double theoreticalMaximumLossAfterUplift) {
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
            this.riskExecutionAllowancePerEth = riskExecutionAllowancePerEth;
            this.riskBudgetUsdt = riskBudgetUsdt;
            this.riskPerEth = riskPerEth;
            this.riskQuantity = riskQuantity;
            this.qualityCap = qualityCap;
            this.finalQuantity = finalQuantity;
            this.theoreticalMaximumLoss = theoreticalMaximumLoss;
            this.priceTick = priceTick;
            this.legacyRiskBudgetUsdt = legacyRiskBudgetUsdt;
            this.legacyRiskQuantity = legacyRiskQuantity;
            this.baselineFinalQuantity = baselineFinalQuantity;
            this.quantityUpliftApplied = quantityUpliftApplied;
            this.upliftedQuantity = upliftedQuantity;
            this.upliftedRiskBudgetUsdt = upliftedRiskBudgetUsdt;
            this.upliftedRiskQuantity = upliftedRiskQuantity;
            this.theoreticalMaximumLossBeforeUplift = theoreticalMaximumLossBeforeUplift;
            this.theoreticalMaximumLossAfterUplift = theoreticalMaximumLossAfterUplift;
            this.resultCostPerUnit = estimatedRoundTripCostPerEth;
            this.riskAllowancePerUnit = riskExecutionAllowancePerEth;
            this.qualityRiskBudget = riskBudgetUsdt;
            this.riskPerUnit = riskPerEth;
            this.rawQuantity = riskQuantity;
        }

        private static Result rejected(String code) {
            return new Result(false, code, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, Double.NaN,
                    Double.NaN, Double.NaN, 0, 0, false, 0, Double.NaN, 0,
                    Double.NaN, Double.NaN);
        }

        private Result withRejection(String code) {
            return copy(false, code);
        }

        private Result withValidity(String code) {
            return copy(true, code);
        }

        private Result copy(boolean newValid, String code) {
            return new Result(newValid, code, a, adverseExcursion60, structuralRoom,
                    stopRequired, stopMaximum, targetFloor, targetRaw, targetDistance,
                    stopLoss, takeProfit, roundedStopDistance, roundedTargetDistance,
                    grossRewardRisk, estimatedRoundTripCostPerEth,
                    riskExecutionAllowancePerEth, riskBudgetUsdt,
                    riskPerEth, riskQuantity, qualityCap, finalQuantity,
                    theoreticalMaximumLoss, priceTick, legacyRiskBudgetUsdt,
                    legacyRiskQuantity, baselineFinalQuantity, quantityUpliftApplied,
                    upliftedQuantity, upliftedRiskBudgetUsdt, upliftedRiskQuantity,
                    theoreticalMaximumLossBeforeUplift,
                    theoreticalMaximumLossAfterUplift);
        }
    }
}
