package com.ethscalper.cockpit;

/** Pure, directionally symmetric metrics shared by the v2.33.0 sleeves. */
public final class NormalizedSignalMetrics {
    private NormalizedSignalMetrics() {}

    public static Result calculate(String side, SignalDecision candidate,
                                   MarketSnapshot snapshot, double adverseExcursion) {
        return calculateInternal(MarketProfile.eth(), side, candidate, snapshot,
                adverseExcursion, true);
    }

    public static Result calculate(MarketProfile profile, String side,
                                   SignalDecision candidate, MarketSnapshot snapshot,
                                   double adverseExcursion) {
        if (profile == null) return Result.invalid();
        if (MarketProfile.ETH_SYMBOL.equals(profile.symbol)) {
            return calculate(side, candidate, snapshot, adverseExcursion);
        }
        return calculateInternal(profile, side, candidate, snapshot, adverseExcursion, false);
    }

    private static Result calculateInternal(MarketProfile profile, String side,
                                            SignalDecision candidate,
                                            MarketSnapshot snapshot,
                                            double adverseExcursion,
                                            boolean historicalEth) {
        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (direction == 0 || candidate == null || snapshot == null
                || !positive(candidate.entry) || !positive(snapshot.avgRange20)
                || !finite(adverseExcursion) || adverseExcursion < 0.0
                || !positive(snapshot.recentHigh) || !positive(snapshot.recentLow)
                || !finite(snapshot.move1) || !finite(snapshot.move3)
                || !finite(snapshot.move8) || !finite(snapshot.flow30)
                || !finite(snapshot.flow60) || !finite(snapshot.volumeRatio)
                || !finite(snapshot.rangePosition)) {
            return Result.invalid();
        }

        double aMin = historicalEth ? 0.35
                : profile.scaledMinimum(profile.aMinimumReference, candidate.entry);
        double a = Math.max(aMin, snapshot.avgRange20);
        double e = adverseExcursion / a;
        double rawRoom = direction > 0
                ? snapshot.recentHigh - candidate.entry
                : candidate.entry - snapshot.recentLow;
        double r = Math.max(0.0, rawRoom);
        double directionalEdge = direction > 0
                ? snapshot.rangePosition : 1.0 - snapshot.rangePosition;
        return new Result(true, direction, a, adverseExcursion, e, r, rawRoom / a,
                direction * snapshot.move1 / a,
                direction * snapshot.move3 / a,
                direction * snapshot.move8 / a,
                direction * snapshot.flow30,
                direction * snapshot.flow60,
                snapshot.volumeRatio, directionalEdge);
    }

    private static boolean positive(double value) {
        return finite(value) && value > 0.0;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    public static final class Result {
        public final boolean valid;
        public final int direction;
        public final double a;
        public final double adverseExcursion;
        public final double e;
        public final double r;
        public final double room;
        public final double m1;
        public final double m3;
        public final double m8;
        public final double f30;
        public final double f60;
        public final double volumeRatio;
        public final double directionalEdge;

        private Result(boolean valid, int direction, double a, double adverseExcursion,
                       double e, double r, double room, double m1, double m3,
                       double m8, double f30, double f60, double volumeRatio,
                       double directionalEdge) {
            this.valid = valid;
            this.direction = direction;
            this.a = a;
            this.adverseExcursion = adverseExcursion;
            this.e = e;
            this.r = r;
            this.room = room;
            this.m1 = m1;
            this.m3 = m3;
            this.m8 = m8;
            this.f30 = f30;
            this.f60 = f60;
            this.volumeRatio = volumeRatio;
            this.directionalEdge = directionalEdge;
        }

        private static Result invalid() {
            return new Result(false, 0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }
}
