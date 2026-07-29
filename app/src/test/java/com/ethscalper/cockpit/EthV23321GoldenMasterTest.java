package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/** Immutable v2.33.2.1 ETH reference. Do not update to accommodate runtime changes. */
public final class EthV23321GoldenMasterTest {
    private static final long SEED = 23_321_042L;
    private static final int SNAPSHOTS = 20_000;

    @Test public void genericEthRuntimeMatchesLegacyForTwentyThousandSnapshotsBitForBit() {
        Random random = new Random(SEED);
        SignalEngine legacyEngine = new SignalEngine();
        SignalEngine genericEngine = new SignalEngine();
        MarketProfile eth = MarketProfile.eth();
        for (int i = 0; i < SNAPSHOTS; i++) {
            MarketSnapshot snapshot = snapshot(random, i);
            SignalDecision legacy = legacyEngine.evaluate(snapshot);
            SignalDecision generic = genericEngine.evaluate(snapshot, eth);
            assertDecisionExact(i, legacy, generic);
            assertEquals(SignalSafetyPolicies.candidateSignature(legacy),
                    SignalSafetyPolicies.candidateSignature(generic));
            assertEquals(SignalSafetyPolicies.deterministicSignature(legacy, i / 60L),
                    SignalSafetyPolicies.deterministicSignature(generic, i / 60L));

            if (legacy.isSignal()) {
                double adverse = random.nextDouble() * Math.max(.01, snapshot.avgRange20);
                DynamicTradePlan.Result oldPlan = DynamicTradePlan.calculate(
                        legacy.side, legacy.entry, snapshot.avgRange20, adverse,
                        snapshot.recentHigh, snapshot.recentLow, 7);
                DynamicTradePlan.Result newPlan = DynamicTradePlan.calculate(
                        eth, legacy.side, legacy.entry, snapshot.avgRange20, adverse,
                        snapshot.recentHigh, snapshot.recentLow, 7);
                assertDynamicPlanExact(oldPlan, newPlan);
                NormalizedSignalMetrics.Result oldMetrics = NormalizedSignalMetrics.calculate(
                        legacy.side, legacy, snapshot, adverse);
                NormalizedSignalMetrics.Result newMetrics = NormalizedSignalMetrics.calculate(
                        eth, legacy.side, legacy, snapshot, adverse);
                assertMetricsExact(oldMetrics, newMetrics);
                assertEquals(P01SleeveFilter.evaluate(oldMetrics, i % 90_001L).reasonCode,
                        P01SleeveFilter.evaluate(newMetrics, i % 90_001L).reasonCode);
            }
        }
    }

    @Test public void explicitEthFactoriesAndAliasesRemainHistoricallyExact() {
        MarketSnapshot legacy = MarketSnapshot.builder(1_000).eth(1900, 1899.99, 1900.01).build();
        MarketSnapshot explicit = MarketSnapshot.builder(1_000)
                .market(MarketProfile.eth(), 1900, 1899.99, 1900.01).build();
        assertEquals("ETHUSDT", explicit.symbol);
        assertEquals("ETH", explicit.asset);
        bits(legacy.ethLast, explicit.marketLast);
        bits(legacy.ethBid, explicit.marketBid);
        bits(legacy.ethAsk, explicit.marketAsk);
        bits(legacy.ethLast, explicit.ethLast);
        bits(legacy.ethBid, explicit.ethBid);
        bits(legacy.ethAsk, explicit.ethAsk);

        SignalDecision oldDecision = SignalDecision.signal("LONG", "CONTINUATION", 90, 3,
                1900, 1902.8, 1898.65, 2.8, 1.35, "ACTIVE", true, 1898, 1901, 3);
        SignalDecision newDecision = SignalDecision.signal(MarketProfile.eth(), "LONG",
                "CONTINUATION", 90, 3, 1900, 1902.8, 1898.65, 2.8, 1.35,
                "ACTIVE", true, 1898, 1901, 3);
        assertDecisionExact(0, oldDecision, newDecision);
        assertEquals(SignalSafetyPolicies.candidateSignature(oldDecision),
                SignalSafetyPolicies.candidateSignature(newDecision));
    }

