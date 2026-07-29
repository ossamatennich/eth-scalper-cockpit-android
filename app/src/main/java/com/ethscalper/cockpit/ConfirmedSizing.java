package com.ethscalper.cockpit;

/**
 * Conservative sizing based only on evidence observed at final confirmation.
 *
 * The historical engine score is recorded for comparison, but never changes the quantity.
 */
public final class ConfirmedSizing {
    public static final int BASE_QUANTITY = 3;
    public static final int MAX_QUANTITY = 7;
    public static final int REPLAY_VETO_MAX_QUANTITY = 5;
    public static final int RANGE_FADE_MAX_QUANTITY = 4;

    private ConfirmedSizing() {}

    public static Result computeConfirmedSizingQuantity(
            SignalDecision candidate,
            MarketSnapshot snapshot,
            ContinuationConfirmation.Result confirmation,
            boolean premium15m,
            boolean historicalReplayRiskVeto) {
        if (candidate == null || snapshot == null) {
            return Result.base(candidate == null ? 0 : candidate.score);
        }

        int direction = "LONG".equals(candidate.side) ? 1
                : "SHORT".equals(candidate.side) ? -1 : 0;
        if (direction == 0) return Result.base(candidate.score);

        double avg = Math.max(0.35, snapshot.avgRange20);
        double move1 = confirmation == null
                ? direction * snapshot.move1 : confirmation.move1Aligned;
        double move3 = confirmation == null
                ? direction * snapshot.move3 : confirmation.move3Aligned;
        double move8 = confirmation == null
                ? direction * snapshot.move8 : confirmation.move8Aligned;
        double move15 = confirmation == null
                ? direction * snapshot.move15 : confirmation.move15Aligned;
        double flow30 = confirmation == null
                ? direction * snapshot.flow30 : confirmation.flow30Aligned;
        double flow60 = direction * snapshot.flow60;
        double btcMove3 = direction * snapshot.btcMove3;
        double volumeRatio = snapshot.avgVolume20 > 0.0
                ? snapshot.lastVolume / snapshot.avgVolume20 : 0.0;

        double move1Threshold = avg * 0.70;
        double move3Threshold = avg * 1.50;
        boolean continuation = ContinuationConfirmation.requiresP01(candidate.family);
        boolean rangeFade = candidate.family != null && candidate.family.contains("RANGE_FADE");

        boolean move1Bonus = continuation && move1 >= move1Threshold;
        boolean move3Bonus = continuation && move3 >= move3Threshold;
        boolean premiumBonus = continuation && premium15m;
        boolean particularlyCleanContext = continuation
                && move8 >= avg * 1.25
                && flow30 >= 0.15
                && flow60 >= 0.10
                && volumeRatio >= 0.80
                && btcMove3 >= -0.00010;

        boolean cleanRangeFadeContext = rangeFade
                && move1 >= avg * 0.75
                && move3 >= avg * 0.50
                && move8 > -avg * 0.25
                && flow30 >= 0.10
                && volumeRatio >= 0.80;

        int evidencePoints = 0;
        if (move1Bonus) evidencePoints++;
        if (move3Bonus) evidencePoints++;
        if (premiumBonus) evidencePoints++;
        if (particularlyCleanContext || cleanRangeFadeContext) evidencePoints++;

        int maxAllowed = continuation ? MAX_QUANTITY
                : rangeFade ? RANGE_FADE_MAX_QUANTITY : BASE_QUANTITY;
        boolean replayCapApplied = continuation && historicalReplayRiskVeto;
        if (replayCapApplied) maxAllowed = Math.min(maxAllowed, REPLAY_VETO_MAX_QUANTITY);

        int rawQuantity = BASE_QUANTITY + evidencePoints;
        int finalQuantity = Math.max(BASE_QUANTITY, Math.min(maxAllowed, rawQuantity));
        boolean rangeFadeCapApplied = rangeFade && rawQuantity > RANGE_FADE_MAX_QUANTITY;

        return new Result(candidate.score, continuation ? "CONTINUATION_P01"
                : rangeFade ? "RANGE_FADE" : "OTHER",
                avg, move1, move3, move8, move15, flow30, flow60, btcMove3, volumeRatio,
                move1Threshold, move3Threshold,
                move1Bonus, move3Bonus, premiumBonus,
                particularlyCleanContext || cleanRangeFadeContext,
                evidencePoints, historicalReplayRiskVeto, replayCapApplied,
                rangeFadeCapApplied, maxAllowed, finalQuantity);
    }

    public static final class Result {
        public final int engineScoreDiagnosticOnly;
        public final String sizingFamily;
        public final int baseQuantity;
        public final int finalQuantity;
        public final int evidencePoints;
        public final int maxAllowedQuantity;
        public final double avgRange20;
        public final double move1Aligned;
        public final double move3Aligned;
        public final double move8Aligned;
        public final double move15Aligned;
        public final double flow30Aligned;
        public final double flow60Aligned;
        public final double btcMove3Aligned;
        public final double volumeRatio;
        public final double move1BonusThreshold;
        public final double move3BonusThreshold;
        public final boolean move1Bonus;
        public final boolean move3Bonus;
        public final boolean premium15mBonus;
        public final boolean cleanContextBonus;
        public final boolean historicalReplayRiskVeto;
        public final boolean replayRiskCapApplied;
        public final boolean rangeFadeCapApplied;

        private Result(int engineScoreDiagnosticOnly, String sizingFamily,
                       double avgRange20, double move1Aligned, double move3Aligned,
                       double move8Aligned, double move15Aligned, double flow30Aligned,
                       double flow60Aligned, double btcMove3Aligned, double volumeRatio,
                       double move1BonusThreshold, double move3BonusThreshold,
                       boolean move1Bonus, boolean move3Bonus, boolean premium15mBonus,
                       boolean cleanContextBonus, int evidencePoints,
                       boolean historicalReplayRiskVeto, boolean replayRiskCapApplied,
                       boolean rangeFadeCapApplied, int maxAllowedQuantity,
                       int finalQuantity) {
            this.engineScoreDiagnosticOnly = engineScoreDiagnosticOnly;
            this.sizingFamily = sizingFamily;
            this.baseQuantity = BASE_QUANTITY;
            this.finalQuantity = finalQuantity;
            this.evidencePoints = evidencePoints;
            this.maxAllowedQuantity = maxAllowedQuantity;
            this.avgRange20 = avgRange20;
            this.move1Aligned = move1Aligned;
            this.move3Aligned = move3Aligned;
            this.move8Aligned = move8Aligned;
            this.move15Aligned = move15Aligned;
            this.flow30Aligned = flow30Aligned;
            this.flow60Aligned = flow60Aligned;
            this.btcMove3Aligned = btcMove3Aligned;
            this.volumeRatio = volumeRatio;
            this.move1BonusThreshold = move1BonusThreshold;
            this.move3BonusThreshold = move3BonusThreshold;
            this.move1Bonus = move1Bonus;
            this.move3Bonus = move3Bonus;
            this.premium15mBonus = premium15mBonus;
            this.cleanContextBonus = cleanContextBonus;
            this.historicalReplayRiskVeto = historicalReplayRiskVeto;
            this.replayRiskCapApplied = replayRiskCapApplied;
            this.rangeFadeCapApplied = rangeFadeCapApplied;
        }

        private static Result base(int engineScore) {
            return new Result(engineScore, "INVALID", 0.0,
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, false, false, false, false,
                    0, false, false, false, BASE_QUANTITY, BASE_QUANTITY);
        }
    }
}
