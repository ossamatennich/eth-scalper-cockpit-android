package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class SignalEngine {
    public static final long COOLDOWN_MS = 45 * 60 * 1000L;
    private static final int MAX_DIAGNOSTICS = 240;

    private static final double FEE_ROUND_TRIP = 1.33;
    private static final double SLIPPAGE_RESEARCH = 0.10;
    private static final double EFFECTIVE_COST = FEE_ROUND_TRIP + SLIPPAGE_RESEARCH;

    private static final double TARGET_MOVE = 5.50;
    private static final double STOP_DISTANCE = 2.20;
    private static final double MIN_ROOM = 3.50;
    private static final double MIN_SCORE = 1.60;
    private static final double MIN_GAP = 0.70;
    private static final double MAX_SPREAD = 0.50;

    private final Deque<DiagnosticEntry> diagnostics = new ArrayDeque<>();

    public synchronized SignalDecision evaluate(MarketSnapshot s) {
        Movement movement = movement(s);

        if (s.ethCandles < 30 || s.btcCandles < 10 || !positive(s.ethLast)) {
            return reject(s, "V229_NO_DATA", "Données ETH/BTC insuffisantes", 0, movement);
        }
        if (!positive(s.avgRange20) || !positive(s.avgVolume20)) {
            return reject(s, "V229_HISTORY_BAD", "Historique range/volume incomplet", 0, movement);
        }
        if (s.lastSignalAt > 0 && s.now - s.lastSignalAt < COOLDOWN_MS) {
            return reject(s, "V229_COOLDOWN_45M", "Cooldown global v2.29 45 minutes", 0, movement);
        }

        double spread = liveSpread(s);
        if (Double.isFinite(spread) && spread > MAX_SPREAD) {
            return reject(s, "V229_SPREAD_BAD", "Spread trop large pour signal research", 0, movement);
        }

        Scores scores = score(s);
        double best = Math.max(scores.longScore, scores.shortScore);
        double gap = Math.abs(scores.longScore - scores.shortScore);
        if (best < MIN_SCORE || gap < MIN_GAP) {
            return reject(s, "V229_SCORE_WEAK",
                    fmt("Score insuffisant L=%.2f S=%.2f gap=%.2f", scores.longScore, scores.shortScore, gap),
                    scoreToInt(best), movement);
        }

        int side = scores.longScore > scores.shortScore ? 1 : -1;
        String sideName = side > 0 ? "LONG" : "SHORT";
        double room = side > 0 ? s.roomLong : s.roomShort;
        double rangePosition = finiteOr(s.rangePosition, 0.5);

        if (!Double.isFinite(room) || room < MIN_ROOM) {
            return reject(s, "V229_ROOM_TOO_SMALL",
                    fmt("Room %s insuffisante %.2f$ < %.2f$", sideName, room, MIN_ROOM),
                    scoreToInt(best), movement);
        }

        if (side > 0 && rangePosition > 0.86) {
            return reject(s, "V229_TERMINAL_LONG", "LONG refusé : rangePosition trop haute", scoreToInt(best), movement);
        }
        if (side < 0 && rangePosition < 0.25) {
            return reject(s, "V229_TERMINAL_SHORT", "SHORT refusé : rangePosition trop basse", scoreToInt(best), movement);
        }

        if (side * s.flow60 <= 0.0) {
            return reject(s, "V229_FLOW60_NOT_ALIGNED", "Flow60 non aligné avec le sens", scoreToInt(best), movement);
        }
        if (side * s.flow30 <= 0.02) {
            return reject(s, "V229_FLOW30_TOO_WEAK", "Flow30 directionnel insuffisant", scoreToInt(best), movement);
        }

        double directionalMove1Z = side * z(s.move1Norm, 0.0, 0.572559);
        if (directionalMove1Z > 2.50 && ((side > 0 && rangePosition > 0.72) || (side < 0 && rangePosition < 0.28))) {
            return reject(s, "V229_LATE_ENTRY", "Entrée tardive : impulsion déjà consommée", scoreToInt(best), movement);
        }

        double btc1 = z(s.btcMove1, -0.000052, 0.000926);
        double btc3 = z(s.btcMove3, -0.000052, 0.000926);
        if (side > 0 && btc1 < -1.50 && btc3 < -0.60) {
            return reject(s, "V229_BTC_CONFLICT", "BTC violemment opposé au LONG", scoreToInt(best), movement);
        }
        if (side < 0 && btc1 > 1.50 && btc3 > 0.60) {
            return reject(s, "V229_BTC_CONFLICT", "BTC violemment opposé au SHORT", scoreToInt(best), movement);
        }

        double netTarget = TARGET_MOVE - EFFECTIVE_COST;
        double riskAfterCost = STOP_DISTANCE + EFFECTIVE_COST;
        if (netTarget <= 0.0 || netTarget / riskAfterCost < 0.95) {
            return reject(s, "V229_EV_TOO_LOW", "Ratio net/risk insuffisant après frais", scoreToInt(best), movement);
        }

        double entry = side > 0 ? (positive(s.ethAsk) ? s.ethAsk : s.ethLast)
                : (positive(s.ethBid) ? s.ethBid : s.ethLast);
        double tp = entry + side * TARGET_MOVE;
        double sl = entry - side * STOP_DISTANCE;
        int score = scoreToInt(best);
        int quantity = computeQuantity(score, STOP_DISTANCE, TARGET_MOVE, movement.consumed);
        if (quantity <= 0) {
            return reject(s, "V229_SIZE_ZERO", "Signal refusé : espérance nette insuffisante", score, movement);
        }

        String family = "v2.29 Pro Score Engine — continuation shadow";
        SignalDecision decision = SignalDecision.signal(sideName, family, score, quantity,
                round2(entry), round2(tp), round2(sl), TARGET_MOVE, STOP_DISTANCE,
                movement.impulse, true, movement.origin, movement.extreme, movement.distance);

        record(s.now, decision.reasonCode,
                fmt("V229_SCORE_ENGINE TP=%.2f SL=%.2f net=%.2f L=%.2f S=%.2f gap=%.2f room=%.2f rp=%.2f",
                        TARGET_MOVE, STOP_DISTANCE, netTarget, scores.longScore, scores.shortScore, gap, room, rangePosition));
        return decision;
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

        double rangePosition = finiteOr(s.rangePosition, 0.5);
        double longContext = 0.10 * z(s.roomLong, 1.975, 2.30)
                - 0.08 * z(s.roomShort, 1.485, 2.06)
                - 0.40 * Math.max(0.0, rangePosition - 0.82);
        double shortContext = 0.10 * z(s.roomShort, 1.485, 2.06)
                - 0.08 * z(s.roomLong, 1.975, 2.30)
                - 0.40 * Math.max(0.0, 0.18 - rangePosition);

        double wickPenalty = z(s.volumeRatio, 0.351660, 0.5404845) > 2.50 && s.antiBurstScore < 0.25 ? 0.45 : 0.0;

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

    public synchronized void clearDiagnostics() { diagnostics.clear(); }

    public static double computeTarget(double avgRange, double recentRange,
                                       double volumeRatio, double flowPower, int score) {
        return TARGET_MOVE;
    }

    public static double computeStop(double avgRange, double recentRange, int score) {
        return STOP_DISTANCE;
    }

    public static double computeStructureStop(double baseStop, int side, double entry,
                                              double recentHigh, double recentLow, double avgRange) {
        return STOP_DISTANCE;
    }

    public static int computeQuantity(int score, double stop, double target, boolean consumed) {
        double riskPerEth = stop + FEE_ROUND_TRIP;
        double netPerEth = target - FEE_ROUND_TRIP;
        if (netPerEth <= 0 || netPerEth / riskPerEth < 0.95) return 0;
        int quantity = score >= 92 && netPerEth / riskPerEth >= 1.20 ? 5
                : score >= 86 ? 4 : score >= 78 ? 3 : 2;
        return consumed ? Math.min(quantity, 3) : quantity;
    }

    private SignalDecision reject(MarketSnapshot s, String code, String text, int score, Movement m) {
        record(s.now, code, text);
        return SignalDecision.waiting(code, text, score, m.impulse, m.reset,
                m.origin, m.extreme, m.distance, m.consumed);
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
        double vertical = positive(s.avgRange20) ? Math.abs(s.move8) / Math.max(0.35, s.avgRange20) : 0;
        double recentRange = Math.max(0, s.recentHigh - s.recentLow);
        boolean consumed = positive(s.avgRange20)
                && (vertical > 5.8 || recentRange > s.avgRange20 * 7.5)
                && Math.abs(s.move1) > s.avgRange20 * 1.1;
        String impulse = Math.abs(s.move3) > Math.max(0.75, s.avgRange20 * 0.8)
                ? "ACTIVE" : Math.abs(s.move3) > Math.max(0.30, s.avgRange20 * 0.35) ? "FAIBLE" : "NEUTRE";
        boolean reset = positive(s.avgRange20) && Math.abs(s.move1) <= s.avgRange20 * 0.65;
        return new Movement(impulse, reset, origin, extreme, distance, consumed);
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
        int mapped = (int)Math.round(70.0 + Math.max(0.0, Math.min(3.4, score)) * 7.5);
        return clamp(mapped, 70, 96);
    }

    private static String fmt(String pattern, Object... args) { return String.format(Locale.US, pattern, args); }
    private static double finiteOr(double value, double fallback) { return Double.isFinite(value) ? value : fallback; }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }

    private static final class Scores {
        final double longScore;
        final double shortScore;
        Scores(double longScore, double shortScore) {
            this.longScore = longScore;
            this.shortScore = shortScore;
        }
    }

    private static final class Movement {
        final String impulse; final boolean reset; final double origin, extreme, distance; final boolean consumed;
        Movement(String impulse, boolean reset, double origin, double extreme, double distance, boolean consumed) {
            this.impulse = impulse; this.reset = reset; this.origin = origin; this.extreme = extreme;
            this.distance = distance; this.consumed = consumed;
        }
    }
}
