package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class SignalEngineTest {
    private MarketSnapshot snapshot(long now, double move1, double move3, double move8,
                                    double move15, double flow30) {
        return MarketSnapshot.builder(now)
                .eth(100.0, 99.99, 100.01)
                .btc(60_000.0, 59_999.0, 60_001.0)
                .candleCounts(60, 20)
                .averages(1.0, 100.0)
                .movement(move1, move3, move8, 103.0, 97.0)
                .move15(move15)
                .flow(flow30, 120.0)
                .flowWindows(flow30, flow30, flow30, flow30)
                .build();
    }

    private ContinuationConfirmation.Result confirm(String side, MarketSnapshot snapshot,
                                                    boolean fresh, long createdAt,
                                                    double progress) {
        return ContinuationConfirmation.evaluate(side, snapshot, fresh, createdAt, progress);
    }

    @Test public void c04LongAccepted() {
        assertTrue(confirm("LONG", snapshot(200_000, .50, 1.20, .30, .20, .10),
                true, 190_000, 0).confirmed);
    }

    @Test public void c04LongRefused() {
        assertEquals(ContinuationConfirmation.C04_REJECT,
                confirm("LONG", snapshot(200_000, .05, 1.20, .30, .20, 0),
                        true, 190_000, 0).reasonCode);
    }

    @Test public void c04ShortAccepted() {
        assertTrue(confirm("SHORT", snapshot(200_000, -.50, -1.20, -.30, -.20, -.10),
                true, 190_000, 0).confirmed);
    }

    @Test public void c04ShortRefused() {
        assertEquals(ContinuationConfirmation.C04_REJECT,
                confirm("SHORT", snapshot(200_000, -.05, -1.20, -.30, -.20, 0),
                        true, 190_000, 0).reasonCode);
    }

    @Test public void c07LongConflict() {
        assertEquals(ContinuationConfirmation.C07_REJECT,
                confirm("LONG", snapshot(200_000, -.10, 1.20, -.10, .20, .10),
                        true, 190_000, 0).reasonCode);
    }

    @Test public void c07ShortConflict() {
        assertEquals(ContinuationConfirmation.C07_REJECT,
                confirm("SHORT", snapshot(200_000, .10, -1.20, .10, -.20, -.10),
                        true, 190_000, 0).reasonCode);
    }

    @Test public void c08ConsumedMoveIsRefused() {
        assertEquals(ContinuationConfirmation.C08_REJECT,
                confirm("LONG", snapshot(300_000, -.10, -.20, .20, .30, .10),
                        true, 100_000, .40).reasonCode);
    }

    @Test public void p01LongConfirmed() {
        ContinuationConfirmation.Result result = confirm("LONG",
                snapshot(200_000, .40, 1.00, .20, .10, 0),
                true, 190_000, 0);
        assertTrue(result.confirmed);
        assertEquals(ContinuationConfirmation.P01_CONFIRMED, result.reasonCode);
    }

    @Test public void p01ShortConfirmedSymmetrically() {
        ContinuationConfirmation.Result result = confirm("SHORT",
                snapshot(200_000, -.40, -1.00, -.20, -.10, 0),
                true, 190_000, 0);
        assertTrue(result.confirmed);
        assertEquals(.40, result.move1Aligned, 1e-9);
        assertEquals(1.00, result.move3Aligned, 1e-9);
    }

    @Test public void p01RejectsInsufficientMove1() {
        assertEquals(ContinuationConfirmation.P01_MOVE1_REJECT,
                confirm("LONG", snapshot(200_000, .30, 1.20, .20, .10, .10),
                        true, 190_000, 0).reasonCode);
    }

    @Test public void p01RejectsInsufficientMove3() {
        assertEquals(ContinuationConfirmation.P01_MOVE3_REJECT,
                confirm("LONG", snapshot(200_000, .50, .90, .20, .10, .10),
                        true, 190_000, 0).reasonCode);
    }

    @Test public void p01RejectsOppositeFlow() {
        assertEquals(ContinuationConfirmation.P01_FLOW_REJECT,
                confirm("LONG", snapshot(200_000, .50, 1.20, .20, .10, -.01),
                        true, 190_000, 0).reasonCode);
    }

    @Test public void p01RejectsStaleFeed() {
        assertEquals(ContinuationConfirmation.P01_STALE_REJECT,
                confirm("LONG", snapshot(200_000, .50, 1.20, .20, .10, .10),
                        false, 190_000, 0).reasonCode);
    }

    @Test public void premium15mLabelIsNonBlocking() {
        ContinuationConfirmation.Result normal = confirm("LONG",
                snapshot(200_000, .50, 1.20, .20, -.01, .10), true, 190_000, 0);
        ContinuationConfirmation.Result premium = confirm("LONG",
                snapshot(200_000, .50, 1.20, .20, .01, .10), true, 190_000, 0);
        assertTrue(normal.confirmed);
        assertFalse(normal.premium15m);
        assertTrue(premium.confirmed);
        assertTrue(premium.premium15m);
    }

    @Test public void p01CooldownStartsOnlyAfterFinalConfirmation() {
        SignalSafetyPolicies.P01CooldownTracker tracker =
                new SignalSafetyPolicies.P01CooldownTracker();
        tracker.candidateDetected(100_000);
        tracker.candidateRejected(110_000);
        assertEquals(0, tracker.lastConfirmedAt());
        assertFalse(tracker.coolingDown(120_000));
        tracker.finalConfirmed(130_000);
        assertEquals(130_000, tracker.lastConfirmedAt());
        assertTrue(tracker.coolingDown(130_001));
        assertFalse(tracker.coolingDown(130_000 + SignalEngine.COOLDOWN_MS));
    }

    @Test public void rangeFadeDoesNotRequireP01() {
        assertTrue(ContinuationConfirmation.requiresP01("SCALP_CONTINUATION"));
        assertFalse(ContinuationConfirmation.requiresP01("RANGE_FADE_LONG"));
    }

    @Test public void finalQuantityMapsAllFiveLevels() {
        assertEquals(3, SignalEngine.computeFinalConfirmedQuantity(74));
        assertEquals(4, SignalEngine.computeFinalConfirmedQuantity(75));
        assertEquals(5, SignalEngine.computeFinalConfirmedQuantity(80));
        assertEquals(6, SignalEngine.computeFinalConfirmedQuantity(85));
        assertEquals(7, SignalEngine.computeFinalConfirmedQuantity(90));
    }

    @Test public void quantityIsAlwaysWithinThreeAndSeven() {
        assertEquals(3, SignalEngine.computeFinalConfirmedQuantity(Integer.MIN_VALUE));
        assertEquals(7, SignalEngine.computeFinalConfirmedQuantity(Integer.MAX_VALUE));
    }
}
