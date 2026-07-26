package com.ethscalper.cockpit;

/**
 * End-to-end, side-effect-free candidate path used by MarketWatchService and integration tests.
 *
 * CONTINUATION replay vetoes are retained as comparative diagnostics, but P01 is the final
 * authority at a fresh executable entry. RANGE_FADE remains diagnostic-only.
 */
public final class CandidateLifecycle {
    public static final String REPLAY_RISK_DIAGNOSTIC = "V232_REPLAY_RISK_VETO";
    public static final String INVALID_DATA = "V2327_CANDIDATE_DATA_INVALID";
    public static final String OPPOSITE_ACTIVE = "V230_SCENARIO_MEMORY_VETO";
    public static final String SILENT_CONFIRMATION_WINDOW = "V2329_SILENT_P01_CONFIRMATION_WINDOW";
    public static final String PENDING_EXPIRED = "V2329_PENDING_CANDIDATE_EXPIRED";
    public static final String TARGET_BEFORE_FILL = "V2329_TARGET_REACHED_BEFORE_CONFIRMED_FILL";
    public static final String RANGE_FADE_DIAGNOSTIC_ONLY = "V2329_RANGE_FADE_DIAGNOSTIC_ONLY";
    public static final String RANGE_FADE_DIAGNOSTIC_TEXT =
            "RANGE_FADE conservé pour calibration — aucune publication finale.";
    public static final String LIMIT_NOT_EXECUTABLE = "V2329_LIMIT_NOT_EXECUTABLE_NOW";
    public static final String FRESH_SNAPSHOT_REQUIRED = "V2329_FRESH_SNAPSHOT_REQUIRED";
    public static final long MIN_CONFIRMATION_AGE_MS = 15_000L;
    public static final long MAX_PENDING_AGE_MS = 120_000L;

    private CandidateLifecycle() {}

    public static AdmissionResult admit(SignalDecision rawCandidate, boolean feedFresh,
                                        boolean oppositeScenarioActive,
                                        String replayRiskVetoDetail) {
        if (rawCandidate == null || !rawCandidate.isSignal()) {
            return new AdmissionResult(rawCandidate, false, "", "");
        }
        if (!feedFresh) {
            return rejected(rawCandidate, ContinuationConfirmation.P01_STALE_REJECT,
                    "Feed ETH périmé : aucun nouveau candidat");
        }
        if (!validPlan(rawCandidate)) {
            return rejected(rawCandidate, INVALID_DATA,
                    "Plan candidat incomplet ou incohérent");
        }
        if (oppositeScenarioActive) {
            return rejected(rawCandidate, OPPOSITE_ACTIVE,
                    "Scénario opposé réellement actif");
        }

        String replayDetail = replayRiskVetoDetail == null ? "" : replayRiskVetoDetail;
        boolean hasReplayVeto = !replayDetail.isEmpty();
        return new AdmissionResult(rawCandidate, true,
                hasReplayVeto ? REPLAY_RISK_DIAGNOSTIC : "", replayDetail);
    }

    public static boolean readyForImmediateConfirmation(boolean marketableAtCreation,
                                                        boolean entryTouched) {
        // Historical creation state can never authorize v2.32.9 publication.
        return false;
    }

    public static boolean currentlyExecutable(SignalDecision candidate, MarketSnapshot snapshot) {
        if (candidate == null || snapshot == null) return false;
        if ("LONG".equals(candidate.side)) {
            return finitePositive(snapshot.ethAsk) && snapshot.ethAsk <= candidate.entry;
        }
        if ("SHORT".equals(candidate.side)) {
            return finitePositive(snapshot.ethBid) && snapshot.ethBid >= candidate.entry;
        }
        return false;
    }

    public static boolean targetReachedBeforeConfirmedFill(SignalDecision candidate,
                                                            MarketSnapshot snapshot) {
        if (candidate == null || snapshot == null) return false;
        if ("LONG".equals(candidate.side)) {
            return finitePositive(snapshot.ethBid) && snapshot.ethBid >= candidate.takeProfit;
        }
        if ("SHORT".equals(candidate.side)) {
            return finitePositive(snapshot.ethAsk) && snapshot.ethAsk <= candidate.takeProfit;
        }
        return false;
    }

