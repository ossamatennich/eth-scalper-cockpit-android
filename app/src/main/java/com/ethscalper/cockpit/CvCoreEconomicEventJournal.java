package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical, bounded recorder for CV Core economic events.
 *
 * <p>The service persists the remembered keys separately so a restored process cannot recreate
 * an opening or terminal. This class remains Android-free and is the only place that increments
 * both {@link MarketDiagnosticRecorder} and {@link CvCoreSummary} economic counters.</p>
 */
public final class CvCoreEconomicEventJournal {
    public static final int MAX_KEYS = 512;
    private final MarketDiagnosticRecorder recorder;
    private final CvCoreSummary summary;
    private final Deque<String> order = new ArrayDeque<>();
    private final Set<String> keys = new LinkedHashSet<>();

    public CvCoreEconomicEventJournal(MarketDiagnosticRecorder recorder, CvCoreSummary summary) {
        if (recorder == null || summary == null) throw new IllegalArgumentException("dependencies");
        this.recorder = recorder;
        this.summary = summary;
    }

    public synchronized Result recordOpen(long at, CvCoreEngine.Result source, CvCorePlan plan,
                                          SignalDecision signal, MarketSnapshot snapshot,
                                          boolean marketFresh, boolean btcFresh,
                                          Map<String,Object> extra) {
        if (plan == null || signal == null) return Result.rejected("");
        String key = openKey(CvCorePolicy.ENGINE_ID, plan.signature);
        if (keys.contains(key)) return Result.duplicate(key);
        LinkedHashMap<String,Object> details = details(source, plan, at, extra);
        details.put("persisted", true);
        details.put("economicEventKey", key);
        MarketDiagnosticRecorder.Record record = recorder.record(at, "CV_CORE_PLAN_PERSISTED",
                "CV_CORE_PLAN_PERSISTED", "Plan CV Core persisté atomiquement.", "CV_CORE_V1",
                "", string(details.get("sourceSleeve")), signal, snapshot,
                Math.max(0L, at - plan.qualificationAt), marketFresh, btcFresh, 0, details);
        remember(key);
        summary.opened();
        return Result.recorded(key, record);
    }

    public synchronized Result recordTerminal(long at, CvCorePlan plan, CvCorePlan.Terminal terminal,
                                              SignalDecision signal, MarketSnapshot snapshot,
                                              boolean marketFresh, boolean btcFresh) {
        if (plan == null || terminal == null || signal == null) return Result.rejected("");
        String type = CvCorePlan.TP.equals(terminal.status)
                ? "CV_CORE_TP_TOUCHED" : "CV_CORE_SL_TOUCHED";
        String key = terminalKey(CvCorePolicy.ENGINE_ID, plan.signature, terminal.status);
        if (keys.contains(key)) return Result.duplicate(key);
        LinkedHashMap<String,Object> details = details(null, plan, at, null);
        details.put("economicEventKey", key);
        details.put("touchQuote", finite(terminal.touchQuote));
        details.put("fillPrice", finite(terminal.fillPrice));
        details.put("terminalStatus", terminal.status);
        details.put("terminalAt", terminal.terminalAt);
        details.put("resultR", finite(terminal.resultR));
        details.put("grossResultUsdt", finite(terminal.grossResultUsdt));
        details.put("estimatedFeesUsdt", finite(terminal.estimatedFeesUsdt));
        details.put("netResultUsdt", finite(terminal.netResultUsdt));
        MarketDiagnosticRecorder.Record record = recorder.record(at, type, terminal.status,
                "Remplissage CV Core au niveau planifié.", "CV_CORE_V1", "",
                string(details.get("sourceSleeve")), signal, snapshot,
                Math.max(0L, at - plan.qualificationAt), marketFresh, btcFresh, 0, details);
        remember(key);
        summary.terminal(terminal);
        return Result.recorded(key, record);
    }

    public synchronized void restore(Collection<String> remembered) {
        order.clear();
        keys.clear();
        if (remembered == null) return;
        for (String key : remembered) if (validKey(key)) remember(key);
    }

    public synchronized List<String> rememberedKeys() {
        return Collections.unmodifiableList(new ArrayList<>(order));
    }

    public synchronized int size() { return keys.size(); }

    public static String openKey(String engineId, String signature) {
        return validPart(engineId) && validPart(signature) ? "OPEN|" + engineId + "|" + signature : "";
    }

    public static String terminalKey(String engineId, String signature, String terminalStatus) {
        return validPart(engineId) && validPart(signature) && validPart(terminalStatus)
                ? "TERMINAL|" + engineId + "|" + signature + "|" + terminalStatus : "";
    }

    private void remember(String key) {
        if (!validKey(key) || keys.contains(key)) return;
        keys.add(key);
        order.addLast(key);
        while (order.size() > MAX_KEYS) keys.remove(order.removeFirst());
    }

    private static LinkedHashMap<String,Object> details(CvCoreEngine.Result source, CvCorePlan plan,
                                                        long at, Map<String,Object> extra) {
        LinkedHashMap<String,Object> out = new LinkedHashMap<>(CvCoreTelemetry.details(source, plan, at));
        if (extra != null) out.putAll(extra);
        return out;
    }

    private static boolean validKey(String key) {
        return validPart(key) && (key.startsWith("OPEN|") || key.startsWith("TERMINAL|"));
    }
    private static boolean validPart(String value) { return value != null && !value.isEmpty(); }
    private static Object finite(double value) { return Double.isFinite(value) ? value : null; }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }

    public static final class Result {
        public final boolean recorded;
        public final boolean duplicate;
        public final String key;
        public final MarketDiagnosticRecorder.Record record;
        private Result(boolean recorded, boolean duplicate, String key,
                       MarketDiagnosticRecorder.Record record) {
            this.recorded = recorded;
            this.duplicate = duplicate;
            this.key = key == null ? "" : key;
            this.record = record;
        }
        static Result recorded(String key, MarketDiagnosticRecorder.Record record) {
            return new Result(true, false, key, record);
        }
        static Result duplicate(String key) { return new Result(false, true, key, null); }
        static Result rejected(String key) { return new Result(false, false, key, null); }
    }
}
