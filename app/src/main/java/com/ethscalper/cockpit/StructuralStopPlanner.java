package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure causal structural stop calculation shared by every registered market.
 *
 * <p>The production search uses five completed minutes and a 0.15 A structural buffer.
 * Legacy absolute stop floors and caps never participate in the public stop decision.
 * A wide integrity envelope detects corrupt data but never clamps a valid stop.</p>
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
        return calculate(profile, side, entry, avgRange20, adverseExcursion,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, candles,
                confirmationAt, PRODUCTION);
    }

    public static Result calculate(MarketProfile profile, String side, double entry,
                                   double avgRange20, double adverseExcursion,
                                   double recentHigh, double recentLow,
                                   double marketBid, double marketAsk,
                                   Iterable<MarketRuntime.MarketBar> candles,
                                   long confirmationAt) {
        return calculate(profile, side, entry, avgRange20, adverseExcursion,
                recentHigh, recentLow, marketBid, marketAsk, candles,
                confirmationAt, PRODUCTION);
    }

    public static Result calculate(MarketProfile profile, String side, double entry,
                                   double avgRange20, double adverseExcursion,
                                   Iterable<MarketRuntime.MarketBar> candles,
                                   long confirmationAt, Config config) {
        return calculate(profile, side, entry, avgRange20, adverseExcursion,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, candles,
                confirmationAt, config);
    }

    public static Result calculate(MarketProfile profile, String side, double entry,
                                   double avgRange20, double adverseExcursion,
                                   double recentHigh, double recentLow,
                                   double marketBid, double marketAsk,
                                   Iterable<MarketRuntime.MarketBar> candles,
                                   long confirmationAt, Config config) {
        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (profile == null || config == null || direction == 0 || !positive(entry)
                || !positive(avgRange20) || !finite(adverseExcursion) || confirmationAt <= 0) {
            return Result.invalid(DATA_INVALID);
        }

        double a = avgRange20;
        double adverse = Math.max(0.0, adverseExcursion);
        double spread = positive(marketBid) && positive(marketAsk) && marketAsk >= marketBid
                ? marketAsk - marketBid : 0.0;
        double spreadAndTick = Math.max(profile.priceTick, spread + profile.priceTick);
        double technicalBuffer = Math.max(config.bufferMultiplier * a, spreadAndTick);
        double volatilityProtection = a;
        double adverseProtection = adverse + Math.max(0.20 * a, spreadAndTick);
        double base = Math.max(volatilityProtection, adverseProtection);

        List<MarketRuntime.MarketBar> eligible = eligibleBars(candles, confirmationAt,
                config.windowMinutes);
        Anchor anchor = findLatestPivot(eligible, direction, entry, a);
        if (anchor == null) {
            double rangeLevel = direction > 0 ? recentLow : recentHigh;
            if (positive(rangeLevel)
                    && (direction > 0 ? rangeLevel < entry : rangeLevel > entry)) {
                anchor = new Anchor(rangeLevel);
            }
        }
        double structureDistance = anchor == null ? 0.0
                : direction > 0 ? entry - anchor.price : anchor.price - entry;
        double structural = anchor == null ? 0.0 : structureDistance + technicalBuffer;
        double required = Math.max(structural,
                Math.max(volatilityProtection, adverseProtection));

        // Technical integrity only: this rejects corrupt inputs and never clamps the stop.
        double sanityEnvelope = Math.max(20.0 * a,
                Math.max(100.0 * profile.priceTick, entry * 0.05));
        String type = dominantType(required, structural, volatilityProtection,
                adverseProtection);
        if (!positive(required) || !finite(required) || !positive(sanityEnvelope)) {
            return Result.invalid(DATA_INVALID);
        }
        if (required > sanityEnvelope + 1e-9) {
            return new Result(false, SANITY_REJECTED, a, base,
                    anchor == null ? Double.NaN : anchor.price,
                    anchor == null ? 0 : config.windowMinutes, technicalBuffer,
                    structureDistance, structural, required, sanityEnvelope, type, spread,
                    volatilityProtection, adverseProtection);
        }
        return new Result(true, anchor == null ? ANCHOR_UNAVAILABLE : CONFIRMED,
                a, base, anchor == null ? Double.NaN : anchor.price,
                anchor == null ? 0 : config.windowMinutes, technicalBuffer,
                structureDistance, structural, required, sanityEnvelope, type, spread,
                volatilityProtection, adverseProtection);
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
            MarketRuntime.MarketBar before = bars.get(i - 1);
            MarketRuntime.MarketBar pivot = bars.get(i);
            MarketRuntime.MarketBar after = bars.get(i + 1);
            if (after.openTime - pivot.openTime > 90_000L
                    || pivot.openTime - before.openTime > 90_000L) continue;
            if (direction > 0) {
                boolean local = pivot.low < entry && pivot.low <= before.low
                        && pivot.low <= after.low;
                boolean confirmed = Math.min(before.close, after.close)
                        >= pivot.low + 0.10 * a;
                if (local && confirmed) return new Anchor(pivot.low);
            } else {
                boolean local = pivot.high > entry && pivot.high >= before.high
                        && pivot.high >= after.high;
                boolean confirmed = Math.max(before.close, after.close)
                        <= pivot.high - 0.10 * a;
                if (local && confirmed) return new Anchor(pivot.high);
            }
        }
        return null;
    }

    private static String dominantType(double required, double structural,
                                       double volatility, double adverse) {
        List<String> factors = new ArrayList<>();
        if (structural > 0.0 && Math.abs(required - structural) <= 1e-12) {
            factors.add("STRUCTURE");
        }
        if (Math.abs(required - volatility) <= 1e-12) factors.add("VOLATILITY");
        if (Math.abs(required - adverse) <= 1e-12) factors.add("ADVERSE_EXCURSION");
        if (factors.size() == 1) return factors.get(0);
        return "COMBINATION(" + String.join("+", factors) + ")";
    }

    private static boolean validBar(MarketRuntime.MarketBar b) {
        return positive(b.open) && positive(b.high) && positive(b.low) && positive(b.close)
                && finite(b.volume) && b.volume >= 0.0 && b.high >= Math.max(b.open, b.close)
                && b.low <= Math.min(b.open, b.close) && b.high >= b.low;
    }

    private static boolean positive(double value) { return finite(value) && value > 0.0; }
    private static boolean finite(double value) { return Double.isFinite(value); }

    private static final class Anchor {
        final double price;
        Anchor(double value) { price = value; }
    }

    public static final class Config {
        public final int windowMinutes;
        public final double bufferMultiplier;

        public Config(int windowMinutes, double bufferMultiplier) {
            if ((windowMinutes != 5 && windowMinutes != 8 && windowMinutes != 15)
                    || !finite(bufferMultiplier) || bufferMultiplier <= 0.0) {
                throw new IllegalArgumentException("Unsupported structural configuration");
            }
            this.windowMinutes = windowMinutes;
            this.bufferMultiplier = bufferMultiplier;
        }

        @Override public String toString() {
            return windowMinutes + "m/" + bufferMultiplier + "A";
        }
    }

    public static final class Result {
        public final boolean valid;
        public final String reasonCode;
        public final double a, baseStop, structuralAnchor, structuralBuffer;
        public final double structureDistance, structuralStop, requiredStop, sanityEnvelope;
        public final double spread, volatilityProtectionDistance;
        public final double adverseExcursionProtectionDistance;
        public final int structuralWindowMinutes;
        public final String calculationType;

        private Result(boolean valid, String reason, double a, double base, double anchor,
                       int window, double buffer, double distance, double structural,
                       double required, double envelope, String type, double spread,
                       double volatilityProtection, double adverseProtection) {
            this.valid = valid;
            reasonCode = reason;
            this.a = a;
            baseStop = base;
            structuralAnchor = anchor;
            structuralWindowMinutes = window;
            structuralBuffer = buffer;
            structureDistance = distance;
            structuralStop = structural;
            requiredStop = required;
            sanityEnvelope = envelope;
            calculationType = type;
            this.spread = spread;
            volatilityProtectionDistance = volatilityProtection;
            adverseExcursionProtectionDistance = adverseProtection;
        }

        private static Result invalid(String reason) {
            return new Result(false, reason, Double.NaN, Double.NaN, Double.NaN, 0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "",
                    Double.NaN, Double.NaN, Double.NaN);
        }
    }
}
