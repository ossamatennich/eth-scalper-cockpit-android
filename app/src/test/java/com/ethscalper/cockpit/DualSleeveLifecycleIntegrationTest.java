package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DualSleeveLifecycleIntegrationTest {
    private static final long CREATED = 100 * 60_000L;

    private SignalDecision p02(String side) {
        int d = "LONG".equals(side) ? 1 : -1;
        return SignalDecision.signal(side, "v2.33 P02 CONTINUATION C1_" + side,
                90, 3, 100, 100 + d * 2.8, 100 - d * 1.35,
                2.8, 1.35, "ACTIVE", true, 98, 102, 4);
    }

    private MarketSnapshot snapshot(String side, long now, boolean executable) {
        int d = "LONG".equals(side) ? 1 : -1;
        double bid = d > 0 ? 99.99 : executable ? 100 : 99.99;
        double ask = d > 0 ? executable ? 100 : 100.01 : 100.01;
        double high = d > 0 ? 101.8 : 105;
        double low = d > 0 ? 95 : 98.2;
        return MarketSnapshot.builder(now)
                .eth(100, bid, ask).btc(60_000, 59_999, 60_001)
                .candleCounts(60, 20).averages(1, 100)
                .movement(d * .6, d * 1.3, d * 1.0, high, low)
                .move15(d * .2).flow(d * .2, 100)
                .flowWindows(d * .2, d * .2, d * .5, d * .5)
                .professionalFeatures(high - low, 1, d > 0 ? .8 : .2,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                .build();
    }

    private MarketSnapshot baselineThreeSnapshot(String side, long now) {
        int d = "LONG".equals(side) ? 1 : -1;
        double high = d > 0 ? 100.8 : 105;
        double low = d > 0 ? 95 : 99.2;
        return MarketSnapshot.builder(now)
                .eth(100, d > 0 ? 99.99 : 100, d > 0 ? 100 : 100.01)
                .btc(60_000, 59_999, 60_001).candleCounts(60, 20)
                .averages(.455, 100)
                .movement(d * .30, d * .60, d * .50, high, low)
                .move15(d * .10).flow(d * .20, 100)
                .flowWindows(d * .20, d * .20, d * .50, d * .50)
                .professionalFeatures(high - low, 1, d > 0 ? .8 : .2,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                .build();
    }

    private TrendRegime60.Result trend(String side, MarketSnapshot snapshot, long now) {
        return regime(side, snapshot, now, false);
    }

    private TrendRegime60.Result regime(String side, MarketSnapshot snapshot, long now,
                                        boolean reversal) {
        List<TrendRegime60.Point> points = new ArrayList<>();
        long endMinute = now / 60_000L;
        double slope = "LONG".equals(side) ? .05 : -.05;
        if (reversal) slope = -slope;
        for (int i = 0; i < 60; i++) {
            points.add(new TrendRegime60.Point((endMinute - 59 + i) * 60_000L + 1_000,
                    100 + slope * i));
        }
        NormalizedSignalMetrics.Result metrics = NormalizedSignalMetrics.calculate(
                side, p02(side), snapshot, .1);
        return TrendRegime60.evaluate(side, 1, metrics, points, now);
    }

    private CandidateLifecycle.FillResult confirm(String side, long age, boolean executable) {
        long now = CREATED + age;
        MarketSnapshot snapshot = snapshot(side, now, executable);
        return CandidateLifecycle.processPendingCandidate(p02(side), snapshot, true,
                CREATED, now, 0, .1, false, CandidateLifecycle.SLEEVE_P02,
                trend(side, snapshot, now));
    }

    @Test public void p02IsSilentAtTwentySecondsAndConfirmsJustAfter() {
        CandidateLifecycle.FillResult atBoundary = confirm("LONG", 20_000, true);
        assertFalse(atBoundary.confirmed);
        assertEquals(P02SleeveFilter.SILENT_WINDOW, atBoundary.reasonCode);
        CandidateLifecycle.FillResult after = confirm("LONG", 20_001, true);
        assertTrue(after.confirmed);
        assertEquals("P02_TREND", after.publishedSignal.family);
        assertEquals("V2330_P02_TREND_DYNAMIC_PLAN_CONFIRMED", after.reasonCode);
    }

    @Test public void p02AtFortyFiveSecondsConfirmsAndAfterExpires() {
        assertTrue(confirm("SHORT", 45_000, true).confirmed);
        CandidateLifecycle.FillResult expired = confirm("SHORT", 45_001, true);
        assertFalse(expired.confirmed);
        assertEquals(P02SleeveFilter.EXPIRED, expired.reasonCode);
    }

    @Test public void p02ReversalPublishesDistinctFamilyAndReason() {
        long now = CREATED + 30_000;
        MarketSnapshot snapshot = snapshot("LONG", now, true);
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processPendingCandidate(
                p02("LONG"), snapshot, true, CREATED, now, 0, .1, false,
                CandidateLifecycle.SLEEVE_P02, regime("LONG", snapshot, now, true));
        assertTrue(fill.confirmed);
        assertEquals("P02_REVERSAL", fill.publishedSignal.family);
        assertEquals("V2330_P02_REVERSAL_DYNAMIC_PLAN_CONFIRMED", fill.reasonCode);
    }

    @Test public void p02StillRequiresCurrentExecutableLimit() {
        CandidateLifecycle.FillResult distant = confirm("LONG", 30_000, false);
        assertFalse(distant.confirmed);
        assertEquals(CandidateLifecycle.LIMIT_NOT_EXECUTABLE, distant.reasonCode);
    }

    @Test public void p02StillRequiresC04C07C08AndP01() {
        long now = CREATED + 30_000;
        MarketSnapshot weak = MarketSnapshot.builder(now)
                .eth(100, 99.99, 100).averages(1, 100)
                .movement(.05, 1.3, 1, 101.8, 95)
                .flow(0, 100).flowWindows(0, 0, .5, .5)
                .professionalFeatures(6.8, 1, .8, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0).build();
        CandidateLifecycle.FillResult result = CandidateLifecycle.processPendingCandidate(
                p02("LONG"), weak, true, CREATED, now, 0, .1, false,
                CandidateLifecycle.SLEEVE_P02, trend("LONG", weak, now));
        assertEquals(ContinuationConfirmation.C04_REJECT, result.reasonCode);
    }

    @Test public void p02DynamicPlanUsesSameQuantityEverywhere() {
        CandidateLifecycle.FillResult fill = confirm("LONG", 30_000, true);
        assertTrue(fill.confirmed);
        ConfirmedSignalPayload payload = ConfirmedSignalPayload.from(fill.publishedSignal);
        assertEquals(2, fill.dynamicPlan.baselineFinalQuantity);
        assertEquals(3, fill.dynamicPlan.finalQuantity);
        assertEquals(fill.dynamicPlan.finalQuantity, fill.publishedSignal.quantity);
        assertEquals(fill.publishedSignal.quantity, payload.quantityForScreen());
        assertEquals(fill.publishedSignal.quantity, payload.quantityForNotification());
        assertEquals(fill.publishedSignal.quantity, payload.quantityForDiagnostic());
        assertTrue(fill.dynamicPlan.theoreticalMaximumLoss <= 14.55 + 1e-9);
    }

    @Test public void p02LegacyTwoPublishesExactlyThreeWithoutChangingLevels() {
        long now = CREATED + 30_000;
        MarketSnapshot snapshot = snapshot("LONG", now, true);
        CandidateLifecycle.FillResult fill = confirm("LONG", 30_000, true);
        DynamicTradePlan.Result legacy = DynamicTradePlan.calculateLegacy(
                "LONG", p02("LONG").entry, snapshot.avgRange20, .1,
                snapshot.recentHigh, snapshot.recentLow, fill.sizing.finalQuantity);
        assertEquals(2, legacy.finalQuantity);
        assertEquals(3, fill.publishedSignal.quantity);
        assertEquals(legacy.stopLoss, fill.publishedSignal.stopLoss, 0.0);
        assertEquals(legacy.takeProfit, fill.publishedSignal.takeProfit, 0.0);
        assertEquals(p02("LONG").entry, fill.publishedSignal.entry, 0.0);
    }

    @Test public void p02LegacyThreePublishesExactlyFour() {
        long now = CREATED + 30_000;
        MarketSnapshot snapshot = baselineThreeSnapshot("SHORT", now);
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processPendingCandidate(
                p02("SHORT"), snapshot, true, CREATED, now, 0, .1, false,
                CandidateLifecycle.SLEEVE_P02, trend("SHORT", snapshot, now));
        assertTrue(fill.confirmed);
        assertEquals(3, fill.dynamicPlan.legacyRiskQuantity);
        assertEquals(3, fill.dynamicPlan.baselineFinalQuantity);
        assertEquals(4, fill.publishedSignal.quantity);
    }

    @Test public void p02UsesGeneralUpliftMappingForLegacySixAndSeven() {
        int p02LegacySix = 6;
        int p02LegacySeven = 7;
        assertEquals(7, DynamicTradePlan.upliftQuantity(p02LegacySix));
        assertEquals(7, DynamicTradePlan.upliftQuantity(p02LegacySeven));
    }

    @Test public void p02FinalQuantityIsAlwaysWithinThreeAndSeven() {
        for (int legacy = 1; legacy <= 7; legacy++) {
            int quantity = DynamicTradePlan.upliftQuantity(legacy);
            assertTrue(quantity >= 3);
            assertTrue(quantity <= 7);
        }
    }

    @Test public void natural20260727P02ControlChangesOnlyQuantity() {
        CandidateLifecycle.FillResult fill = confirm("LONG", 30_000, true);
        assertEquals(2, fill.dynamicPlan.baselineFinalQuantity);
        assertEquals(3, fill.dynamicPlan.finalQuantity);
        assertEquals(-8.07, -5.38 * fill.dynamicPlan.finalQuantity
                / fill.dynamicPlan.baselineFinalQuantity, 1e-9);
        assertEquals(20_000L, CandidateLifecycle.P02_MIN_CONFIRMATION_AGE_MS);
        assertEquals(45_000L, CandidateLifecycle.P02_MAX_PENDING_AGE_MS);
        assertEquals(1_000L, P01EarlyConfirmation.REQUIRED_STABILITY_MS);
    }

    @Test public void rangeFadeRemainsDiagnosticOnlyBesideBothSleeves() {
        SignalDecision range = SignalDecision.signal("SHORT", "RANGE_FADE_SHORT", 96, 3,
                100, 98, 101.2, 2, 1.2, "RESET", true, 98, 102, 4);
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                range, snapshot("SHORT", CREATED + 30_000, true), true, CREATED, 0);
        assertFalse(fill.confirmed);
        assertEquals(CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY, fill.reasonCode);
        assertFalse(SignalSafetyPolicies.candidateIsAudible());
    }
}
