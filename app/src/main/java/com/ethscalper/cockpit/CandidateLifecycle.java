package com.ethscalper.cockpit;

/**
 * End-to-end, side-effect-free candidate path used by MarketWatchService and integration tests.
 *
 * CONTINUATION replay vetoes are retained as comparative diagnostics, but P01 is the final
 * authority at entry touch. RANGE_FADE keeps the legacy replay protections.
 */
public final class CandidateLifecycle {
    public static final String REPLAY_RISK_DIAGNOSTIC = "V232_REPLAY_RISK_VETO";
    public static final String INVALID_DATA = "V2327_CANDIDATE_DATA_INVALID";
    public static final String OPPOSITE_ACTIVE = "V230_SCENARIO_MEMORY_VETO";

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
        boolean continuation = ContinuationConfirmation.requiresP01(rawCandidate.family);
        if (!continuation && hasReplayVeto) {
            return rejected(rawCandidate, REPLAY_RISK_DIAGNOSTIC,
                    "Veto replay v2.32 : " + replayDetail);
        }

        return new AdmissionResult(rawCandidate, true,
                hasReplayVeto ? REPLAY_RISK_DIAGNOSTIC : "", replayDetail);
    }

    public static boolean readyForImmediateConfirmation(boolean marketableAtCreation,
                                                        boolean entryTouched) {
        return marketableAtCreation || entryTouched;
    }

    public static FillResult confirmAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill) {
        if (candidate == null || !candidate.isSignal() || !validPlan(candidate)) {
            return FillResult.rejected(INVALID_DATA, null);
        }

        boolean continuation = ContinuationConfirmation.requiresP01(candidate.family);
        ContinuationConfirmation.Result confirmation = null;
        if (continuation) {
            confirmation = ContinuationConfirmation.evaluate(candidate.side, snapshot, feedFresh,
                    candidateCreatedAt, targetProgressBeforeFill);
            if (!confirmation.confirmed) {
                return FillResult.rejected(confirmation.reasonCode, confirmation);
            }
        }

        boolean premium15m = confirmation != null && confirmation.premium15m;
        String finalFamily = candidate.family;
        String finalCode;
        String finalText;
        if (continuation) {
            finalFamily += " · P01" + (premium15m ? " · P01_PREMIUM_15M" : "");
            finalCode = ContinuationConfirmation.P01_CONFIRMED;
            finalText = premium15m
                    ? "CONTINUATION confirmée — Qualité premium 15 min"
                    : "CONTINUATION confirmée";
        } else {
            finalCode = "RANGE_FADE_CONFIRMED_AT_FILL";
            finalText = "RANGE_FADE confirmé au niveau d'entrée";
        }

        int quantity = SignalEngine.computeFinalConfirmedQuantity(candidate.score);
        SignalDecision published = SignalDecision.confirmed(candidate.side, finalFamily,
                finalCode, finalText, candidate.score, quantity,
                candidate.entry, candidate.takeProfit, candidate.stopLoss,
                candidate.targetMove, candidate.stopDistance, candidate.impulse,
                candidate.resetConfirmed, candidate.movementOrigin,
                candidate.movementExtreme, candidate.movementDistance);
        return new FillResult(true, finalCode, published, premium15m, confirmation);
    }

    public static FillResult processAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill) {
        String revalidation = entryRevalidationCode(candidate, snapshot,
                snapshot == null ? Double.NaN : snapshot.ethLast);
        if (!revalidation.isEmpty()) {
            return FillResult.rejected(revalidation, null);
        }
        return confirmAtFill(candidate, snapshot, feedFresh,
                candidateCreatedAt, targetProgressBeforeFill);
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
            return new TerminalResolution(false, 0L, Double.NaN, "",
                    SignalSafetyPolicies.result(false, entryTriggered, 0.0, 0.0,
                            signal == null ? 0 : signal.quantity, 0L),
                    SignalSafetyPolicies.isOpenActiveRisk(status, entryTriggered)
                            ? "OPEN_ACTIVE_RISK" : status);
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

        private FillResult(boolean confirmed, String reasonCode, SignalDecision publishedSignal,
                           boolean premium15m,
                           ContinuationConfirmation.Result continuationConfirmation) {
            this.confirmed = confirmed;
            this.reasonCode = reasonCode;
            this.publishedSignal = publishedSignal;
            this.premium15m = premium15m;
            this.continuationConfirmation = continuationConfirmation;
        }

        private static FillResult rejected(String reasonCode,
                                           ContinuationConfirmation.Result confirmation) {
            return new FillResult(false, reasonCode, null, false, confirmation);
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
