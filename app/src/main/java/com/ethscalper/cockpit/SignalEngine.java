package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class SignalEngine {
    public static final long COOLDOWN_MS = 18 * 60 * 1000L;
    private static final int MAX_DIAGNOSTICS = 320;

    private static final double FEE_ROUND_TRIP = 1.33;
    private static final double SLIPPAGE_RESEARCH = 0.10;
    private static final double EFFECTIVE_COST = FEE_ROUND_TRIP + SLIPPAGE_RESEARCH;

    private static final double TP_SCALP = 2.80;
    private static final double TP_STANDARD = 3.50;
    private static final double TP_PREMIUM = 5.50;

    private static final double SL_SCALP = 1.35;
    private static final double SL_STANDARD = 1.65;
    private static final double SL_PREMIUM = 2.20;

    private static final double MAX_SPREAD = 0.55;

    private final Deque<DiagnosticEntry> diagnostics = new ArrayDeque<>();

    public synchronized SignalDecision evaluate(MarketSnapshot s) {
        Movement movement = movement(s);

        if (s.ethCandles < 30 || s.btcCandles < 10 || !positive(s.ethLast)) {
            return reject(s, "V230_NO_DATA", "Données ETH/BTC insuffisantes", 0, movement);
        }

        if (!positive(s.avgRange20) || !positive(s.avgVolume20)) {
            return reject(s, "V230_HISTORY_BAD", "Historique range/volume incomplet", 0, movement);
        }

        if (s.lastSignalAt > 0 && s.now - s.lastSignalAt < COOLDOWN_MS) {
            return reject(s, "V230_COOLDOWN_18M", "Cooldown v2.30 18 minutes", 0, movement);
        }

        double spread = liveSpread(s);
        if (Double.isFinite(spread) && spread > MAX_SPREAD) {
            return reject(s, "V230_SPREAD_BAD", "Spread trop large pour scalp research", 0, movement);
        }

        Scores scores = score(s);
        Plan premium = premiumContinuationPlan(s, scores);
        Plan scalp = scalpContinuationPlan(s, scores);
        Plan fade = rangeFadePlan(s, scores);

        Plan plan = best(premium, scalp, fade);

        if (!plan.pass) {
            double bestScore = Math.max(scores.longScore, scores.shortScore);
            double gap = Math.abs(scores.longScore - scores.shortScore);
            return reject(
                    s,
                    "V230_NO_EDGE",
                    fmt("Aucun edge propre L=%.2f S=%.2f gap=%.2f rp=%.2f roomL=%.2f roomS=%.2f",
                            scores.longScore, scores.shortScore, gap, finiteOr(s.rangePosition, 0.5), s.roomLong, s.roomShort),
                    scoreToInt(bestScore),
                    movement
            );
        }

        double entry = plan.side > 0 ? (positive(s.ethAsk) ? s.ethAsk : s.ethLast)
                : (positive(s.ethBid) ? s.ethBid : s.ethLast);

        double tp = entry + plan.side * plan.target;
        double sl = entry - plan.side * plan.stop;

        int score = scoreToInt(plan.strength);
        int quantity = computeQuantity(score, plan.stop, plan.target, movement.consumed, plan.family);
        quantity = capRiskyRangeFadeQuantity(quantity, s, plan);

        if (quantity <= 0) {
            return reject(s, "V230_SIZE_ZERO", "Signal refusé : taille research nulle", score, movement);
        }

        String family = "v2.30 " + plan.family + " — hybrid AI ready";

        SignalDecision decision = SignalDecision.signal(
                plan.side > 0 ? "LONG" : "SHORT",
                family,
                score,
                quantity,
                round2(entry),
                round2(tp),
                round2(sl),
                plan.target,
                plan.stop,
                movement.impulse,
                true,
                movement.origin,
                movement.extreme,
                movement.distance
        );

        record(
                s.now,
                decision.reasonCode,
                fmt("V230_SIGNAL family=%s side=%s TP=%.2f SL=%.2f net=%.2f strength=%.2f L=%.2f S=%.2f rp=%.2f roomL=%.2f roomS=%.2f",
                        plan.family,
                        plan.side > 0 ? "LONG" : "SHORT",
                        plan.target,
                        plan.stop,
                        plan.target - EFFECTIVE_COST,
                        plan.strength,
                        scores.longScore,
                        scores.shortScore,
                        finiteOr(s.rangePosition, 0.5),
                        s.roomLong,
                        s.roomShort)
        );

        return decision;
    }

    private static Plan premiumContinuationPlan(MarketSnapshot s, Scores scores) {
        int side = scores.longScore >= scores.shortScore ? 1 : -1;
        double best = Math.max(scores.longScore, scores.shortScore);
        double gap = Math.abs(scores.longScore - scores.shortScore);
        double room = side > 0 ? s.roomLong : s.roomShort;
        double rp = finiteOr(s.rangePosition, 0.5);

        if (best < 2.05 || gap < 0.85) return Plan.no();
        if (room < 2.60) return Plan.no();
        if (side > 0 && rp > 0.84) return Plan.no();
        if (side < 0 && rp < 0.24) return Plan.no();
        if (side * s.flow30 < 0.025) return Plan.no();
        if (side * s.flow60 < 0.035) return Plan.no();
        if (btcHardConflict(s, side)) return Plan.no();
        if (lateImpulse(s, side, rp, 2.65)) return Plan.no();
        if (wickRisk(s)) return Plan.no();

        double strength = best + gap * 0.45 + clamp(room / 5.0, 0.0, 0.45);
        return Plan.pass(side, "PREMIUM_CONTINUATION", TP_PREMIUM, SL_PREMIUM, strength);
    }

    private static Plan scalpContinuationPlan(MarketSnapshot s, Scores scores) {
        int side = scores.longScore >= scores.shortScore ? 1 : -1;
        double best = Math.max(scores.longScore, scores.shortScore);
        double gap = Math.abs(scores.longScore - scores.shortScore);
        double room = side > 0 ? s.roomLong : s.roomShort;
        double rp = finiteOr(s.rangePosition, 0.5);

        if (best < 1.32 || gap < 0.42) return Plan.no();
        if (room < 1.55) return Plan.no();
        if (side > 0 && rp > 0.78) return Plan.no();
        if (side < 0 && rp < 0.30) return Plan.no();
        if (side * s.flow30 < 0.005) return Plan.no();
        if (side * s.flow60 < -0.035) return Plan.no();
        if (btcSoftConflict(s, side)) return Plan.no();
        if (lateImpulse(s, side, rp, 2.95)) return Plan.no();

        double move3 = side * z(s.move3Norm, -0.078585, 1.36059);
        double move8 = side * z(s.move8Norm, -0.10989, 2.58921);
        if (move3 < 0.18 && move8 < -0.25) return Plan.no();

        double target = best >= 1.72 && gap >= 0.55 && room >= 2.45 ? TP_STANDARD : TP_SCALP;
        double stop = target >= TP_STANDARD ? SL_STANDARD : SL_SCALP;

        double strength = best + gap * 0.55 + clamp(side * s.flow30, 0.0, 0.35) + clamp(room / 6.0, 0.0, 0.35);
        return Plan.pass(side, target >= TP_STANDARD ? "SCALP_CONTINUATION_PLUS" : "SCALP_CONTINUATION", target, stop, strength);
    }

    private static Plan rangeFadePlan(MarketSnapshot s, Scores scores) {
        double rp = finiteOr(s.rangePosition, 0.5);

        Plan shortFade = Plan.no();
        if (rp >= 0.74 && s.roomShort >= 2.05) {
            double exhaustion = 0.0;
            exhaustion += clamp((rp - 0.74) / 0.20, 0.0, 1.4);
            exhaustion += clamp(-z(s.move1Norm, 0.0, 0.572559), 0.0, 1.0) * 0.35;
            exhaustion += clamp(-s.flow15, 0.0, 0.45);
            exhaustion += clamp(-s.flow30, 0.0, 0.40);
            exhaustion += clamp(s.antiBurstScore - 0.35, 0.0, 0.80) * 0.35;
            exhaustion += clamp(-z(s.btcMove3, -0.000052, 0.000926), 0.0, 1.0) * 0.18;
            exhaustion += clamp(scores.shortScore - scores.longScore + 0.30, 0.0, 0.80) * 0.30;

            boolean strongLongContinuation = s.move1 > s.avgRange20 * 0.45
                    && s.move3 > s.avgRange20 * 0.85
                    && s.flow30 > 0.04;

            boolean rejection = rp <= 0.98
                    && (s.move1 <= -Math.max(0.08, s.avgRange20 * 0.08)
                    || s.flow15 < -0.020
                    || s.flow30 < 0.000);

            if (strongLongContinuation || rangeFadeCounterTrendTrap(s, -1)) return Plan.no();

            if (exhaustion >= 0.95 && rejection && !btcHardConflict(s, -1) && !btcFadeConflict(s, -1)) {
                double target = s.roomShort >= 3.20 && exhaustion >= 1.55 ? TP_STANDARD : TP_SCALP;
                double stop = target >= TP_STANDARD ? SL_STANDARD : SL_SCALP;
                shortFade = Plan.pass(-1, target >= TP_STANDARD ? "RANGE_FADE_SHORT_PLUS" : "RANGE_FADE_SHORT", target, stop, 1.35 + exhaustion);
            }
        }

        Plan longFade = Plan.no();
        if (rp <= 0.26 && s.roomLong >= 2.05) {
            double exhaustion = 0.0;
            exhaustion += clamp((0.26 - rp) / 0.20, 0.0, 1.4);
            exhaustion += clamp(z(s.move1Norm, 0.0, 0.572559), 0.0, 1.0) * 0.35;
            exhaustion += clamp(s.flow15, 0.0, 0.45);
            exhaustion += clamp(s.flow30, 0.0, 0.40);
            exhaustion += clamp(s.antiBurstScore - 0.35, 0.0, 0.80) * 0.35;
            exhaustion += clamp(z(s.btcMove3, -0.000052, 0.000926), 0.0, 1.0) * 0.18;
            exhaustion += clamp(scores.longScore - scores.shortScore + 0.30, 0.0, 0.80) * 0.30;

            boolean strongShortContinuation = s.move1 < -s.avgRange20 * 0.45
                    && s.move3 < -s.avgRange20 * 0.85
                    && s.flow30 < -0.04;

            boolean rejection = rp >= 0.02
                    && (s.move1 >= Math.max(0.08, s.avgRange20 * 0.08)
                    || s.flow15 > 0.020
                    || s.flow30 > 0.000);

            if (strongShortContinuation || rangeFadeCounterTrendTrap(s, 1)) return Plan.no();

            if (exhaustion >= 0.95 && rejection && !btcHardConflict(s, 1) && !btcFadeConflict(s, 1)) {
                double target = s.roomLong >= 3.20 && exhaustion >= 1.55 ? TP_STANDARD : TP_SCALP;
                double stop = target >= TP_STANDARD ? SL_STANDARD : SL_SCALP;
                longFade = Plan.pass(1, target >= TP_STANDARD ? "RANGE_FADE_LONG_PLUS" : "RANGE_FADE_LONG", target, stop, 1.35 + exhaustion);
            }
        }

        return best(shortFade, longFade);
    }

    private static int capRiskyRangeFadeQuantity(int quantity, MarketSnapshot s, Plan plan) {
        if (s == null || plan == null || plan.family == null || !plan.family.contains("RANGE_FADE")) {
            return quantity;
        }

        if (riskyRangeFadeSizingContext(s, plan.side)) {
            return Math.min(quantity, 3);
        }

        return quantity;
    }

    private static boolean riskyRangeFadeSizingContext(MarketSnapshot s, int fadeSide) {
        double avg = Math.max(0.35, s.avgRange20);
        double rp = finiteOr(s.rangePosition, 0.5);

        if (fadeSide < 0) {
            boolean ethStillPushingLong = s.move3 > avg * 0.90 && s.move8 > avg * 1.65 && rp >= 0.86;
            boolean btcOrFlowStillLong = s.btcMove3 > 0.00020 || s.btcMove8 > 0.00035
                    || s.flow60 > 0.03 || s.flow120 > 0.25;
            boolean notARealRejectionYet = s.move1 > -avg * 0.45 && s.flow15 > -0.26;
            return ethStillPushingLong && btcOrFlowStillLong && notARealRejectionYet;
        }

        if (fadeSide > 0) {
            boolean ethStillPushingShort = s.move3 < -avg * 0.90 && s.move8 < -avg * 1.65 && rp <= 0.14;
            boolean btcOrFlowStillShort = s.btcMove3 < -0.00020 || s.btcMove8 < -0.00035
                    || s.flow60 < -0.03 || s.flow120 < -0.25;
            boolean notARealRejectionYet = s.move1 < avg * 0.45 && s.flow15 < 0.26;
            return ethStillPushingShort && btcOrFlowStillShort && notARealRejectionYet;
        }

        return false;
    }

    private static boolean rangeFadeCounterTrendTrap(MarketSnapshot s, int fadeSide) {
        double avg = Math.max(0.35, s.avgRange20);
        double rp = finiteOr(s.rangePosition, 0.5);

        if (fadeSide < 0) {
            boolean ethLongPush = s.move3 > avg * 1.25 && s.move8 > avg * 2.35 && rp >= 0.88;
            boolean btcLongConfirm = s.btcMove3 > 0.00022 || s.btcMove8 > 0.00035;
            boolean mediumFlowLong = s.flow60 > 0.03 || s.flow120 > 0.30;
            boolean noImmediateRejection = s.move1 > -avg * 0.35 && s.flow15 > -0.24;
            return ethLongPush && btcLongConfirm && mediumFlowLong && noImmediateRejection;
        }

        if (fadeSide > 0) {
            boolean ethShortPush = s.move3 < -avg * 1.25 && s.move8 < -avg * 2.35 && rp <= 0.12;
            boolean btcShortConfirm = s.btcMove3 < -0.00022 || s.btcMove8 < -0.00035;
            boolean mediumFlowShort = s.flow60 < -0.03 || s.flow120 < -0.30;
            boolean noImmediateRejection = s.move1 < avg * 0.35 && s.flow15 < 0.24;
            return ethShortPush && btcShortConfirm && mediumFlowShort && noImmediateRejection;
        }

        return false;
    }

    private static Scores score(MarketSnapshot s) {
        double p = 0.30 * z(s.move3Norm, -0.078585, 1.36059)
                + 0.18 * z(s.move8Norm, -0.10989, 2.58921)
                + 0.12 * z(s.move1Norm, 0.0, 0.572559);

        double f = 0.30 * z(s.flow30, -0.001565, 0.1615885)
                + 0.25 * z(s.flow60, -0.007899, 0.3274025)
                + 0.15 * z(s.flow120, -0.033593, 0.6465315)
                + 0.15 * z(s.flowAccel, 0.003883, 0.286618);

        double b = 0.12 * z(s.btcMove3, -0.000052, 0.000926)
                + 0.08 * z(s.btcMove8, -0.000019, 0.001892)
                + 0.06 * z(s.btcAccel3_8, 0.000041, 0.001404);

        double rp = finiteOr(s.rangePosition, 0.5);

        double longContext = 0.10 * z(s.roomLong, 1.975, 2.30)
                - 0.07 * z(s.roomShort, 1.485, 2.06)
                - 0.35 * Math.max(0.0, rp - 0.82);

        double shortContext = 0.10 * z(s.roomShort, 1.485, 2.06)
                - 0.07 * z(s.roomLong, 1.975, 2.30)
                - 0.35 * Math.max(0.0, 0.18 - rp);

        double wickPenalty = wickRisk(s) ? 0.35 : 0.0;

        double longScore = p + f + b + longContext - wickPenalty;
        double shortScore = -p - f - b + shortContext - wickPenalty;

        return new Scores(longScore, shortScore);
    }

    public synchronized List<DiagnosticEntry> recentDiagnostics(int limit) {
        int wanted = Math.max(0, Math.min(limit, diagnostics.size()));
        List<DiagnosticEntry> out = new ArrayList<>(wanted);
        int skip = diagnostics.size() - wanted;
        int index = 0;
        for (DiagnosticEntry entry : diagnostics) {
            if (index++ >= skip) out.add(entry);
        }
        return out;
    }

    public synchronized void clearDiagnostics() {
        diagnostics.clear();
    }

    public static double computeTarget(double avgRange, double recentRange, double volumeRatio, double flowPower, int score) {
        if (score >= 88) return TP_PREMIUM;
        if (score >= 82) return TP_STANDARD;
        return TP_SCALP;
    }

    public static double computeStop(double avgRange, double recentRange, int score) {
        if (score >= 88) return SL_PREMIUM;
        if (score >= 82) return SL_STANDARD;
        return SL_SCALP;
    }

    public static double computeStructureStop(double baseStop, int side, double entry, double recentHigh, double recentLow, double avgRange) {
        return baseStop;
    }

    public static int computeQuantity(int score, double stop, double target, boolean consumed) {
        return computeQuantity(score, stop, target, consumed, "");
    }

    public static int computeQuantity(int score, double stop, double target, boolean consumed, String family) {
        double netPerEth = target - FEE_ROUND_TRIP;
        if (netPerEth <= 0.0) return 0;

        int quantity;
        if (score >= 94) quantity = 7;
        else if (score >= 90) quantity = 6;
        else if (score >= 86) quantity = 5;
        else if (score >= 82) quantity = 4;
        else quantity = 3;

        if (family != null && family.contains("PREMIUM") && score >= 88) {
            quantity = Math.max(quantity, 5);
        }

        if (consumed) {
            quantity = Math.max(3, quantity - 1);
        }

        return Math.max(3, Math.min(7, quantity));
    }

    private SignalDecision reject(MarketSnapshot s, String code, String text, int score, Movement m) {
        record(s.now, code, text);
        return SignalDecision.waiting(code, text, score, m.impulse, m.reset, m.origin, m.extreme, m.distance, m.consumed);
    }

    private void record(long now, String code, String message) {
        diagnostics.addLast(new DiagnosticEntry(now, code, message));
        while (diagnostics.size() > MAX_DIAGNOSTICS) diagnostics.removeFirst();
    }

    private static Movement movement(MarketSnapshot s) {
        double origin = positive(s.ethLast) ? s.ethLast - s.move8 : Double.NaN;
        double extreme = s.move8 >= 0 ? s.recentHigh : s.recentLow;

        if (!positive(extreme)) extreme = s.ethLast;

        double distance = positive(origin) && positive(s.ethLast) ? Math.abs(s.ethLast - origin) : Double.NaN;
        double vertical = positive(s.avgRange20) ? Math.abs(s.move8) / Math.max(0.35, s.avgRange20) : 0.0;
        double recentRange = Math.max(0.0, s.recentHigh - s.recentLow);

        boolean consumed = positive(s.avgRange20)
                && (vertical > 6.2 || recentRange > s.avgRange20 * 8.0)
                && Math.abs(s.move1) > s.avgRange20 * 1.25;

        String impulse = Math.abs(s.move3) > Math.max(0.75, s.avgRange20 * 0.80)
                ? "ACTIVE"
                : Math.abs(s.move3) > Math.max(0.30, s.avgRange20 * 0.35) ? "FAIBLE" : "NEUTRE";

        boolean reset = positive(s.avgRange20) && Math.abs(s.move1) <= s.avgRange20 * 0.70;

        return new Movement(impulse, reset, origin, extreme, distance, consumed);
    }

    private static boolean lateImpulse(MarketSnapshot s, int side, double rp, double limit) {
        double directionalMove1Z = side * z(s.move1Norm, 0.0, 0.572559);
        return directionalMove1Z > limit && ((side > 0 && rp > 0.70) || (side < 0 && rp < 0.32));
    }

    private static boolean btcFadeConflict(MarketSnapshot s, int side) {
        if (side > 0) return s.btcMove1 < -0.00045 && s.btcMove3 < -0.00040;
        return s.btcMove1 > 0.00045 && s.btcMove3 > 0.00040;
    }

    private static boolean btcHardConflict(MarketSnapshot s, int side) {
        double btc1 = z(s.btcMove1, -0.000052, 0.000926);
        double btc3 = z(s.btcMove3, -0.000052, 0.000926);
        if (side > 0) return btc1 < -1.85 && btc3 < -0.85;
        return btc1 > 1.85 && btc3 > 0.85;
    }

    private static boolean btcSoftConflict(MarketSnapshot s, int side) {
        double btc1 = z(s.btcMove1, -0.000052, 0.000926);
        double btc3 = z(s.btcMove3, -0.000052, 0.000926);
        if (side > 0) return btc1 < -2.25 && btc3 < -1.15;
        return btc1 > 2.25 && btc3 > 1.15;
    }

    private static boolean wickRisk(MarketSnapshot s) {
        double vr = z(s.volumeRatio, 0.351660, 0.5404845);
        return vr > 2.80 && s.antiBurstScore < 0.20;
    }

    private static Plan best(Plan... plans) {
        Plan best = Plan.no();
        for (Plan p : plans) {
            if (p != null && p.pass && (!best.pass || p.strength > best.strength)) best = p;
        }
        return best;
    }

    private static double z(double value, double median, double iqr) {
        if (!Double.isFinite(value) || !Double.isFinite(iqr) || Math.abs(iqr) < 1e-9) return 0.0;

        double v = (value - median) / iqr;

        if (v > 3.0) return 3.0;
        if (v < -3.0) return -3.0;

        return v;
    }

    private static double liveSpread(MarketSnapshot s) {
        if (positive(s.ethBid) && positive(s.ethAsk)) return s.ethAsk - s.ethBid;
        return Double.NaN;
    }

    private static int scoreToInt(double score) {
        int mapped = (int) Math.round(70.0 + Math.max(0.0, Math.min(3.8, score)) * 7.0);
        return clampInt(mapped, 70, 96);
    }

    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.US, pattern, args);
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class Scores {
        final double longScore;
        final double shortScore;

        Scores(double longScore, double shortScore) {
            this.longScore = longScore;
            this.shortScore = shortScore;
        }
    }

    private static final class Plan {
        final boolean pass;
        final int side;
        final String family;
        final double target;
        final double stop;
        final double strength;

        private Plan(boolean pass, int side, String family, double target, double stop, double strength) {
            this.pass = pass;
            this.side = side;
            this.family = family;
            this.target = target;
            this.stop = stop;
            this.strength = strength;
        }

        static Plan pass(int side, String family, double target, double stop, double strength) {
            return new Plan(true, side, family, target, stop, strength);
        }

        static Plan no() {
            return new Plan(false, 0, "NO_PLAN", Double.NaN, Double.NaN, Double.NEGATIVE_INFINITY);
        }
    }

    private static final class Movement {
        final String impulse;
        final boolean reset;
        final double origin;
        final double extreme;
        final double distance;
        final boolean consumed;

        Movement(String impulse, boolean reset, double origin, double extreme, double distance, boolean consumed) {
            this.impulse = impulse;
            this.reset = reset;
            this.origin = origin;
            this.extreme = extreme;
            this.distance = distance;
            this.consumed = consumed;
        }
    }
}
