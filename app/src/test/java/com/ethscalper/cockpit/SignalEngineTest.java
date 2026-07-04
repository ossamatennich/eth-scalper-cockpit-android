package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class SignalEngineTest {
    private MarketSnapshot snapshot(long now, long lastSignalAt, double move1, double move3,
                                    double move8, double flow, double btcMove,
                                    double volume, double high, double low) {
        return MarketSnapshot.builder(now)
                .lastSignalAt(lastSignalAt)
                .eth(100, 99.99, 100.01)
                .btc(60_000, 59_999, 60_001)
                .candleCounts(30, 10)
                .averages(1.0, 100)
                .movement(move1, move3, move8, high, low)
                .flow(flow, volume)
                .btcMove5(btcMove)
                .build();
    }

    @Test public void c1FreshLong() {
        SignalDecision d = new SignalEngine().evaluate(snapshot(1_000_000, 0, 1.0, 1.2, 2.0, .30, .001, 160, 103, 96));
        assertTrue(d.isSignal()); assertEquals("LONG", d.side); assertEquals("C1 cassure fraîche", d.family);
    }

    @Test public void c1FreshShort() {
        SignalDecision d = new SignalEngine().evaluate(snapshot(1_000_000, 0, -1.0, -1.2, -2.0, -.30, -.001, 160, 104, 97));
        assertTrue(d.isSignal()); assertEquals("SHORT", d.side);
    }

    @Test public void c2ControlledLongAndShort() {
        SignalEngine engine = new SignalEngine();
        SignalDecision longDecision = engine.evaluate(snapshot(1_000_000, 0, .10, 1.25, 1.7, .30, .001, 170, 103, 97));
        SignalDecision shortDecision = engine.evaluate(snapshot(2_000_000, 0, -.10, -1.25, -1.7, -.30, -.001, 170, 103, 97));
        assertTrue(longDecision.isSignal()); assertEquals("C2 reprise contrôlée", longDecision.family);
        assertTrue(shortDecision.isSignal()); assertEquals("SHORT", shortDecision.side);
    }

    @Test public void btcIsOnlyAVeto() {
        SignalDecision d = new SignalEngine().evaluate(snapshot(1_000_000, 0, 1.0, 1.2, 2.0, .30, -.003, 160, 103, 96));
        assertEquals("ATTENDRE", d.decision); assertEquals("BTC_VETO", d.reasonCode);
    }

    @Test public void consumedMovementIsRejected() {
        SignalDecision d = new SignalEngine().evaluate(snapshot(1_000_000, 0, 2.0, 2.2, 7.0, .35, .001, 180, 108, 96));
        assertEquals("MOVE_CONSUMED", d.reasonCode); assertTrue(d.movementConsumed);
    }

    @Test public void cooldownIsReturned() {
        long now = 1_000_000;
        SignalDecision d = new SignalEngine().evaluate(snapshot(now, now - 30_000, 1.0, 1.2, 2.0, .30, .001, 160, 103, 96));
        assertEquals("COOLDOWN", d.reasonCode);
    }

    @Test public void scoreTooLowIsDiagnosed() {
        SignalDecision d = new SignalEngine().evaluate(snapshot(1_000_000, 0, .80, .90, 1.0, .051, 0, 100, 101, 98));
        assertEquals("SCORE_TOO_LOW", d.reasonCode); assertFalse(d.isSignal());
    }

    @Test public void targetAndStopAreDynamic() {
        double lowTarget = SignalEngine.computeTarget(.8, 2, 1.0, .1, 65);
        double highTarget = SignalEngine.computeTarget(1.8, 7, 2.0, .8, 90);
        double lowStop = SignalEngine.computeStop(.8, 2, 65);
        double highStop = SignalEngine.computeStop(1.8, 7, 85);
        assertTrue(highTarget > lowTarget); assertTrue(highStop > lowStop);
    }

    @Test public void quantityStaysBetweenThreeAndSeven() {
        assertEquals(3, SignalEngine.computeQuantity(65, 1.0, 3.1, false));
        assertEquals(5, SignalEngine.computeQuantity(80, 1.0, 4.0, false));
        assertEquals(7, SignalEngine.computeQuantity(92, .8, 5.0, false));
        assertEquals(4, SignalEngine.computeQuantity(92, .8, 5.0, true));
    }

    @Test public void diagnosticsAreBoundedAndResettable() {
        SignalEngine engine = new SignalEngine();
        for (int i=0; i<230; i++) engine.evaluate(MarketSnapshot.builder(i).build());
        assertEquals(200, engine.recentDiagnostics(500).size());
        assertEquals("NO_DATA", engine.recentDiagnostics(1).get(0).code);
        engine.clearDiagnostics();
        assertTrue(engine.recentDiagnostics(10).isEmpty());
    }
}
