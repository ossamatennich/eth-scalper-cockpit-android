package com.ethscalper.cockpit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Small in-memory index that makes a silent pending candidate unique by plan signature.
 * The first creation time and object are deliberately retained across market updates.
 */
public final class PendingCandidateIndex<T> {
    private final Map<String, Entry<T>> pending = new LinkedHashMap<>();

    public UpsertResult<T> upsert(String signature, long observedAt, Supplier<T> factory) {
        String key = signature == null ? "" : signature;
        Entry<T> existing = pending.get(key);
        if (existing != null) {
            existing.lastObservedAt = observedAt;
            existing.updateCount++;
            return new UpsertResult<>(existing.value, false, existing.createdAt,
                    existing.lastObservedAt, existing.updateCount);
        }

        T value = factory.get();
        Entry<T> created = new Entry<>(value, observedAt);
        pending.put(key, created);
        return new UpsertResult<>(value, true, observedAt, observedAt, 0);
    }

    public void remove(String signature) {
        pending.remove(signature == null ? "" : signature);
    }

    public int size() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
    }

    private static final class Entry<T> {
        final T value;
        final long createdAt;
        long lastObservedAt;
        int updateCount;

        Entry(T value, long createdAt) {
            this.value = value;
            this.createdAt = createdAt;
            this.lastObservedAt = createdAt;
        }
    }

    public static final class UpsertResult<T> {
        public final T value;
        public final boolean created;
        public final long createdAt;
        public final long lastObservedAt;
        public final int updateCount;

        private UpsertResult(T value, boolean created, long createdAt,
                             long lastObservedAt, int updateCount) {
            this.value = value;
            this.created = created;
            this.createdAt = createdAt;
            this.lastObservedAt = lastObservedAt;
            this.updateCount = updateCount;
        }
    }
}
