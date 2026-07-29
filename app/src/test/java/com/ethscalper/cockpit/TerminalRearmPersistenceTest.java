package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class TerminalRearmPersistenceTest {
    @Test public void blocksBeforeThreeMinutesAndAllowsExactlyAtBoundary() {
        long terminal = 1_000_000L;
        assertFalse(TerminalRearmPersistence.allowsNewCandidate(terminal, terminal));
        assertFalse(TerminalRearmPersistence.allowsNewCandidate(
                terminal + 179_999L, terminal));
        assertEquals(1L, TerminalRearmPersistence.remainingMs(
                terminal + 179_999L, terminal));
        assertTrue(TerminalRearmPersistence.allowsNewCandidate(
                terminal + 180_000L, terminal));
        assertEquals(0L, TerminalRearmPersistence.remainingMs(
                terminal + 180_000L, terminal));
    }

    @Test public void terminalTimestampPersistsAcrossNewInstance() {
        MemoryBackend backend = new MemoryBackend();
        assertTrue(new TerminalRearmPersistence(backend).save(1_234_567L));
        assertEquals(1_234_567L, new TerminalRearmPersistence(backend).restore());
        assertFalse(TerminalRearmPersistence.allowsNewCandidate(
                1_234_567L + 100_000L,
                new TerminalRearmPersistence(backend).restore()));
    }

    @Test public void corruptTimestampIsIgnoredWithoutCrash() {
        MemoryBackend backend = new MemoryBackend();
        backend.values.put(TerminalRearmPersistence.KEY_LAST_TERMINAL_AT, "corrupt");
        assertEquals(0L, new TerminalRearmPersistence(backend).restore());
    }

    private static final class MemoryBackend implements TerminalRearmPersistence.Backend {
        final Map<String, String> values = new HashMap<>();
        @Override public Map<String, String> read() { return new HashMap<>(values); }
        @Override public boolean write(Map<String, String> incoming) {
            values.clear(); values.putAll(incoming); return true;
        }
    }
}
