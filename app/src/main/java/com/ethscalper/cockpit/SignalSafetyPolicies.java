package com.ethscalper.cockpit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Pure safety and lifecycle policies shared by the service and deterministic unit tests. */
public final class SignalSafetyPolicies {
    public static final double RESEARCH_ROUND_TRIP_COST_PER_ETH = 1.43;

    private SignalSafetyPolicies() {}

    public static boolean realTradingAllowed() {
        return false;
    }

    public static boolean staleFeedBlocksNewEntry(boolean feedFresh) {
        return !feedFresh;
    }

    public static boolean lifecycleMustRun(boolean feedFresh) {
        return true;
    }

    public static boolean marketableAtCreation(String side, double bid, double ask, double limit) {
        if ("LONG".equals(side)) return finitePositive(ask) && ask <= limit;
        if ("SHORT".equals(side)) return finitePositive(bid) && bid >= limit;
        return false;
    }

    public static boolean shouldExtendRangeFade(double scenarioRisk, double scenarioProgress,
                                                double move3Aligned, double move8Aligned,
                                                double avgRange20) {
        return scenarioRisk <= 0.45
                && scenarioProgress >= 0.35
                && move3Aligned > 0.0
                && move8Aligned > -0.25 * Math.max(0.0, avgRange20);
    }

    public static String executionClassification(boolean marketableAtCreation,
                                                 long createdAt, long departureAt,
                                                 long firstEntryTouchAt,
                                                 double maxProgressBeforeFill,
                                                 boolean simulatedFilled,
                                                 boolean terminalResolved) {
        if (firstEntryTouchAt > 0 && departureAt > 0 && firstEntryTouchAt > departureAt) {
            return maxProgressBeforeFill >= 0.80 ? "LATE_RETURN_NEAR_TARGET" : "LATE_RETURN_PARTIAL";
        }
        if (simulatedFilled && !terminalResolved) return "OPEN_ACTIVE_RISK";
        if (marketableAtCreation) return simulatedFilled ? "OPEN_ACTIVE_RISK" : "MARKETABLE_AT_CREATION";
        if (departureAt <= 0) return "PENDING_NO_DEPARTURE";
        long elapsed = Math.max(0L, departureAt - createdAt);
        if (elapsed <= 120_000L) return "FAST_DEPARTURE";
        if (elapsed < 15 * 60_000L) return "DELAYED_DEPARTURE";
        return "POST_TIMEOUT_DEPARTURE";
    }

    public static String deterministicSignature(SignalDecision signal, long timestampBucket) {
        if (signal == null) return "";
        String raw = signal.side + "|" + signal.family + "|"
                + price(signal.entry) + "|" + price(signal.takeProfit) + "|"
                + price(signal.stopLoss) + "|" + timestampBucket;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public static boolean candidateIsAudible() {
        return false;
    }

    public static boolean finalSignalIsAudible(boolean signatureAlreadyAlerted) {
        return !signatureAlreadyAlerted;
    }

    public static boolean lifecycleUpdateIsAudible() {
        return false;
    }

    public static int confirmedNotificationId(String signature) {
        return 30_000 + Math.floorMod(signature == null ? 0 : signature.hashCode(), 60_000);
    }

    public static boolean absoluteTimeoutReached(long activeSince, long now) {
        return activeSince > 0 && now - activeSince >= 45 * 60_000L;
    }

    /** AI may annotate diagnostics after publication, but the published plan is immutable. */
    public static SignalDecision preservePublishedPlan(SignalDecision published) {
        return published;
    }

    public static String publicAction(long confirmedAt, long now, boolean stillValid) {
        if (!stillValid) return "SIGNAL EXPIRÉ — NE PAS ENTRER";
        if (confirmedAt > 0 && now - confirmedAt <= 120_000L) return "À EXÉCUTER MAINTENANT";
        return "GÉRER LE PLAN ACTIF";
    }

    public static RealizedAndLatentResult result(boolean terminalResolved, double realizedMovePerEth,
                                                  double markedMovePerEth, int quantity,
                                                  long openRiskAgeMs) {
        int qty = Math.max(0, quantity);
        double fee = terminalResolved ? RESEARCH_ROUND_TRIP_COST_PER_ETH * qty : 0.0;
        double realizedGross = terminalResolved ? realizedMovePerEth * qty : 0.0;
        double realizedNet = terminalResolved ? realizedGross - fee : 0.0;
        double latentGross = terminalResolved ? 0.0 : markedMovePerEth * qty;
        double latentNet = terminalResolved ? 0.0
                : latentGross - RESEARCH_ROUND_TRIP_COST_PER_ETH * qty;
        return new RealizedAndLatentResult(terminalResolved, realizedGross, fee, realizedNet,
                latentGross, latentNet, Math.max(0L, openRiskAgeMs));
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static String price(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    public static final class RealizedAndLatentResult {
        public final boolean terminalResolved;
        public final double realizedGross;
        public final double realizedFees;
        public final double realizedNet;
        public final double latentGross;
        public final double latentNet;
        public final long openRiskAgeMs;

        private RealizedAndLatentResult(boolean terminalResolved, double realizedGross,
                                        double realizedFees, double realizedNet,
                                        double latentGross, double latentNet,
                                        long openRiskAgeMs) {
            this.terminalResolved = terminalResolved;
            this.realizedGross = realizedGross;
            this.realizedFees = realizedFees;
            this.realizedNet = realizedNet;
            this.latentGross = latentGross;
            this.latentNet = latentNet;
            this.openRiskAgeMs = openRiskAgeMs;
        }
    }

    public static final class P01CooldownTracker {
        private long lastConfirmedAt;

        public void candidateDetected(long at) {
            // Deliberately no-op.
        }

        public void candidateRejected(long at) {
            // Deliberately no-op.
        }

        public void finalConfirmed(long at) {
            lastConfirmedAt = at;
        }

        public long lastConfirmedAt() {
            return lastConfirmedAt;
        }

        public boolean coolingDown(long now) {
            return lastConfirmedAt > 0 && now - lastConfirmedAt < SignalEngine.COOLDOWN_MS;
        }
    }
}
