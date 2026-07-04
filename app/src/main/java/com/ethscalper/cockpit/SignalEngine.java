package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class SignalEngine {
    public static final long COOLDOWN_MS = 8 * 60 * 1000L;
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
            return reject(s, "COOLDOWN", "Cooldown après le dernier signal", 0, movement);
        double spread = positive(s.ethBid) && positive(s.ethAsk) ? s.ethAsk - s.ethBid : Double.NaN;
        if (Double.isFinite(spread) && spread > Math.max(0.75, s.avgRange20 * 0.55))
            return reject(s, "SPREAD_BAD", "Spread ETH trop large", 0, movement);
        if (s.avgRange20 < 0.15)
            return reject(s, "RANGE_TOO_SMALL", "Range trop faible pour un scalp propre", 0, movement);

        double threshold = Math.max(0.75, s.avgRange20 * 0.55);
        int side = 0;
        String family = "";
        boolean reset = false;
        if (s.move1 > threshold && s.move3 > threshold * 1.15) {
            side = 1; family = "C1 cassure fraîche"; reset = true;
        } else if (s.move1 < -threshold && s.move3 < -threshold * 1.15) {
            side = -1; family = "C1 cassure fraîche"; reset = true;
        } else if (s.move3 > threshold * 1.35 && s.move1 > -s.avgRange20 * 0.25) {
            side = 1; family = "C2 reprise contrôlée"; reset = Math.abs(s.move1) <= s.avgRange20 * 0.65;
        } else if (s.move3 < -threshold * 1.35 && s.move1 < s.avgRange20 * 0.25) {
            side = -1; family = "C2 reprise contrôlée"; reset = Math.abs(s.move1) <= s.avgRange20 * 0.65;
        }
        if (side == 0) return reject(s, "NO_SETUP", "Aucun setup C1/C2 confirmé", 0, movement);

        double volumeRatio = s.lastVolume / s.avgVolume20;
        if (volumeRatio < 0.60)
            return reject(s, "VOLUME_TOO_LOW", "Volume trop faible", 0, movement);
        if ((side > 0 && s.flowNorm < 0.05) || (side < 0 && s.flowNorm > -0.05))
            return reject(s, "FLOW_TOO_WEAK", "Flow non aligné avec le mouvement", 0, movement);
        if ((side > 0 && s.btcMove5 < -0.0012) || (side < 0 && s.btcMove5 > 0.0012))
            return reject(s, "BTC_VETO", "BTC oppose le signal ETH", 0, movement);
        if (movement.consumed)
            return reject(s, "MOVE_CONSUMED", "Mouvement déjà trop consommé : ne pas poursuivre", 0, movement);
        if (!reset)
            return reject(s, "RESET_NOT_CONFIRMED", "Reset non confirmé", 0, movement);

        int score = 52;
        score += clamp((int)Math.round(Math.abs(s.move3) / Math.max(0.35, s.avgRange20) * 7), 0, 18);
        score += clamp((int)Math.round((volumeRatio - 1.0) * 10), 0, 12);
        score += clamp((int)Math.round(Math.abs(s.flowNorm) * 18), 0, 12);
        if ((side > 0 && s.btcMove5 > 0) || (side < 0 && s.btcMove5 < 0)) score += 8;
        if (family.startsWith("C2")) score -= 3;
        score = clamp(score, 0, 94);
        if (score < MIN_SCORE)
            return reject(s, "SCORE_TOO_LOW", "Score " + score + "/100 sous le minimum", score, movement);

        double recentRange = Math.max(0, s.recentHigh - s.recentLow);
        double target = computeTarget(s.avgRange20, recentRange, volumeRatio, Math.abs(s.flowNorm), score);
        double stop = computeStop(s.avgRange20, recentRange, score);
        int quantity = computeQuantity(score, stop, target, false);
        if (quantity < 3)
            return reject(s, "SCORE_TOO_LOW", "Ratio rendement/risque insuffisant", score, movement);
        double entry = side > 0 ? (positive(s.ethAsk) ? s.ethAsk : s.ethLast)
                : (positive(s.ethBid) ? s.ethBid : s.ethLast);
        double tp = entry + side * target;
        double sl = entry - side * stop;
        String sideName = side > 0 ? "LONG" : "SHORT";
        SignalDecision decision = SignalDecision.signal(sideName, family, score, quantity,
                round2(entry), round2(tp), round2(sl), target, stop, movement.impulse,
                reset, movement.origin, movement.extreme, movement.distance);
        record(s.now, decision.reasonCode, family + " · score " + score + "/100");
        return decision;
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
        double stop = Math.max(0.78, avgRange * 0.92) + Math.min(0.8, recentRange * 0.06);
        if (score >= 82) stop *= 0.92;
        return round2(Math.max(0.78, Math.min(2.35, stop)));
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
