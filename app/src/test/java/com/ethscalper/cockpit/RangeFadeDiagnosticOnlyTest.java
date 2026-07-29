package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class RangeFadeDiagnosticOnlyTest {
    private SignalDecision range(String side) {
        boolean longSide = "LONG".equals(side);
        return SignalDecision.signal(side, "RANGE_FADE_" + side, 90, 3,
                100, longSide ? 102.8 : 97.2, longSide ? 98.8 : 101.2,
                2.8, 1.2, "RESET", true, 99, 101, 2);
    }

    private MarketSnapshot snapshot(long now) {
        return MarketSnapshot.builder(now).eth(100, 99.99, 100)
                .averages(1, 100).movement(.8, 1.6, 1.3, 105, 95)
                .move15(.2).flowWindows(.2, .2, .1, .1).build();
    }

    @Test public void rangeFadeLongIsRecordedButNotPublished() {
        CandidateLifecycle.AdmissionResult a = CandidateLifecycle.admit(
                range("LONG"), true, false, "RANGE_FADE_REJET_INSUFFISANT");
        assertTrue(a.observed);
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processPendingCandidate(
                a.decision, snapshot(20_000), true, 0, 20_000, 0, 0, false);
        assertFalse(fill.confirmed);
        assertEquals(CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY, fill.reasonCode);
        assertEquals("RANGE_FADE conservé pour calibration — aucune publication finale.",
                CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_TEXT);
    }

    @Test public void rangeFadeShortIsRecordedButNotPublished() {
        CandidateLifecycle.AdmissionResult a = CandidateLifecycle.admit(
                range("SHORT"), true, false, "");
        assertTrue(a.observed);
        assertEquals(CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY,
                CandidateLifecycle.processPendingCandidate(a.decision, snapshot(20_000),
                        true, 0, 20_000, 0, 0, false).reasonCode);
    }

    @Test public void rangeFadeNeverSounds() {
        assertFalse(SignalSafetyPolicies.candidateIsAudible());
        assertFalse(SignalSafetyPolicies.lifecycleUpdateIsAudible());
    }

    @Test public void rangeFadeNeverCreatesActivePlan() {
        assertFalse(SignalSafetyPolicies.blocksNewFinalSignal("DIAGNOSTIC_ONLY", false, 0));
    }

    @Test public void diagnosticRangeFadeDoesNotBlockFutureP01() {
        assertFalse(SignalSafetyPolicies.blocksNewFinalSignal("DIAGNOSTIC_ONLY", false, 0));
        assertTrue(ContinuationConfirmation.requiresP01("SCALP_CONTINUATION"));
    }

    @Test public void theoreticalRangeFadeLevelsRemainExportable() {
        SignalDecision r = range("LONG");
        assertTrue(r.isSignal());
        assertTrue(r.takeProfit > r.entry);
        assertTrue(r.stopLoss < r.entry);
        assertEquals(2.8, r.targetMove, 0.0);
    }
}
