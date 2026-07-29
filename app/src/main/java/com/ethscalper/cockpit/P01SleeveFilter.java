package com.ethscalper.cockpit;

/** Exact v2.33.0 P01 post-confirmation sleeve calibration. */
public final class P01SleeveFilter {
    private static final double EPS = 1e-12;
    public static final String CONFIRMED_EARLY = "V2330_P01_EARLY_CONFIRMED";
    public static final String CONFIRMED_DELAYED = "V2330_P01_DELAYED_CONFIRMED";
    public static final String INVALID = "V2330_P01_METRICS_INVALID";
    public static final String VR_HIGH = "V2330_P01_VOLUME_RATIO_HIGH";
    public static final String EARLY_ROOM_LOW = "V2330_P01_EARLY_ROOM_LOW";
    public static final String EARLY_M1_HIGH = "V2330_P01_EARLY_M1_HIGH";
    public static final String EARLY_F30_HIGH = "V2330_P01_EARLY_F30_HIGH";
    public static final String EARLY_ACCEPT_MISSING = "V2330_P01_EARLY_ACCEPT_MISSING";
    public static final String EARLY_CONSUMED = "V2330_P01_EARLY_CONSUMED";
    public static final String DELAYED_ROOM_LOW = "V2330_P01_DELAYED_ROOM_LOW";
    public static final String DELAYED_E_HIGH = "V2330_P01_DELAYED_E_HIGH";
    public static final String DELAYED_F30_HIGH = "V2330_P01_DELAYED_F30_HIGH";
    public static final String DELAYED_F60_HIGH = "V2330_P01_DELAYED_F60_HIGH";
    public static final String DELAYED_SUPPORT_MISSING = "V2330_P01_DELAYED_SUPPORT_MISSING";
    public static final String AGE_EXPIRED = "V2330_P01_AGE_OVER_90S";

    private P01SleeveFilter() {}

    public static Result evaluate(NormalizedSignalMetrics.Result m, long ageMs) {
        if (m == null || !m.valid || ageMs < 0L) return Result.reject(INVALID, "INVALID");
        if (above(m.volumeRatio, 3.00)) return Result.reject(VR_HIGH, phase(ageMs));
        if (ageMs <= 25_000L) {
            if (below(m.room, 1.60)) return Result.reject(EARLY_ROOM_LOW, "EARLY");
            if (above(m.m1, 1.80)) return Result.reject(EARLY_M1_HIGH, "EARLY");
            if (above(m.f30, 0.60)) return Result.reject(EARLY_F30_HIGH, "EARLY");
            boolean flowBacked = atLeast(m.f30, 0.20)
                    && (atMost(m.m3, 2.80) || atLeast(m.room, 2.50));
            boolean priceLed = atLeast(m.m1, 1.40) && atLeast(m.f30, 0.04)
                    && atMost(m.f60, 0.75);
            if (!(flowBacked || priceLed)) {
                return new Result(false, EARLY_ACCEPT_MISSING, "EARLY",
                        flowBacked, priceLed, false);
            }
            boolean consumed = above(m.m8, 2.50) && below(m.f30, 0.15);
            if (consumed) {
                return new Result(false, EARLY_CONSUMED, "EARLY",
                        flowBacked, priceLed, true);
            }
            return new Result(true, CONFIRMED_EARLY, "EARLY",
                    flowBacked, priceLed, false);
        }
        if (ageMs <= 90_000L) {
            if (below(m.room, 1.30)) return Result.reject(DELAYED_ROOM_LOW, "DELAYED");
            if (above(m.e, 0.80)) return Result.reject(DELAYED_E_HIGH, "DELAYED");
            if (above(m.f30, 0.60)) return Result.reject(DELAYED_F30_HIGH, "DELAYED");
            if (above(m.f60, 1.00)) return Result.reject(DELAYED_F60_HIGH, "DELAYED");
            boolean supported = atLeast(m.f30, 0.20)
                    || (atLeast(m.e, 0.20) && atMost(m.e, 0.80));
            if (!supported) return Result.reject(DELAYED_SUPPORT_MISSING, "DELAYED");
            return new Result(true, CONFIRMED_DELAYED, "DELAYED", false, false, false);
        }
        return Result.reject(AGE_EXPIRED, "EXPIRED");
    }

    private static String phase(long ageMs) {
        return ageMs <= 25_000L ? "EARLY" : ageMs <= 90_000L ? "DELAYED" : "EXPIRED";
    }

    private static boolean below(double value, double threshold) {
        return value < threshold - EPS;
    }
    private static boolean above(double value, double threshold) {
        return value > threshold + EPS;
    }
    private static boolean atLeast(double value, double threshold) { return !below(value, threshold); }
    private static boolean atMost(double value, double threshold) { return !above(value, threshold); }

    public static final class Result {
        public final boolean accepted;
        public final String reasonCode;
        public final String phase;
        public final boolean flowBacked;
        public final boolean priceLed;
        public final boolean rejectConsumed;

        private Result(boolean accepted, String reasonCode, String phase,
                       boolean flowBacked, boolean priceLed, boolean rejectConsumed) {
            this.accepted = accepted;
            this.reasonCode = reasonCode;
            this.phase = phase;
            this.flowBacked = flowBacked;
            this.priceLed = priceLed;
            this.rejectConsumed = rejectConsumed;
        }

        private static Result reject(String code, String phase) {
            return new Result(false, code, phase, false, false, false);
        }
    }
}
