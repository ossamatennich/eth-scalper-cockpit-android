package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class V4RuntimeStatusPolicyTest {
    private static final long NOW = 1_000_000L;

    @Test public void activeRequiresEveryLiveCondition() {
        assertEquals(V4RuntimeStatusPolicy.State.ACTIF, evaluate(true, true, "ACTIF",
                NOW - 15_000L, false, NOW - 1_000L, 53, "", 0));
    }

    @Test public void startupReconnectAndSyncRemainSynchronizing() {
        assertEquals(V4RuntimeStatusPolicy.State.SYNCHRO, evaluate(true, false, "SYNCHRO",
                0, false, 0, 0, "", 0));
        assertEquals(V4RuntimeStatusPolicy.State.SYNCHRO, evaluate(true, true, "SYNCHRO",
                0, false, NOW - 1_000L, 53, "", 0));
        assertEquals(V4RuntimeStatusPolicy.State.SYNCHRO, evaluate(true, true, "ACTIF",
                NOW - 1_000L, true, NOW - 1_000L, 53, "", 0));
        assertEquals(V4RuntimeStatusPolicy.State.SYNCHRO, evaluate(true, true, "ACTIF",
                NOW - 1_000L, false, 0, 53, "", 0));
    }

    @Test public void networkTransportStaleQuoteAndPersistentErrorAreOffline() {
        assertEquals(V4RuntimeStatusPolicy.State.HORS_LIGNE, evaluate(false, true, "ACTIF",
                NOW - 1_000L, false, NOW - 1_000L, 53, "", 0));
        assertEquals(V4RuntimeStatusPolicy.State.HORS_LIGNE, evaluate(true, true, "HORS LIGNE",
                NOW - 1_000L, false, NOW - 1_000L, 53, "", 0));
        assertEquals(V4RuntimeStatusPolicy.State.HORS_LIGNE, evaluate(true, true, "ACTIF",
                NOW - 15_001L, false, NOW - 1_000L, 53, "", 0));
        assertEquals(V4RuntimeStatusPolicy.State.HORS_LIGNE, evaluate(true, true, "ACTIF",
                NOW - 1_000L, false, NOW - 1_000L, 53, "sync failed", NOW - 30_000L));
    }

    @Test public void transientErrorSynchronizesAndQuoteAgeIsBounded() {
        assertEquals(V4RuntimeStatusPolicy.State.SYNCHRO, evaluate(true, true, "ACTIF",
                NOW - 1_000L, false, NOW - 1_000L, 53, "retrying", NOW - 2_000L));
        assertEquals(1_000L, V4RuntimeStatusPolicy.quoteAgeMs(NOW, NOW - 1_000L));
        assertEquals(-1L, V4RuntimeStatusPolicy.quoteAgeMs(NOW, 0));
    }

    private static V4RuntimeStatusPolicy.State evaluate(boolean network, boolean socket,
            String transport, long quote, boolean sync, long analysis, int eligible,
            String error, long errorAt) {
        return V4RuntimeStatusPolicy.evaluate(new V4RuntimeStatusPolicy.Input(NOW, network,
                socket, transport, quote, sync, analysis, eligible, error, errorAt));
    }
}
