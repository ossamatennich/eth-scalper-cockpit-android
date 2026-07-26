package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ConfirmedP01EntryLifecycleTest {
    private static final long CREATED = 100_000L;

    private SignalDecision candidate(String side) {
        boolean longSide = "LONG".equals(side);
        return SignalDecision.signal(side, "SCALP_CONTINUATION", 96, 3,
                100.01, longSide ? 102.81 : 97.21, longSide ? 98.66 : 101.36,
                2.80, 1.35, "ACTIVE", true, 98, 102, 4);
    }

    private MarketSnapshot snapshot(String side, long now, boolean executable,
                                    double move1Aligned, double move3Aligned,
                                    double move8Aligned, double flowAligned) {
        int d = "LONG".equals(side) ? 1 : -1;
        double bid = "LONG".equals(side) ? 100.00 : executable ? 100.01 : 100.00;
        double ask = "LONG".equals(side) ? executable ? 100.01 : 100.02 : 100.02;
        return MarketSnapshot.builder(now)
                .eth(100.01, bid, ask).btc(60_000, 59_999, 60_001)
                .candleCounts(60, 20).averages(1.0, 100)
                .movement(d * move1Aligned, d * move3Aligned, d * move8Aligned, 106, 94)
                .move15(d * .20).flow(d * flowAligned, 120)
                .flowWindows(d * flowAligned, d * flowAligned, d * .10, d * .10)
                .build();
    }

    private CandidateLifecycle.FillResult evaluate(String side, long age,
                                                    boolean executable, boolean fresh) {
        long now = CREATED + age;
        MarketSnapshot s = snapshot(side, fresh ? now : now - 1, executable,
                .80, 1.60, 1.30, .20);
        return CandidateLifecycle.processPendingCandidate(candidate(side), s, true,
                CREATED, now, 0, .01, false);
    }

    @Test public void marketableAtCreationDoesNotPublishImmediately() {
        assertTrue(SignalSafetyPolicies.marketableAtCreation("LONG", 100, 100.01, 100.01));
        assertFalse(evaluate("LONG", 0, true, true).confirmed);
    }

    @Test public void creationIsSilent() {
        assertFalse(SignalSafetyPolicies.candidateIsAudible());
        assertFalse(SignalSafetyPolicies.finalSignalIsAudible(true));
    }

    @Test public void noFinalPlanAtZeroMilliseconds() {
        assertEquals(CandidateLifecycle.SILENT_CONFIRMATION_WINDOW,
                evaluate("LONG", 0, true, true).reasonCode);
    }

    @Test public void noFinalPlanAtFourteenThousandNineHundredNinetyNine() {
        assertEquals(CandidateLifecycle.SILENT_CONFIRMATION_WINDOW,
                evaluate("LONG", 14_999, true, true).reasonCode);
    }

    @Test public void freshSnapshotIsRequiredAtFifteenSeconds() {
        assertEquals(CandidateLifecycle.FRESH_SNAPSHOT_REQUIRED,
                evaluate("LONG", 15_000, true, false).reasonCode);
    }

    @Test public void creationMarketabilityNeverOverridesCurrentQuote() {
        assertEquals(CandidateLifecycle.LIMIT_NOT_EXECUTABLE,
                evaluate("LONG", 15_000, false, true).reasonCode);
    }

    @Test public void longRequiresCurrentAskAtOrBelowLimit() {
        assertFalse(evaluate("LONG", 15_000, false, true).confirmed);
        assertTrue(evaluate("LONG", 15_000, true, true).confirmed);
    }

    @Test public void shortRequiresCurrentBidAtOrAboveLimit() {
        assertFalse(evaluate("SHORT", 15_000, false, true).confirmed);
        assertTrue(evaluate("SHORT", 15_000, true, true).confirmed);
    }

    @Test public void distantLimitRemainsSilent() {
        CandidateLifecycle.FillResult r = evaluate("LONG", 40_000, false, true);
        assertFalse(r.confirmed);
        assertNull(r.publishedSignal);
        assertFalse(SignalSafetyPolicies.candidateIsAudible());
    }

    @Test public void realReturnToLimitRunsFullRevalidation() {
        assertFalse(evaluate("LONG", 20_000, false, true).confirmed);
        assertTrue(evaluate("LONG", 21_000, true, true).confirmed);
    }

    @Test public void c04BlocksPublication() {
        long now = CREATED + 20_000;
        MarketSnapshot s = snapshot("LONG", now, true, .05, 1.2, .2, 0);
        assertEquals(ContinuationConfirmation.C04_REJECT,
                CandidateLifecycle.processPendingCandidate(candidate("LONG"), s, true,
                        CREATED, now, 0, 0, false).reasonCode);
    }

    @Test public void c07BlocksPublication() {
        long now = CREATED + 20_000;
        MarketSnapshot s = snapshot("SHORT", now, true, -.10, 1.2, -.10, .20);
        assertEquals(ContinuationConfirmation.C07_REJECT,
                CandidateLifecycle.processPendingCandidate(candidate("SHORT"), s, true,
                        CREATED, now, 0, 0, false).reasonCode);
    }

    @Test public void c08BlocksPublication() {
        long now = CREATED + 120_000;
        MarketSnapshot s = snapshot("LONG", now, true, -.10, -.10, .20, .20);
        assertEquals(ContinuationConfirmation.C08_REJECT,
                CandidateLifecycle.processPendingCandidate(candidate("LONG"), s, true,
                        CREATED, now, .40, 0, false).reasonCode);
    }

    @Test public void p01FailureBlocksPublication() {
        long now = CREATED + 20_000;
        MarketSnapshot s = snapshot("LONG", now, true, .30, 1.20, .20, .20);
        assertEquals(ContinuationConfirmation.P01_MOVE1_REJECT,
                CandidateLifecycle.processPendingCandidate(candidate("LONG"), s, true,
                        CREATED, now, 0, 0, false).reasonCode);
    }

    @Test public void staleFeedBlocksPublication() {
        long now = CREATED + 20_000;
        MarketSnapshot s = snapshot("LONG", now, true, .80, 1.60, 1.30, .20);
        assertEquals(ContinuationConfirmation.P01_STALE_REJECT,
                CandidateLifecycle.processPendingCandidate(candidate("LONG"), s, false,
                        CREATED, now, 0, 0, false).reasonCode);
    }

    @Test public void validCandidateCanPublishAtFifteenSeconds() {
        CandidateLifecycle.FillResult r = evaluate("LONG", 15_000, true, true);
        assertTrue(r.confirmed);
        assertEquals(DynamicTradePlan.CONFIRMED, r.reasonCode);
    }

    @Test public void validCandidateCanPublishAtSixtyThreeSeconds() {
        assertTrue(evaluate("SHORT", 63_000, true, true).confirmed);
    }

    @Test public void exactlyOneHundredTwentySecondsIsDeterministicallyEligible() {
        assertTrue(evaluate("LONG", 120_000, true, true).confirmed);
    }

    @Test public void afterOneHundredTwentySecondsCandidateExpiresSilently() {
        CandidateLifecycle.FillResult r = evaluate("LONG", 120_001, true, true);
        assertFalse(r.confirmed);
        assertEquals(CandidateLifecycle.PENDING_EXPIRED, r.reasonCode);
        assertFalse(SignalSafetyPolicies.lifecycleUpdateIsAudible());
    }

    @Test public void targetBeforeConfirmationIsMissedNoFill() {
        long now = CREATED + 10_000;
        MarketSnapshot target = MarketSnapshot.builder(now)
                .eth(102.82, 102.81, 102.82).averages(1, 100)
                .movement(.8, 1.6, 1.3, 106, 94).flowWindows(.2, .2, .1, .1).build();
        assertEquals(CandidateLifecycle.TARGET_BEFORE_FILL,
                CandidateLifecycle.processPendingCandidate(candidate("LONG"), target, true,
                        CREATED, now, 1.0, 0, false).reasonCode);
        assertFalse(SignalSafetyPolicies.isTerminalStatus("MISSED_NO_FILL"));
        assertEquals("MISSED_NO_FILL", SignalSafetyPolicies.executionClassification(
                true, CREATED, now, 0, 1.0, false, "MISSED_NO_FILL"));
    }

    @Test public void missedCandidateIsRemovedAndCannotReturn() {
        PendingCandidateIndex<SignalDecision> index = new PendingCandidateIndex<>();
        CandidateTombstones tombstones = new CandidateTombstones();
        SignalDecision c = candidate("LONG");
        String signature = SignalSafetyPolicies.candidateSignature(c);
        index.upsert(signature, CREATED, () -> c);
        index.remove(signature);
        tombstones.markMissed(signature);
        assertEquals(0, index.size());
        assertTrue(tombstones.blocks(signature));
        assertEquals(1, tombstones.size());
    }

    @Test public void missedNoFillNeverCountsAsTakeProfit() {
        assertFalse(SignalSafetyPolicies.isTerminalStatus("MISSED_NO_FILL"));
        CandidateLifecycle.TerminalResolution r = CandidateLifecycle.resolveTerminal(
                "MISSED_NO_FILL", candidate("LONG"), false, CREATED + 20_000, 103);
        assertFalse(r.terminalResolved);
        assertEquals(0.0, r.result.realizedNet, 0.0);
    }

    @Test public void finalNotificationSoundsExactlyOnce() {
        SignalDecision published = evaluate("LONG", 15_000, true, true).publishedSignal;
        String signature = SignalSafetyPolicies.deterministicSignature(published, 1);
        Set<String> sounded = new HashSet<>();
        int count = 0;
        if (SignalSafetyPolicies.finalSignalIsAudible(sounded.contains(signature))) count++;
        sounded.add(signature);
        if (SignalSafetyPolicies.finalSignalIsAudible(sounded.contains(signature))) count++;
        assertEquals(1, count);
    }

    @Test public void duplicateUpdatesKeepOneObject() {
        PendingCandidateIndex<Object> index = new PendingCandidateIndex<>();
        String signature = SignalSafetyPolicies.candidateSignature(candidate("LONG"));
        Object first = null;
        for (int i = 0; i < 27; i++) {
            PendingCandidateIndex.UpsertResult<Object> u =
                    index.upsert(signature, CREATED + i, Object::new);
            if (first == null) first = u.value;
            assertSame(first, u.value);
        }
        assertEquals(1, index.size());
    }

    @Test public void duplicateUpdatesKeepInitialCreatedAt() {
        PendingCandidateIndex<Object> index = new PendingCandidateIndex<>();
        String signature = SignalSafetyPolicies.candidateSignature(candidate("SHORT"));
        PendingCandidateIndex.UpsertResult<Object> a = index.upsert(signature, CREATED, Object::new);
        PendingCandidateIndex.UpsertResult<Object> b = index.upsert(signature, CREATED + 50_000, Object::new);
        assertEquals(CREATED, a.createdAt);
        assertEquals(CREATED, b.createdAt);
    }

    @Test public void publishedValuesMatchEverySurfaceAndPersistence() {
        CandidateLifecycle.FillResult fill = evaluate("LONG", 15_000, true, true);
        SignalDecision signal = fill.publishedSignal;
        ConfirmedSignalPayload payload = ConfirmedSignalPayload.from(signal);
        ActivePlanState state = ActivePlanState.builder()
                .status("ACTIVE").side(signal.side).family(signal.family)
                .reasonCode(signal.reasonCode).reasonText(signal.reasonText)
                .score(signal.score).quantity(signal.quantity)
                .prices(signal.entry, signal.takeProfit, signal.stopLoss)
                .risk(signal.targetMove, signal.stopDistance)
                .times(CREATED, CREATED + 15_000, CREATED + 15_000)
                .premium15m(fill.premium15m).notification("signature", 30_001)
                .lastMarket(100.01, 100.00, 100.01, 1.0)
                .lastP01ConfirmedAt(CREATED + 15_000)
                .movement(signal.impulse, signal.resetConfirmed, signal.movementOrigin,
                        signal.movementExtreme, signal.movementDistance)
                .replayRisk("", "").p01(.8, 1.6, 1.3, .2, .2)
                .sizingDiagnostic("{\"finalQuantity\":" + signal.quantity + "}").build();
        assertTrue(state.isValid());
        assertEquals(signal.quantity, payload.quantityForNotification());
        assertEquals(signal.quantity, payload.quantityForScreen());
        assertEquals(signal.quantity, payload.quantityForDiagnostic());
        assertEquals(signal.quantity, state.quantity);
        assertEquals(signal.takeProfit, state.takeProfit, 0.0);
        assertEquals(signal.stopLoss, state.stopLoss, 0.0);
        assertEquals(signal.quantity, fill.dynamicPlan.finalQuantity);
    }
}