    public static FillResult processPendingCandidate(
            SignalDecision candidate, MarketSnapshot currentSnapshot, boolean feedFresh,
            long candidateCreatedAt, long confirmationAt, double targetProgressBeforeFill,
            double adverseExcursion60, boolean historicalReplayRiskVeto) {
        if (candidate == null || !candidate.isSignal() || !validPlan(candidate)) {
            return FillResult.rejected(INVALID_DATA, null);
        }
        if (!ContinuationConfirmation.requiresP01(candidate.family)) {
            return FillResult.rejected(RANGE_FADE_DIAGNOSTIC_ONLY, null);
        }
        if (feedFresh && targetReachedBeforeConfirmedFill(candidate, currentSnapshot)) {
            return FillResult.rejected(TARGET_BEFORE_FILL, null);
        }

        long age = Math.max(0L, confirmationAt - candidateCreatedAt);
        if (age > MAX_PENDING_AGE_MS) {
            return FillResult.rejected(PENDING_EXPIRED, null);
        }
        if (age < MIN_CONFIRMATION_AGE_MS) {
            return FillResult.rejected(SILENT_CONFIRMATION_WINDOW, null);
        }
        if (currentSnapshot == null || currentSnapshot.now != confirmationAt) {
            return FillResult.rejected(FRESH_SNAPSHOT_REQUIRED, null);
        }
        if (!feedFresh) {
            return FillResult.rejected(ContinuationConfirmation.P01_STALE_REJECT, null);
        }
        if (!currentlyExecutable(candidate, currentSnapshot)) {
            return FillResult.rejected(LIMIT_NOT_EXECUTABLE, null);
        }
        return processAtFill(candidate, currentSnapshot, true, candidateCreatedAt,
                targetProgressBeforeFill, historicalReplayRiskVeto, adverseExcursion60);
    }

