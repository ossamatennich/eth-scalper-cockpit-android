package com.ethscalper.cockpit;

import java.util.Locale;

/** Frozen, public Scalp Action V1 route selection. No Android dependency. */
public final class ScalpActionPolicy {
    public static final String ENGINE_ID = "NMC_SCALP_ACTION_V1";
    public static final String POLICY_ID = "SCALP_ACTION_V1_20260805";
    public static final String SCHEMA_ID = "SCALP_ACTION_SCHEMA_V1";
    public static final String VERSION_NAME = "2.34.4.8";

    public static final String RAW = "RAW";
    public static final String LEGACY_CONFIRMATION = "LEGACY_CONFIRMATION";

    public static final Route RANGE_EXTREME = new Route(
            "ETH_SHORT_RANGE_EXTREME_V1", 1, 2.5, 1.25, 96,
            "SCALP_ACTION_RANGE_EXTREME_SHORT");
    public static final Route CONFIRM_MOVE3 = new Route(
            "ETH_CONFIRM_MOVE3_V1", 2, 1.5, 1.25, 92,
            "SCALP_ACTION_CONFIRM_MOVE3");
    public static final Route P01_SHORT_MICROVOL = new Route(
            "ETH_P01_SHORT_LOW_SOL_MICROVOL_V1", 3, 2.5, 1.25, 90,
            "SCALP_ACTION_P01_SHORT_LOW_SOL_MICROVOL");
    public static final Route CONT_COVERAGE = new Route(
            "ETH_CONT_SOL_COVERAGE_V1", 4, 1.5, 1.0, 88,
            "SCALP_ACTION_CONT_SOL_COVERAGE");
    public static final Route REVERSAL_8M = new Route(
            "ETH_REVERSAL_8M_V1", 5, 2.0, 1.0, 86,
            "SCALP_ACTION_REVERSAL_8M");

    private ScalpActionPolicy() {}

    public static Route selectRaw(SignalDecision decision, MarketSnapshot snapshot,
                                  ScalpActionContextTracker.Metrics metrics) {
        if (!eligibleEthSignal(decision) || snapshot == null || metrics == null) return null;
        String side = text(decision.side);
        double dRangePos = 1.0 - snapshot.rangePosition;
        if ("SHORT".equals(side) && finite(dRangePos) && snapshot.rangePosition >= 0.9526144) {
            return RANGE_EXTREME;
        }
        String family = text(decision.family).toUpperCase(Locale.US);
        if (family.contains("CONTINUATION") && metrics.solCov180Valid
                && metrics.solCov180 >= 0.982383) return CONT_COVERAGE;
        if (metrics.ethDret480Valid && metrics.ethDret480 <= -0.00411754) {
            return REVERSAL_8M;
        }
        return null;
    }

    public static Route selectLegacy(String symbol, String side, String sleeve,
                                     double sgMove3Norm,
                                     ScalpActionContextTracker.Metrics metrics) {
        if (!MarketProfile.ETH_SYMBOL.equals(symbol)
                || !("LONG".equals(side) || "SHORT".equals(side))) return null;
        if (finite(sgMove3Norm) && sgMove3Norm >= -0.381534) return CONFIRM_MOVE3;
        if (CandidateLifecycle.SLEEVE_P01.equals(sleeve) && "SHORT".equals(side)
                && metrics != null && metrics.solRv30Valid
                && metrics.solRv30 <= 0.0000689415) return P01_SHORT_MICROVOL;
        return null;
    }

    private static boolean eligibleEthSignal(SignalDecision decision) {
        return decision != null && decision.isSignal()
                && MarketProfile.ETH_SYMBOL.equals(decision.symbol)
                && ("LONG".equals(decision.side) || "SHORT".equals(decision.side));
    }

    private static boolean finite(double value) { return Double.isFinite(value); }
    private static String text(String value) { return value == null ? "" : value; }

    public static final class Route {
        public final String routeId;
        public final int priority;
        public final double targetMultiple;
        public final double stopMultiple;
        public final int score;
        public final String reasonCode;
        public final String family;

        Route(String id, int priority, double target, double stop, int score, String reason) {
            this.routeId=id;this.priority=priority;this.targetMultiple=target;
            this.stopMultiple=stop;this.score=score;this.reasonCode=reason;
            this.family=ENGINE_ID+"/"+id;
        }
    }
}
