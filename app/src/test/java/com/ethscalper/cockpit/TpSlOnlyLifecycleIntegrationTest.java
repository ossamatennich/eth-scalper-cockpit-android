package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Integration tests for the policies and registries used directly by MarketWatchService. */
public class TpSlOnlyLifecycleIntegrationTest {
    private SignalDecision continuation(String side, int score) {
        boolean longSide = "LONG".equals(side);
        return SignalDecision.signal(side, "SCALP_CONTINUATION", score, 3,
                100.01, longSide ? 102.81 : 97.21, longSide ? 98.66 : 101.36,
                2.80, 1.35, "ACTIVE", true, 98.0, 101.0, 3.0);
    }

    private SignalDecision rangeFade() {
        return SignalDecision.signal("LONG", "RANGE_FADE_LONG", 88, 3,
                100.01, 102.01, 98.81, 2.0, 1.20,
                "RESET", true, 99.0, 101.0, 2.0);
    }

    private MarketSnapshot p01(long now) {
        return MarketSnapshot.builder(now)
                .eth(100.0, 99.99, 100.00)
                .btc(60_000, 59_999, 60_001)
                .candleCounts(60, 20)
                .averages(1.0, 100.0)
                .movement(.50, 1.20, .30, 103, 97)
                .move15(.20)
                .flow(.20, 120)
                .flowWindows(.20, .20, .10, .10)
                .build();
    }

    private static boolean blocked() {
        return SignalSafetyPolicies.blocksNewFinalSignal("ACTIVE", true, 1_000);
    }

    @Test public void activeFinalBlocksNewP01Long() {
        assertTrue(blocked());
        assertTrue(ContinuationConfirmation.requiresP01(continuation("LONG", 90).family));
        assertEquals("V2328_ACTIVE_SIGNAL_ALREADY_RUNNING",
                SignalSafetyPolicies.blockedCandidateReasonCode());
        assertTrue(SignalSafetyPolicies.blockedCandidateDiagnosticText().contains("TP ou au SL"));
    }

    @Test public void activeFinalBlocksNewP01Short() {
        assertTrue(blocked());
        assertTrue(ContinuationConfirmation.requiresP01(continuation("SHORT", 90).family));
    }

    @Test public void activeFinalBlocksNewRangeFade() {
        assertTrue(blocked());
        assertFalse(ContinuationConfirmation.requiresP01(rangeFade().family));
    }

    @Test public void blockedCandidateIsSilent() {
        assertTrue(blocked());
        assertFalse(SignalSafetyPolicies.candidateIsAudible());
        assertFalse(SignalSafetyPolicies.lifecycleUpdateIsAudible());
    }

    @Test public void sameCandidateReceivedTwentySevenTimesCreatesOneObject() {
        PendingCandidateIndex<Object> index = new PendingCandidateIndex<>();
        SignalDecision decision = continuation("LONG", 90);
        String signature = SignalSafetyPolicies.candidateSignature(decision);
        Object first = null;
        for (int i = 0; i < 27; i++) {
            PendingCandidateIndex.UpsertResult<Object> result =
                    index.upsert(signature, 10_000 + i * 1_000L, Object::new);
            if (i == 0) first = result.value;
            assertSame(first, result.value);
            assertEquals(i == 0, result.created);
        }
        assertEquals(1, index.size());
    }

    @Test public void candidateKeepsInitialCreatedAtAcrossUpdates() {
        PendingCandidateIndex<Object> index = new PendingCandidateIndex<>();
        String signature = SignalSafetyPolicies.candidateSignature(continuation("LONG", 90));
        PendingCandidateIndex.UpsertResult<Object> first =
                index.upsert(signature, 10_000, Object::new);
        PendingCandidateIndex.UpsertResult<Object> updated =
                index.upsert(signature, 38_000, Object::new);
        assertEquals(10_000, first.createdAt);
        assertEquals(first.createdAt, updated.createdAt);
        assertEquals(38_000, updated.lastObservedAt);
    }

    @Test public void duplicateCandidateCanLaterConfirmOnlyOnce() {
        PendingCandidateIndex<SignalDecision> index = new PendingCandidateIndex<>();
        SignalDecision raw = continuation("LONG", 96);
        String candidateSignature = SignalSafetyPolicies.candidateSignature(raw);
        CandidateLifecycle.FillResult confirmed = null;
        for (int i = 0; i < 27; i++) {
            PendingCandidateIndex.UpsertResult<SignalDecision> update =
                    index.upsert(candidateSignature, 20_000 + i, () -> raw);
            if (i == 0) {
                MarketSnapshot weak = MarketSnapshot.builder(20_000)
                        .eth(100, 99.99, 100).averages(1, 100)
                        .movement(.10, .50, .20, 103, 97)
                        .flowWindows(.10, .10, .10, .10).build();
                assertFalse(CandidateLifecycle.processAtFill(
                        update.value, weak, true, update.createdAt, 0.0).confirmed);
            }
            if (i == 26) {
                confirmed = CandidateLifecycle.processAtFill(
                        update.value, p01(30_000), true, update.createdAt, 0.0);
                index.remove(candidateSignature);
            }
        }
        assertNotNull(confirmed);
        assertTrue(confirmed.confirmed);
        assertEquals(0, index.size());
    }

