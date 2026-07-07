package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class SignalEngine {
    public static final long COOLDOWN_MS = 20 * 60 * 1000L;
    private static final int MAX_DIAGNOSTICS = 200;
    private static final int MIN_SCORE = 62;
    private final Deque<DiagnosticEntry> diagnostics = new ArrayDeque<>();

    public synchronized SignalDecision evaluate(MarketSnapshot s) {
        Movement movement = movement(s);

        if (s.ethCandles < 30 || s.btcCandles < 10 || !positive(s.ethLast))
            return reject(s, "NO_DATA", "Données natives insuffisantes", 0, movement);

        if (!positive(s.avgRange20) || !positive(s.avgVolume20))
            return reject(s, "NO_DATA", "Historique de range ou volume incomplet", 0, movement);

        if (s.lastSignalAt > 0 && s.now - s.lastSignalAt < COOLDOWN_MS)
            return reject(s, "COOLDOWN_20M", "Cooldown Clean C1 20 minutes", 0, movement);

        double spread = positive(s.ethBid) && positive(s.ethAsk) ? s.ethAsk - s.ethBid : Double.NaN;
        if (Double.isFinite(spread) && spread > Math.max(0.75, s.avgRange20 * 0.55))
            return reject(s, "SPREAD_BAD", "Spread ETH trop large", 0, movement);

        if (s.avgRange20 < 0.15)
            return reject(s, "RANGE_TOO_SMALL", "Range trop faible pour un scalp propre", 0, movement);

        double threshold = Math.max(0.75, s.avgRange20 * 0.55);
        int side = 0;
        String family = "";

        if (s.move1 > threshold && s.move3 > threshold * 1.15) {
            side = 1;
            family = "C1 continuation propre";
        } else if (s.move1 < -threshold && s.move3 < -threshold * 1.15) {
            side = -1;
            family = "C1 continuation propre";
        } else {
            return reject(s, "NO_CLEAN_C1", "Aucun C1 continuation propre", 0, movement);
        }

        double volumeRatio = s.lastVolume / s.avgVolume20;
        double recentRange = Math.max(0, s.recentHigh - s.recentLow);
        double avgRange = Math.max(0.35, s.avgRange20);
        double recentRangeRatio = recentRange / avgRange;

        double directionalMove1 = side * s.move1;
        double directionalMove3 = side * s.move3;
        double directionalMove8 = side * s.move8;
        double directionalBtc = side * s.btcMove5;
        double directionalFlow = side * s.flowNorm;

        double rangePosition = recentRange > 0 ? (s.ethLast - s.recentLow) / recentRange : 0.5;
        double sideRangePosition = side > 0 ? rangePosition : 1.0 - rangePosition;

        /*
         * v2.27.0 — CLEAN C1 CANDIDATE
         * Validé en playback offline sur v2.26.0 → v2.26.4 :
         * 13 entrées théoriques, 13 TP, 0 SL, 0 TIME.
         * Recherche uniquement. Aucun trade réel.
         */
        if (directionalMove1 < 0.80)
            return reject(s, "CLEAN_C1_MOVE1_WEAK", "Move1 insuffisant pour Clean C1", 0, movement);

        if (directionalMove3 < 0.60)
            return reject(s, "CLEAN_C1_MOVE3_WEAK", "Move3 insuffisant pour Clean C1", 0, movement);

        if (directionalMove8 < 0.00 || directionalMove8 > 7.00)
            return reject(s, "CLEAN_C1_MOVE8_BAD", "Move8 non aligné ou trop extrême", 0, movement);

        if (volumeRatio < 0.25)
            return reject(s, "CLEAN_C1_VOLUME_LOW", "Volume minimum absent", 0, movement);

        if (recentRangeRatio < 2.00 || recentRangeRatio > 5.00)
            return reject(s, "CLEAN_C1_RANGE_BAD", "Range récent hors zone propre", 0, movement);

        if (sideRangePosition > 0.65)
            return reject(s, "CLEAN_C1_ENTRY_LATE", "Prix trop tard dans le range", 0, movement);

        if (directionalBtc < -0.0020)
            return reject(s, "CLEAN_C1_BTC_AGAINST", "BTC trop opposé au sens ETH", 0, movement);

        if (directionalFlow < -0.20)
            return reject(s, "CLEAN_C1_FLOW_AGAINST", "Flow trop opposé au sens ETH", 0, movement);

        int score = cleanC1Score(directionalMove1, directionalMove3, directionalMove8,
                volumeRatio, recentRangeRatio, sideRangePosition, directionalBtc, directionalFlow);

        double target = 2.20;
        double stop = 1.10;

        double entry = side > 0 ? (positive(s.ethAsk) ? s.ethAsk : s.ethLast)
                : (positive(s.ethBid) ? s.ethBid : s.ethLast);

        double tp = entry + side * target;
        double sl = entry - side * stop;
        String sideName = side > 0 ? "LONG" : "SHORT";

        int quantity = score >= 88 ? 5 : score >= 82 ? 4 : 3;

        SignalDecision decision = SignalDecision.signal(sideName, family, score, quantity,
                round2(entry), round2(tp), round2(sl), target, stop,
                movement.impulse, true, movement.origin, movement.extreme, movement.distance);

        record(s.now, decision.reasonCode,
                "CLEAN_C1_CANDIDATE · playback v2.26.0-v2.26.4 · score " + score + "/100");
        return decision;
    }

    private static int cleanC1Score(double move1, double move3, double move8,
                                    double volumeRatio, double recentRangeRatio,
                                    double sideRangePosition, double btc, double flow) {
        int score = 72;
        score += clamp((int)Math.round((move1 - 0.80) * 4.0), 0, 8);
        score += clamp((int)Math.round((move3 - 0.60) * 3.5), 0, 8);
        score += clamp((int)Math.round(Math.min(move8, 4.0) * 1.5), 0, 8);
        score += volumeRatio >= 0.50 ? 4 : 0;
        score += volumeRatio >= 1.20 ? 4 : 0;
        score += recentRangeRatio >= 2.20 && recentRangeRatio <= 4.20 ? 5 : 0;
        score += sideRangePosition <= 0.55 ? 4 : 0;
        score += btc >= 0 ? 4 : 0;
        score += flow >= 0 ? 4 : 0;
        return clamp(score, 72, 94);
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
        double target = Math.max(2.85, avgRange * 1.95);
        target += Math.min(2.4, recentRange * 0.18);
        target += Math.min(1.2, Math.max(0, volumeRatio - 1.0) * 0.45);
        target += Math.min(1.0, flowPower * 0.75);
        if (score >= 82) target += 0.8;
        if (score >= 88) target += 0.8;
        return round2(Math.max(2.80, Math.min(8.80, target)));
    }

    public static double computeStop(double avgRange, double recentRange, int score) {
        double volatilityStop = Math.max(1.35, avgRange * 1.12);
        double noiseBuffer = Math.min(0.90, Math.max(0.22, recentRange * 0.10));
        double stop = volatilityStop + noiseBuffer;

        if (score >= 82) {
            stop = Math.max(stop, avgRange * 1.25 + 0.25);
        }

        return round2(Math.max(1.35, Math.min(3.40, stop)));
    }

    public static double computeStructureStop(double baseStop, int side, double entry,
                                              double recentHigh, double recentLow, double avgRange) {
        double buffer = Math.max(0.22, Math.min(0.55, avgRange * 0.25));
        double stop = baseStop;

        if (side > 0 && positive(recentLow) && positive(entry) && recentLow < entry) {
            stop = Math.max(stop, (entry - recentLow) + buffer);
        } else if (side < 0 && positive(recentHigh) && positive(entry) && recentHigh > entry) {
            stop = Math.max(stop, (recentHigh - entry) + buffer);
        }

        return round2(Math.max(1.35, Math.min(3.40, stop)));
    }

    public static int computeQuantity(int score, double stop, double target, boolean consumed) {
        double feeRoundTrip = 1.33;
        double riskPerEth = stop + feeRoundTrip;
        double netPerEth = target - feeRoundTrip;
        if (netPerEth <= 0 || netPerEth / riskPerEth < 0.72) return 0;
        int quantity = score >= 90 && netPerEth / riskPerEth >= 1.20 ? 7
                : score >= 85 && netPerEth / riskPerEth >= 1.05 ? 6
                : score >= 78 ? 5 : score >= 70 ? 4 : 3;
        return consumed ? Math.min(quantity, 4) : quantity;
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

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }

    private static final class Movement {
        final String impulse; final boolean reset; final double origin, extreme, distance; final boolean consumed;
        Movement(String impulse, boolean reset, double origin, double extreme, double distance, boolean consumed) {
            this.impulse=impulse; this.reset=reset; this.origin=origin; this.extreme=extreme; this.distance=distance; this.consumed=consumed;
        }
    }
}
