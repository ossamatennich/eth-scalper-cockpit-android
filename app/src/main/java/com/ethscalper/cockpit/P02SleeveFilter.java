package com.ethscalper.cockpit;

/** Pure exact-appearance, prefilter and confirmation rules for sleeve P02. */
public final class P02SleeveFilter {
    private static final double EPS = 1e-12;
    public static final String NONE = "NONE";
    public static final String PREFILTER_CONFIRMED = "V2330_P02_PREFILTER_CONFIRMED";
    public static final String CONFIRMED = "V2330_P02_CONFIRMATION_CONFIRMED";
    public static final String SILENT_WINDOW = "V2330_P02_SILENT_CONFIRMATION_WINDOW";
    public static final String EXPIRED = "V2330_P02_PENDING_CANDIDATE_EXPIRED";
    public static final String INVALID = "V2330_P02_METRICS_INVALID";

    private P02SleeveFilter() {}

    public static String setupCandidateFor(MarketSnapshot s) {
        if (s == null) return NONE;
        double threshold = Math.max(0.75, s.avgRange20 * 0.55);
        boolean c1Long = s.move1 > threshold && s.move3 > threshold * 1.15;
        boolean c1Short = s.move1 < -threshold && s.move3 < -threshold * 1.15;
        boolean c2Long = s.move3 > threshold * 1.35 && s.move1 > -s.avgRange20 * 0.25;
        boolean c2Short = s.move3 < -threshold * 1.35 && s.move1 < s.avgRange20 * 0.25;
        if (c1Long) return "C1_LONG";
        if (c1Short) return "C1_SHORT";
        if (c2Long) return "C2_LONG";
        if (c2Short) return "C2_SHORT";
        return NONE;
    }

    public static String sideFor(String setup) {
        return setup != null && setup.endsWith("_LONG") ? "LONG"
                : setup != null && setup.endsWith("_SHORT") ? "SHORT" : "";
    }

    public static Result prefilter(NormalizedSignalMetrics.Result m) {
        if (m == null || !m.valid) return Result.reject(INVALID);
        if (below(m.m1, -0.25)) return Result.reject("V2330_P02_PREFILTER_M1_LOW");
        if (above(m.m1, 0.90)) return Result.reject("V2330_P02_PREFILTER_M1_HIGH");
        if (below(m.m3, 1.00)) return Result.reject("V2330_P02_PREFILTER_M3_LOW");
        if (below(m.m8, 0.80)) return Result.reject("V2330_P02_PREFILTER_M8_LOW");
        if (below(m.f30, 0.00)) return Result.reject("V2330_P02_PREFILTER_F30_LOW");
        if (below(m.f60, 0.00)) return Result.reject("V2330_P02_PREFILTER_F60_LOW");
        if (above(m.room, 1.10)) return Result.reject("V2330_P02_PREFILTER_ROOM_HIGH");
        if (below(m.directionalEdge, 0.70)) return Result.reject("V2330_P02_PREFILTER_EDGE_LOW");
        if (below(m.volumeRatio, 0.05)) return Result.reject("V2330_P02_PREFILTER_VR_LOW");
        return Result.accept(PREFILTER_CONFIRMED);
    }

    public static Result confirmation(NormalizedSignalMetrics.Result m, long ageMs) {
        if (m == null || !m.valid || ageMs < 0L) return Result.reject(INVALID);
        if (ageMs <= 20_000L) return Result.reject(SILENT_WINDOW);
        if (ageMs > 45_000L) return Result.reject(EXPIRED);
        if (below(m.m1, 0.50)) return Result.reject("V2330_P02_CONFIRM_M1_LOW");
        if (above(m.m1, 0.80)) return Result.reject("V2330_P02_CONFIRM_M1_HIGH");
        if (below(m.m3, 1.20)) return Result.reject("V2330_P02_CONFIRM_M3_LOW");
        if (below(m.f30, 0.10)) return Result.reject("V2330_P02_CONFIRM_F30_LOW");
        if (above(m.room, 2.00)) return Result.reject("V2330_P02_CONFIRM_ROOM_HIGH");
        if (above(m.e, 0.80)) return Result.reject("V2330_P02_CONFIRM_E_HIGH");
        if (below(m.volumeRatio, 0.20)) return Result.reject("V2330_P02_CONFIRM_VR_LOW");
        if (above(m.volumeRatio, 3.00)) return Result.reject("V2330_P02_CONFIRM_VR_HIGH");
        return Result.accept(CONFIRMED);
    }

    public static final class SetupTracker {
        private String previous = NONE;

        public boolean observe(String current) {
            String normalized = current == null || current.isEmpty() ? NONE : current;
            boolean appeared = !NONE.equals(normalized) && !normalized.equals(previous);
            previous = normalized;
            return appeared;
        }

        public String previous() { return previous; }
    }

    private static boolean below(double value, double threshold) {
        return value < threshold - EPS;
    }
    private static boolean above(double value, double threshold) {
        return value > threshold + EPS;
    }

    public static final class Result {
        public final boolean accepted;
        public final String reasonCode;

        private Result(boolean accepted, String reasonCode) {
            this.accepted = accepted;
            this.reasonCode = reasonCode;
        }

        private static Result accept(String code) { return new Result(true, code); }
        private static Result reject(String code) { return new Result(false, code); }
    }
}
