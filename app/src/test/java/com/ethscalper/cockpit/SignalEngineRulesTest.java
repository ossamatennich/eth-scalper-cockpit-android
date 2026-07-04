package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineRulesTest {
    private MarketSnapshot snapshot(int ethCandles, int btcCandles,
                                    double avgRange, double avgVolume,
                                    double move1, double move3, double move8,
                                    double flowNorm, double lastVolume,
                                    double btcMove5,
                                    double recentHigh, double recentLow) {
        return MarketSnapshot.builder(1_000_000L)
                .lastSignalAt(0)
                .eth(1800.00, 1799.99, 1800.01)
                .btc(63000.00, 62999.90, 63000.10)
                .candleCounts(ethCandles, btcCandles)
                .averages(avgRange, avgVolume)
                .movement(move1, move3, move8, recentHigh, recentLow)
                .flow(flowNorm, lastVolume)
                .btcMove5(btcMove5)
                .build();
    }

    @Test public void rejectsWhenNativeDataIsMissing() {
        SignalDecision d = new SignalEngine().evaluate(
                snapshot(10, 10, 1.0, 1000.0, 1.0, 1.0, 1.0, 1.0, 1000.0, 0.0, 1801.0, 1799.0));
        assertEquals("ATTENDRE", d.decision);
        assertEquals("NO_DATA", d.reasonCode);
    }

    @Test public void btcIsOnlyAVetoAgainstEthLong() {
        SignalDecision d = new SignalEngine().evaluate(
                snapshot(180, 180, 1.0, 1000.0, 1.0, 1.0, 1.0, 1.0, 1000.0, -0.0020, 1801.0, 1799.0));
        assertEquals("ATTENDRE", d.decision);
        assertEquals("BTC_VETO", d.reasonCode);
    }

    @Test public void rejectsWeakFlowEvenIfMoveExists() {
        SignalDecision d = new SignalEngine().evaluate(
                snapshot(180, 180, 1.0, 1000.0, 1.0, 1.0, 1.0, 0.0, 1000.0, 0.0, 1801.0, 1799.0));
        assertEquals("ATTENDRE", d.decision);
        assertEquals("FLOW_TOO_WEAK", d.reasonCode);
    }

    @Test public void rejectsConsumedMove() {
        SignalDecision d = new SignalEngine().evaluate(
                snapshot(180, 180, 1.0, 1000.0, 1.2, 1.4, 7.0, 1.0, 1000.0, 0.0, 1808.0, 1799.0));
        assertEquals("ATTENDRE", d.decision);
        assertEquals("MOVE_CONSUMED", d.reasonCode);
    }

    @Test public void quantityRejectsBadRiskReward() {
        assertEquals(0, SignalEngine.computeQuantity(90, 5.0, 1.0, false));
    }
}
