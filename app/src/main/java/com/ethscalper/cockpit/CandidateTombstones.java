package com.ethscalper.cockpit;

import java.util.HashSet;
import java.util.Set;

/** Prevents a missed-no-fill candidate signature from being resurrected later. */
public final class CandidateTombstones {
    private final Set<String> missedBeforeFill = new HashSet<>();

    public void markMissed(String signature) {
        if (signature != null && !signature.isEmpty()) missedBeforeFill.add(signature);
    }

    public boolean blocks(String signature) {
        return signature != null && missedBeforeFill.contains(signature);
    }

    public void clear() {
        missedBeforeFill.clear();
    }

    public int size() {
        return missedBeforeFill.size();
    }
}
