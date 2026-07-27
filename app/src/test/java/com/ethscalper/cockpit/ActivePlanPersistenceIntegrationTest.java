package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Full save/restore/reset/terminal path using the same persistence boundary as the service. */
public class ActivePlanPersistenceIntegrationTest {
    private static final long CREATED = 100_000L;
    private static final long CONFIRMED = 130_000L;

    private SignalDecision confirmedContinuation() {
        SignalDecision candidate = SignalDecision.signal("LONG", "SCALP_CONTINUATION", 96, 3,
                100.01, 102.81, 98.66, 2.80, 1.35,
                "ACTIVE", true, 98, 101, 3);
        MarketSnapshot snapshot = MarketSnapshot.builder(CONFIRMED)
                .eth(100, 99.99, 100).btc(60_000, 59_999, 60_001)
                .candleCounts(60, 20).averages(1, 100)
                .movement(.80, 1.60, 1.30, 103, 97).move15(.20)
                .flow(.20, 120).flowWindows(.20, .20, .20, .20).build();
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processAtFill(
                candidate, snapshot, true, CREATED, 0.0);
        assertTrue(fill.confirmed);
        return fill.publishedSignal;
    }

    private ActivePlanState activeState() {
        SignalDecision signal = confirmedContinuation();
        String signature = SignalSafetyPolicies.deterministicSignature(signal, CREATED / 60_000L);
        return ActivePlanState.builder()
                .status("ACTIVE").side(signal.side).family(signal.family)
                .reasonCode(signal.reasonCode).reasonText(signal.reasonText)
                .score(signal.score).quantity(signal.quantity)
                .prices(signal.entry, signal.takeProfit, signal.stopLoss)
                .risk(signal.targetMove, signal.stopDistance)
                .times(CREATED, CONFIRMED, CONFIRMED)
                .premium15m(true)
                .notification(signature, SignalSafetyPolicies.confirmedNotificationId(signature))
                .lastMarket(100.20, 100.19, 100.21, 1.0)
                .lastP01ConfirmedAt(CONFIRMED)
                .movement(signal.impulse, signal.resetConfirmed, signal.movementOrigin,
                        signal.movementExtreme, signal.movementDistance)
                .replayRisk("", "")
                .p01(.80, 1.60, 1.30, .20, .20)
                .sizingDiagnostic("{\"finalQuantity\":" + signal.quantity + "}")
                .build();
    }

    @Test public void activePlanIsPersistedAfterConfirmation() {
        MemoryBackend backend = new MemoryBackend();
        ActivePlanPersistence persistence = new ActivePlanPersistence(backend);
        assertTrue(persistence.save(activeState()));
        assertEquals("ACTIVE", backend.values.get("status"));
        assertEquals(ActivePlanPersistence.PERSISTED, "V23281_ACTIVE_PLAN_PERSISTED");
    }

    @Test public void newServiceRepositoryRestoresPlan() {
        MemoryBackend backend = new MemoryBackend();
        assertTrue(new ActivePlanPersistence(backend).save(activeState()));
        ActivePlanPersistence.RestoreResult restored =
                new ActivePlanPersistence(backend).restore();
        assertNotNull(restored.state);
        assertEquals(ActivePlanPersistence.RESTORED, restored.reasonCode);
        assertEquals(CONFIRMED, restored.state.lastP01ConfirmedAt);
        assertEquals(activeState().quantity, restored.state.quantity);
        assertTrue(restored.state.sizingDiagnostic.contains("finalQuantity"));
        assertNotNull(restored.state.toSignalDecision());
    }

    @Test public void restoredPlanBlocksNewLong() {
        ActivePlanState restored = restore();
        assertEquals("LONG", restored.side);
        assertTrue(blocks(restored));
    }

    @Test public void restoredPlanBlocksNewShort() {
        ActivePlanState restored = restore();
        assertTrue(blocks(restored));
        assertEquals("SHORT", SignalDecision.signal("SHORT", "SCALP_CONTINUATION", 90, 3,
                100, 97.2, 101.3, 2.8, 1.3, "", true, 100, 100, 0).side);
    }

    @Test public void restoredPlanBlocksRangeFade() {
        ActivePlanState restored = restore();
        SignalDecision range = SignalDecision.signal("LONG", "RANGE_FADE_LONG", 88, 3,
                100, 102, 98.8, 2, 1.2, "", true, 100, 100, 0);
        assertFalse(ContinuationConfirmation.requiresP01(range.family));
        assertTrue(blocks(restored));
    }

    @Test public void restorationNeverSounds() {
        assertFalse(SignalSafetyPolicies.restoredPlanIsAudible());
        assertFalse(SignalSafetyPolicies.lifecycleUpdateIsAudible());
    }

    @Test public void restorationKeepsNotificationId() {
        ActivePlanState restored = restore();
        assertEquals(restored.notificationId, SignalSafetyPolicies.restoredNotificationId(
                restored.notificationId, restored.notificationSignature));
    }

    @Test public void restoredPlanStaysActiveAfterFifteenMinutes() {
        assertEquals("ACTIVE", SignalSafetyPolicies.liveStatusUntilTpOrSl("TIMEOUT_15M"));
    }