    public static FillResult confirmAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill) {
        return confirmAtFill(candidate, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill, false, 0.0);
    }

    public static FillResult confirmAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto) {
        return confirmAtFill(candidate, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill, historicalReplayRiskVeto, 0.0);
    }

    public static FillResult confirmAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto,
                                           double adverseExcursion60) {
        if (candidate == null || !candidate.isSignal() || !validPlan(candidate)) {
            return FillResult.rejected(INVALID_DATA, null);
        }

        boolean continuation = ContinuationConfirmation.requiresP01(candidate.family);
        if (!continuation) {
            return FillResult.rejected(RANGE_FADE_DIAGNOSTIC_ONLY, null);
        }
        ContinuationConfirmation.Result confirmation = ContinuationConfirmation.evaluate(
                candidate.side, snapshot, feedFresh, candidateCreatedAt, targetProgressBeforeFill);
        if (!confirmation.confirmed) {
            return FillResult.rejected(confirmation.reasonCode, confirmation);
        }

        boolean premium15m = confirmation.premium15m;
        String finalFamily = candidate.family + " · P01"
                + (premium15m ? " · P01_PREMIUM_15M" : "");
        String finalText = premium15m
                ? "CONTINUATION confirmée — Qualité premium 15 min"
                : "CONTINUATION confirmée";

        ConfirmedSizing.Result sizing = ConfirmedSizing.computeConfirmedSizingQuantity(
                candidate, snapshot, confirmation, premium15m, historicalReplayRiskVeto);
        DynamicTradePlan.Result dynamicPlan = DynamicTradePlan.calculate(
                candidate.side, candidate.entry, snapshot.avgRange20, adverseExcursion60,
                snapshot.recentHigh, snapshot.recentLow, sizing.finalQuantity);
        if (!dynamicPlan.valid) {
            return FillResult.rejected(dynamicPlan.reasonCode, confirmation, sizing, dynamicPlan);
        }
        SignalDecision published = SignalDecision.confirmed(candidate.side, finalFamily,
                DynamicTradePlan.CONFIRMED, finalText, candidate.score,
                dynamicPlan.finalQuantity, candidate.entry,
                dynamicPlan.takeProfit, dynamicPlan.stopLoss,
                dynamicPlan.targetDistance, dynamicPlan.stopRequired, candidate.impulse,
                candidate.resetConfirmed, candidate.movementOrigin,
                candidate.movementExtreme, candidate.movementDistance);
        return new FillResult(true, DynamicTradePlan.CONFIRMED, published, premium15m,
                confirmation, sizing, dynamicPlan);
    }

    public static FillResult processAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill) {
        return processAtFill(candidate, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill, false, 0.0);
    }

    public static FillResult processAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto) {
        return processAtFill(candidate, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill, historicalReplayRiskVeto, 0.0);
    }

    public static FillResult processAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto,
                                           double adverseExcursion60) {
        if (!currentlyExecutable(candidate, snapshot)) {
            return FillResult.rejected(LIMIT_NOT_EXECUTABLE, null);
        }
        String revalidation = entryRevalidationCode(candidate, snapshot,
                snapshot == null ? Double.NaN : snapshot.ethLast);
        if (!revalidation.isEmpty()) {
            return FillResult.rejected(revalidation, null);
        }
        return confirmAtFill(candidate, snapshot, feedFresh,
                candidateCreatedAt, targetProgressBeforeFill, historicalReplayRiskVeto,
                adverseExcursion60);
    }

    public static String entryRevalidationCode(SignalDecision candidate, MarketSnapshot snapshot,
                                               double price) {
        if (candidate == null || snapshot == null) return "DONNEES_MANQUANTES";

        int side = "LONG".equals(candidate.side) ? 1 : "SHORT".equals(candidate.side) ? -1 : 0;
        if (side == 0) return "COTE_INVALIDE";

        double stop = Math.max(0.10, candidate.stopDistance);
        double adverse = adverseMove(candidate, price);
        if (adverse >= Math.min(0.30, stop * 0.24)) {
            return "PRIX_DEJA_TROP_LOIN";
        }

        double avg = Math.max(0.35, snapshot.avgRange20);
        double move1 = side * snapshot.move1;
        double move3 = side * snapshot.move3;
        double move8 = side * snapshot.move8;
        double flow30 = side * snapshot.flow30;
        double flow60 = side * snapshot.flow60;
        double btc8 = side * snapshot.btcMove8;
        double rangePosition = Double.isFinite(snapshot.rangePosition)
                ? snapshot.rangePosition : 0.5;
        String family = candidate.family == null ? "" : candidate.family;

        if (family.contains("CONTINUATION")) {
            boolean extendedWithoutFreshFlow = move3 > avg * 2.80
                    && move8 > avg * 2.50
                    && flow30 < 0.05;
            if (extendedWithoutFreshFlow) return "CONTINUATION_CONSOMMEE";

            boolean flowPriceDivergence = flow60 > 1.50
                    && move3 < avg * 0.60
                    && move8 < avg * 0.80;
            if (flowPriceDivergence) return "CONTINUATION_DIVERGENCE_FLOW_PRIX";
        }

        if (family.contains("RANGE_FADE")) {
            if (side > 0) {
                boolean oppositeDrift = move8 < -avg * 1.30
                        && move3 < 0
                        && flow30 < 0;
                if (oppositeDrift) return "RANGE_FADE_LONG_DERIVE_OPPOSEE";

                boolean persistentTrend = move8 < -avg * 2.50
                        && (flow30 < 0 || btc8 < -0.0020);
                if (persistentTrend) return "RANGE_FADE_LONG_TENDANCE_PERSISTANTE";

                boolean noReboundWithBtc = move8 < -avg * 2.20
                        && move1 < 0
                        && btc8 < -0.0010
                        && flow60 < 0.10;
                if (noReboundWithBtc) return "RANGE_FADE_LONG_SANS_REBOND";
            } else {
                boolean notExtremeAndTrendAlive = move8 < -avg * 2.00
                        && rangePosition < 0.85
                        && flow30 < 0;
                if (notExtremeAndTrendAlive) return "RANGE_FADE_SHORT_PAS_ASSEZ_EXTREME";
            }
        }

        return "";
    }

    public static TerminalResolution resolveTerminal(String status, SignalDecision signal,
                                                     boolean entryTriggered, long now,
                                                     double markedPrice) {
        boolean terminal = SignalSafetyPolicies.isTerminalStatus(status);
        if (!terminal || signal == null) {
            boolean activeRisk = signal != null && entryTriggered;
            double markedMove = activeRisk ? favorableMove(signal, markedPrice) : 0.0;
            return new TerminalResolution(false, 0L, Double.NaN, "",
                    SignalSafetyPolicies.result(false, entryTriggered, 0.0, markedMove,
                            signal == null ? 0 : signal.quantity, 0L),
                    activeRisk ? "OPEN_ACTIVE_RISK" : status);
        }
        double exitPrice = SignalSafetyPolicies.terminalExitPrice(status, signal, markedPrice);
        double realizedMove = entryTriggered ? favorableMove(signal, exitPrice) : 0.0;
        SignalSafetyPolicies.RealizedAndLatentResult result =
                SignalSafetyPolicies.result(true, entryTriggered, realizedMove, 0.0,
                        signal.quantity, 0L);
        return new TerminalResolution(true, now, exitPrice, status, result, status);
    }

    private static AdmissionResult rejected(SignalDecision candidate, String code, String text) {
        return new AdmissionResult(SignalDecision.waiting(code, text,
                candidate == null ? 0 : candidate.score,
                candidate == null ? "" : candidate.impulse,
                candidate != null && candidate.resetConfirmed,
                candidate == null ? 0 : candidate.movementOrigin,
                candidate == null ? 0 : candidate.movementExtreme,
                candidate == null ? 0 : candidate.movementDistance,
                candidate != null && candidate.movementConsumed), false, "", "");
    }

    private static boolean validPlan(SignalDecision candidate) {
        if (candidate == null || !candidate.isSignal()) return false;
        if (!"LONG".equals(candidate.side) && !"SHORT".equals(candidate.side)) return false;
        if (candidate.family == null || candidate.family.trim().isEmpty()) return false;
        if (!finitePositive(candidate.entry)
                || !finitePositive(candidate.takeProfit)
                || !finitePositive(candidate.stopLoss)
                || !finitePositive(candidate.targetMove)
                || !finitePositive(candidate.stopDistance)) return false;
        if ("LONG".equals(candidate.side)) {
            return candidate.takeProfit > candidate.entry && candidate.stopLoss < candidate.entry;
        }
        return candidate.takeProfit < candidate.entry && candidate.stopLoss > candidate.entry;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static double adverseMove(SignalDecision signal, double price) {
        if (signal == null || !finitePositive(price)) return 99.0;
        if ("LONG".equals(signal.side)) return Math.max(0.0, signal.entry - price);
        if ("SHORT".equals(signal.side)) return Math.max(0.0, price - signal.entry);
        return 99.0;
    }

    private static double favorableMove(SignalDecision signal, double price) {
        if (signal == null || !finitePositive(price)) return 0.0;
        if ("LONG".equals(signal.side)) return price - signal.entry;
        if ("SHORT".equals(signal.side)) return signal.entry - price;
        return 0.0;
    }

    public static final class AdmissionResult {
        public final SignalDecision decision;
        public final boolean observed;
        public final String replayRiskReasonCode;
        public final String replayRiskDetail;

        private AdmissionResult(SignalDecision decision, boolean observed,
                                String replayRiskReasonCode, String replayRiskDetail) {
            this.decision = decision;
            this.observed = observed;
            this.replayRiskReasonCode = replayRiskReasonCode;
            this.replayRiskDetail = replayRiskDetail;
        }
    }

    public static final class FillResult {
        public final boolean confirmed;
        public final String reasonCode;
        public final SignalDecision publishedSignal;
        public final boolean premium15m;
        public final ContinuationConfirmation.Result continuationConfirmation;
        public final ConfirmedSizing.Result sizing;
        public final DynamicTradePlan.Result dynamicPlan;

        private FillResult(boolean confirmed, String reasonCode, SignalDecision publishedSignal,
                           boolean premium15m,
                           ContinuationConfirmation.Result continuationConfirmation,
                           ConfirmedSizing.Result sizing,
                           DynamicTradePlan.Result dynamicPlan) {
            this.confirmed = confirmed;
            this.reasonCode = reasonCode;
            this.publishedSignal = publishedSignal;
            this.premium15m = premium15m;
            this.continuationConfirmation = continuationConfirmation;
            this.sizing = sizing;
            this.dynamicPlan = dynamicPlan;
        }

        private static FillResult rejected(String reasonCode,
                                           ContinuationConfirmation.Result confirmation) {
            return new FillResult(false, reasonCode, null, false, confirmation, null, null);
        }

        private static FillResult rejected(String reasonCode,
                                           ContinuationConfirmation.Result confirmation,
                                           ConfirmedSizing.Result sizing,
                                           DynamicTradePlan.Result dynamicPlan) {
            return new FillResult(false, reasonCode, null, false, confirmation, sizing,
                    dynamicPlan);
        }
    }

    public static final class TerminalResolution {
        public final boolean terminalResolved;
        public final long exitAt;
        public final double exitPrice;
        public final String exitReason;
        public final SignalSafetyPolicies.RealizedAndLatentResult result;
        public final String executionClassification;

        private TerminalResolution(boolean terminalResolved, long exitAt, double exitPrice,
                                   String exitReason,
                                   SignalSafetyPolicies.RealizedAndLatentResult result,
                                   String executionClassification) {
            this.terminalResolved = terminalResolved;
            this.exitAt = exitAt;
            this.exitPrice = exitPrice;
            this.exitReason = exitReason;
            this.result = result;
            this.executionClassification = executionClassification;
        }
    }
}
