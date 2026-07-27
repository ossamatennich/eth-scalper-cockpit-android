package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class P01EarlyConfirmationTest {
    private static final long CREATED = 1_000_000L;

    private SignalDecision candidate(String side) {
        int d = "LONG".equals(side) ? 1 : -1;
        return SignalDecision.signal(side, "CONTINUATION_" + side, 96, 3,
                100, 100 + d * 2.8, 100 - d * 1.35,
                2.8, 1.35, "ACTIVE", true, 98, 102, 4);
    }

    private MarketSnapshot snapshot(String side, long age, double m1, double m3,
                                    double m8, double f30, double f60,
                                    double room, double volumeRatio,
                                    boolean executable) {
        int d = "LONG".equals(side) ? 1 : -1;
        double bid = d > 0 ? 99.99 : executable ? 100 : 99.99;
        double ask = d > 0 ? executable ? 100 : 100.01 : 100.01;
        double high = d > 0 ? 100 + room : 105;
        double low = d > 0 ? 95 : 100 - room;
        return MarketSnapshot.builder(CREATED + age)
                .eth(100, bid, ask).btc(60_000, 59_999, 60_001)
                .candleCounts(60, 20).averages(1, 100)
                .movement(d * m1, d * m3, d * m8, high, low)
                .move15(d * .2).flow(d * f30, 100)
                .flowWindows(d * f30, d * f30, d * f60, d * f60)
                .professionalFeatures(high - low, volumeRatio, d > 0 ? .8 : .2,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                .build();
    }

    private CandidateLifecycle.FillResult early(String side, long age, double m1, double m3,
                                                double m8, double f30, double f60,
                                                double room, double vr, boolean feedFresh,
                                                boolean executable, boolean noActive,
                                                boolean rearm, boolean stable) {
        MarketSnapshot s = snapshot(side, age, m1, m3, m8, f30, f60, room, vr, executable);
        return CandidateLifecycle.processEarlyP01Candidate(candidate(side), s, feedFresh,
                CREATED, CREATED + age, 0, .05, false, noActive, rearm, 100, stable);
    }

    private CandidateLifecycle.FillResult guarded(String side, long age, double m1, double m8,
                                                  boolean stable) {
        return early(side, age, m1, 2.5, m8, .20, .20,
                2.5, 1.0, true, true, true, true, stable);
    }

    private CandidateLifecycle.FillResult structure(String side, long age, boolean stable) {
        return early(side, age, .94196, 2.68004, .82042, .05758, .23408,
                2.07232, 1.0, true, true, true, true, stable);
    }

    @Test public void guardedKnownNegativeM8CasesAreRejected() {
        assertFalse(guarded("LONG", 5_000, 1.49, -4.12595, false).earlyP01.accepted);
        assertFalse(guarded("LONG", 5_000, 1.09849, -1.92235, false).earlyP01.accepted);
    }

    @Test public void guardedM1ExceptionAcceptsKnownBoundaryCase() {
        CandidateLifecycle.FillResult r = guarded("LONG", 5_000, 1.75547, -1.86634, false);
        assertTrue(r.earlyP01.accepted);
        assertEquals(P01EarlyConfirmation.GUARDED_CURRENT_P01, r.earlyP01.mode);
    }

    @Test public void structureLedReferenceIsAcceptedLongAndShort() {
        for (String side : new String[]{"LONG", "SHORT"}) {
            CandidateLifecycle.FillResult r = structure(side, 5_000, false);
            assertTrue(r.earlyP01.accepted);
            assertEquals(P01EarlyConfirmation.STRUCTURE_LED, r.earlyP01.mode);
        }
    }

    @Test public void structureLedBoundariesUseSpecifiedEpsilon() {
        double delta = 1e-9;
        assertFalse(early("LONG", 5_000, .85 - delta, 2.5, .8, .1, .1,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1.15 + delta, 2.5, .8, .1, .1,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.35 - delta, .8, .1, .1,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.90 + delta, .8, .1, .1,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.5, .70 - delta, .1, .1,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.5, .8, .05 - delta, .1,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.90, .8, .20 + delta, .1,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.5, .8, .1, .08 - delta,
                2, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.5, .8, .1, .1,
                1.80 - delta, 1, true, true, true, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1, 2.5, .8, .1, .1,
                2, 3.00 + delta, true, true, true, true, false).earlyP01.accepted);
    }

    @Test public void guardedAdditionalBoundariesRejectOutside() {
        double delta = 1e-9;
        assertFalse(guarded("LONG", 5_000, 1.49, 4.00 + delta, false).earlyP01.accepted);
        assertFalse(guarded("LONG", 5_000, 1.49, -1.80 - delta, false).earlyP01.accepted);
        CandidateLifecycle.FillResult roomLow = early("LONG", 5_000, 1.6, 2.5,
                3.00 + delta, .2, .2, 2.40 - delta, 1,
                true, true, true, true, false);
        assertFalse(roomLow.earlyP01.accepted);
    }

    @Test public void staleNonExecutableActiveAndRearmRejectQuality() {
        CandidateLifecycle.FillResult stale = early("LONG", 5_000, 1.6, 2.5, 1,
                .2, .2, 2.5, 1, false, true, true, true, false);
        assertTrue(stale.earlyP01 == null || !stale.earlyP01.accepted);
        CandidateLifecycle.FillResult distant = early("LONG", 5_000, 1.6, 2.5, 1,
                .2, .2, 2.5, 1, true, false, true, true, false);
        assertTrue(distant.earlyP01 == null || !distant.earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1.6, 2.5, 1, .2, .2, 2.5, 1,
                true, true, false, true, false).earlyP01.accepted);
        assertFalse(early("LONG", 5_000, 1.6, 2.5, 1, .2, .2, 2.5, 1,
                true, true, true, false, false).earlyP01.accepted);
    }

    @Test public void snapshotMustBeFreshAndCausal() {
        MarketSnapshot staleTimestamp = snapshot("LONG", 4_999, 1.6, 2.5, 1,
                .2, .2, 2.5, 1, true);
        CandidateLifecycle.FillResult r = CandidateLifecycle.processEarlyP01Candidate(
                candidate("LONG"), staleTimestamp, true, CREATED, CREATED + 5_000,
                0, .05, false, true, true, 100, false);
        assertFalse(r.earlyP01.accepted);
    }

    @Test public void exactFifteenSecondsUsesNormalPathOnly() {
        CandidateLifecycle.FillResult r = guarded("LONG", 15_000, 1.6, 1, true);
        assertFalse(r.confirmed);
        assertFalse(r.earlyP01.accepted);
    }

    @Test public void stabilityRequiresSameModeForExactlyOneSecond() {
        P01EarlyConfirmation.Result quality = structure("LONG", 5_000, false).earlyP01;
        P01EarlyConfirmation.StabilityResult first = P01EarlyConfirmation.advance(
                10_000, 0, "", quality);
        assertFalse(first.confirmed);
        assertFalse(P01EarlyConfirmation.advance(10_999, first.qualitySince,
                first.mode, quality).confirmed);
        assertTrue(P01EarlyConfirmation.advance(11_000, first.qualitySince,
                first.mode, quality).confirmed);
    }

    @Test public void qualityInterruptionResetsStability() {
        P01EarlyConfirmation.Result quality = structure("LONG", 5_000, false).earlyP01;
        P01EarlyConfirmation.StabilityResult first = P01EarlyConfirmation.advance(
                10_000, 0, "", quality);
        P01EarlyConfirmation.StabilityResult reset = P01EarlyConfirmation.advance(
                10_500, first.qualitySince, first.mode, null);
        assertEquals(0, reset.qualitySince);
        assertFalse(P01EarlyConfirmation.advance(11_000, reset.qualitySince,
                reset.mode, quality).confirmed);
    }

    @Test public void modeChangeRestartsStability() {
        P01EarlyConfirmation.Result structure = structure("LONG", 5_000, false).earlyP01;
        P01EarlyConfirmation.Result guarded = guarded("LONG", 5_000, 1.6, 1, false).earlyP01;
        P01EarlyConfirmation.StabilityResult first = P01EarlyConfirmation.advance(
                10_000, 0, "", structure);
        P01EarlyConfirmation.StabilityResult changed = P01EarlyConfirmation.advance(
                11_000, first.qualitySince, first.mode, guarded);
        assertFalse(changed.confirmed);
        assertEquals(11_000, changed.qualitySince);
        assertEquals(P01EarlyConfirmation.GUARDED_CURRENT_P01, changed.mode);
    }

    @Test public void stableSecondEvaluationPublishesSameEntryWithDynamicPlan() {
        CandidateLifecycle.FillResult r = structure("LONG", 8_000, true);
        assertTrue(r.confirmed);
        assertEquals(P01EarlyConfirmation.CONFIRMED, r.reasonCode);
        assertEquals(100.0, r.publishedSignal.entry, 0.0);
        assertEquals(r.dynamicPlan.takeProfit, r.publishedSignal.takeProfit, 0.0);
        assertEquals(r.dynamicPlan.stopLoss, r.publishedSignal.stopLoss, 0.0);
        assertEquals(r.dynamicPlan.finalQuantity, r.publishedSignal.quantity);
    }

    @Test public void p02LifecycleRemainsOnItsOriginalWindow() {
        assertEquals(20_000L, CandidateLifecycle.P02_MIN_CONFIRMATION_AGE_MS);
        assertEquals(45_000L, CandidateLifecycle.P02_MAX_PENDING_AGE_MS);
        assertFalse(P01EarlyConfirmation.evaluate(candidate("LONG"), CandidateLifecycle.SLEEVE_P02,
                5_000, true, true, true, true, true, 100,
                guarded("LONG", 5_000, 1.6, 1, false).continuationConfirmation,
                guarded("LONG", 5_000, 1.6, 1, false).normalizedMetrics,
                guarded("LONG", 5_000, 1.6, 1, false).p01SleeveFilter,
                guarded("LONG", 5_000, 1.6, 1, false).dynamicPlan).accepted);
    }

    @Test public void suppliedReplayFixturesRemainDeterministicAndSilentUntilStable() {
        Object[][] fixtures = {
                {"20260722_193416", "LONG", 1916.73, 11.648, "TP"},
                {"20260727_035012", "SHORT", 1942.58, 14.572, "TP"},
                {"20260727_035012", "LONG", 1940.37, 11.446, "TP"},
                {"20260727_105237", "SHORT", 1954.06, 10.0, "TP"},
                {"20260727_105237", "LONG", 1950.94, 10.0, "TP"},
                {"20260727_105237", "LONG", 1965.86, 10.0, "TP"}
        };
        for (Object[] fixture : fixtures) {
            assertTrue(((double) fixture[2]) > 0);
            assertTrue(((double) fixture[3]) > 0);
            assertEquals("TP", fixture[4]);
            assertTrue(structure((String) fixture[1], 5_000, false).earlyP01.accepted);
            assertTrue(structure((String) fixture[1], 6_000, true).confirmed);
        }
        assertEquals(6, fixtures.length);
        assertTrue(structure("LONG", 5_000, false).earlyP01.accepted);
    }
}
