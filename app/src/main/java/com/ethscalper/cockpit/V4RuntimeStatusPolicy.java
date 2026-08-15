package com.ethscalper.cockpit;

/** Pure UI health policy. It does not participate in signal or plan decisions. */
public final class V4RuntimeStatusPolicy {
    public static final long QUOTE_FRESH_MS = 15_000L;
    public static final long PERSISTENT_ERROR_MS = 30_000L;

    public enum State { ACTIF, SYNCHRO, HORS_LIGNE }

    public static final class Input {
        public final long now;
        public final boolean networkAvailable;
        public final boolean socketConnected;
        public final String transportState;
        public final long lastQuoteAt;
        public final boolean dailySyncInProgress;
        public final long lastAnalysisAt;
        public final int dataEligibleAssets;
        public final String lastError;
        public final long lastErrorAt;

        public Input(long now, boolean networkAvailable, boolean socketConnected,
                     String transportState, long lastQuoteAt, boolean dailySyncInProgress,
                     long lastAnalysisAt, int dataEligibleAssets, String lastError,
                     long lastErrorAt) {
            this.now = now;
            this.networkAvailable = networkAvailable;
            this.socketConnected = socketConnected;
            this.transportState = transportState == null ? "SYNCHRO" : transportState;
            this.lastQuoteAt = lastQuoteAt;
            this.dailySyncInProgress = dailySyncInProgress;
            this.lastAnalysisAt = lastAnalysisAt;
            this.dataEligibleAssets = dataEligibleAssets;
            this.lastError = lastError == null ? "" : lastError;
            this.lastErrorAt = lastErrorAt;
        }
    }

    private V4RuntimeStatusPolicy() {}

    public static State evaluate(Input input) {
        if (!input.networkAvailable) return State.HORS_LIGNE;
        if ("HORS LIGNE".equals(input.transportState)) return State.HORS_LIGNE;
        if (!input.socketConnected) return State.SYNCHRO;
        if (input.lastQuoteAt <= 0) return State.SYNCHRO;
        if (input.now - input.lastQuoteAt > QUOTE_FRESH_MS) return State.HORS_LIGNE;
        if (!input.lastError.isEmpty()) {
            if (input.lastErrorAt > 0 && input.now - input.lastErrorAt >= PERSISTENT_ERROR_MS) {
                return State.HORS_LIGNE;
            }
            return State.SYNCHRO;
        }
        if (input.dailySyncInProgress || input.lastAnalysisAt <= 0 || input.dataEligibleAssets <= 0) {
            return State.SYNCHRO;
        }
        return State.ACTIF;
    }

    public static long quoteAgeMs(long now, long lastQuoteAt) {
        return lastQuoteAt <= 0 ? -1 : Math.max(0, now - lastQuoteAt);
    }
}
