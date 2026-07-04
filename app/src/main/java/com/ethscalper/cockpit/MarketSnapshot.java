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
    }

    public static Builder builder(long now) { return new Builder(now); }

    public static final class Builder {
        private final long now;
        private long lastSignalAt;
        private double ethLast, ethBid, ethAsk, btcLast, btcBid, btcAsk;
        private int ethCandles, btcCandles;
        private double avgRange20, avgVolume20, lastVolume, move1, move3, move8;
        private double flowNorm, btcMove5, recentHigh, recentLow;

        private Builder(long now) { this.now = now; }

        public Builder lastSignalAt(long v) { lastSignalAt = v; return this; }
        public Builder eth(double last, double bid, double ask) { ethLast=last; ethBid=bid; ethAsk=ask; return this; }
        public Builder btc(double last, double bid, double ask) { btcLast=last; btcBid=bid; btcAsk=ask; return this; }
        public Builder candleCounts(int ethCount, int btcCount) { ethCandles=ethCount; btcCandles=btcCount; return this; }
        public Builder averages(double range, double volume) { avgRange20=range; avgVolume20=volume; return this; }
        public Builder movement(double one, double three, double eight, double high, double low) {
            move1=one; move3=three; move8=eight; recentHigh=high; recentLow=low; return this;
        }
        public Builder flow(double normalized, double volume) { flowNorm=normalized; lastVolume=volume; return this; }
        public Builder btcMove5(double v) { btcMove5=v; return this; }
        public MarketSnapshot build() { return new MarketSnapshot(this); }
    }
}
