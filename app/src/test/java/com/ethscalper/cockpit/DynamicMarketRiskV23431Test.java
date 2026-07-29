package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DynamicMarketRiskV23431Test {
    private static final long NOW = 2_000_000L;

    private static MarketRuntime.MarketBar bar(long at, double open, double high,
                                                double low, double close) {
        return new MarketRuntime.MarketBar(at, open, high, low, close, 10.0);
    }

    private static List<MarketRuntime.MarketBar> longPivot() {
        List<MarketRuntime.MarketBar> values = new ArrayList<>();
        values.add(bar(NOW - 240_000L, 99.5, 100.2, 99.1, 99.7));
        values.add(bar(NOW - 180_000L, 99.3, 99.8, 98.6, 98.8));
        values.add(bar(NOW - 120_000L, 98.9, 100.0, 98.8, 99.6));
        return values;
    }

    private static List<MarketRuntime.MarketBar> shortPivot() {
        List<MarketRuntime.MarketBar> values = new ArrayList<>();
        values.add(bar(NOW - 240_000L, 100.5, 100.9, 99.8, 100.3));
        values.add(bar(NOW - 180_000L, 100.7, 101.4, 100.2, 101.2));
        values.add(bar(NOW - 120_000L, 101.1, 101.2, 100.0, 100.4));
        return values;
    }

    private static AdaptiveRiskSizing.Evidence evidence() {
        return new AdaptiveRiskSizing.Evidence(true, true, true, true, false,
                true, true, true, true, 7);
    }

    private static DynamicTradePlan.Result plan(MarketProfile profile, String side,
                                                 double entry, double averageRange,
                                                 double adverse, double recentHigh,
                                                 double recentLow,
                                                 StructuralStopPlanner.Result stop,
                                                 int qualityLevel) {
        return DynamicTradePlan.calculateStructural(profile, side, entry, averageRange,
                adverse, recentHigh, recentLow, qualityLevel, stop, evidence());
    }

    private static MarketProfile customProfile(String symbol, int step, int minimum,
                                                int maximum) {
        return MarketProfile.builder(symbol, symbol.replace("USDT", ""), "TEST")
                .referencePrice(100).priceTick(.01).quantity(step, minimum, maximum)
                .researchCandidate(true).adaptivePriceScale(false)
                .qualityLevelCapsQuantity(false)
                .detection(.01, .02, .03).stops(.01, 50).targets(.10, 20)
                .p02Seed(.10, .05).revalidation(.01, .02).lateDistances(.03, .02)
                .costs(.10, .20).riskBudgets(10, 14.55)
                .qualityBudgets(10, 11, 12, 13, 14.55).staleReasonCode("STALE")
                .build();
    }

    @Test public void longStopIsBelowRealStructure() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(
                MarketProfile.eth(), "LONG", 100, 1, 0,
                102, 98.8, 99.90, 99.92, longPivot(), NOW);
        assertTrue(stop.valid);
        assertEquals(98.6, stop.structuralAnchor, 0.0);
        assertEquals(1.55, stop.requiredStop, 1e-12);
    }

    @Test public void shortStopIsAboveRealStructure() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(
                MarketProfile.eth(), "SHORT", 100, 1, 0,
                101.2, 98, 100.08, 100.10, shortPivot(), NOW);
        assertTrue(stop.valid);
        assertEquals(101.4, stop.structuralAnchor, 0.0);
        assertEquals(1.55, stop.requiredStop, 1e-12);
    }

    @Test public void volatilityCanDominateWithoutAbsoluteFloor() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(
                MarketProfile.eth(), "LONG", 100, .20, 0,
                Double.NaN, Double.NaN, 99.98, 100.00,
                Collections.emptyList(), NOW);
        assertEquals(.20, stop.requiredStop, 1e-12);
        assertEquals("VOLATILITY", stop.calculationType);
    }

    @Test public void structureCanDominate() {
        assertEquals("STRUCTURE", StructuralStopPlanner.calculate(MarketProfile.eth(),
                "LONG", 100, 1, 0, 102, 98.8, 99.9, 99.92,
                longPivot(), NOW).calculationType);
    }

    @Test public void adverseExcursionCanDominate() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(
                MarketProfile.eth(), "LONG", 100, 1, 2,
                Double.NaN, Double.NaN, 99.98, 100.00,
                Collections.emptyList(), NOW);
        assertEquals(2.2, stop.requiredStop, 1e-12);
        assertEquals("ADVERSE_EXCURSION", stop.calculationType);
    }

    @Test public void spreadAndTickAreIncludedInTechnicalProtection() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(
                MarketProfile.eth(), "LONG", 100, .10, 0,
                Double.NaN, Double.NaN, 99.70, 100.00,
                Collections.emptyList(), NOW);
        assertEquals(.31, stop.structuralBuffer, 1e-12);
        assertEquals(.31, stop.adverseExcursionProtectionDistance, 1e-12);
        assertEquals(.31, stop.requiredStop, 1e-12);
    }

    @Test public void futureCandleIsNeverUsed() {
        List<MarketRuntime.MarketBar> bars = longPivot();
        bars.add(bar(NOW, 100, 101, 90, 100));
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(
                MarketProfile.eth(), "LONG", 100, 1, 0,
                Double.NaN, Double.NaN, 99.9, 99.92, bars, NOW);
        assertEquals(98.6, stop.structuralAnchor, 0.0);
    }

    @Test public void stopIsNeverTightenedForQuantity() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(
                MarketProfile.eth(), "LONG", 100, 1, 2.3,
                Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(MarketProfile.eth(), "LONG", 100, 1,
                2.3, 110, 99, stop, 7);
        assertEquals(stop.requiredStop, plan.roundedStopDistance, .011);
        assertEquals((int) Math.floor(14.55 / plan.roundedStopDistance),
                plan.riskQuantity);
    }

    @Test public void minimumQuantityAboveGrossBudgetIsRejectedPrecisely() {
        MarketProfile profile = customProfile("X1USDT", 1, 2, 100);
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(profile,
                "LONG", 100, 8, 0, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(profile, "LONG", 100, 8, 0,
                120, 90, stop, 7);
        assertFalse(plan.valid);
        assertEquals(DynamicTradePlan.GROSS_RISK_BUDGET_EXCEEDED, plan.reasonCode);
    }

    @Test public void grossLossMayEqualFourteenFiftyFiveExactly() {
        MarketProfile profile = customProfile("X2USDT", 1, 1, 100);
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(profile,
                "LONG", 100, 4, 4.05, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(profile, "LONG", 100, 4, 4.05,
                110, 90, stop, 7);
        assertTrue(plan.valid);
        assertEquals(4.85, plan.roundedStopDistance, 1e-9);
        assertEquals(3, plan.finalQuantity);
        assertEquals(14.55, plan.grossLossAtSl, 1e-9);
    }

    @Test public void feesAreSeparateFromGrossBudget() {
        MarketProfile profile = customProfile("X3USDT", 1, 1, 100);
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(profile,
                "LONG", 100, 1, 0, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(profile, "LONG", 100, 1, 0,
                110, 90, stop, 7);
        assertEquals(plan.finalQuantity * plan.roundedStopDistance,
                plan.grossLossAtSl, 1e-12);
        assertEquals(plan.finalQuantity * profile.resultRoundTripCostReference,
                plan.estimatedRoundTripFees, 1e-12);
        assertEquals(plan.grossLossAtSl + plan.estimatedRoundTripFees,
                plan.estimatedTotalLossAtSl, 1e-12);
        assertTrue(plan.estimatedTotalLossAtSl > plan.grossLossAtSl);
    }

    @Test public void ethUsesEthProfileQualityCap() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(MarketProfile.eth(),
                "LONG", 100, 1, 0, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(MarketProfile.eth(), "LONG", 100, 1, 0,
                110, 90, stop, 4);
        assertEquals(4, plan.qualityCap);
        assertTrue(plan.finalQuantity <= 4);
    }

    @Test public void solUsesItsOwnProfileAndDoesNotCopyEthDistance() {
        StructuralStopPlanner.Result eth = StructuralStopPlanner.calculate(MarketProfile.eth(),
                "LONG", 100, 1, 0, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        StructuralStopPlanner.Result sol = StructuralStopPlanner.calculate(MarketProfile.sol(),
                "LONG", 75.8, .04, 0, Double.NaN, Double.NaN, 75.78, 75.80,
                Collections.emptyList(), NOW);
        assertEquals(1.0, eth.requiredStop, 1e-12);
        assertEquals(.04, sol.requiredStop, 1e-12);
        assertEquals(120, MarketProfile.sol().quantityCapForQuality(7));
    }

    @Test public void quantityStepIsAppliedAfterGrossRiskQuantity() {
        MarketProfile profile = customProfile("X5USDT", 5, 5, 100);
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(profile,
                "LONG", 100, .5, 0, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(profile, "LONG", 100, .5, 0,
                110, 90, stop, 7);
        assertEquals(0, plan.finalQuantity % 5);
        assertTrue(plan.grossLossAtSl <= 14.55 + 1e-9);
    }

    @Test public void currentDynamicTargetFormulaIsPreserved() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(MarketProfile.eth(),
                "LONG", 100, 1, 0, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(MarketProfile.eth(), "LONG", 100, 1, 0,
                105, 95, stop, 7);
        assertEquals(3.7, plan.targetRaw, 1e-12);
    }

    @Test public void insufficientRewardRiskRejectsWithoutMovingStop() {
        StructuralStopPlanner.Result stop = StructuralStopPlanner.calculate(MarketProfile.eth(),
                "LONG", 100, 1, 3.8, Double.NaN, Double.NaN, 99.9, 99.92,
                Collections.emptyList(), NOW);
        DynamicTradePlan.Result plan = plan(MarketProfile.eth(), "LONG", 100, 1, 3.8,
                101, 99, stop, 7);
        assertFalse(plan.valid);
        assertEquals(DynamicTradePlan.REWARD_RISK_INSUFFICIENT, plan.reasonCode);
        assertEquals(stop.requiredStop, plan.stopRequired, 1e-12);
    }

    @Test public void planMetricsExposeGrossFeesAndTotalSeparately() {
        PlanMetricsCalculator.Result metrics = PlanMetricsCalculator.calculate("LONG", 3,
                100, 104, 98, 101, 100, 101, 1.43, 2.35, 14.55, 5);
        assertEquals(6.0, metrics.grossLoss, 1e-12);
        assertEquals(4.29, metrics.estimatedFees, 1e-12);
        assertEquals(10.29, metrics.netLoss, 1e-12);
        assertEquals(6.0, metrics.theoreticalMaximumLoss, 1e-12);
        assertEquals(300.0, metrics.notional, 1e-12);
    }

    @Test public void ethAndSolPlansRemainIndependent() {
        MarketRuntime eth = new MarketRuntime(MarketProfile.eth());
        MarketRuntime sol = new MarketRuntime(MarketProfile.sol());
        eth.last = 100;
        sol.last = 75.8;
        assertNotEquals(eth.last, sol.last, 0.0);
        assertNotSame(eth.observedSignals, sol.observedSignals);
    }

    @Test public void lifecycleStillEndsOnlyAtTpOrSl() {
        assertTrue(SignalSafetyPolicies.isTerminalStatus("TP_TOUCHED"));
        assertTrue(SignalSafetyPolicies.isTerminalStatus("SL_TOUCHED"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT"));
    }

    @Test public void historicalReplayIsAbsentFromActiveWorkflow() throws Exception {
        String workflow = new String(Files.readAllBytes(
                Path.of("../.github/workflows/nmc-ci.yml")), StandardCharsets.UTF_8);
        assertFalse(workflow.contains("validate_eth_v2331_replay"));
        assertFalse(Files.exists(Path.of("../tools/validate_eth_v2331_replay.py")));
        assertFalse(Files.exists(Path.of("../tools/fixtures/eth_v2331_validated_plans.csv")));
    }
}
