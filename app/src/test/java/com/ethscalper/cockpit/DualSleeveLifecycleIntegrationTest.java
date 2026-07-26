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
        assertEquals(fill.dynamicPlan.finalQuantity, fill.publishedSignal.quantity);
        assertEquals(fill.publishedSignal.quantity, payload.quantityForScreen());
        assertEquals(fill.publishedSignal.quantity, payload.quantityForNotification());
        assertEquals(fill.publishedSignal.quantity, payload.quantityForDiagnostic());
        assertTrue(fill.dynamicPlan.theoreticalMaximumLoss <= 10.0 + 1e-9);
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
