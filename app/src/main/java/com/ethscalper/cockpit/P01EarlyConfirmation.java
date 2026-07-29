package com.ethscalper.cockpit;

/** Pure v2.33.2 selective early-confirmation quality and stability evaluator. */
public final class P01EarlyConfirmation {
    public static final double EPS = 1e-12;
    public static final long MAX_EARLY_AGE_MS = 15_000L;
    public static final long REQUIRED_STABILITY_MS = 1_000L;

    public static final String GUARDED_CURRENT_P01 = "GUARDED_CURRENT_P01";
    public static final String STRUCTURE_LED = "STRUCTURE_LED";
    public static final String GUARDED_QUALITY = "V2332_P01_EARLY_GUARDED_QUALITY";
    public static final String STRUCTURE_QUALITY = "V2332_P01_EARLY_STRUCTURE_QUALITY";
    public static final String STABILITY_PENDING = "V2332_P01_EARLY_STABILITY_PENDING";
    public static final String CONFIRMED = "V2332_P01_EARLY_CONFIRMED";
    public static final String REJECTED = "V2332_P01_EARLY_REJECTED";

    private P01EarlyConfirmation() {}

    public static Result evaluate(SignalDecision candidate, String sleeve, long ageMs,
                                  boolean feedFresh, boolean snapshotFreshAndCausal,
                                  boolean noActivePlan, boolean rearmComplete,
                                  boolean currentlyExecutable, double originalEntry,
                                  ContinuationConfirmation.Result continuation,
                                  NormalizedSignalMetrics.Result metrics,
                                  P01SleeveFilter.Result p01Filter,
                                  DynamicTradePlan.Result dynamicPlan) {
        boolean common = candidate != null
                && ContinuationConfirmation.requiresP01(candidate.family)
                && CandidateLifecycle.SLEEVE_P01.equals(sleeve)
                && ageMs >= 0L && ageMs < MAX_EARLY_AGE_MS
                && feedFresh && snapshotFreshAndCausal && noActivePlan && rearmComplete
                && currentlyExecutable
                && finitePositive(candidate.entry)
                && Math.abs(candidate.entry - originalEntry) <= EPS
                && continuation != null && continuation.confirmed
                && metrics != null && metrics.valid
                && dynamicPlan != null && dynamicPlan.valid;
        if (!common) return Result.rejected();

        boolean guarded = p01Filter != null && p01Filter.accepted
                && atMost(metrics.m8, 4.00)
                && (atLeast(metrics.m8, -1.80) || atLeast(metrics.m1, 1.50))
                && (atMost(metrics.m8, 3.00) || atLeast(metrics.room, 2.40));
        if (guarded) return Result.accepted(GUARDED_CURRENT_P01, GUARDED_QUALITY);

        boolean structureLed = atLeast(metrics.m1, 0.85)
                && atMost(metrics.m1, 1.15)
                && atLeast(metrics.m3, 2.35)
                && atMost(metrics.m3, 2.90)
                && atLeast(metrics.m8, 0.70)
                && atLeast(metrics.room, 1.80)
                && atLeast(metrics.f30, 0.05)
                && atMost(metrics.f30, 0.20)
                && atLeast(metrics.f60, 0.08)
                && atMost(metrics.volumeRatio, 3.00);
        return structureLed
                ? Result.accepted(STRUCTURE_LED, STRUCTURE_QUALITY)
                : Result.rejected();
    }

    public static StabilityResult advance(long now, long qualitySince, String previousMode,
                                          Result quality) {
        if (quality == null || !quality.accepted || now < 0L) {
            return StabilityResult.reset(REJECTED);
        }
        String oldMode = previousMode == null ? "" : previousMode;
        if (qualitySince <= 0L || now < qualitySince || !quality.mode.equals(oldMode)) {
            return new StabilityResult(false, now, quality.mode, 0L, STABILITY_PENDING);
        }
        long stableFor = now - qualitySince;
        if (stableFor < REQUIRED_STABILITY_MS) {
            return new StabilityResult(false, qualitySince, quality.mode, stableFor,
                    STABILITY_PENDING);
        }
        return new StabilityResult(true, qualitySince, quality.mode, stableFor, CONFIRMED);
    }

    private static boolean below(double value, double threshold) {
        return !Double.isFinite(value) || value < threshold - EPS;
    }

    private static boolean above(double value, double threshold) {
        return !Double.isFinite(value) || value > threshold + EPS;
    }

    private static boolean atLeast(double value, double threshold) {
        return !below(value, threshold);
    }

    private static boolean atMost(double value, double threshold) {
        return !above(value, threshold);
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    public static final class Result {
        public final boolean accepted;
        public final String mode;
        public final String reasonCode;

        private Result(boolean accepted, String mode, String reasonCode) {
            this.accepted = accepted;
            this.mode = mode;
            this.reasonCode = reasonCode;
        }

        private static Result accepted(String mode, String reasonCode) {
            return new Result(true, mode, reasonCode);
        }

        private static Result rejected() {
            return new Result(false, "", REJECTED);
        }
    }

    public static final class StabilityResult {
        public final boolean confirmed;
        public final long qualitySince;
        public final String mode;
        public final long stabilityMs;
        public final String reasonCode;

        private StabilityResult(boolean confirmed, long qualitySince, String mode,
                                long stabilityMs, String reasonCode) {
            this.confirmed = confirmed;
            this.qualitySince = qualitySince;
            this.mode = mode;
            this.stabilityMs = stabilityMs;
            this.reasonCode = reasonCode;
        }

        public static StabilityResult reset(String reasonCode) {
            return new StabilityResult(false, 0L, "", 0L,
                    reasonCode == null ? REJECTED : reasonCode);
        }
    }
}
