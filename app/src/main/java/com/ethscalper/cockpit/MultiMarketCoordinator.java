package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Symbol-keyed coordinator: one active plan and one rearm clock per registered market. */
public final class MultiMarketCoordinator {
    private final MarketRegistry registry;
    private final LinkedHashMap<String, MarketRuntime> runtimes = new LinkedHashMap<>();

    public MultiMarketCoordinator(MarketRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry");
        this.registry = registry;
        for (MarketProfile profile : registry.tradedMarkets()) {
            runtimes.put(profile.symbol, new MarketRuntime(profile));
        }
    }

    public MarketRuntime runtime(String symbol) {
        MarketRuntime runtime = runtimes.get(symbol);
        if (runtime == null) throw new IllegalArgumentException("Unknown traded symbol: " + symbol);
        return runtime;
    }

    public Map<String, MarketRuntime> runtimes() {
        return Collections.unmodifiableMap(runtimes);
    }

    public boolean publish(String symbol, ActivePlanState state, long now) {
        MarketRuntime runtime = runtime(symbol);
        if (state == null || !symbol.equals(state.symbol) || !runtime.allowsNewPlan(now)) return false;
        runtime.activePlan = state;
        return true;
    }

    public boolean terminal(String symbol, String status, long now) {
        if (!SignalSafetyPolicies.isTerminalStatus(status)) return false;
        MarketRuntime runtime = runtime(symbol);
        if (!runtime.hasActivePlan()) return false;
        runtime.terminal(now,status);
        return true;
    }

    public int activePlanCount() {
        int count = 0;
        for (MarketRuntime runtime : runtimes.values()) if (runtime.hasActivePlan()) count++;
        return count;
    }

    public double aggregateModeledRisk() {
        double total = 0.0;
        for (MarketRuntime runtime : runtimes.values()) {
            if (runtime.hasActivePlan()) total += Math.max(0.0, runtime.activePlan.theoreticalMaximumLoss);
        }
        return total;
    }

    public MarketRegistry registry() { return registry; }
}
