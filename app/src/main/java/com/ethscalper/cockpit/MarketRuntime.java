package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.Deque;

/** Independent mutable state for exactly one traded symbol. */
public final class MarketRuntime {
    public final MarketProfile profile;
    public final SignalEngine signalEngine = new SignalEngine();
    public final Deque<MarketBar> candles = new ArrayDeque<>();
    public final Deque<AggTrade> aggTrades = new ArrayDeque<>();
    public final Deque<Object> observedSignals = new ArrayDeque<>();
    public final PendingCandidateIndex<Object> pendingCandidates = new PendingCandidateIndex<>();
    public final CandidateTombstones candidateTombstones = new CandidateTombstones();
    public final P02SleeveFilter.SetupTracker p02SetupTracker = new P02SleeveFilter.SetupTracker();
    public final Deque<MarketSnapshot> marketFrames = new ArrayDeque<>();
    private final Deque<String> recordedEngineDiagnosticKeys = new ArrayDeque<>();
    public final MarketDiagnosticRecorder recorder;

    public double last = Double.NaN, bid = Double.NaN, ask = Double.NaN;
    public long lastTickerAt, lastKlineAt, lastAggTradeAt, lastRestTickerAt, lastRestKlineAt;
    public long bookTickerMessages, klineMessages, aggTradeMessages;
    public long restKlineRefreshes, restTradeRefreshes;
    public long lastAggTradeId = -1L;
    public long lastP01ConfirmedAt, lastTerminalAt;
    public long lastPersistedRecorderSequence,lastPersistentFrameAt;
    public long lastRecordedEngineDiagnosticAt;
    public String lastTerminalStatus="";
    public SignalDecision lastSignal;
    public SignalDecision lastDecision;
    public ActivePlanState activePlan;
    public String aiStatus = "AI_OFF_ENGINE_COMPLETE";

    public MarketRuntime(MarketProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile = profile;
        this.recorder = new MarketDiagnosticRecorder(profile);
    }

    public boolean hasActivePlan() {
        return activePlan != null && "ACTIVE".equals(activePlan.status);
    }

    public boolean allowsNewPlan(long now) {
        return !hasActivePlan() && TerminalRearmPersistence.allowsNewCandidate(now, lastTerminalAt);
    }

    public long rearmRemainingMs(long now) {
        return TerminalRearmPersistence.remainingMs(now, lastTerminalAt);
    }

    public boolean claimPersistentFrameSlot(long now) {
        if(lastPersistentFrameAt>0
                &&now-lastPersistentFrameAt<PersistentMarketLog.FRAME_INTERVAL_MS)return false;
        lastPersistentFrameAt=now;return true;
    }

    public boolean rememberEngineDiagnostic(long timestamp,String code,String message) {
        String key=timestamp+"|"+(code==null?"":code)+"|"+(message==null?"":message);
        if(recordedEngineDiagnosticKeys.contains(key))return false;
        recordedEngineDiagnosticKeys.addLast(key);
        while(recordedEngineDiagnosticKeys.size()>160)recordedEngineDiagnosticKeys.removeFirst();
        lastRecordedEngineDiagnosticAt=Math.max(lastRecordedEngineDiagnosticAt,timestamp);
        return true;
    }

    public void terminal(long now) {
        terminal(now,"");
    }
    public void terminal(long now,String status) {
        activePlan = null;
        lastTerminalAt = now;
        lastTerminalStatus=status==null?"":status;
        p02SetupTracker.reset();
    }

    /** Clears diagnostics and pending state while preserving and re-inserting an active plan. */
    public void resetDiagnosticsPreservingActivePlan() {
        p02SetupTracker.reset();
        signalEngine.clearDiagnostics();
        observedSignals.clear();
        pendingCandidates.clear();
        candidateTombstones.clear();
        marketFrames.clear();
        recordedEngineDiagnosticKeys.clear();
        recorder.reset();
        recorder.record(System.currentTimeMillis(),"DIAGNOSTICS_RESET","V23402_DIAGNOSTICS_RESET",
                "Diagnostics et frames non actifs réinitialisés.","STRUCTURAL_SHARED","","",
                null,null,0,true,true,0,java.util.Collections.emptyMap());
        if (hasActivePlan()) {
            observedSignals.addLast(activePlan);
            recorder.record(System.currentTimeMillis(),"RESET_ACTIVE_PLAN_REINSERTED",
                    "V23402_DIAGNOSTICS_RESET_ACTIVE_PLAN_REINSERTED",
                    "Plan actif conservé et réinséré après reset.","STRUCTURAL_SHARED","","",
                    activePlan.toSignalDecision(),null,0,true,true,0,java.util.Map.of("quantity",activePlan.quantity,
                            "entry",activePlan.entry,"tp",activePlan.takeProfit,"sl",activePlan.stopLoss,
                            "riskBudgetUsdt",activePlan.qualityRiskBudget,
                            "resultCostPerUnit",activePlan.resultCostPerUnit,
                            "riskAllowancePerUnit",activePlan.riskAllowancePerUnit,
                            "theoreticalMaximumLoss",activePlan.theoreticalMaximumLoss));
        }
    }

    public static final class MarketBar {
        public final long openTime;
        public final double open, high, low, close, volume;
        public MarketBar(long openTime, double open, double high, double low,
                         double close, double volume) {
            this.openTime=openTime;this.open=open;this.high=high;this.low=low;
            this.close=close;this.volume=volume;
        }
    }

    public static final class AggTrade {
        public final long id, at;
        public final double price, quantity;
        public final boolean buyerMaker;
        public AggTrade(long id,long at,double price,double quantity,boolean buyerMaker) {
            this.id=id;this.at=at;this.price=price;this.quantity=quantity;this.buyerMaker=buyerMaker;
        }
    }
}