    @Test public void restoredPlanStaysActiveAfterFortyFiveMinutes() {
        assertEquals("ACTIVE", SignalSafetyPolicies.liveStatusUntilTpOrSl("TIMEOUT_45M"));
    }

    @Test public void contextChangeDoesNotRemoveRestoredPlan() {
        MemoryBackend backend = savedBackend();
        assertEquals("ACTIVE", SignalSafetyPolicies.liveStatusUntilTpOrSl("BTC_VETO"));
        assertNotNull(new ActivePlanPersistence(backend).restore().state);
    }

    @Test public void takeProfitDeletesPersistentActiveState() {
        MemoryBackend backend = savedBackend();
        ActivePlanPersistence persistence = new ActivePlanPersistence(backend);
        assertTrue(persistence.clearForTerminal("TP_TOUCHED"));
        assertNull(persistence.restore().state);
    }

    @Test public void stopLossDeletesPersistentActiveState() {
        MemoryBackend backend = savedBackend();
        ActivePlanPersistence persistence = new ActivePlanPersistence(backend);
        assertTrue(persistence.clearForTerminal("SL_TOUCHED"));
        assertNull(persistence.restore().state);
    }

    @Test public void newSignalAllowedAfterTakeProfit() {
        assertFalse(SignalSafetyPolicies.blocksNewFinalSignal("TP_TOUCHED", true, CONFIRMED));
    }

    @Test public void newSignalAllowedAfterStopLoss() {
        assertFalse(SignalSafetyPolicies.blocksNewFinalSignal("SL_TOUCHED", true, CONFIRMED));
    }

    @Test public void diagnosticsResetKeepsActivePlan() {
        MemoryBackend backend = savedBackend();
        ActivePlanPersistence persistence = new ActivePlanPersistence(backend);
        ActivePlanPersistence.ResetResult reset = persistence.resetDiagnostics(true);
        assertTrue(reset.diagnosticsReset);
        assertNotNull(persistence.restore().state);
    }

    @Test public void diagnosticsResetWithoutActivePlanClearsNormally() {
        MemoryBackend backend = savedBackend();
        ActivePlanPersistence persistence = new ActivePlanPersistence(backend);
        ActivePlanPersistence.ResetResult reset = persistence.resetDiagnostics(false);
        assertTrue(reset.persistenceOperationSucceeded);
        assertNull(persistence.restore().state);
    }

    @Test public void corruptStateIsIgnoredWithoutCrash() {
        MemoryBackend backend = new MemoryBackend();
        backend.values.put("formatVersion", "not-a-number");
        ActivePlanPersistence.RestoreResult restored =
                new ActivePlanPersistence(backend).restore();
        assertNull(restored.state);
        assertTrue(restored.invalid);
        assertEquals(ActivePlanPersistence.RESTORE_INVALID, restored.reasonCode);
    }

    @Test public void automaticOrdersRemainDisabledAfterRestore() {
        assertNotNull(restore());
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
    }

    @Test public void dynamicOneAndTwoEthPlansRemainPersistable() {
        Map<String, String> values = activeState().toMap();
        values.put("quantity", "1");
        assertEquals(1, ActivePlanState.fromMap(values).quantity);
        values.put("quantity", "2");
        assertEquals(2, ActivePlanState.fromMap(values).quantity);
    }

    @Test public void upliftedP02QuantityIsIdenticalAfterPersistenceRestore() {
        Map<String, String> values = activeState().toMap();
        values.put("family", "P02_TREND");
        values.put("quantity", "3");
        values.put("sizingDiagnostic", "{\"baselineFinalQuantity\":2,\"finalQuantity\":3}");
        ActivePlanState p02 = ActivePlanState.fromMap(values);
        MemoryBackend backend = new MemoryBackend();
        assertTrue(new ActivePlanPersistence(backend).save(p02));
        ActivePlanState restored = new ActivePlanPersistence(backend).restore().state;
        assertNotNull(restored);
        assertEquals(3, restored.quantity);
        assertEquals(3, restored.toSignalDecision().quantity);
        assertTrue(restored.sizingDiagnostic.contains("\"finalQuantity\":3"));
    }

    private ActivePlanState restore() {
        return new ActivePlanPersistence(savedBackend()).restore().state;
    }

    private MemoryBackend savedBackend() {
        MemoryBackend backend = new MemoryBackend();
        assertTrue(new ActivePlanPersistence(backend).save(activeState()));
        return backend;
    }

    private boolean blocks(ActivePlanState state) {
        return SignalSafetyPolicies.blocksNewFinalSignal(
                state.status, true, state.finalConfirmedAt);
    }

    private static final class MemoryBackend implements ActivePlanPersistence.Backend {
        private Map<String, String> values = new LinkedHashMap<>();

        @Override public Map<String, String> readAll() {
            return new LinkedHashMap<>(values);
        }

        @Override public boolean replaceAll(Map<String, String> replacement) {
            values = new LinkedHashMap<>(replacement);
            return true;
        }
    }
}
