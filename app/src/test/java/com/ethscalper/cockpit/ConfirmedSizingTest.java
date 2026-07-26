package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class ConfirmedSizingTest {
    private SignalDecision continuation(int engineScore) {
        return SignalDecision.signal("LONG", "v2.32 SCALP_CONTINUATION", engineScore, 3,
                100.01, 102.81, 98.66, 2.80, 1.35,
                "ACTIVE", true, 98.0, 101.0, 3.0);
    }

    private SignalDecision rangeFade(int engineScore) {
        return SignalDecision.signal("LONG", "v2.32 RANGE_FADE_LONG", engineScore, 3,
                100.01, 102.01, 98.81, 2.0, 1.20,
                "RESET", true, 99.0, 101.0, 2.0);
    }

    private MarketSnapshot snapshot(long now, double move1, double move3, double move8,
                                    double move15, double flow30, double flow60,
                                    double btcMove3, double lastVolume) {
        return MarketSnapshot.builder(now)
                .eth(100.0, 99.99, 100.00)
                .btc(60_000.0, 59_999.0, 60_001.0)
                .candleCounts(60, 20)
                .averages(1.0, 100.0)
                .movement(move1, move3, move8, 103.0, 97.0)
                .move15(move15)
                .flow(flow30, lastVolume)
                .flowWindows(flow30, flow30, flow60, flow60)
                .btcMoves(0.0, btcMove3, 0.0, btcMove3)
                .build();
    }

    private CandidateLifecycle.FillResult fill(int score, MarketSnapshot snapshot,
                                               boolean replayVeto) {
        return CandidateLifecycle.processAtFill(
                continuation(score), snapshot, true, snapshot.now, 0.0, replayVeto);
    }

    @Test public void representativeEvidenceProducesThreeEth() {
        CandidateLifecycle.FillResult fill = fill(96,
                snapshot(1_000, .40, 1.00, .20, -.10, .20, 0.0, 0.0, 100), false);
        assertTrue(fill.confirmed);
        assertEquals(2, fill.publishedSignal.quantity);
        assertEquals(0, fill.sizing.evidencePoints);
    }

    @Test public void representativeEvidenceProducesFourEth() {
        CandidateLifecycle.FillResult fill = fill(96,
                snapshot(2_000, .75, 1.00, .20, -.10, .20, .05, 0.0, 100), false);
        assertTrue(fill.confirmed);
        assertEquals(2, fill.publishedSignal.quantity);
        assertTrue(fill.sizing.move1Bonus);
        assertFalse(fill.sizing.move3Bonus);
    }

    @Test public void representativeEvidenceProducesFiveEth() {
        CandidateLifecycle.FillResult fill = fill(96,
                snapshot(3_000, .75, 1.55, .30, -.10, .20, .05, 0.0, 100), false);
        assertTrue(fill.confirmed);
        assertEquals(2, fill.publishedSignal.quantity);
        assertEquals(5, fill.sizing.finalQuantity);
        assertTrue(fill.sizing.move1Bonus);
        assertTrue(fill.sizing.move3Bonus);
    }

    @Test public void representativeEvidenceProducesSixEth() {
        CandidateLifecycle.FillResult fill = fill(96,
                snapshot(4_000, .75, 1.55, .30, .20, .20, .05, 0.0, 100), false);
        assertTrue(fill.confirmed);
        assertEquals(2, fill.publishedSignal.quantity);
        assertEquals(6, fill.sizing.finalQuantity);
        assertTrue(fill.sizing.premium15mBonus);
        assertFalse(fill.sizing.cleanContextBonus);
    }

    @Test public void representativeEvidenceProducesSevenEthOnlyWithCleanContext() {
        CandidateLifecycle.FillResult fill = fill(96,
                snapshot(5_000, .80, 1.60, 1.30, .20, .20, .15, .00010, 120), false);
        assertTrue(fill.confirmed);
        assertEquals(2, fill.publishedSignal.quantity);
        assertEquals(7, fill.sizing.finalQuantity);
        assertEquals(4, fill.sizing.evidencePoints);
        assertTrue(fill.sizing.cleanContextBonus);
    }

    @Test public void engineScoreNinetySixDoesNotAutomaticallyProduceSevenEth() {
        CandidateLifecycle.FillResult fill = fill(96,
                snapshot(6_000, .40, 1.00, .20, -.10, .20, 0.0, 0.0, 100), false);
        assertTrue(fill.confirmed);
        assertEquals(96, fill.sizing.engineScoreDiagnosticOnly);
        assertEquals(2, fill.publishedSignal.quantity);
    }

    @Test public void historicalReplayVetoCapsOtherwiseSevenEthAtFive() {
        CandidateLifecycle.FillResult fill = fill(96,
                snapshot(7_000, .80, 1.60, 1.30, .20, .20, .15, .00010, 120), true);
        assertTrue(fill.confirmed);
        assertEquals(2, fill.publishedSignal.quantity);
        assertEquals(5, fill.sizing.finalQuantity);
        assertTrue(fill.sizing.historicalReplayRiskVeto);
        assertTrue(fill.sizing.replayRiskCapApplied);
        assertEquals(5, fill.sizing.maxAllowedQuantity);
    }

    @Test public void rangeFadeWithHighEngineScoreRemainsConservative() {
        MarketSnapshot snapshot =
                snapshot(8_000, .20, .20, .10, .20, .05, .05, 0.0, 100);
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                rangeFade(96), snapshot, true, snapshot.now, 0.0, false);
        assertFalse(fill.confirmed);
        assertEquals(CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY, fill.reasonCode);
        assertNull(fill.publishedSignal);
    }

    @Test public void exceptionallyCleanRangeFadeStillCannotExceedFourEth() {
        MarketSnapshot snapshot =
                snapshot(9_000, .80, .60, .10, .20, .12, .10, 0.0, 120);
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                rangeFade(96), snapshot, true, snapshot.now, 0.0, false);
        assertFalse(fill.confirmed);
        assertEquals(CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY, fill.reasonCode);
        assertNull(fill.publishedSignal);
    }
}
