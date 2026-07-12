package com.ethscalper.cockpit;

public final class MarketSnapshot {
    public final long now;
    public final long lastSignalAt;
    public final double ethLast;
    public final double ethBid;
    public final double ethAsk;
    public final double btcLast;
    public final double btcBid;
    public final double btcAsk;
    public final int ethCandles;
    public final int btcCandles;
    public final int ethCandleCount;
    public final int btcCandleCount;
    public final double avgRange20;
    public final double avgVolume20;
    public final double lastVolume;
    public final double move1;
    public final double move3;
    public final double move8;
    public final double flowNorm;
    public final double btcMove5;
    public final double recentHigh;
    public final double recentLow;

    // v2.30.7 Professional Feature Replay Lab
    public final double recentRange;
    public final double volumeRatio;
    public final double rangePosition;
    public final double distanceToHigh;
    public final double distanceToLow;
    public final double roomLong;
    public final double roomShort;
    public final double pullbackFromHigh;
    public final double pullbackFromLow;

    public final double move1Norm;
    public final double move3Norm;
    public final double move8Norm;
    public final double moveAccel13;
    public final double moveAccel38;

    public final double flow15;
    public final double flow30;
    public final double flow60;
    public final double flow120;
    public final double deltaFlow15_60;
    public final double deltaFlow30_120;
    public final double flowAccel;

    public final double btcMove1;
    public final double btcMove3;
    public final double btcMove8;
    public final double btcAccel1_5;
    public final double btcAccel3_8;

    public final double breakoutHighDistance;
    public final double breakoutLowDistance;
    public final double antiBurstScore;

    private MarketSnapshot(Builder b) {
        now = b.now;
        lastSignalAt = b.lastSignalAt;
        ethLast = b.ethLast;
        ethBid = b.ethBid;
        ethAsk = b.ethAsk;
        btcLast = b.btcLast;
        btcBid = b.btcBid;
        btcAsk = b.btcAsk;
        ethCandles = b.ethCandles;
        btcCandles = b.btcCandles;
        ethCandleCount = b.ethCandles;
        btcCandleCount = b.btcCandles;
        avgRange20 = b.avgRange20;
        avgVolume20 = b.avgVolume20;
        lastVolume = b.lastVolume;
        move1 = b.move1;
        move3 = b.move3;
        move8 = b.move8;
        flowNorm = b.flowNorm;
        btcMove5 = b.btcMove5;
        recentHigh = b.recentHigh;
        recentLow = b.recentLow;

        recentRange = b.recentRange;
        volumeRatio = b.volumeRatio;
        rangePosition = b.rangePosition;
        distanceToHigh = b.distanceToHigh;
        distanceToLow = b.distanceToLow;
        roomLong = b.roomLong;
        roomShort = b.roomShort;
        pullbackFromHigh = b.pullbackFromHigh;
        pullbackFromLow = b.pullbackFromLow;

        move1Norm = b.move1Norm;
        move3Norm = b.move3Norm;
        move8Norm = b.move8Norm;
        moveAccel13 = b.moveAccel13;
        moveAccel38 = b.moveAccel38;

        flow15 = b.flow15;
        flow30 = b.flow30;
        flow60 = b.flow60;
        flow120 = b.flow120;
        deltaFlow15_60 = b.deltaFlow15_60;
        deltaFlow30_120 = b.deltaFlow30_120;
        flowAccel = b.flowAccel;

        btcMove1 = b.btcMove1;
        btcMove3 = b.btcMove3;
        btcMove8 = b.btcMove8;
        btcAccel1_5 = b.btcAccel1_5;
        btcAccel3_8 = b.btcAccel3_8;

        breakoutHighDistance = b.breakoutHighDistance;
        breakoutLowDistance = b.breakoutLowDistance;
        antiBurstScore = b.antiBurstScore;
    }

    public static Builder builder(long now) { return new Builder(now); }

