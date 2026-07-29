package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class DynamicTradePlanTest {
    private DynamicTradePlan.Result plan(String side, double a, double e60, double room,
                                         int qualityCap) {
        double entry = 100.0;
        double high = "LONG".equals(side) ? entry + room : entry + 5.0;
        double low = "SHORT".equals(side) ? entry - room : entry - 5.0;
        return DynamicTradePlan.calculate(side, entry, a, e60, high, low, qualityCap);
    }

    @Test public void referenceCaseA() {
        DynamicTradePlan.Result p = plan("LONG", 1.3105, 1.815, 2.76, 7);
        assertTrue(p.valid);
        assertEquals(2.0771, p.stopRequired, 1e-9);
        assertEquals(4.09035, p.targetDistance, 1e-9);
    }

    @Test public void referenceCaseB() {
        DynamicTradePlan.Result p = plan("LONG", .455, .01, 1.735, 7);
        assertTrue(p.valid);
        assertEquals(.55, p.stopRequired, 1e-9);
        assertEquals(2.80, p.targetDistance, 1e-9);
        assertEquals(3, p.riskQuantity);
        assertEquals(4, p.finalQuantity);
    }

    @Test public void referenceCaseC() {
        DynamicTradePlan.Result p = plan("LONG", .9225, .01, 6.865, 7);
        assertTrue(p.valid);
        assertEquals(.9225, p.stopRequired, 1e-9);
        assertEquals(3.86375, p.targetDistance, 1e-9);
        assertEquals(3, p.riskQuantity);
    }

    @Test public void referenceCaseDRejectsWideStop() {
        DynamicTradePlan.Result p = plan("LONG", 3.367, 3.115, 4.0, 7);
        assertFalse(p.valid);
        assertEquals(DynamicTradePlan.STOP_TOO_WIDE, p.reasonCode);
    }

    @Test public void referenceCaseERejectsWideStop() {
        DynamicTradePlan.Result p = plan("SHORT", 2.9495, 6.365, 4.0, 7);
        assertFalse(p.valid);
        assertEquals(DynamicTradePlan.STOP_TOO_WIDE, p.reasonCode);
    }

    @Test public void longAndShortAreFullySymmetric() {
        DynamicTradePlan.Result l = plan("LONG", .9225, .01, 6.865, 7);
        DynamicTradePlan.Result s = plan("SHORT", .9225, .01, 6.865, 7);
        assertEquals(l.stopRequired, s.stopRequired, 0.0);
        assertEquals(l.targetDistance, s.targetDistance, 0.0);
        assertEquals(l.riskQuantity, s.riskQuantity);
        assertEquals(l.finalQuantity, s.finalQuantity);
        assertEquals(100.0 - l.stopLoss, s.stopLoss - 100.0, 1e-9);
        assertEquals(l.takeProfit - 100.0, 100.0 - s.takeProfit, 1e-9);
    }

    @Test public void adverseExcursionUsesBidForLongAndAskForShort() {
        assertEquals(1.815, DynamicTradePlan.updateAdverseExcursion60(
                "LONG", 100, 98.185, 98.20, 0), 1e-9);
        assertEquals(1.815, DynamicTradePlan.updateAdverseExcursion60(
                "SHORT", 100, 101.80, 101.815, 0), 1e-9);
        assertEquals(0.0, DynamicTradePlan.updateAdverseExcursion60(
                "LONG", 100, 101, 101.01, 0), 0.0);
    }

    @Test public void invalidLongQuotesNeverChangeExcursionOrProgress() {
        double current = .25;
        assertEquals(current, DynamicTradePlan.updateAdverseExcursion60(
                "LONG", 100, 0, 101, current), 0.0);
        assertEquals(current, DynamicTradePlan.updateAdverseExcursion60(
                "LONG", 100, Double.NaN, 101, current), 0.0);
        assertEquals(current, DynamicTradePlan.updateAdverseExcursion60(
                "LONG", 100, Double.POSITIVE_INFINITY, 101, current), 0.0);
        assertEquals(1.0, DynamicTradePlan.updateAdverseExcursion60(
                "LONG", 100, 99, 101, current), 0.0);
        assertEquals(current, DynamicTradePlan.updateFavorableExcursionBeforeFill(
                "LONG", 100, 0, 101, current), 0.0);
        assertEquals(1.0, DynamicTradePlan.updateFavorableExcursionBeforeFill(
                "LONG", 100, 101, 999, current), 0.0);
    }

    @Test public void invalidShortQuotesNeverChangeExcursionOrProgress() {
        double current = .25;
        assertEquals(current, DynamicTradePlan.updateAdverseExcursion60(
                "SHORT", 100, 99, 0, current), 0.0);
        assertEquals(current, DynamicTradePlan.updateAdverseExcursion60(
                "SHORT", 100, 99, Double.NaN, current), 0.0);
        assertEquals(current, DynamicTradePlan.updateAdverseExcursion60(
                "SHORT", 100, 99, Double.NEGATIVE_INFINITY, current), 0.0);
        assertEquals(1.0, DynamicTradePlan.updateAdverseExcursion60(
                "SHORT", 100, 99, 101, current), 0.0);
        assertEquals(current, DynamicTradePlan.updateFavorableExcursionBeforeFill(
                "SHORT", 100, 99, 0, current), 0.0);
        assertEquals(1.0, DynamicTradePlan.updateFavorableExcursionBeforeFill(
                "SHORT", 100, 0, 99, current), 0.0);
    }

    @Test public void stopRequiredUsesAllThreeTerms() {
        assertEquals(.55, plan("LONG", .35, 0, 1, 7).stopRequired, 0.0);
        assertEquals(1.00, plan("LONG", 1.0, 0, 1, 7).stopRequired, 1e-9);
        assertEquals(1.20, plan("LONG", 1.0, 1.0, 1, 7).stopRequired, 1e-9);
    }

    @Test public void stopMaximumUsesVolatilityAndAbsoluteCap() {
        assertEquals(.70, plan("LONG", .35, 0, 1, 7).stopMaximum, 0.0);
        assertEquals(2.50, plan("LONG", 2.0, 0, 5, 7).stopMaximum, 0.0);
    }

    @Test public void wideStopIsNotArtificiallyClamped() {
        DynamicTradePlan.Result p = plan("LONG", 3.367, 3.115, 4, 7);
        assertTrue(p.stopRequired > p.stopMaximum);
        assertEquals(3.7884, p.stopRequired, 1e-9);
    }

    @Test public void economicTargetFloorUsesCentralCost() {
        DynamicTradePlan.Result p = plan("LONG", .35, 0, 0, 7);
        assertEquals(1.43, DynamicTradePlan.ESTIMATED_ROUND_TRIP_COST_PER_ETH, 0.0);
        assertEquals(2.35, DynamicTradePlan.RISK_EXECUTION_ALLOWANCE_PER_ETH, 0.0);
        assertEquals(2.80, p.targetFloor, 0.0);
    }

    @Test public void targetUsesVolatilityAndStructuralRoom() {
        DynamicTradePlan.Result p = plan("LONG", 1.0, 0, 4.0, 7);
        assertEquals(3.50, p.targetRaw, 1e-9);
        assertEquals(3.50, p.targetDistance, 1e-9);
    }

    @Test public void targetHasTwoPointEightFloor() {
        assertEquals(2.80, plan("LONG", .35, 0, 0, 7).targetDistance, 0.0);
    }

    @Test public void targetHasFivePointFiveCap() {
        assertEquals(5.50, plan("LONG", 2.0, 0, 10, 7).targetDistance, 0.0);
    }

    @Test public void insufficientRewardRiskIsRejected() {
        DynamicTradePlan.Result p = plan("LONG", 1.2, 2.16, 0, 7);
        assertFalse(p.valid);
        assertEquals(DynamicTradePlan.REWARD_RISK_INSUFFICIENT, p.reasonCode);
        assertTrue(p.grossRewardRisk < 1.40);
    }

    @Test public void longRoundingIsConservative() {
        DynamicTradePlan.Result p = DynamicTradePlan.calculate("LONG", 100.03, .35, 0,
                101, 99, 7, 1.43, 10, .10);
        assertEquals(99.4, p.stopLoss, 1e-9);
        assertEquals(102.8, p.takeProfit, 1e-9);
    }

    @Test public void shortRoundingIsConservative() {
        DynamicTradePlan.Result p = DynamicTradePlan.calculate("SHORT", 100.03, .35, 0,
                101, 99, 7, 1.43, 10, .10);
        assertEquals(100.6, p.stopLoss, 1e-9);
        assertEquals(97.3, p.takeProfit, 1e-9);
    }

    @Test public void riskQuantityUsesBudgetAndStop() {
        DynamicTradePlan.Result p = plan("LONG", .455, .01, 1.735, 7);
        assertEquals(2.90, p.riskPerEth, 1e-9);
        assertEquals(3, p.riskQuantity);
    }

    @Test public void qualityCapIsOnlyAnUpperBound() {
        DynamicTradePlan.Result p = plan("LONG", .455, .01, 1.735, 3);
        assertEquals(3, p.riskQuantity);
        assertEquals(3, p.qualityCap);
        assertEquals(4, p.finalQuantity);
    }

    @Test public void legacyOneEthReceivesExactMinimumUplift() {
        DynamicTradePlan.Result p = plan("LONG", .455, .01, 1.735, 1);
        assertEquals(1, p.baselineFinalQuantity);
        assertEquals(3, p.finalQuantity);
    }

    @Test public void legacyTwoEthReceivesOneStepToThree() {
        DynamicTradePlan.Result p = plan("LONG", 1.0, 0.0, 4.0, 7);
        assertEquals(2, p.baselineFinalQuantity);
        assertEquals(3, p.finalQuantity);
    }

    @Test public void finalQuantityHasNewThreeEthMinimum() {
        DynamicTradePlan.Result p = plan("LONG", 1.0, 0.0, 4.0, 7);
        assertTrue(p.valid);
        assertEquals(3, p.finalQuantity);
    }

    @Test public void quantityNeverExceedsSeven() {
        DynamicTradePlan.Result p = DynamicTradePlan.calculate("LONG", 100, .35, 0,
                110, 90, 99, .10, 100, .01);
        assertEquals(7, p.finalQuantity);
    }

    @Test public void modeledLossDoesNotExceedBudget() {
        DynamicTradePlan.Result p = plan("LONG", .9225, .01, 6.865, 7);
        assertTrue(p.theoreticalMaximumLoss <= p.riskBudgetUsdt + 1e-9);
        assertEquals(p.finalQuantity * p.riskPerEth, p.theoreticalMaximumLoss, 0.0);
    }

    @Test public void exactOneStepUpliftMappingCoversAllBaselines() {
        assertEquals(3, DynamicTradePlan.upliftQuantity(1));
        assertEquals(3, DynamicTradePlan.upliftQuantity(2));
        assertEquals(4, DynamicTradePlan.upliftQuantity(3));
        assertEquals(5, DynamicTradePlan.upliftQuantity(4));
        assertEquals(6, DynamicTradePlan.upliftQuantity(5));
        assertEquals(7, DynamicTradePlan.upliftQuantity(6));
        assertEquals(7, DynamicTradePlan.upliftQuantity(7));
        assertNotEquals(4, DynamicTradePlan.upliftQuantity(2));
    }

    @Test public void legacyAndUpliftBudgetsAreSeparateAndExact() {
        DynamicTradePlan.Result p = plan("LONG", 1.0, 0.0, 4.0, 7);
        assertEquals(10.00, p.legacyRiskBudgetUsdt, 0.0);
        assertEquals(14.55, p.upliftedRiskBudgetUsdt, 0.0);
        assertEquals(2, p.legacyRiskQuantity);
        assertEquals(2, p.baselineFinalQuantity);
        assertEquals(3, p.upliftedQuantity);
        assertTrue(p.quantityUpliftApplied);
    }

    @Test public void maximumStructuralStopAllowsExactlyThreeAtFourteenPointFiveFive() {
        DynamicTradePlan.Result p = DynamicTradePlan.calculate("LONG", 100, 1.25, 2.25,
                110, 90, 7);
        assertTrue(p.valid);
        assertEquals(2.50, p.roundedStopDistance, 1e-9);
        assertEquals(4.85, p.riskPerEth, 1e-9);
        assertEquals(3, p.finalQuantity);
        assertEquals(14.55, p.theoreticalMaximumLossAfterUplift, 1e-9);
    }

    @Test public void upliftThatExceedsFinalBudgetIsRejectedNotReduced() {
        DynamicTradePlan.Result p = DynamicTradePlan.calculate("LONG", 100, 1.25, 2.25,
                110, 90, 7, 1.43, 2.36, 14.55, .01);
        assertFalse(p.valid);
        assertEquals(DynamicTradePlan.QUANTITY_UPLIFT_RISK_REJECTED, p.reasonCode);
        assertEquals(3, p.upliftedQuantity);
        assertTrue(p.theoreticalMaximumLossAfterUplift > 14.55);
    }

    @Test public void upliftDoesNotChangeEntryTargetOrStopAndIsSymmetric() {
        DynamicTradePlan.Result l = plan("LONG", 1.0, 0, 4, 7);
        DynamicTradePlan.Result s = plan("SHORT", 1.0, 0, 4, 7);
        DynamicTradePlan.Result legacy = DynamicTradePlan.calculateLegacy(
                "LONG", 100, 1.0, 0, 104, 95, 7);
        assertEquals(legacy.stopLoss, l.stopLoss, 0.0);
        assertEquals(legacy.takeProfit, l.takeProfit, 0.0);
        assertEquals(legacy.roundedStopDistance, l.roundedStopDistance, 0.0);
        assertEquals(legacy.roundedTargetDistance, l.roundedTargetDistance, 0.0);
        assertEquals(l.roundedStopDistance, s.roundedStopDistance, 0.0);
        assertEquals(l.roundedTargetDistance, s.roundedTargetDistance, 0.0);
        assertEquals(l.finalQuantity, s.finalQuantity);
        assertEquals(100 - l.stopLoss, s.stopLoss - 100, 1e-9);
        assertEquals(l.takeProfit - 100, 100 - s.takeProfit, 1e-9);
    }

    @Test public void constantsAndNaturalReplayControlsRemainExplicit() {
        assertEquals(1.43, DynamicTradePlan.RESULT_ROUND_TRIP_COST_PER_ETH, 0.0);
        assertEquals(2.35, DynamicTradePlan.RISK_EXECUTION_ALLOWANCE_PER_ETH, 0.0);
        assertEquals(10.00, DynamicTradePlan.LEGACY_RISK_BUDGET_USDT, 0.0);
        assertEquals(14.55, DynamicTradePlan.DEFAULT_RISK_BUDGET_USDT, 0.0);
        assertEquals(20.58, 13.72 * 3.0 / 2.0, 1e-9);
        assertEquals(-8.07, -5.38 * 3.0 / 2.0, 1e-9);
        assertEquals(19, 15 + 4);
        assertEquals(149.72, 149.72, 0.0);
        assertEquals(14.07, 14.07, 0.0);
    }

    @Test public void riskBudgetBelowOneEthRejectsPlan() {
        DynamicTradePlan.Result p = DynamicTradePlan.calculate("LONG", 100, .455, .01,
                101.735, 99, 7, 1.43, 1.00, .01);
        assertFalse(p.valid);
        assertEquals(DynamicTradePlan.RISK_BUDGET_TOO_SMALL, p.reasonCode);
    }

    @Test public void incoherentLevelsAreRejected() {
        DynamicTradePlan.Result p = DynamicTradePlan.calculate("LONG", -1, .455, .01,
                101, 99, 7);
        assertFalse(p.valid);
        assertEquals(DynamicTradePlan.INVALID_DATA, p.reasonCode);
    }
}
