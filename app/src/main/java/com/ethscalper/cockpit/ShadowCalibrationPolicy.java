package com.ethscalper.cockpit;

/** Pure thresholds for the v2.34.4.0 shadow-only calibration experiment. */
public final class ShadowCalibrationPolicy {
    public static final String VERSION = "SHADOW_V23440_20260801";
    public static final String P01_GUARD = "P01_FINAL_CONFIRMATION_GUARD";
    public static final String P02_GUARD = "P02_ANTI_EXHAUSTION";
    public static final String PULLBACK = "P01_PULLBACK_RESUMPTION";
    public static final String ETH_MID_VOL = "ETH_MID_VOL_TREND_EXPANSION";

    private static final double EPS = 1e-12;
    private ShadowCalibrationPolicy() {}

    public static Decision p01FinalGuard(int score, NormalizedSignalMetrics.Result m,
                                         P01SleeveFilter.Result filter,
                                         String entryRevalidationCode) {
        if (score < 95) return block("SHADOW_P01_SCORE_TOO_LOW");
        if (m == null || !m.valid) return block("SHADOW_P01_METRICS_INVALID");
        if (filter == null || !filter.accepted) return block("SHADOW_P01_PUBLIC_FILTER_REJECTED");
        if (isCriticalCurrentRevalidation(entryRevalidationCode))
            return block("SHADOW_P01_CURRENT_REVALIDATION_BLOCKED");
        if (m.volumeRatio > 1.50 + EPS) return block("SHADOW_P01_VOLUME_TOO_HIGH");
        if (m.room + EPS < 1.00) return block("SHADOW_P01_ROOM_TOO_LOW");
        if (m.f60 + EPS < .40) return block("SHADOW_P01_FLOW60_TOO_LOW");
        if (m.f60 > 1.00 + EPS) return block("SHADOW_P01_FLOW60_TOO_HIGH");
        if (m.directionalEdge > .60 + EPS) return block("SHADOW_P01_DIRECTIONAL_EDGE_EXHAUSTED");
        if (m.m8 > 3.50 + EPS) return block("SHADOW_P01_MOVE8_OVEREXTENDED");
        if (m.f30 + EPS < .15) return block("SHADOW_P01_FLOW30_TOO_LOW");
        if ("EARLY".equals(filter.phase)
                && !(filter.flowBacked || filter.priceLed))
            return block("SHADOW_P01_EARLY_NOT_FLOW_OR_PRICE_BACKED");
        return keep("SHADOW_P01_KEEP");
    }

    public static Decision p02AntiExhaustion(int score, NormalizedSignalMetrics.Result m) {
        if (m == null || !m.valid) return block("SHADOW_P02_METRICS_INVALID");
        if (m.volumeRatio + EPS < .30) return block("SHADOW_P02_VOLUME_TOO_LOW");
        if (m.room + EPS < .30) return block("SHADOW_P02_ROOM_TOO_LOW");
        if (m.f60 + EPS < 0.0) return block("SHADOW_P02_FLOW60_OPPOSED");
        if (m.directionalEdge > .85 + EPS)
            return block("SHADOW_P02_DIRECTIONAL_EDGE_EXHAUSTED");
        if (m.m8 > 3.50 + EPS) return block("SHADOW_P02_MOVE8_OVEREXTENDED");
        return keep("SHADOW_P02_KEEP");
    }

    public static Decision pullback(int score, NormalizedSignalMetrics.Result m) {
        if (score < 95) return block("SHADOW_PULLBACK_SCORE_TOO_LOW");
        if (m == null || !m.valid) return block("SHADOW_PULLBACK_METRICS_INVALID");
        if (m.volumeRatio > 1.20 + EPS) return block("SHADOW_PULLBACK_VOLUME_TOO_HIGH");
        if (m.room + EPS < 1.50) return block("SHADOW_PULLBACK_ROOM_TOO_LOW");
        if (m.f30 + EPS < .50) return block("SHADOW_PULLBACK_FLOW30_TOO_LOW");
        if (m.f60 + EPS < .60 || m.f60 > 1.10 + EPS)
            return block("SHADOW_PULLBACK_FLOW60_OUTSIDE_RANGE");
        if (m.m1 + EPS < .60) return block("SHADOW_PULLBACK_MOVE1_TOO_LOW");
        if (m.m3 + EPS < 1.50) return block("SHADOW_PULLBACK_MOVE3_TOO_LOW");
        if (m.m8 + EPS < -3.00 || m.m8 >= -1.00 - EPS)
            return block("SHADOW_PULLBACK_MOVE8_OUTSIDE_RANGE");
        if (m.directionalEdge > .55 + EPS)
            return block("SHADOW_PULLBACK_DIRECTIONAL_EDGE_EXHAUSTED");
        return keep("SHADOW_PULLBACK_KEEP");
    }

    public static Decision ethMidVol(MarketProfile profile, int score,
                                     NormalizedSignalMetrics.Result m) {
        if (profile == null || !MarketProfile.ETH_SYMBOL.equals(profile.symbol))
            return block("SHADOW_MID_VOL_ETH_ONLY");
        if (score < 95) return block("SHADOW_MID_VOL_SCORE_TOO_LOW");
        if (m == null || !m.valid) return block("SHADOW_MID_VOL_METRICS_INVALID");
        if (m.a + EPS < 1.20 || m.a > 1.65 + EPS)
            return block("SHADOW_MID_VOL_A_OUTSIDE_RANGE");
        if (m.m1 <= -.30 + EPS) return block("SHADOW_MID_VOL_MOVE1_TOO_LOW");
        if (m.m8 + EPS < 0.0) return block("SHADOW_MID_VOL_MOVE8_TOO_LOW");
        if (m.f30 + EPS < .22) return block("SHADOW_MID_VOL_FLOW30_TOO_LOW");
        if (m.f60 + EPS < .60) return block("SHADOW_MID_VOL_FLOW60_TOO_LOW");
        return keep("SHADOW_MID_VOL_KEEP");
    }

    public static boolean isCriticalCurrentRevalidation(String code) {
        if (code == null || code.isEmpty() || "PRIX_DEJA_TROP_LOIN".equals(code)) return false;
        String c = code.toUpperCase(java.util.Locale.ROOT);
        return c.contains("MOVE1") || c.contains("MOVE3") || c.contains("FRAICHEUR")
                || c.contains("STALE") || c.contains("CONFLIT_1M_8M")
                || c.contains("FLOW_OPPOSE") || c.contains("REPLAY_QUALITY");
    }

    private static Decision keep(String reason) { return new Decision(true, "KEEP", reason); }
    private static Decision block(String reason) { return new Decision(false, "BLOCK", reason); }

    public static final class Decision {
        public final boolean keep;
        public final String decision, reasonCode;
        private Decision(boolean keep, String decision, String reasonCode) {
            this.keep=keep;this.decision=decision;this.reasonCode=reasonCode;
        }
    }
}