    public static final class Builder {
        private final long now;
        private long lastSignalAt;
        private double ethLast, ethBid, ethAsk, btcLast, btcBid, btcAsk;
        private int ethCandles, btcCandles;
        private double avgRange20, avgVolume20, lastVolume, move1, move3, move8;
        private double flowNorm, btcMove5, recentHigh, recentLow;

        private double recentRange, volumeRatio, rangePosition, distanceToHigh, distanceToLow;
        private double roomLong, roomShort, pullbackFromHigh, pullbackFromLow;
        private double move1Norm, move3Norm, move8Norm, moveAccel13, moveAccel38;
        private double flow15, flow30, flow60, flow120, deltaFlow15_60, deltaFlow30_120, flowAccel;
        private double btcMove1, btcMove3, btcMove8, btcAccel1_5, btcAccel3_8;
        private double breakoutHighDistance, breakoutLowDistance, antiBurstScore;

        private Builder(long now) { this.now = now; }

        public Builder lastSignalAt(long v) { lastSignalAt = v; return this; }
        public Builder eth(double last, double bid, double ask) { ethLast=last; ethBid=bid; ethAsk=ask; return this; }
        public Builder btc(double last, double bid, double ask) { btcLast=last; btcBid=bid; btcAsk=ask; return this; }
        public Builder candleCounts(int ethCount, int btcCount) { ethCandles=ethCount; btcCandles=btcCount; return this; }
        public Builder averages(double range, double volume) { avgRange20=range; avgVolume20=volume; return this; }

        public Builder movement(double one, double three, double eight, double high, double low) {
            move1=one; move3=three; move8=eight; recentHigh=high; recentLow=low; return this;
        }

        public Builder flow(double normalized, double volume) {
            flowNorm=normalized; lastVolume=volume; return this;
        }

        public Builder btcMove5(double v) { btcMove5=v; return this; }

        public Builder professionalFeatures(
                double recentRange,
                double volumeRatio,
                double rangePosition,
                double distanceToHigh,
                double distanceToLow,
                double roomLong,
                double roomShort,
                double pullbackFromHigh,
                double pullbackFromLow,
                double move1Norm,
                double move3Norm,
                double move8Norm,
                double moveAccel13,
                double moveAccel38,
                double breakoutHighDistance,
                double breakoutLowDistance,
                double antiBurstScore) {
            this.recentRange = recentRange;
            this.volumeRatio = volumeRatio;
            this.rangePosition = rangePosition;
            this.distanceToHigh = distanceToHigh;
            this.distanceToLow = distanceToLow;
            this.roomLong = roomLong;
            this.roomShort = roomShort;
            this.pullbackFromHigh = pullbackFromHigh;
            this.pullbackFromLow = pullbackFromLow;
            this.move1Norm = move1Norm;
            this.move3Norm = move3Norm;
            this.move8Norm = move8Norm;
            this.moveAccel13 = moveAccel13;
            this.moveAccel38 = moveAccel38;
            this.breakoutHighDistance = breakoutHighDistance;
            this.breakoutLowDistance = breakoutLowDistance;
            this.antiBurstScore = antiBurstScore;
            return this;
        }

        public Builder flowWindows(double f15, double f30, double f60, double f120) {
            flow15 = f15;
            flow30 = f30;
            flow60 = f60;
            flow120 = f120;
            deltaFlow15_60 = f15 - f60;
            deltaFlow30_120 = f30 - f120;
            flowAccel = (f15 - f30) + (f30 - f60);
            return this;
        }

        public Builder btcMoves(double m1, double m3, double m5, double m8) {
            btcMove1 = m1;
            btcMove3 = m3;
            btcMove5 = m5;
            btcMove8 = m8;
            btcAccel1_5 = m1 - m5;
            btcAccel3_8 = m3 - m8;
            return this;
        }

        public MarketSnapshot build() { return new MarketSnapshot(this); }
    }
}
