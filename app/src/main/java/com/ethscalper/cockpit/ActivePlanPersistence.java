package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.Map;

/** Transaction boundary for the dedicated active-plan state. */
public final class ActivePlanPersistence {
    public static final String PERSISTED = "V23281_ACTIVE_PLAN_PERSISTED";
    public static final String RESTORED = "V23281_ACTIVE_PLAN_RESTORED";
    public static final String RESTORE_INVALID = "V23281_ACTIVE_PLAN_RESTORE_INVALID";
    public static final String PERSIST_FAILED = "V23281_ACTIVE_PLAN_PERSIST_FAILED";

    private final Backend backend;

    public ActivePlanPersistence(Backend backend) {
        this.backend = backend;
    }

    public boolean save(ActivePlanState state) {
        if (state == null || !state.isValid()) return false;
        try {
            return backend.replaceAll(state.toMap());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public RestoreResult restore() {
        try {
            Map<String, String> values = backend.readAll();
            if (values == null || values.isEmpty()) return RestoreResult.empty();
            ActivePlanState state = ActivePlanState.fromMap(values);
            return state == null ? RestoreResult.invalid() : RestoreResult.restored(state);
        } catch (RuntimeException ignored) {
            return RestoreResult.invalid();
        }
    }

    public boolean clearForTerminal(String status) {
        if (!SignalSafetyPolicies.isTerminalStatus(status)) return false;
        return clear();
    }

    public ResetResult resetDiagnostics(boolean activePlanPresent) {
        if (activePlanPresent) return new ResetResult(true, true);
        return new ResetResult(true, clear());
    }

    public boolean clear() {
        try {
            return backend.replaceAll(Collections.emptyMap());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public interface Backend {
        Map<String, String> readAll();
        boolean replaceAll(Map<String, String> values);
    }

    public static final class RestoreResult {
        public final ActivePlanState state;
        public final String reasonCode;
        public final boolean invalid;

        private RestoreResult(ActivePlanState state, String reasonCode, boolean invalid) {
            this.state = state;
            this.reasonCode = reasonCode;
            this.invalid = invalid;
        }

        private static RestoreResult empty() { return new RestoreResult(null, "", false); }
        private static RestoreResult invalid() { return new RestoreResult(null, RESTORE_INVALID, true); }
        private static RestoreResult restored(ActivePlanState state) { return new RestoreResult(state, RESTORED, false); }
    }

    public static final class ResetResult {
        public final boolean diagnosticsReset;
        public final boolean persistenceOperationSucceeded;

        private ResetResult(boolean diagnosticsReset, boolean persistenceOperationSucceeded) {
            this.diagnosticsReset = diagnosticsReset;
            this.persistenceOperationSucceeded = persistenceOperationSucceeded;
        }
    }
}
