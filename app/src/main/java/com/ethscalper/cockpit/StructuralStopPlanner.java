package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Causal, profile-aware structural stop calculation.
 *
 * <p>The selected production configuration is deliberately parsimonious: an eight-minute
 * completed-candle window and a 0.15 A volatility buffer. The research validator compares the
 * complete 5/8/15 x 0.15/0.20/0.25/0.35 grid without changing this production constant.</p>
 */
public final class StructuralStopPlanner {
    public static final String CONFIRMED = "STRUCTURAL_STOP_CONFIRMED";
    public static final String ANCHOR_UNAVAILABLE = "STRUCTURAL_ANCHOR_UNAVAILABLE";
    public static final String DATA_INVALID = "STRUCTURAL_STOP_DATA_INVALID";
    public static final String SANITY_REJECTED = "STRUCTURAL_STOP_SANITY_REJECTED";
    public static final Config PRODUCTION = new Config(5, 0.15);

    private StructuralStopPlanner() {}

    public static Result calculate(MarketProfile profile, String side, double entry,
                                   double avgRange20, double adverseExcursion,
                                   Iterable<MarketRuntime.MarketBar> candles,
                                   long confirmationAt) {
        return calculate(profile, side, entry, avgRange20, adverseExcursion, candles,
                confirmationAt, PRODUCTION);
    }

    public static Result calculate(MarketProfile profile, String side, double entry,
                                   double avgRange20, double adverseExcursion,
                                   Iterable<MarketRuntime.MarketBar> candles,
                                   long confirmationAt, Config config) {
        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (profile == null || config == null || direction == 0 || !positive(entry)
                || !finite(avgRange20) || !finite(adverseExcursion) || confirmationAt <= 0) {
            return Result.invalid(DATA_INVALID);
        }
        double a = Math.max(profile.scaledMinimum(profile.aMinimumReference, entry), avgRange20);
        double stopMinimum = profile.scaledMinimum(profile.stopMinimumReference, entry);
        double adverse = Math.max(0.0, adverseExcursion);
        double base = Math.max(stopMinimum, Math.max(a, adverse + 0.20 * a));
        if (!positive(a) || !positive(base)) return Result.invalid(DATA_INVALID);

        List<MarketRuntime.MarketBar> eligible = eligibleBars(candles, confirmationAt,
                config.windowMinutes);
        Anchor anchor = findLatestPivot(eligible, direction, entry, a);
        double buffer = anchor == null ? 0.0 : config.bufferMultiplier * a;
        double structureDistance = anchor == null ? 0.0
                : direction > 0 ? entry - anchor.price : anchor.price - entry;
        double structural = anchor == null ? 0.0 : structureDistance + buffer;
        double required = Math.max(base, structural);

        // This is a technical-integrity envelope, not a tradable stop cap. It never clamps.
        double sanityEnvelope = Math.max(12.0 * a,
                Math.max(12.0 * stopMinimum, entry * 0.03));
        String type = anchor == null ? dominantBaseType(base, a, adverse, stopMinimum)
                : structural > base + 1e-12 ? "STRUCTURE" : "COMBINAISON";
        if (!positive(required) || !finite(required) || !positive(sanityEnvelope)) {
            return Result.invalid(DATA_INVALID);
        }
        if (required > sanityEnvelope + 1e-9) {
            return new Result(false, SANITY_REJECTED, a, base, anchor == null ? Double.NaN : anchor.price,
                    anchor == null ? 0 : config.windowMinutes, buffer, structureDistance,
                    structural, required, sanityEnvelope, type);
        }
        return new Result(true, anchor == null ? ANCHOR_UNAVAILABLE : CONFIRMED,
                a, base, anchor == null ? Double.NaN : anchor.price,
                anchor == null ? 0 : config.windowMinutes, buffer, structureDistance,
                structural, required, sanityEnvelope, type);
    }

