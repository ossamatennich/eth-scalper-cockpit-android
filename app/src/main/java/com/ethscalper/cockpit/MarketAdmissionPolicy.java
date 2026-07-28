package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure admission policy for non-ETH traded runtimes. ETH keeps its historical pipeline. */
public final class MarketAdmissionPolicy {
    public static final String ACCEPTED = "V23401_MARKET_ADMISSION_ACCEPTED";
    public static final String INVALID = "V23401_MARKET_CANDIDATE_INVALID";
    public static final String BTC_STALE = "V2340_BTC_REFERENCE_FEED_STALE";
    public static final String ACTIVE = "V23401_ACTIVE_PLAN_FOR_SYMBOL";
    public static final String REARM = "V23401_SYMBOL_REARM_ACTIVE";
    public static final String OPPOSITE = "V23401_OPPOSITE_SCENARIO_MEMORY";
    public static final String DUPLICATE = "V23401_PENDING_CANDIDATE_DUPLICATE";
    public static final String TOMBSTONE = "V23401_CANDIDATE_TOMBSTONED";
    public static final String TARGET_CONSUMED = "V23401_CONTINUATION_ALREADY_CONSUMED";
    public static final String FLOW_CONFLICT = "V23401_CONTINUATION_FLOW_CONFLICT";
    public static final String MOVEMENT_CONFLICT = "V23401_CONTINUATION_MOVEMENT_CONFLICT";
    public static final String SOL_REPLAY_UNAVAILABLE =
            "V23401_SOL_HISTORICAL_REPLAY_MODEL_UNAVAILABLE";

    public enum Classification { STRUCTURAL_SHARED, ETH_HISTORICAL_ONLY }

    private static final List<RuleDefinition> RULES;
    static {
        List<RuleDefinition> rules = new ArrayList<>();
        rules.add(new RuleDefinition("VALID_CANDIDATE", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("TRADED_FEED_FRESH", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("BTC_FEED_FRESH", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("ONE_ACTIVE_PLAN_PER_SYMBOL", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("TERMINAL_REARM_PER_SYMBOL", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("OPPOSITE_SCENARIO_MEMORY", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("CANDIDATE_DEDUPLICATION", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("CANDIDATE_TOMBSTONES", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("CONTINUATION_CONSUMED", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("MOVEMENT_FLOW_CONFLICT", Classification.STRUCTURAL_SHARED));
        rules.add(new RuleDefinition("ETH_REPLAY_RISK_ARBITER", Classification.ETH_HISTORICAL_ONLY));
        RULES = Collections.unmodifiableList(rules);
    }

    private MarketAdmissionPolicy() {}

    public static Result evaluate(MarketProfile profile, SignalDecision candidate,
                                  MarketSnapshot snapshot, Context context) {
        if (profile == null || candidate == null || !candidate.isSignal()
                || snapshot == null || !profile.symbol.equals(candidate.symbol)
                || !validPlan(candidate)) return Result.reject(INVALID);
        if (!context.marketFeedFresh) return Result.reject(profile.staleReasonCode);
        if (!context.btcFeedFresh) return Result.reject(BTC_STALE);
        if (context.activePlan) return Result.reject(ACTIVE);
        if (!context.rearmComplete) return Result.reject(REARM);
        if (context.oppositeScenarioActive) return Result.reject(OPPOSITE);
        if (context.duplicate) return Result.reject(DUPLICATE);
        if (context.tombstoned) return Result.reject(TOMBSTONE);
        if (context.targetConsumed
                || CandidateLifecycle.targetReachedBeforeConfirmedFill(candidate, snapshot)) {
            return Result.reject(TARGET_CONSUMED);
        }

        ContinuationConfirmation.Result continuation = ContinuationConfirmation.evaluate(
                profile, candidate.side, snapshot, true, snapshot.now, 0.0);
        if (ContinuationConfirmation.C04_REJECT.equals(continuation.reasonCode)) {
            return Result.reject(FLOW_CONFLICT);
        }
        if (ContinuationConfirmation.C07_REJECT.equals(continuation.reasonCode)) {
            return Result.reject(MOVEMENT_CONFLICT);
        }

        String historicalDiagnostic = profile.adaptivePriceScale
                ? SOL_REPLAY_UNAVAILABLE : "";
        return new Result(true, ACCEPTED, Classification.STRUCTURAL_SHARED,
                false, historicalDiagnostic);
    }

    public static List<RuleDefinition> rules() { return RULES; }

    private static boolean validPlan(SignalDecision candidate) {
        if (!"LONG".equals(candidate.side) && !"SHORT".equals(candidate.side)) return false;
        if (!positive(candidate.entry) || !positive(candidate.takeProfit)
                || !positive(candidate.stopLoss) || !positive(candidate.targetMove)
                || !positive(candidate.stopDistance)) return false;
        return "LONG".equals(candidate.side)
                ? candidate.takeProfit > candidate.entry && candidate.stopLoss < candidate.entry
                : candidate.takeProfit < candidate.entry && candidate.stopLoss > candidate.entry;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    public static final class Context {
        public final boolean marketFeedFresh, btcFeedFresh, activePlan, rearmComplete;
        public final boolean oppositeScenarioActive, duplicate, tombstoned, targetConsumed;

        public Context(boolean marketFeedFresh, boolean btcFeedFresh, boolean activePlan,
                       boolean rearmComplete, boolean oppositeScenarioActive,
                       boolean duplicate, boolean tombstoned, boolean targetConsumed) {
            this.marketFeedFresh=marketFeedFresh;this.btcFeedFresh=btcFeedFresh;
            this.activePlan=activePlan;this.rearmComplete=rearmComplete;
            this.oppositeScenarioActive=oppositeScenarioActive;this.duplicate=duplicate;
            this.tombstoned=tombstoned;this.targetConsumed=targetConsumed;
        }
    }

    public static final class Result {
        public final boolean accepted;
        public final String reasonCode;
        public final Classification classification;
        public final boolean historicalReplayRiskVeto;
        public final String historicalDiagnosticCode;
        private Result(boolean accepted, String reasonCode, Classification classification,
                       boolean veto, String diagnostic) {
            this.accepted=accepted;this.reasonCode=reasonCode;this.classification=classification;
            historicalReplayRiskVeto=veto;historicalDiagnosticCode=diagnostic;
        }
        static Result reject(String code) {
            return new Result(false, code, Classification.STRUCTURAL_SHARED, false, "");
        }
    }

    public static final class RuleDefinition {
        public final String name;
        public final Classification classification;
        RuleDefinition(String name, Classification classification) {
            this.name=name;this.classification=classification;
        }
    }
}
