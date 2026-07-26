package com.ethscalper.cockpit;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Causal OLS60 regime used exclusively by P02 at final confirmation. */
public final class TrendRegime60 {
    public static final String TREND = "TREND";
    public static final String REVERSAL = "REVERSAL";
    public static final String INSUFFICIENT = "V2330_P02_OLS60_INSUFFICIENT";
    public static final String REGIME_REJECTED = "V2330_P02_OLS60_REGIME_REJECTED";
    public static final String TREND_CONFIRMED = "V2330_P02_TREND_CONFIRMED";
    public static final String REVERSAL_CONFIRMED = "V2330_P02_REVERSAL_CONFIRMED";
    private static final long MINUTE_MS = 60_000L;
    private static final double EPS = 1e-12;

    private TrendRegime60() {}

    public static List<Point> pointsFromMinuteCloses(List<MinuteClose> candles,
                                                     long confirmationAt,
                                                     double currentEthLast) {
        Map<Long, Point> lastByMinute = new HashMap<>();
        if (candles != null) {
            for (MinuteClose candle : candles) {
                if (candle == null || candle.openTime > confirmationAt
                        || !positive(candle.close)) continue;
                long minute = Math.floorDiv(candle.openTime, MINUTE_MS);
                Point current = lastByMinute.get(minute);
                if (current == null || candle.openTime >= current.at) {
                    lastByMinute.put(minute, new Point(candle.openTime, candle.close));
                }
            }
        }
        if (confirmationAt >= 0L && positive(currentEthLast)) {
            long currentMinute = Math.floorDiv(confirmationAt, MINUTE_MS);
            lastByMinute.put(currentMinute, new Point(confirmationAt, currentEthLast));
        }

        long endMinute = Math.floorDiv(confirmationAt, MINUTE_MS);
        List<Point> points = new ArrayList<>(60);
        for (long minute = endMinute - 59L; minute <= endMinute; minute++) {
            Point point = lastByMinute.get(minute);
            if (point != null) points.add(point);
        }
        return points;
    }

    public static Result evaluate(String side, double a,
                                  NormalizedSignalMetrics.Result metrics,
                                  List<Point> points, long confirmationAt) {
        int direction = "LONG".equals(side) ? 1 : "SHORT".equals(side) ? -1 : 0;
        if (direction == 0 || !positive(a) || metrics == null || !metrics.valid
                || points == null || confirmationAt < 0L) {
            return Result.rejected(INSUFFICIENT, 0, Double.NaN, Double.NaN, 0L);
        }

        Map<Long, Point> lastByMinute = new HashMap<>();
        for (Point point : points) {
            if (point == null || point.at > confirmationAt || !positive(point.price)) continue;
            long minute = Math.floorDiv(point.at, MINUTE_MS);
            Point current = lastByMinute.get(minute);
            if (current == null || point.at >= current.at) lastByMinute.put(minute, point);
        }

        long endMinute = Math.floorDiv(confirmationAt, MINUTE_MS);
        double[] prices = new double[60];
        int count = 0;
        long lastPointAt = 0L;
        for (int i = 0; i < 60; i++) {
            long minute = endMinute - 59L + i;
            Point point = lastByMinute.get(minute);
            if (point == null) continue;
            prices[i] = point.price;
            count++;
            if (point.at > lastPointAt) lastPointAt = point.at;
        }
        if (count != 60) {
            return Result.rejected(INSUFFICIENT, count, Double.NaN, Double.NaN, lastPointAt);
        }

        double meanX = 29.5;
        double meanY = 0.0;
        for (double price : prices) meanY += price;
        meanY /= 60.0;
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < 60; i++) {
            double dx = i - meanX;
            numerator += dx * (prices[i] - meanY);
            denominator += dx * dx;
        }
        double slope = numerator / denominator;
        double t60 = direction * slope * 60.0 / a;
        if (!Double.isFinite(slope) || !Double.isFinite(t60)) {
            return Result.rejected(INSUFFICIENT, count, slope, t60, lastPointAt);
        }
        if (t60 >= 2.00 - EPS && t60 <= 8.00 + EPS) {
            return new Result(true, TREND_CONFIRMED, TREND, count, slope, t60, lastPointAt);
        }
        if (t60 >= -12.00 - EPS && t60 <= -2.00 + EPS
                && metrics.m8 >= 1.00 - EPS && metrics.f60 >= 0.50 - EPS
                && metrics.e <= 0.10 + EPS) {
            return new Result(true, REVERSAL_CONFIRMED, REVERSAL,
                    count, slope, t60, lastPointAt);
        }
        return Result.rejected(REGIME_REJECTED, count, slope, t60, lastPointAt);
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    public static final class Point {
        public final long at;
        public final double price;
        public Point(long at, double price) { this.at = at; this.price = price; }
    }

    public static final class MinuteClose {
        public final long openTime;
        public final double close;
        public MinuteClose(long openTime, double close) {
            this.openTime = openTime;
            this.close = close;
        }
    }

    public static final class Result {
        public final boolean accepted;
        public final String reasonCode;
        public final String mode;
        public final int count;
        public final double slope;
        public final double t60;
        public final long lastPointAt;

        private Result(boolean accepted, String reasonCode, String mode, int count,
                       double slope, double t60, long lastPointAt) {
            this.accepted = accepted;
            this.reasonCode = reasonCode;
            this.mode = mode;
            this.count = count;
            this.slope = slope;
            this.t60 = t60;
            this.lastPointAt = lastPointAt;
        }

        private static Result rejected(String reasonCode, int count, double slope,
                                       double t60, long lastPointAt) {
            return new Result(false, reasonCode, "", count, slope, t60, lastPointAt);
        }
    }
}
