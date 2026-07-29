package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TrendRegime60Test {
    private static final long MINUTE = 60_000L;

    private SignalDecision candidate(String side) {
        int d = "LONG".equals(side) ? 1 : -1;
        return SignalDecision.signal(side, "P02 CONTINUATION", 90, 3,
                100, 100 + d * 2.8, 100 - d * 1.35,
                2.8, 1.35, "ACTIVE", true, 98, 102, 4);
    }

    private NormalizedSignalMetrics.Result metrics(String side, double m8,
                                                    double f60, double e) {
        int d = "LONG".equals(side) ? 1 : -1;
        MarketSnapshot s = MarketSnapshot.builder(1)
                .eth(100, 99.99, 100.01).averages(1, 100)
                .movement(d * .6, d * 1.3, d * m8, 102, 98)
                .flow(d * .2, 100).flowWindows(d * .2, d * .2, d * f60, d * f60)
                .professionalFeatures(4, 1, .8, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0).build();
        return NormalizedSignalMetrics.calculate(side, candidate(side), s, e);
    }

    private List<TrendRegime60.Point> series(double start, double slope, int count,
                                             long firstMinute) {
        List<TrendRegime60.Point> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long minute = firstMinute + i;
            out.add(new TrendRegime60.Point(minute * MINUTE + 1_000, start + slope * i - .01));
            out.add(new TrendRegime60.Point(minute * MINUTE + 50_000, start + slope * i));
        }
        return out;
    }

    private TrendRegime60.Result evaluate(String side, double slope,
                                          double m8, double f60, double e) {
        long first = 100;
        long confirmation = (first + 59) * MINUTE + 59_000;
        return TrendRegime60.evaluate(side, 1.0, metrics(side, m8, f60, e),
                series(100, slope, 60, first), confirmation);
    }

    @Test public void increasingDecreasingAndFlatSeriesAreCausal() {
        TrendRegime60.Result increasing = evaluate("LONG", .05, 1, .5, .1);
        assertTrue(increasing.accepted);
        assertEquals(TrendRegime60.TREND, increasing.mode);
        assertEquals(.05, increasing.slope, 1e-12);
        assertEquals(3.0, increasing.t60, 1e-9);

        TrendRegime60.Result decreasing = evaluate("SHORT", -.05, 1, .5, .1);
        assertTrue(decreasing.accepted);
        assertEquals(TrendRegime60.TREND, decreasing.mode);
        assertEquals(3.0, decreasing.t60, 1e-9);

        assertFalse(evaluate("LONG", 0, 1, .5, .1).accepted);
    }

    @Test public void insufficientOrInvalidMinutesAreRejected() {
        long first = 100;
        long confirmation = (first + 59) * MINUTE + 59_000;
        TrendRegime60.Result r = TrendRegime60.evaluate("LONG", 1,
                metrics("LONG", 1, .5, .1), series(100, .05, 59, first), confirmation);
        assertFalse(r.accepted);
        assertEquals(59, r.count);
        assertEquals(TrendRegime60.INSUFFICIENT, r.reasonCode);
    }

    @Test public void trendAndReversalModesAreSymmetricLongShort() {
        assertEquals(TrendRegime60.TREND, evaluate("LONG", .05, 1, .5, .1).mode);
        assertEquals(TrendRegime60.TREND, evaluate("SHORT", -.05, 1, .5, .1).mode);
        assertEquals(TrendRegime60.REVERSAL, evaluate("LONG", -.05, 1, .5, .1).mode);
        assertEquals(TrendRegime60.REVERSAL, evaluate("SHORT", .05, 1, .5, .1).mode);
    }

    @Test public void trendAndReversalT60BoundariesAreInclusive() {
        assertEquals(TrendRegime60.TREND, evaluate("LONG", 2.0 / 60.0, 1, .5, .1).mode);
        assertEquals(TrendRegime60.TREND, evaluate("LONG", 8.0 / 60.0, 1, .5, .1).mode);
        assertEquals(TrendRegime60.REVERSAL, evaluate("LONG", -2.0 / 60.0, 1, .5, .1).mode);
        assertEquals(TrendRegime60.REVERSAL, evaluate("LONG", -12.0 / 60.0, 1, .5, .1).mode);
        assertFalse(evaluate("LONG", (2.0 - 1e-9) / 60.0, 1, .5, .1).accepted);
        assertFalse(evaluate("LONG", (8.0 + 1e-9) / 60.0, 1, .5, .1).accepted);
    }

    @Test public void reversalRequiresM8Flow60AndLowExcursion() {
        assertFalse(evaluate("LONG", -.05, 1 - 1e-9, .5, .1).accepted);
        assertFalse(evaluate("LONG", -.05, 1, .5 - 1e-9, .1).accepted);
        assertFalse(evaluate("LONG", -.05, 1, .5, .1 + 1e-9).accepted);
    }

    @Test public void futurePointsNeverEnterOls() {
        long first = 100;
        long confirmation = (first + 59) * MINUTE + 59_000;
        List<TrendRegime60.Point> points = series(100, .05, 60, first);
        points.add(new TrendRegime60.Point(confirmation + 1, 10_000));
        TrendRegime60.Result r = TrendRegime60.evaluate("LONG", 1,
                metrics("LONG", 1, .5, .1), points, confirmation);
        assertEquals(.05, r.slope, 1e-12);
        assertTrue(r.lastPointAt <= confirmation);
    }
}
