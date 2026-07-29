package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class P02DiagnosticFixTest {
    private static final long MINUTE_MS = 60_000L;

    private boolean observeIfAllowed(P02SleeveFilter.SetupTracker tracker, String setup,
                                     boolean feedFresh, boolean activePlan,
                                     long now, long lastTerminalAt) {
        boolean rearmComplete = TerminalRearmPersistence.allowsNewCandidate(now, lastTerminalAt);
        return P02SleeveFilter.canObserveSetup(feedFresh, activePlan, rearmComplete)
                && tracker.observe(setup);
    }

    @Test public void staleFeedDoesNotConsumeSetupAppearance() {
        P02SleeveFilter.SetupTracker tracker = new P02SleeveFilter.SetupTracker();
        assertFalse(observeIfAllowed(tracker, "C1_LONG", false, false, 1_000, 0));
        assertEquals(P02SleeveFilter.NONE, tracker.previous());
        assertTrue(observeIfAllowed(tracker, "C1_LONG", true, false, 2_000, 0));
        assertFalse(observeIfAllowed(tracker, "C1_LONG", true, false, 3_000, 0));
    }

    @Test public void terminalRearmDoesNotConsumeSetupAndBoundaryIsInclusive() {
        long terminalAt = 1_000_000L;
        P02SleeveFilter.SetupTracker tracker = new P02SleeveFilter.SetupTracker();
        assertFalse(observeIfAllowed(tracker, "C2_SHORT", true, false,
                terminalAt + 179_999L, terminalAt));
        assertEquals(P02SleeveFilter.NONE, tracker.previous());
        assertTrue(observeIfAllowed(tracker, "C2_SHORT", true, false,
                terminalAt + 180_000L, terminalAt));
        assertFalse(observeIfAllowed(tracker, "C2_SHORT", true, false,
                terminalAt + 180_001L, terminalAt));
    }

    @Test public void activePlanDoesNotConsumeSetupAppearance() {
        P02SleeveFilter.SetupTracker tracker = new P02SleeveFilter.SetupTracker();
        assertFalse(observeIfAllowed(tracker, "C1_SHORT", true, true, 1_000, 0));
        assertEquals(P02SleeveFilter.NONE, tracker.previous());
        assertTrue(observeIfAllowed(tracker, "C1_SHORT", true, false, 2_000, 0));
    }

    @Test public void resetReturnsTrackerToNone() {
        P02SleeveFilter.SetupTracker tracker = new P02SleeveFilter.SetupTracker();
        assertTrue(tracker.observe("C1_LONG"));
        assertEquals("C1_LONG", tracker.previous());
        tracker.reset();
        assertEquals(P02SleeveFilter.NONE, tracker.previous());
        assertTrue(tracker.observe("C1_LONG"));
    }

    @Test public void sixtyPreloadedConsecutiveCandlesImmediatelyProvideOls60() {
        long firstMinute = 10_000L;
        long confirmationAt = (firstMinute + 59L) * MINUTE_MS + 50_000L;
        List<TrendRegime60.MinuteClose> candles = minuteCloses(firstMinute, 60, .05);
        List<TrendRegime60.Point> points = TrendRegime60.pointsFromMinuteCloses(
                candles, confirmationAt, 102.95);
        TrendRegime60.Result result = TrendRegime60.evaluate("LONG", 1.0,
                metrics("LONG"), points, confirmationAt);
        assertEquals(60, result.count);
        assertTrue(result.accepted);
        assertEquals(TrendRegime60.TREND, result.mode);
    }

    @Test public void missingMinuteKeepsInsufficientReason() {
        long firstMinute = 20_000L;
        long confirmationAt = (firstMinute + 59L) * MINUTE_MS + 50_000L;
        List<TrendRegime60.MinuteClose> candles = minuteCloses(firstMinute, 60, .05);
        candles.remove(24);
        TrendRegime60.Result result = TrendRegime60.evaluate("LONG", 1.0,
                metrics("LONG"), TrendRegime60.pointsFromMinuteCloses(
                        candles, confirmationAt, 102.95), confirmationAt);
        assertEquals(59, result.count);
        assertEquals(TrendRegime60.INSUFFICIENT, result.reasonCode);
    }

    @Test public void futureCandleIsIgnored() {
        long firstMinute = 30_000L;
        long confirmationAt = (firstMinute + 59L) * MINUTE_MS + 50_000L;
        List<TrendRegime60.MinuteClose> candles = minuteCloses(firstMinute, 60, .05);
        candles.add(new TrendRegime60.MinuteClose(confirmationAt + 1L, 10_000.0));
        TrendRegime60.Result result = TrendRegime60.evaluate("LONG", 1.0,
                metrics("LONG"), TrendRegime60.pointsFromMinuteCloses(
                        candles, confirmationAt, 102.95), confirmationAt);
        assertEquals(60, result.count);
        assertEquals(.05, result.slope, 1e-12);
        assertTrue(result.lastPointAt <= confirmationAt);
    }

    @Test public void zeroNanAndInfiniteClosesAreIgnored() {
        long firstMinute = 40_000L;
        long confirmationAt = (firstMinute + 59L) * MINUTE_MS + 50_000L;
        List<TrendRegime60.MinuteClose> candles = minuteCloses(firstMinute, 60, .05);
        candles.set(10, new TrendRegime60.MinuteClose((firstMinute + 10L) * MINUTE_MS, 0.0));
        candles.set(20, new TrendRegime60.MinuteClose((firstMinute + 20L) * MINUTE_MS, Double.NaN));
        candles.set(30, new TrendRegime60.MinuteClose((firstMinute + 30L) * MINUTE_MS,
                Double.POSITIVE_INFINITY));
        List<TrendRegime60.Point> points = TrendRegime60.pointsFromMinuteCloses(
                candles, confirmationAt, 102.95);
        TrendRegime60.Result result = TrendRegime60.evaluate("LONG", 1.0,
                metrics("LONG"), points, confirmationAt);
        assertEquals(57, points.size());
        assertEquals(57, result.count);
        assertEquals(TrendRegime60.INSUFFICIENT, result.reasonCode);
    }

    @Test public void preloadedOlsPathRemainsLongShortSymmetric() {
        long firstMinute = 50_000L;
        long confirmationAt = (firstMinute + 59L) * MINUTE_MS + 50_000L;
        TrendRegime60.Result longResult = TrendRegime60.evaluate("LONG", 1.0,
                metrics("LONG"), TrendRegime60.pointsFromMinuteCloses(
                        minuteCloses(firstMinute, 60, .05), confirmationAt, 102.95), confirmationAt);
        TrendRegime60.Result shortResult = TrendRegime60.evaluate("SHORT", 1.0,
                metrics("SHORT"), TrendRegime60.pointsFromMinuteCloses(
                        minuteCloses(firstMinute, 60, -.05), confirmationAt, 97.05), confirmationAt);
        assertEquals(TrendRegime60.TREND, longResult.mode);
        assertEquals(TrendRegime60.TREND, shortResult.mode);
        assertEquals(longResult.t60, shortResult.t60, 1e-9);
    }

    private List<TrendRegime60.MinuteClose> minuteCloses(long firstMinute, int count,
                                                         double slope) {
        List<TrendRegime60.MinuteClose> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candles.add(new TrendRegime60.MinuteClose((firstMinute + i) * MINUTE_MS,
                    100.0 + slope * i));
        }
        return candles;
    }

    private NormalizedSignalMetrics.Result metrics(String side) {
        int direction = "LONG".equals(side) ? 1 : -1;
        SignalDecision candidate = SignalDecision.signal(side, "P02 CONTINUATION", 90, 3,
                100, 100 + direction * 2.8, 100 - direction * 1.35,
                2.8, 1.35, "ACTIVE", true, 98, 102, 4);
        MarketSnapshot snapshot = MarketSnapshot.builder(1)
                .eth(100, 99.99, 100.01).averages(1, 100)
                .movement(direction * .6, direction * 1.3, direction, 102, 98)
                .flow(direction * .2, 100)
                .flowWindows(direction * .2, direction * .2,
                        direction * .5, direction * .5)
                .professionalFeatures(4, 1, .8, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0).build();
        return NormalizedSignalMetrics.calculate(side, candidate, snapshot, .1);
    }
}