    private static MarketSnapshot snapshot(Random r, int i) {
        double last = 1500 + r.nextDouble() * 1500;
        double spread = r.nextDouble() * .70;
        double bid = last - spread * .5;
        double ask = last + spread * .5;
        double avg = .20 + r.nextDouble() * 2.5;
        double m1 = (r.nextDouble() - .5) * avg * 5;
        double m3 = (r.nextDouble() - .5) * avg * 9;
        double m8 = (r.nextDouble() - .5) * avg * 15;
        double lowRoom = r.nextDouble() * 8;
        double highRoom = r.nextDouble() * 8;
        double range = lowRoom + highRoom;
        double rp = range > 0 ? lowRoom / range : .5;
        double volumeRatio = r.nextDouble() * 4;
        return MarketSnapshot.builder(1_700_000_000_000L + i * 1000L)
                .eth(last, bid, ask).btc(60_000, 59_999.5, 60_000.5)
                .candleCounts(30 + r.nextInt(151), 10 + r.nextInt(171))
                .averages(avg, 100 + r.nextDouble() * 1000)
                .movement(m1, m3, m8, last + highRoom, last - lowRoom)
                .move15((r.nextDouble() - .5) * avg * 20)
                .flow((r.nextDouble() - .5), 50 + r.nextDouble() * 1000)
                .flowWindows((r.nextDouble() - .5), (r.nextDouble() - .5),
                        (r.nextDouble() - .5), (r.nextDouble() - .5))
                .professionalFeatures(range, volumeRatio, rp, highRoom, lowRoom,
                        highRoom, lowRoom, highRoom * .1, lowRoom * .1,
                        m1 / avg, m3 / avg, m8 / avg, m1 / avg - m3 / avg,
                        m3 / avg - m8 / avg, highRoom, lowRoom, r.nextDouble())
                .btcMoves((r.nextDouble() - .5) * .006, (r.nextDouble() - .5) * .006,
                        (r.nextDouble() - .5) * .006, (r.nextDouble() - .5) * .006)
                .build();
    }

    private static void assertDecisionExact(int index, SignalDecision expected,
                                            SignalDecision actual) {
        assertEquals("decision " + index, expected.decision, actual.decision);
        assertEquals("reason " + index, expected.reasonCode, actual.reasonCode);
        assertEquals("text " + index, expected.reasonText, actual.reasonText);
        assertEquals("side " + index, expected.side, actual.side);
        assertEquals("family " + index, expected.family, actual.family);
        assertEquals("score " + index, expected.score, actual.score);
        assertEquals("quantity " + index, expected.quantity, actual.quantity);
        bits(expected.entry, actual.entry);
        bits(expected.takeProfit, actual.takeProfit);
        bits(expected.stopLoss, actual.stopLoss);
        bits(expected.targetMove, actual.targetMove);
        bits(expected.stopDistance, actual.stopDistance);
        assertEquals(expected.impulse, actual.impulse);
        assertEquals(expected.resetConfirmed, actual.resetConfirmed);
        bits(expected.movementOrigin, actual.movementOrigin);
        bits(expected.movementExtreme, actual.movementExtreme);
        bits(expected.movementDistance, actual.movementDistance);
        assertEquals(expected.movementConsumed, actual.movementConsumed);
    }

    private static void assertDynamicPlanExact(DynamicTradePlan.Result a,
                                               DynamicTradePlan.Result b) {
        assertEquals(a.valid, b.valid);
        assertEquals(a.reasonCode, b.reasonCode);
        bits(a.a, b.a); bits(a.adverseExcursion60, b.adverseExcursion60);
        bits(a.structuralRoom, b.structuralRoom); bits(a.stopRequired, b.stopRequired);
        bits(a.stopMaximum, b.stopMaximum); bits(a.targetFloor, b.targetFloor);
        bits(a.targetRaw, b.targetRaw); bits(a.targetDistance, b.targetDistance);
        bits(a.stopLoss, b.stopLoss); bits(a.takeProfit, b.takeProfit);
        bits(a.roundedStopDistance, b.roundedStopDistance);
        bits(a.roundedTargetDistance, b.roundedTargetDistance);
        bits(a.grossRewardRisk, b.grossRewardRisk); bits(a.riskPerEth, b.riskPerEth);
        assertEquals(a.finalQuantity, b.finalQuantity);
        bits(a.theoreticalMaximumLoss, b.theoreticalMaximumLoss);
    }

    private static void assertMetricsExact(NormalizedSignalMetrics.Result a,
                                           NormalizedSignalMetrics.Result b) {
        assertEquals(a.valid, b.valid); assertEquals(a.direction, b.direction);
        bits(a.a, b.a); bits(a.adverseExcursion, b.adverseExcursion); bits(a.e, b.e);
        bits(a.r, b.r); bits(a.room, b.room); bits(a.m1, b.m1); bits(a.m3, b.m3);
        bits(a.m8, b.m8); bits(a.f30, b.f30); bits(a.f60, b.f60);
        bits(a.volumeRatio, b.volumeRatio); bits(a.directionalEdge, b.directionalEdge);
    }

    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }
}