    @Test public void finalConfirmationSoundsExactlyOnce() {
        SignalDecision published = CandidateLifecycle.processAtFill(
                continuation("LONG", 96), p01(40_000), true, 40_000, 0.0).publishedSignal;
        String signature = SignalSafetyPolicies.deterministicSignature(published, 1);
        Set<String> alerted = new HashSet<>();
        int sounds = 0;
        if (SignalSafetyPolicies.finalSignalIsAudible(alerted.contains(signature))) sounds++;
        alerted.add(signature);
        if (SignalSafetyPolicies.finalSignalIsAudible(alerted.contains(signature))) sounds++;
        assertEquals(1, sounds);
    }

    @Test public void signalRemainsActiveAfterFifteenMinutes() {
        assertEquals("ACTIVE", SignalSafetyPolicies.liveStatusUntilTpOrSl("TIMEOUT_15M"));
    }

    @Test public void signalRemainsActiveAfterFortyFiveMinutes() {
        assertEquals("ACTIVE", SignalSafetyPolicies.liveStatusUntilTpOrSl("TIMEOUT_45M"));
    }

    @Test public void unfavorableContextDoesNotCloseSignal() {
        assertEquals("ACTIVE", SignalSafetyPolicies.liveStatusUntilTpOrSl("REVERSAL_FLOW"));
        assertEquals("ACTIVE", SignalSafetyPolicies.liveStatusUntilTpOrSl("BTC_VETO"));
    }

    @Test public void scenarioInvalidatedDoesNotCloseActiveSignal() {
        assertFalse(SignalSafetyPolicies.isTerminalStatus("SCENARIO_INVALIDATED"));
        assertEquals("ACTIVE",
                SignalSafetyPolicies.liveStatusUntilTpOrSl("SCENARIO_INVALIDATED"));
    }

    @Test public void publicActionNeverSaysExit() {
        String action = SignalSafetyPolicies.publicAction(1_000, 99_000_000, false);
        assertEquals("GÉRER LE PLAN ACTIF", action);
        assertFalse(action.contains("SORTIR"));
    }

    @Test public void publicActionNeverSaysExpired() {
        String action = SignalSafetyPolicies.publicAction(1_000, 99_000_000, false);
        assertFalse(action.contains("EXPIR"));
    }

    @Test public void takeProfitClosesAndRealizesSignal() {
        SignalDecision signal = continuation("LONG", 90);
        CandidateLifecycle.TerminalResolution resolution = CandidateLifecycle.resolveTerminal(
                "TP_TOUCHED", signal, true, 50_000, signal.takeProfit);
        assertTrue(resolution.terminalResolved);
        assertEquals("TP_TOUCHED", resolution.exitReason);
        assertEquals(signal.takeProfit, resolution.exitPrice, 0.0);
    }

    @Test public void stopLossClosesAndRealizesSignal() {
        SignalDecision signal = continuation("LONG", 90);
        CandidateLifecycle.TerminalResolution resolution = CandidateLifecycle.resolveTerminal(
                "SL_TOUCHED", signal, true, 50_000, signal.stopLoss);
        assertTrue(resolution.terminalResolved);
        assertEquals("SL_TOUCHED", resolution.exitReason);
        assertEquals(signal.stopLoss, resolution.exitPrice, 0.0);
    }

    @Test public void newSignalAllowedAfterTakeProfit() {
        assertFalse(SignalSafetyPolicies.blocksNewFinalSignal(
                "TP_TOUCHED", true, 1_000));
    }

    @Test public void newSignalAllowedAfterStopLoss() {
        assertFalse(SignalSafetyPolicies.blocksNewFinalSignal(
                "SL_TOUCHED", true, 1_000));
    }

    @Test public void quantityMatchesPlanNotificationScreenAndDiagnostic() {
        SignalDecision published = CandidateLifecycle.processAtFill(
                continuation("LONG", 96), p01(60_000), true, 60_000, 0.0).publishedSignal;
        ConfirmedSignalPayload payload = ConfirmedSignalPayload.from(published);
        assertEquals(published.quantity, payload.quantityForNotification());
        assertEquals(published.quantity, payload.quantityForScreen());
        assertEquals(published.quantity, payload.quantityForDiagnostic());
    }

    @Test public void automaticOrdersRemainImpossible() {
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
    }
}