    private static List<MarketRuntime.MarketBar> eligibleBars(
            Iterable<MarketRuntime.MarketBar> source, long confirmationAt, int windowMinutes) {
        List<MarketRuntime.MarketBar> all = new ArrayList<>();
        if (source != null) for (MarketRuntime.MarketBar bar : source) {
            if (bar == null || bar.openTime < 0 || bar.openTime + 60_000L > confirmationAt
                    || !validBar(bar)) continue;
            all.add(bar);
        }
        all.sort(Comparator.comparingLong(v -> v.openTime));
        long from = confirmationAt - windowMinutes * 60_000L;
        List<MarketRuntime.MarketBar> out = new ArrayList<>();
        for (MarketRuntime.MarketBar bar : all) if (bar.openTime >= from) out.add(bar);
        return out;
    }

    private static Anchor findLatestPivot(List<MarketRuntime.MarketBar> bars, int direction,
                                          double entry, double a) {
        if (bars.size() < 3) return null;
        for (int i = bars.size() - 2; i >= 1; i--) {
            MarketRuntime.MarketBar before = bars.get(i - 1), pivot = bars.get(i), after = bars.get(i + 1);
            if (after.openTime - pivot.openTime > 90_000L || pivot.openTime - before.openTime > 90_000L)
                continue;
            if (direction > 0) {
                boolean local = pivot.low < entry && pivot.low <= before.low && pivot.low <= after.low;
                boolean confirmed = Math.min(before.close, after.close) >= pivot.low + 0.10 * a;
                boolean coherent = entry - pivot.low <= 1.50 * a + 1e-12;
                if (local && confirmed && coherent) return new Anchor(pivot.low);
            } else {
                boolean local = pivot.high > entry && pivot.high >= before.high && pivot.high >= after.high;
                boolean confirmed = Math.max(before.close, after.close) <= pivot.high - 0.10 * a;
                boolean coherent = pivot.high - entry <= 1.50 * a + 1e-12;
                if (local && confirmed && coherent) return new Anchor(pivot.high);
            }
        }
        return null;
    }

    private static String dominantBaseType(double base, double a, double adverse, double minimum) {
        if (base <= minimum + 1e-12) return "VOLATILITÉ";
        if (base <= a + 1e-12) return "VOLATILITÉ";
        if (base <= adverse + 0.20 * a + 1e-12) return "EXCURSION";
        return "COMBINAISON";
    }

    private static boolean validBar(MarketRuntime.MarketBar b) {
        return positive(b.open) && positive(b.high) && positive(b.low) && positive(b.close)
                && finite(b.volume) && b.volume >= 0.0 && b.high >= Math.max(b.open, b.close)
                && b.low <= Math.min(b.open, b.close) && b.high >= b.low;
    }
    private static boolean positive(double v) { return finite(v) && v > 0.0; }
    private static boolean finite(double v) { return Double.isFinite(v); }

    private static final class Anchor { final double price; Anchor(double value) { price=value; } }

    public static final class Config {
        public final int windowMinutes;
        public final double bufferMultiplier;
        public Config(int windowMinutes, double bufferMultiplier) {
            if ((windowMinutes != 5 && windowMinutes != 8 && windowMinutes != 15)
                    || !finite(bufferMultiplier) || bufferMultiplier <= 0.0) {
                throw new IllegalArgumentException("Unsupported structural configuration");
            }
            this.windowMinutes=windowMinutes;this.bufferMultiplier=bufferMultiplier;
        }
        @Override public String toString() {
            return windowMinutes+"m/"+bufferMultiplier+"A";
        }
    }

    public static final class Result {
        public final boolean valid;
        public final String reasonCode;
        public final double a, baseStop, structuralAnchor, structuralBuffer;
        public final double structureDistance, structuralStop, requiredStop, sanityEnvelope;
        public final int structuralWindowMinutes;
        public final String calculationType;
        private Result(boolean valid,String reason,double a,double base,double anchor,int window,
                       double buffer,double distance,double structural,double required,
                       double envelope,String type) {
            this.valid=valid;reasonCode=reason;this.a=a;baseStop=base;structuralAnchor=anchor;
            structuralWindowMinutes=window;structuralBuffer=buffer;structureDistance=distance;
            structuralStop=structural;requiredStop=required;sanityEnvelope=envelope;
            calculationType=type;
        }
        private static Result invalid(String reason) {
            return new Result(false,reason,Double.NaN,Double.NaN,Double.NaN,0,Double.NaN,
                    Double.NaN,Double.NaN,Double.NaN,Double.NaN,"");
        }
    }
}
