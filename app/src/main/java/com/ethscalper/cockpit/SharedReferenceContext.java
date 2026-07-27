package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.Deque;

/** Shared BTC context. BTC is never a traded runtime. */
public final class SharedReferenceContext {
    public static final String SYMBOL = MarketProfile.BTC_SYMBOL;
    public final Deque<MarketRuntime.MarketBar> candles = new ArrayDeque<>();
    public double last = Double.NaN, bid = Double.NaN, ask = Double.NaN;
    public long lastTickerAt, lastKlineAt, bookTickerMessages, klineMessages;

    public boolean fresh(long now, long maximumAgeMs) {
        return positive(last) && lastTickerAt > 0 && now - lastTickerAt <= maximumAgeMs;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
