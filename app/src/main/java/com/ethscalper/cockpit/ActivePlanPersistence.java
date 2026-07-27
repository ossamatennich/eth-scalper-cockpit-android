package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
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

    public boolean saveForMarket(ActivePlanState state) {
        if (state == null || !state.isValid()) return false;
        try {
            Map<String,String> all = new LinkedHashMap<>(backend.readAll());
            removePrefix(all, prefix(state.symbol));
            for (Map.Entry<String,String> entry : state.toMap().entrySet()) {
                all.put(prefix(state.symbol) + entry.getKey(), entry.getValue());
            }
            return backend.replaceAll(all);
        } catch (RuntimeException ignored) { return false; }
    }

    public RestoreResult restore() {
        try {
            Map<String, String> values = backend.readAll();
            if (values == null || values.isEmpty()) return RestoreResult.empty();
            if (!values.containsKey("entry")) {
                boolean namespaced=false;for(String key:values.keySet())if(key.startsWith("plan.")){namespaced=true;break;}
                return namespaced?restore(MarketProfile.ETH_SYMBOL):RestoreResult.invalid();
            }
            ActivePlanState state = ActivePlanState.fromMap(values);
            return state == null ? RestoreResult.invalid() : RestoreResult.restored(state);
        } catch (RuntimeException ignored) {
            return RestoreResult.invalid();
        }
    }

    public RestoreResult restore(String symbol) {
        try {
            Map<String,String> all=backend.readAll();
            Map<String,String> values=extract(all,prefix(symbol));
            if (values.isEmpty() && MarketProfile.ETH_SYMBOL.equals(symbol)
                    && all.containsKey("entry")) values=new LinkedHashMap<>(all);
            if (values.isEmpty()) return RestoreResult.empty();
            ActivePlanState state=ActivePlanState.fromMap(values);
            if (state==null || !symbol.equals(state.symbol)) return RestoreResult.invalid();
            return RestoreResult.restored(state);
        } catch (RuntimeException ignored) { return RestoreResult.invalid(); }
    }

    public List<RestoreResult> restoreAll(MarketRegistry registry) {
        List<RestoreResult> out=new ArrayList<>();
        for (MarketProfile profile:registry.tradedMarkets()) out.add(restore(profile.symbol));
        return out;
    }

    public boolean clearForTerminal(String status) {
        if (!SignalSafetyPolicies.isTerminalStatus(status)) return false;
        return clear();
    }

    public boolean clearForTerminal(String symbol,String status) {
        if (!SignalSafetyPolicies.isTerminalStatus(status)) return false;
        try {
            Map<String,String> all=new LinkedHashMap<>(backend.readAll());
            removePrefix(all,prefix(symbol));
            if (MarketProfile.ETH_SYMBOL.equals(symbol) && all.containsKey("entry")) {
                for (String key:new ArrayList<>(all.keySet())) if (!key.startsWith("plan.")) all.remove(key);
            }
            return backend.replaceAll(all);
        } catch (RuntimeException ignored) { return false; }
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

    public boolean clear(String symbol) {
        try {Map<String,String> all=new LinkedHashMap<>(backend.readAll());removePrefix(all,prefix(symbol));
            if(MarketProfile.ETH_SYMBOL.equals(symbol)&&all.containsKey("entry"))for(String key:new ArrayList<>(all.keySet()))if(!key.startsWith("plan."))all.remove(key);
            return backend.replaceAll(all);}catch(RuntimeException ignored){return false;}
    }

    public interface Backend {
        Map<String, String> readAll();
        boolean replaceAll(Map<String, String> values);
    }

    private static String prefix(String symbol) { return "plan."+symbol+"."; }
    private static Map<String,String> extract(Map<String,String> all,String prefix) {
        Map<String,String> out=new LinkedHashMap<>();
        if (all==null) return out;
        for (Map.Entry<String,String> entry:all.entrySet()) {
            if (entry.getKey().startsWith(prefix)) out.put(entry.getKey().substring(prefix.length()),entry.getValue());
        }
        return out;
    }
    private static void removePrefix(Map<String,String> all,String prefix) {
        for (String key:new ArrayList<>(all.keySet())) if (key.startsWith(prefix)) all.remove(key);
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
