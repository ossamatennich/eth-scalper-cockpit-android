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
    public static final long MAX_PENDING_AGE_MS = 90_000L;
    public static final long P02_MIN_CONFIRMATION_AGE_MS = 20_000L;
    public static final long P02_MAX_PENDING_AGE_MS = 45_000L;
    public static final String SLEEVE_P01 = "P01";
    public static final String SLEEVE_P02 = "P02";

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
        // Historical creation state can never authorize v2.33.0 publication.
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
        return processPendingCandidate(candidate, currentSnapshot, feedFresh,
                candidateCreatedAt, confirmationAt, targetProgressBeforeFill,
                adverseExcursion60, historicalReplayRiskVeto, SLEEVE_P01, null);
    }

    public static FillResult processPendingCandidate(
            SignalDecision candidate, MarketSnapshot currentSnapshot, boolean feedFresh,
            long candidateCreatedAt, long confirmationAt, double targetProgressBeforeFill,
            double adverseExcursion60, boolean historicalReplayRiskVeto,
            String sleeve, TrendRegime60.Result trendRegime) {
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
        boolean p02 = SLEEVE_P02.equals(sleeve);
        if (age > (p02 ? P02_MAX_PENDING_AGE_MS : MAX_PENDING_AGE_MS)) {
            return FillResult.rejected(p02 ? P02SleeveFilter.EXPIRED
                    : P01SleeveFilter.AGE_EXPIRED, null);
        }
        if (p02 ? age <= P02_MIN_CONFIRMATION_AGE_MS : age < MIN_CONFIRMATION_AGE_MS) {
            return FillResult.rejected(p02 ? P02SleeveFilter.SILENT_WINDOW
                    : SILENT_CONFIRMATION_WINDOW, null);
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
                targetProgressBeforeFill, historicalReplayRiskVeto, adverseExcursion60,
                sleeve, trendRegime);
    }

    public static FillResult processPendingCandidate(
            MarketProfile profile, SignalDecision candidate, MarketSnapshot currentSnapshot,
            boolean feedFresh, long candidateCreatedAt, long confirmationAt,
            double targetProgressBeforeFill, double adverseExcursion60,
            boolean historicalReplayRiskVeto, String sleeve,
            TrendRegime60.Result trendRegime) {
        if (candidate == null || !candidate.isSignal() || !validPlan(candidate)) {
            return FillResult.rejected(INVALID_DATA, null);
        }
        if (!ContinuationConfirmation.requiresP01(candidate.family)) {
            return FillResult.rejected(RANGE_FADE_DIAGNOSTIC_ONLY, null);
        }
        if (feedFresh && targetReachedBeforeConfirmedFill(candidate, currentSnapshot)) {
            return FillResult.rejected(TARGET_BEFORE_FILL, null);
        }
        long age=Math.max(0L,confirmationAt-candidateCreatedAt);
        boolean p02=SLEEVE_P02.equals(sleeve);
        if (age>(p02?P02_MAX_PENDING_AGE_MS:MAX_PENDING_AGE_MS)) {
            return FillResult.rejected(p02?P02SleeveFilter.EXPIRED:P01SleeveFilter.AGE_EXPIRED,null);
        }
        if (p02?age<=P02_MIN_CONFIRMATION_AGE_MS:age<MIN_CONFIRMATION_AGE_MS) {
            return FillResult.rejected(p02?P02SleeveFilter.SILENT_WINDOW:SILENT_CONFIRMATION_WINDOW,null);
        }
        if (currentSnapshot==null||currentSnapshot.now!=confirmationAt) return FillResult.rejected(FRESH_SNAPSHOT_REQUIRED,null);
        if (!feedFresh) return FillResult.rejected(profile.staleReasonCode,null);
        if (!currentlyExecutable(candidate,currentSnapshot)) return FillResult.rejected(LIMIT_NOT_EXECUTABLE,null);
        return processAtFill(profile,candidate,currentSnapshot,true,candidateCreatedAt,
                targetProgressBeforeFill,historicalReplayRiskVeto,adverseExcursion60,
                sleeve,trendRegime);
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
        return confirmAtFill(candidate, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill, historicalReplayRiskVeto, adverseExcursion60,
                SLEEVE_P01, null);
    }

    public static FillResult confirmAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto,
                                           double adverseExcursion60, String sleeve,
                                           TrendRegime60.Result trendRegime) {
        return confirmAtFill(MarketProfile.eth(), candidate, snapshot, feedFresh,
                candidateCreatedAt, targetProgressBeforeFill, historicalReplayRiskVeto,
                adverseExcursion60, sleeve, trendRegime);
    }

    public static FillResult confirmAtFill(MarketProfile profile, SignalDecision candidate,
                                           MarketSnapshot snapshot, boolean feedFresh,
                                           long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto,
                                           double adverseExcursion60, String sleeve,
                                           TrendRegime60.Result trendRegime) {
        if (candidate == null || !candidate.isSignal() || !validPlan(candidate)) {
            return FillResult.rejected(INVALID_DATA, null);
        }

        boolean continuation = ContinuationConfirmation.requiresP01(candidate.family);
        if (!continuation) {
            return FillResult.rejected(RANGE_FADE_DIAGNOSTIC_ONLY, null);
        }
        ContinuationConfirmation.Result confirmation = ContinuationConfirmation.evaluate(
                profile, candidate.side, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill);
        if (!confirmation.confirmed) {
            return FillResult.rejected(confirmation.reasonCode, confirmation);
        }

        long ageMs = Math.max(0L, snapshot.now - candidateCreatedAt);
        NormalizedSignalMetrics.Result metrics = NormalizedSignalMetrics.calculate(
                profile, candidate.side, candidate, snapshot, adverseExcursion60);
        P01SleeveFilter.Result p01Filter = null;
        P02SleeveFilter.Result p02Filter = null;
        boolean p02 = SLEEVE_P02.equals(sleeve);
        if (p02) {
            p02Filter = P02SleeveFilter.confirmation(metrics, ageMs);
            if (!p02Filter.accepted) {
                return FillResult.rejected(p02Filter.reasonCode, confirmation, null, null,
                        metrics, null, p02Filter, trendRegime, sleeve);
            }
            if (trendRegime == null || !trendRegime.accepted) {
                String reason = trendRegime == null
                        ? TrendRegime60.INSUFFICIENT : trendRegime.reasonCode;
                return FillResult.rejected(reason, confirmation, null, null,
                        metrics, null, p02Filter, trendRegime, sleeve);
            }
        } else {
            p01Filter = P01SleeveFilter.evaluate(metrics, ageMs);
            if (!p01Filter.accepted) {
                return FillResult.rejected(p01Filter.reasonCode, confirmation, null, null,
                        metrics, p01Filter, null, null, sleeve);
            }
        }

        boolean premium15m = confirmation.premium15m;
        String p02Mode = p02 && trendRegime != null ? trendRegime.mode : "";
        String finalFamily = p02
                ? "P02_" + p02Mode
                : candidate.family + " · P01" + (premium15m ? " · P01_PREMIUM_15M" : "");
        String finalText = p02 ? "P02 " + p02Mode + " confirmé" : premium15m
                ? "CONTINUATION confirmée — Qualité premium 15 min"
                : "CONTINUATION confirmée";

        ConfirmedSizing.Result sizing = ConfirmedSizing.computeConfirmedSizingQuantity(
                candidate, snapshot, confirmation, premium15m, historicalReplayRiskVeto);
        DynamicTradePlan.Result dynamicPlan = DynamicTradePlan.calculate(
                profile, candidate.side, candidate.entry, snapshot.avgRange20, adverseExcursion60,
                snapshot.recentHigh, snapshot.recentLow, sizing.finalQuantity);
        if (!dynamicPlan.valid) {
            return FillResult.rejected(dynamicPlan.reasonCode, confirmation, sizing, dynamicPlan,
                    metrics, p01Filter, p02Filter, trendRegime, sleeve);
        }
        String confirmedReason = p02
                ? "V2330_P02_" + p02Mode + "_DYNAMIC_PLAN_CONFIRMED"
                : "V2330_P01_DYNAMIC_PLAN_CONFIRMED";
        SignalDecision published = SignalDecision.confirmed(profile, candidate.side, finalFamily,
                confirmedReason, finalText, candidate.score,
                dynamicPlan.finalQuantity, candidate.entry,
                dynamicPlan.takeProfit, dynamicPlan.stopLoss,
                dynamicPlan.roundedTargetDistance, dynamicPlan.roundedStopDistance,
                candidate.impulse,
                candidate.resetConfirmed, candidate.movementOrigin,
                candidate.movementExtreme, candidate.movementDistance);
        return new FillResult(true, confirmedReason, published, premium15m,
                confirmation, sizing, dynamicPlan, metrics, p01Filter, p02Filter,
                trendRegime, sleeve, null);
    }

    /** Selective P01-only path used before the unchanged 15-second normal lifecycle gate. */
    public static FillResult processEarlyP01Candidate(
            SignalDecision candidate, MarketSnapshot snapshot, boolean feedFresh,
            long candidateCreatedAt, long confirmationAt, double targetProgressBeforeFill,
            double adverseExcursion60, boolean historicalReplayRiskVeto,
            boolean noActivePlan, boolean rearmComplete, double originalEntry,
            boolean stabilitySatisfied) {
        return processEarlyP01Candidate(MarketProfile.eth(), candidate, snapshot, feedFresh,
                candidateCreatedAt, confirmationAt, targetProgressBeforeFill,
                adverseExcursion60, historicalReplayRiskVeto, noActivePlan,
                rearmComplete, originalEntry, stabilitySatisfied);
    }

    public static FillResult processEarlyP01Candidate(
            MarketProfile profile, SignalDecision candidate, MarketSnapshot snapshot,
            boolean feedFresh, long candidateCreatedAt, long confirmationAt,
            double targetProgressBeforeFill, double adverseExcursion60,
            boolean historicalReplayRiskVeto, boolean noActivePlan,
            boolean rearmComplete, double originalEntry, boolean stabilitySatisfied) {
        if (candidate == null || snapshot == null || !candidate.isSignal() || !validPlan(candidate)
                || !ContinuationConfirmation.requiresP01(candidate.family)) {
            return FillResult.rejected(INVALID_DATA, null);
        }
        if (!currentlyExecutable(candidate, snapshot)) {
            return FillResult.earlyRejected(LIMIT_NOT_EXECUTABLE, null, null, null,
                    null, null, null);
        }
        String revalidation = entryRevalidationCode(candidate, snapshot, snapshot.ethLast);
        if (!revalidation.isEmpty()) {
            return FillResult.earlyRejected(revalidation, null, null, null,
                    null, null, null);
        }

        ContinuationConfirmation.Result confirmation = ContinuationConfirmation.evaluate(
                profile, candidate.side, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill);
        long ageMs = confirmationAt - candidateCreatedAt;
        NormalizedSignalMetrics.Result metrics = NormalizedSignalMetrics.calculate(
                profile, candidate.side, candidate, snapshot, adverseExcursion60);
        P01SleeveFilter.Result p01Filter = P01SleeveFilter.evaluate(metrics, ageMs);
        boolean premium15m = confirmation.premium15m;
        ConfirmedSizing.Result sizing = confirmation.confirmed
                ? ConfirmedSizing.computeConfirmedSizingQuantity(candidate, snapshot,
                        confirmation, premium15m, historicalReplayRiskVeto)
                : null;
        DynamicTradePlan.Result dynamicPlan = sizing == null ? null
                : DynamicTradePlan.calculate(profile, candidate.side, candidate.entry,
                        snapshot.avgRange20, adverseExcursion60,
                        snapshot.recentHigh, snapshot.recentLow, sizing.finalQuantity);
        P01EarlyConfirmation.Result early = P01EarlyConfirmation.evaluate(
                candidate, SLEEVE_P01, ageMs, feedFresh,
                snapshot.now == confirmationAt, noActivePlan, rearmComplete,
                currentlyExecutable(candidate, snapshot), originalEntry,
                confirmation, metrics, p01Filter, dynamicPlan);
        if (!early.accepted) {
            String reason = confirmation.confirmed
                    ? P01EarlyConfirmation.REJECTED : confirmation.reasonCode;
            if (dynamicPlan != null && !dynamicPlan.valid) reason = dynamicPlan.reasonCode;
            return FillResult.earlyRejected(reason, confirmation, sizing, dynamicPlan,
                    metrics, p01Filter, early);
        }
        if (!stabilitySatisfied) {
            return FillResult.earlyRejected(P01EarlyConfirmation.STABILITY_PENDING,
                    confirmation, sizing, dynamicPlan, metrics, p01Filter, early);
        }

        String family = candidate.family + " · P01"
                + (premium15m ? " · P01_PREMIUM_15M" : "");
        String text = premium15m
                ? "CONTINUATION confirmée — Qualité premium 15 min"
                : "CONTINUATION confirmée";
        SignalDecision published = SignalDecision.confirmed(profile, candidate.side, family,
                P01EarlyConfirmation.CONFIRMED, text, candidate.score,
                dynamicPlan.finalQuantity, candidate.entry,
                dynamicPlan.takeProfit, dynamicPlan.stopLoss,
                dynamicPlan.roundedTargetDistance, dynamicPlan.roundedStopDistance,
                candidate.impulse, candidate.resetConfirmed, candidate.movementOrigin,
                candidate.movementExtreme, candidate.movementDistance);
        return new FillResult(true, P01EarlyConfirmation.CONFIRMED, published, premium15m,
                confirmation, sizing, dynamicPlan, metrics, p01Filter, null,
                null, SLEEVE_P01, early);
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
        return processAtFill(candidate, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill, historicalReplayRiskVeto, adverseExcursion60,
                SLEEVE_P01, null);
    }

    public static FillResult processAtFill(SignalDecision candidate, MarketSnapshot snapshot,
                                           boolean feedFresh, long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto,
                                           double adverseExcursion60, String sleeve,
                                           TrendRegime60.Result trendRegime) {
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
                adverseExcursion60, sleeve, trendRegime);
    }

    public static FillResult processAtFill(MarketProfile profile, SignalDecision candidate,
                                           MarketSnapshot snapshot, boolean feedFresh,
                                           long candidateCreatedAt,
                                           double targetProgressBeforeFill,
                                           boolean historicalReplayRiskVeto,
                                           double adverseExcursion60, String sleeve,
                                           TrendRegime60.Result trendRegime) {
        if (!currentlyExecutable(candidate, snapshot)) {
            return FillResult.rejected(LIMIT_NOT_EXECUTABLE, null);
        }
        String revalidation = entryRevalidationCode(profile, candidate, snapshot,
                snapshot == null ? Double.NaN : snapshot.marketLast);
        if (!revalidation.isEmpty()) return FillResult.rejected(revalidation, null);
        return confirmAtFill(profile, candidate, snapshot, feedFresh, candidateCreatedAt,
                targetProgressBeforeFill, historicalReplayRiskVeto, adverseExcursion60,
                sleeve, trendRegime);
    }

    public static String entryRevalidationCode(SignalDecision candidate, MarketSnapshot snapshot,
                                               double price) {
        return entryRevalidationCode(MarketProfile.eth(), candidate, snapshot, price);
    }

    public static String entryRevalidationCode(MarketProfile profile,
                                               SignalDecision candidate,
                                               MarketSnapshot snapshot, double price) {
        if (candidate == null || snapshot == null) return "DONNEES_MANQUANTES";

        int side = "LONG".equals(candidate.side) ? 1 : "SHORT".equals(candidate.side) ? -1 : 0;
        if (side == 0) return "COTE_INVALIDE";

        double minimumStop = profile.scaledMinimum(
                profile.revalidationMinimumStopReference, candidate.entry);
        double maximumAdverse = profile.scaledMaximum(
                profile.revalidationMaximumAdverseReference, candidate.entry, minimumStop);
        double stop = Math.max(minimumStop, candidate.stopDistance);
        double adverse = adverseMove(candidate, price);
        if (adverse >= Math.min(maximumAdverse, stop * 0.24)) {
            return "PRIX_DEJA_TROP_LOIN";
        }

        double avg = Math.max(profile.scaledMinimum(profile.aMinimumReference,
                candidate.entry), snapshot.avgRange20);
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
        public final NormalizedSignalMetrics.Result normalizedMetrics;
        public final P01SleeveFilter.Result p01SleeveFilter;
        public final P02SleeveFilter.Result p02SleeveFilter;
        public final TrendRegime60.Result trendRegime60;
        public final String sleeve;
        public final P01EarlyConfirmation.Result earlyP01;

        private FillResult(boolean confirmed, String reasonCode, SignalDecision publishedSignal,
                           boolean premium15m,
                           ContinuationConfirmation.Result continuationConfirmation,
                           ConfirmedSizing.Result sizing,
                           DynamicTradePlan.Result dynamicPlan,
                           NormalizedSignalMetrics.Result normalizedMetrics,
                           P01SleeveFilter.Result p01SleeveFilter,
                           P02SleeveFilter.Result p02SleeveFilter,
                           TrendRegime60.Result trendRegime60, String sleeve,
                           P01EarlyConfirmation.Result earlyP01) {
            this.confirmed = confirmed;
            this.reasonCode = reasonCode;
            this.publishedSignal = publishedSignal;
            this.premium15m = premium15m;
            this.continuationConfirmation = continuationConfirmation;
            this.sizing = sizing;
            this.dynamicPlan = dynamicPlan;
            this.normalizedMetrics = normalizedMetrics;
            this.p01SleeveFilter = p01SleeveFilter;
            this.p02SleeveFilter = p02SleeveFilter;
            this.trendRegime60 = trendRegime60;
            this.sleeve = sleeve == null ? SLEEVE_P01 : sleeve;
            this.earlyP01 = earlyP01;
        }

        private static FillResult rejected(String reasonCode,
                                           ContinuationConfirmation.Result confirmation) {
            return new FillResult(false, reasonCode, null, false, confirmation, null, null,
                    null, null, null, null, SLEEVE_P01, null);
        }

        private static FillResult rejected(String reasonCode,
                                           ContinuationConfirmation.Result confirmation,
                                           ConfirmedSizing.Result sizing,
                                           DynamicTradePlan.Result dynamicPlan) {
            return new FillResult(false, reasonCode, null, false, confirmation, sizing,
                    dynamicPlan, null, null, null, null, SLEEVE_P01, null);
        }

        private static FillResult rejected(String reasonCode,
                                           ContinuationConfirmation.Result confirmation,
                                           ConfirmedSizing.Result sizing,
                                           DynamicTradePlan.Result dynamicPlan,
                                           NormalizedSignalMetrics.Result metrics,
                                           P01SleeveFilter.Result p01Filter,
                                           P02SleeveFilter.Result p02Filter,
                                           TrendRegime60.Result trendRegime,
                                           String sleeve) {
            return new FillResult(false, reasonCode, null, false, confirmation, sizing,
                    dynamicPlan, metrics, p01Filter, p02Filter, trendRegime, sleeve, null);
        }

        private static FillResult earlyRejected(String reasonCode,
                                                ContinuationConfirmation.Result confirmation,
                                                ConfirmedSizing.Result sizing,
                                                DynamicTradePlan.Result dynamicPlan,
                                                NormalizedSignalMetrics.Result metrics,
                                                P01SleeveFilter.Result p01Filter,
                                                P01EarlyConfirmation.Result earlyP01) {
            return new FillResult(false, reasonCode, null, false, confirmation, sizing,
                    dynamicPlan, metrics, p01Filter, null, null, SLEEVE_P01, earlyP01);
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
