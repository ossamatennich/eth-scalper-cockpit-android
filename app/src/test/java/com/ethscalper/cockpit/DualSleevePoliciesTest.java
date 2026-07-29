package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class DualSleevePoliciesTest {
    private static final double EPS = 1e-9;

    private SignalDecision candidate(String side) {
        int d = "LONG".equals(side) ? 1 : -1;
        return SignalDecision.signal(side, "CONTINUATION", 96, 3,
                100.0, 100.0 + d * 2.8, 100.0 - d * 1.35,
                2.8, 1.35, "ACTIVE", true, 98, 102, 4);
    }

    private NormalizedSignalMetrics.Result metrics(String side, double m1, double m3,
                                                    double m8, double f30, double f60,
                                                    double e, double room, double vr,
                                                    double directionalEdge) {
        int d = "LONG".equals(side) ? 1 : -1;
        double a = 1.0;
        double high = d > 0 ? 100.0 + room * a : 110.0;
        double low = d > 0 ? 90.0 : 100.0 - room * a;
        double rangePosition = d > 0 ? directionalEdge : 1.0 - directionalEdge;
        MarketSnapshot snapshot = MarketSnapshot.builder(1_000_000L)
                .eth(100, 99.99, 100.01).averages(a, 100)
                .movement(d * m1 * a, d * m3 * a, d * m8 * a, high, low)
                .flow(d * f30, vr * 100)
                .flowWindows(d * f30, d * f30, d * f60, d * f60)
                .professionalFeatures(high - low, vr, rangePosition,
                        0, 0, 0, 0, 0, 0,
                        d * m1, d * m3, d * m8, 0, 0, 0, 0, 0)
                .build();
        return NormalizedSignalMetrics.calculate(side, candidate(side), snapshot, e * a);
    }

    private NormalizedSignalMetrics.Result early(String side) {
        return metrics(side, .8, 2.8, 2.5, .2, .75, 0, 1.6, 3.0, .7);
    }

    private NormalizedSignalMetrics.Result delayed(String side) {
        return metrics(side, .8, 1.5, 1.0, .2, 1.0, .2, 1.3, 3.0, .7);
    }

    @Test public void p01EarlyAllBoundariesAreInclusiveAndSymmetric() {
        assertTrue(P01SleeveFilter.evaluate(early("LONG"), 25_000).accepted);
        assertTrue(P01SleeveFilter.evaluate(early("SHORT"), 25_000).accepted);
        assertEquals(P01SleeveFilter.VR_HIGH, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 2.8, 2.5, .2, .75, 0, 1.6, 3 + EPS, .7), 25_000).reasonCode);
        assertEquals(P01SleeveFilter.EARLY_ROOM_LOW, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 2.8, 2.5, .2, .75, 0, 1.6 - EPS, 3, .7), 25_000).reasonCode);
        assertEquals(P01SleeveFilter.EARLY_M1_HIGH, P01SleeveFilter.evaluate(
                metrics("LONG", 1.8 + EPS, 2.8, 2.5, .2, .75, 0, 1.6, 3, .7), 25_000).reasonCode);
        assertEquals(P01SleeveFilter.EARLY_F30_HIGH, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 2.8, 2.5, .6 + EPS, .75, 0, 1.6, 3, .7), 25_000).reasonCode);
    }

    @Test public void p01EarlyFlowBackedAndPriceLedUseExactOrLogic() {
        assertTrue(P01SleeveFilter.evaluate(metrics("LONG", .8, 2.8, 2.5,
                .2, 2, 0, 1.6, 1, .7), 20_000).flowBacked);
        P01SleeveFilter.Result priceLed = P01SleeveFilter.evaluate(metrics("LONG", 1.4,
                3.0, 2.5, .04, .75, 0, 1.6, 1, .7), 20_000);
        assertTrue(priceLed.accepted);
        assertTrue(priceLed.priceLed);
        assertEquals(P01SleeveFilter.EARLY_ACCEPT_MISSING,
                P01SleeveFilter.evaluate(metrics("LONG", 1.4 - EPS, 3.0, 2.5,
                        .04, .75, 0, 1.6, 1, .7), 20_000).reasonCode);
        assertEquals(P01SleeveFilter.EARLY_ACCEPT_MISSING,
                P01SleeveFilter.evaluate(metrics("LONG", 1.4, 3.0, 2.5,
                        .04 - EPS, .75, 0, 1.6, 1, .7), 20_000).reasonCode);
        assertEquals(P01SleeveFilter.EARLY_ACCEPT_MISSING,
                P01SleeveFilter.evaluate(metrics("LONG", 1.4, 3.0, 2.5,
                        .04, .75 + EPS, 0, 1.6, 1, .7), 20_000).reasonCode);
    }

    @Test public void p01EarlyConsumedBoundaryIsStrict() {
        assertTrue(P01SleeveFilter.evaluate(metrics("LONG", 1.4, 3, 2.5,
                .10, .5, 0, 1.6, 1, .7), 20_000).accepted);
        assertEquals(P01SleeveFilter.EARLY_CONSUMED,
                P01SleeveFilter.evaluate(metrics("LONG", 1.4, 3, 2.5 + EPS,
                        .10, .5, 0, 1.6, 1, .7), 20_000).reasonCode);
        assertTrue(P01SleeveFilter.evaluate(metrics("LONG", 1.4, 3, 2.5 + EPS,
                .15, .5, 0, 1.6, 1, .7), 20_000).accepted);
    }

    @Test public void p01DelayedAllBoundariesAndAgeAreExact() {
        assertTrue(P01SleeveFilter.evaluate(delayed("LONG"), 25_001).accepted);
        assertTrue(P01SleeveFilter.evaluate(delayed("SHORT"), 90_000).accepted);
        assertEquals(P01SleeveFilter.DELAYED_ROOM_LOW, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 1.5, 1, .2, 1, .2, 1.3 - EPS, 3, .7), 30_000).reasonCode);
        assertEquals(P01SleeveFilter.DELAYED_E_HIGH, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 1.5, 1, .2, 1, .8 + EPS, 1.3, 3, .7), 30_000).reasonCode);
        assertEquals(P01SleeveFilter.DELAYED_F30_HIGH, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 1.5, 1, .6 + EPS, 1, .2, 1.3, 3, .7), 30_000).reasonCode);
        assertEquals(P01SleeveFilter.DELAYED_F60_HIGH, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 1.5, 1, .2, 1 + EPS, .2, 1.3, 3, .7), 30_000).reasonCode);
        assertEquals(P01SleeveFilter.DELAYED_SUPPORT_MISSING, P01SleeveFilter.evaluate(
                metrics("LONG", .8, 1.5, 1, .2 - EPS, 1, .2 - EPS, 1.3, 3, .7), 30_000).reasonCode);
        assertEquals(P01SleeveFilter.AGE_EXPIRED,
                P01SleeveFilter.evaluate(delayed("LONG"), 90_001).reasonCode);
    }

    @Test public void setupAppearanceIsExactAndNeverDuplicatesSameRun() {
        P02SleeveFilter.SetupTracker tracker = new P02SleeveFilter.SetupTracker();
        assertFalse(tracker.observe(P02SleeveFilter.NONE));
        assertTrue(tracker.observe("C1_LONG"));
        for (int i = 0; i < 27; i++) assertFalse(tracker.observe("C1_LONG"));
        assertTrue(tracker.observe("C2_LONG"));
        assertFalse(tracker.observe("C2_LONG"));
        assertFalse(tracker.observe(P02SleeveFilter.NONE));
        assertTrue(tracker.observe("C2_LONG"));
    }

    @Test public void setupCandidateUsesExactC1AndC2Formulas() {
        MarketSnapshot c1 = MarketSnapshot.builder(1).averages(1, 1)
                .movement(.750001, .8625012, 0, 101, 99).build();
        assertEquals("C1_LONG", P02SleeveFilter.setupCandidateFor(c1));
        MarketSnapshot c2 = MarketSnapshot.builder(1).averages(1, 1)
                .movement(-.249999, 1.012501, 0, 101, 99).build();
        assertEquals("C2_LONG", P02SleeveFilter.setupCandidateFor(c2));
        MarketSnapshot shortC2 = MarketSnapshot.builder(1).averages(1, 1)
                .movement(.249999, -1.012501, 0, 101, 99).build();
        assertEquals("C2_SHORT", P02SleeveFilter.setupCandidateFor(shortC2));
    }

    @Test public void p02PrefilterAllInclusiveBoundariesPassLongAndShort() {
        NormalizedSignalMetrics.Result l = metrics("LONG", -.25, 1, .8, 0, 0,
                0, 1.1, .05, .7);
        NormalizedSignalMetrics.Result s = metrics("SHORT", -.25, 1, .8, 0, 0,
                0, 1.1, .05, .7);
        assertTrue(P02SleeveFilter.prefilter(l).accepted);
        assertTrue(P02SleeveFilter.prefilter(s).accepted);
        assertTrue(P02SleeveFilter.prefilter(metrics("LONG", .9, 1, .8, 0, 0,
                0, 1.1, .05, .7)).accepted);
    }

    @Test public void p02PrefilterRejectsEveryOutsideBoundary() {
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", -.25 - EPS, 1, .8, 0, 0, 0, 1.1, .05, .7)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", .9 + EPS, 1, .8, 0, 0, 0, 1.1, .05, .7)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", 0, 1 - EPS, .8, 0, 0, 0, 1.1, .05, .7)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", 0, 1, .8 - EPS, 0, 0, 0, 1.1, .05, .7)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", 0, 1, .8, -EPS, 0, 0, 1.1, .05, .7)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", 0, 1, .8, 0, -EPS, 0, 1.1, .05, .7)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", 0, 1, .8, 0, 0, 0, 1.1 + EPS, .05, .7)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", 0, 1, .8, 0, 0, 0, 1.1, .05, .7 - EPS)).accepted);
        assertFalse(P02SleeveFilter.prefilter(metrics("LONG", 0, 1, .8, 0, 0, 0, 1.1, .05 - EPS, .7)).accepted);
    }

    @Test public void p02ConfirmationTimeAndMetricBoundariesAreExact() {
        NormalizedSignalMetrics.Result m = metrics("LONG", .5, 1.2, 1, .1, .5,
                .8, 2, .2, .7);
        assertEquals(P02SleeveFilter.SILENT_WINDOW,
                P02SleeveFilter.confirmation(m, 20_000).reasonCode);
        assertTrue(P02SleeveFilter.confirmation(m, 20_001).accepted);
        assertTrue(P02SleeveFilter.confirmation(m, 45_000).accepted);
        assertEquals(P02SleeveFilter.EXPIRED,
                P02SleeveFilter.confirmation(m, 45_001).reasonCode);
        assertTrue(P02SleeveFilter.confirmation(metrics("SHORT", .8, 1.2, 1, .1, .5,
                .8, 2, 3, .7), 45_000).accepted);
    }

    @Test public void p02ConfirmationRejectsEveryOutsideMetricBoundary() {
        long age = 30_000;
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .5 - EPS, 1.2, 1, .1, .5, .8, 2, .2, .7), age).accepted);
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .8 + EPS, 1.2, 1, .1, .5, .8, 2, .2, .7), age).accepted);
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .5, 1.2 - EPS, 1, .1, .5, .8, 2, .2, .7), age).accepted);
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .5, 1.2, 1, .1 - EPS, .5, .8, 2, .2, .7), age).accepted);
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .5, 1.2, 1, .1, .5, .8, 2 + EPS, .2, .7), age).accepted);
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .5, 1.2, 1, .1, .5, .8 + EPS, 2, .2, .7), age).accepted);
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .5, 1.2, 1, .1, .5, .8, 2, .2 - EPS, .7), age).accepted);
        assertFalse(P02SleeveFilter.confirmation(metrics("LONG", .5, 1.2, 1, .1, .5, .8, 2, 3 + EPS, .7), age).accepted);
    }
}
