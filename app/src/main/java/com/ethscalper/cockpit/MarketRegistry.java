package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered immutable registry used by service loops and test coordinators. */
public final class MarketRegistry {
    private final List<MarketProfile> tradedMarkets;
    private final Map<String, MarketProfile> bySymbol;

    public MarketRegistry(List<MarketProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) throw new IllegalArgumentException("profiles");
        LinkedHashMap<String, MarketProfile> ordered = new LinkedHashMap<>();
        for (MarketProfile profile : profiles) {
            if (profile == null || ordered.put(profile.symbol, profile) != null) {
                throw new IllegalArgumentException("Null or duplicate market profile");
            }
        }
        tradedMarkets = Collections.unmodifiableList(new ArrayList<>(ordered.values()));
        bySymbol = Collections.unmodifiableMap(ordered);
    }

    public static MarketRegistry production() {
        List<MarketProfile> profiles = new ArrayList<>();
        profiles.add(MarketProfile.eth());
        profiles.add(MarketProfile.sol());
        return new MarketRegistry(profiles);
    }

    public List<MarketProfile> tradedMarkets() { return tradedMarkets; }

    public MarketProfile require(String symbol) {
        MarketProfile profile = bySymbol.get(symbol);
        if (profile == null) throw new IllegalArgumentException("Unknown traded symbol: " + symbol);
        return profile;
    }

    public boolean contains(String symbol) { return bySymbol.containsKey(symbol); }
    public int size() { return tradedMarkets.size(); }
}
