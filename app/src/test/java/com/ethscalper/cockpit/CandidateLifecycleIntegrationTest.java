package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Integration coverage for the same admission and fill path called by MarketWatchService.
 */
public class CandidateLifecycleIntegrationTest {
    private SignalDecision continuation(int score) {
        return SignalDecision.signal("LONG", "SCALP_CONTINUATION", score, 3,
                100.01, 102.81, 98.66, 2.80, 1.35,
                "ACTIVE", true, 98.0, 101.0, 3.0);
    }

    private SignalDecision rangeFade(int score) {
        return SignalDecision.signal("LONG", "RANGE_FADE_LONG", score, 3,
                100.01, 102.01, 98.81, 2.0, 1.20,
                "RESET", true, 99.0, 101.0, 2.0);
    }

    private MarketSnapshot p01Snapshot(long now) {
        return MarketSnapshot.builder(now)
                .eth(100.0, 99.99, 100.00)
                .btc(60_000.0, 59_999.0, 60_001.0)
                .candleCounts(60, 20)
                .averages(1.0, 100.0)
                .movement(.50, 1.20, .30, 103.0, 97.0)
                .move15(.20)
                .flow(.10, 120.0)
                .flowWindows(.10, .10, .10, .10)
                .build();
    }

    @Test public void historicalReplayVetoContinuationReachesP01AndPublishes() {
        long createdAt = 100_000;
        SignalDecision raw = continuation(82);
        CandidateLifecycle.AdmissionResult admission = CandidateLifecycle.admit(
                raw, true, false, "CONTINUATION_REPLAY_QUALITE_INSUFFISANTE");

        assertTrue(admission.observed);
        assertSame(raw, admission.decision);
        assertEquals(CandidateLifecycle.REPLAY_RISK_DIAGNOSTIC,
                admission.replayRiskReasonCode);
        assertFalse(SignalSafetyPolicies.candidateIsAudible());

        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                admission.decision, p01Snapshot(createdAt), true, createdAt, 0.0, true);
        assertTrue(fill.confirmed);
        assertEquals(ContinuationConfirmation.P01_CONFIRMED, fill.reasonCode);
        assertNotNull(fill.publishedSignal);
        assertEquals(4, fill.publishedSignal.quantity);
        assertTrue(fill.sizing.replayRiskCapApplied);
    }

    @Test public void marketableCandidateConfirmsAtCreationWithoutLegacyDelay() {
        long createdAt = 500_000;
        SignalDecision raw = continuation(90);
        assertTrue(SignalSafetyPolicies.marketableAtCreation(
                raw.side, 99.99, 100.00, raw.entry));
        assertTrue(CandidateLifecycle.readyForImmediateConfirmation(true, false));

        CandidateLifecycle.AdmissionResult admission =
                CandidateLifecycle.admit(raw, true, false, "");
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                admission.decision, p01Snapshot(createdAt), true, createdAt, 0.0);

        assertTrue(fill.confirmed);
        assertEquals(createdAt, p01Snapshot(createdAt).now);
        assertEquals(4, fill.publishedSignal.quantity);
    }

    @Test public void candidateIsSilentAndFinalSignatureSoundsOnlyOnce() {
        long createdAt = 700_000;
        CandidateLifecycle.AdmissionResult admission = CandidateLifecycle.admit(
                continuation(88), true, false, "CONTINUATION_TROP_TARDIVE");
        assertTrue(admission.observed);
        assertFalse(SignalSafetyPolicies.candidateIsAudible());

        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                admission.decision, p01Snapshot(createdAt), true, createdAt, 0.0);
        assertTrue(fill.confirmed);

        Set<String> alerted = new HashSet<>();
        String signature = SignalSafetyPolicies.deterministicSignature(
                fill.publishedSignal, createdAt / 60_000L);
        assertTrue(SignalSafetyPolicies.finalSignalIsAudible(alerted.contains(signature)));
        alerted.add(signature);
        assertFalse(SignalSafetyPolicies.finalSignalIsAudible(alerted.contains(signature)));
        assertEquals(1, alerted.size());
    }

    @Test public void filledTimeoutIsDiagnosticOnlyAndRiskStaysActive() {
        SignalDecision published = CandidateLifecycle.processAtFill(
                continuation(85), p01Snapshot(900_000), true, 900_000, 0).publishedSignal;
        assertNotNull(published);
        CandidateLifecycle.TerminalResolution resolution =
                CandidateLifecycle.resolveTerminal(
                        "TIMEOUT_15M", published, true, 1_800_000, 100.61);

        assertFalse(resolution.terminalResolved);
        assertEquals(0L, resolution.exitAt);
        assertTrue(Double.isNaN(resolution.exitPrice));
        assertEquals("", resolution.exitReason);
        assertEquals(0.0, resolution.result.realizedFees, 0.0);
        assertNotEquals(0.0, resolution.result.latentGross, 0.0);
        assertEquals(0L, resolution.result.openRiskAgeMs);
        assertEquals("OPEN_ACTIVE_RISK", resolution.executionClassification);
        assertFalse(SignalSafetyPolicies.isOpenActiveRisk("TIMEOUT_15M", true));
    }

    @Test public void filledInvalidationIsDiagnosticOnlyAndRiskStaysActive() {
        SignalDecision published = CandidateLifecycle.processAtFill(
                continuation(75), p01Snapshot(1_100_000), true, 1_100_000, 0).publishedSignal;
        assertNotNull(published);
        CandidateLifecycle.TerminalResolution resolution =
                CandidateLifecycle.resolveTerminal(
                        "SCENARIO_INVALIDATED", published, true, 1_400_000, 99.71);

        assertFalse(resolution.terminalResolved);
        assertEquals(0L, resolution.exitAt);
        assertTrue(Double.isNaN(resolution.exitPrice));
        assertEquals("", resolution.exitReason);
        assertEquals(0.0, resolution.result.realizedGross, 0.0);
        assertNotEquals(0.0, resolution.result.latentNet, 0.0);
        assertEquals("OPEN_ACTIVE_RISK", resolution.executionClassification);
        assertFalse(SignalSafetyPolicies.isOpenActiveRisk("SCENARIO_INVALIDATED", true));
    }

    @Test public void quantityIsIdenticalAcrossPublishedSurfaces() {
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                continuation(88), p01Snapshot(1_300_000), true, 1_300_000, 0);
        assertTrue(fill.confirmed);
        ConfirmedSignalPayload payload = ConfirmedSignalPayload.from(fill.publishedSignal);
        int planQuantity = fill.publishedSignal.quantity;
        int notificationQuantity = payload.quantityForNotification();
        int screenQuantity = payload.quantityForScreen();
        int diagnosticQuantity = payload.quantityForDiagnostic();
        assertEquals(4, planQuantity);
        assertEquals(planQuantity, notificationQuantity);
        assertEquals(planQuantity, screenQuantity);
        assertEquals(planQuantity, diagnosticQuantity);
        assertTrue(payload.notificationBody(false).contains("· 4 ETH"));
    }

    @Test public void onlyTpAndSlAreLiveTerminalStatuses() {
        assertTrue(SignalSafetyPolicies.isTerminalStatus("TP_TOUCHED"));
        assertTrue(SignalSafetyPolicies.isTerminalStatus("SL_TOUCHED"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("SCENARIO_INVALIDATED"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT_15M"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT_45M"));
        assertTrue(SignalSafetyPolicies.isHistoricalTerminalStatus("TIMEOUT_45M"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("ACTIVE"));
    }

    @Test public void rangeFadeKeepsReplayVetoAndNeverUsesP01() {
        CandidateLifecycle.AdmissionResult vetoed = CandidateLifecycle.admit(
                rangeFade(88), true, false, "RANGE_FADE_REJET_INSUFFISANT");
        assertFalse(vetoed.observed);
        assertEquals(CandidateLifecycle.REPLAY_RISK_DIAGNOSTIC,
                vetoed.decision.reasonCode);

        CandidateLifecycle.AdmissionResult accepted =
                CandidateLifecycle.admit(rangeFade(88), true, false, "");
        assertTrue(accepted.observed);
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                accepted.decision,
                MarketSnapshot.builder(1_500_000)
                        .eth(100, 99.99, 100.00)
                        .averages(1, 100)
                        .movement(.10, .10, .10, 103, 97)
                        .flowWindows(0, 0, 0, 0)
                        .build(),
                true, 1_500_000, 0);
        assertTrue(fill.confirmed);
        assertEquals("RANGE_FADE_CONFIRMED_AT_FILL", fill.reasonCode);
        assertFalse(fill.publishedSignal.family.contains("P01"));
    }
}
