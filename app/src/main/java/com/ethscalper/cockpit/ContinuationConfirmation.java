package com.ethscalper.cockpit;

/**
 * Pure, symmetric fill-time rules for CONTINUATION candidates.
 *
 * The class deliberately returns accept/reject facts only. It never changes entry, target,
 * stop or quantity and has no notification or trading side effect.
 */
public final class ContinuationConfirmation {
    public static final String C04_REJECT = "CONTINUATION_FRAICHEUR_PERDUE_AU_FILL";
    public static final String C07_REJECT = "CONTINUATION_CONFLIT_1M_8M_AU_FILL";
    public static final String C08_REJECT = "CONTINUATION_MOUVEMENT_CONSOMME_AVANT_FILL";
    public static final String P01_CONFIRMED = "CONTINUATION_CONFLUENCE_POSITIVE_AU_FILL";
    public static final String P01_MOVE1_REJECT = "CONTINUATION_P01_MOVE1_INSUFFISANT_AU_FILL";
    public static final String P01_MOVE3_REJECT = "CONTINUATION_P01_MOVE3_INSUFFISANT_AU_FILL";
    public static final String P01_FLOW_REJECT = "CONTINUATION_P01_FLOW_OPPOSE_AU_FILL";
    public static final String P01_STALE_REJECT = "V2326_ETH_FEED_STALE";

    private ContinuationConfirmation() {}

    public static boolean requiresP01(String family) {
        return family != null && family.contains("CONTINUATION");
    }

    public static Result evaluate(String side, MarketSnapshot snapshot, boolean feedFresh,
                                  long candidateCreatedAt, double targetProgressBeforeFill) {
        if (snapshot == null) return Result.reject("CONTINUATION_DONNEES_FILL_MANQUANTES");

        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (direction == 0) return Result.reject("CONTINUATION_COTE_INVALIDE_AU_FILL");

        double avg = Math.max(0.0, snapshot.avgRange20);
        double move1 = direction * snapshot.move1;
        double move3 = direction * snapshot.move3;
        double move8 = direction * snapshot.move8;
        double move15 = direction * snapshot.move15;
        double flow30 = direction * snapshot.flow30;
        long latency = Math.max(0L, snapshot.now - candidateCreatedAt);

        if (!feedFresh) {
            return new Result(false, P01_STALE_REJECT, false, move1, move3, move8, move15, flow30);
        }
        if (move1 < avg * 0.08 && flow30 <= 0.0) {
            return new Result(false, C04_REJECT, false, move1, move3, move8, move15, flow30);
        }
        if (move1 < 0.0 && move8 < 0.0) {
            return new Result(false, C07_REJECT, false, move1, move3, move8, move15, flow30);
        }
        if (targetProgressBeforeFill >= 0.40
                && latency >= 120_000L
                && move1 < 0.0
                && move3 < 0.0) {
            return new Result(false, C08_REJECT, false, move1, move3, move8, move15, flow30);
        }
        if (move1 < avg * 0.40) {
            return new Result(false, P01_MOVE1_REJECT, false, move1, move3, move8, move15, flow30);
        }
        if (move3 < avg) {
            return new Result(false, P01_MOVE3_REJECT, false, move1, move3, move8, move15, flow30);
        }
        if (flow30 < 0.0) {
            return new Result(false, P01_FLOW_REJECT, false, move1, move3, move8, move15, flow30);
        }

        return new Result(true, P01_CONFIRMED, move15 > 0.0,
                move1, move3, move8, move15, flow30);
    }

    public static final class Result {
        public final boolean confirmed;
        public final String reasonCode;
        public final boolean premium15m;
        public final double move1Aligned;
        public final double move3Aligned;
        public final double move8Aligned;
        public final double move15Aligned;
        public final double flow30Aligned;

        private Result(boolean confirmed, String reasonCode, boolean premium15m,
                       double move1Aligned, double move3Aligned, double move8Aligned,
                       double move15Aligned, double flow30Aligned) {
            this.confirmed = confirmed;
            this.reasonCode = reasonCode;
            this.premium15m = premium15m;
            this.move1Aligned = move1Aligned;
            this.move3Aligned = move3Aligned;
            this.move8Aligned = move8Aligned;
            this.move15Aligned = move15Aligned;
            this.flow30Aligned = flow30Aligned;
        }

        private static Result reject(String reasonCode) {
            return new Result(false, reasonCode, false,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }
}
