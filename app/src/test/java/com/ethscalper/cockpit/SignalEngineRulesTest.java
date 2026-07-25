package com.ethscalper.cockpit;

import org.junit.Test;

import static org.junit.Assert.*;

public class SignalEngineRulesTest {
    private SignalDecision signal(int quantity) {
        return SignalDecision.confirmed("LONG", "SCALP_CONTINUATION · P01",
                ContinuationConfirmation.P01_CONFIRMED, "confirmé", 88, quantity,
                1900.50, 1903.30, 1899.15, 2.80, 1.35,
                "ACTIVE", true, 1898.0, 1901.0, 3.0);
    }

    @Test public void c01StaleBlocksCreationButKeepsLifecycle() {
        assertTrue(SignalSafetyPolicies.staleFeedBlocksNewEntry(false));
        assertTrue(SignalSafetyPolicies.lifecycleMustRun(false));
        assertFalse(SignalSafetyPolicies.staleFeedBlocksNewEntry(true));
    }

    @Test public void c05ExtendsOnlyCorrectRangeFadeContext() {
        assertTrue(SignalSafetyPolicies.shouldExtendRangeFade(.45, .35, .01, -.24, 1.0));
        assertFalse(SignalSafetyPolicies.shouldExtendRangeFade(.46, .35, .01, -.24, 1.0));
        assertFalse(SignalSafetyPolicies.shouldExtendRangeFade(.45, .34, .01, -.24, 1.0));
        assertFalse(SignalSafetyPolicies.shouldExtendRangeFade(.45, .35, 0, -.24, 1.0));
        assertFalse(SignalSafetyPolicies.shouldExtendRangeFade(.45, .35, .01, -.26, 1.0));
    }

    @Test public void absolute45MinuteTimeoutRemains() {
        long start = 1_000;
        assertFalse(SignalSafetyPolicies.absoluteTimeoutReached(start,
                start + 45 * 60_000L - 1));
        assertTrue(SignalSafetyPolicies.absoluteTimeoutReached(start,
                start + 45 * 60_000L));
    }

    @Test public void candidateNeverProducesSound() {
        assertFalse(SignalSafetyPolicies.candidateIsAudible());
    }

    @Test public void finalSignalSoundsExactlyOncePerSignature() {
        assertTrue(SignalSafetyPolicies.finalSignalIsAudible(false));
        assertFalse(SignalSafetyPolicies.finalSignalIsAudible(true));
    }

    @Test public void duplicateSignatureUsesSameNotificationId() {
        SignalDecision signal = signal(6);
        String a = SignalSafetyPolicies.deterministicSignature(signal, 1234);
        String b = SignalSafetyPolicies.deterministicSignature(signal, 1234);
        assertEquals(a, b);
        assertEquals(SignalSafetyPolicies.confirmedNotificationId(a),
                SignalSafetyPolicies.confirmedNotificationId(b));
    }

    @Test public void invalidationUpdatesSilently() {
        assertFalse(SignalSafetyPolicies.lifecycleUpdateIsAudible());
        assertEquals("SIGNAL EXPIRÉ — NE PAS ENTRER",
                SignalSafetyPolicies.publicAction(1000, 2000, false));
    }

    @Test public void aiCannotModifyQuantity() {
        SignalDecision published = signal(6);
        SignalDecision afterAi = SignalSafetyPolicies.preservePublishedPlan(published);
        assertSame(published, afterAi);
        assertEquals(6, afterAi.quantity);
    }

    @Test public void aiCannotModifyEntryTpOrSl() {
        SignalDecision published = signal(5);
        SignalDecision afterAi = SignalSafetyPolicies.preservePublishedPlan(published);
        assertEquals(1900.50, afterAi.entry, 0);
        assertEquals(1903.30, afterAi.takeProfit, 0);
        assertEquals(1899.15, afterAi.stopLoss, 0);
    }

    @Test public void marketableAtCreationLongAndShort() {
        assertTrue(SignalSafetyPolicies.marketableAtCreation("LONG", 99.9, 100.0, 100.0));
        assertFalse(SignalSafetyPolicies.marketableAtCreation("LONG", 99.9, 100.01, 100.0));
        assertTrue(SignalSafetyPolicies.marketableAtCreation("SHORT", 100.0, 100.1, 100.0));
        assertFalse(SignalSafetyPolicies.marketableAtCreation("SHORT", 99.99, 100.1, 100.0));
    }

    @Test public void executionClassificationsAreDistinct() {
        long created = 1_000;
        assertEquals("FAST_DEPARTURE", SignalSafetyPolicies.executionClassification(
                false, created, created + 120_000, 0, 0, false, false));
        assertEquals("DELAYED_DEPARTURE", SignalSafetyPolicies.executionClassification(
                false, created, created + 120_001, 0, 0, false, false));
        assertEquals("POST_TIMEOUT_DEPARTURE", SignalSafetyPolicies.executionClassification(
                false, created, created + 15 * 60_000L, 0, 0, false, false));
        assertEquals("LATE_RETURN_PARTIAL", SignalSafetyPolicies.executionClassification(
                false, created, created + 100_000, created + 200_000, .50, true, false));
        assertEquals("LATE_RETURN_NEAR_TARGET", SignalSafetyPolicies.executionClassification(
                false, created, created + 100_000, created + 200_000, .80, true, false));
        assertEquals("OPEN_ACTIVE_RISK", SignalSafetyPolicies.executionClassification(
                true, created, 0, created, 0, true, false));
    }

    @Test public void openRiskIsNotRealizedLoss() {
        SignalSafetyPolicies.RealizedAndLatentResult result =
                SignalSafetyPolicies.result(false, -1.35, -.50, 5, 300_000);
        assertFalse(result.terminalResolved);
        assertEquals(0, result.realizedGross, 0);
        assertEquals(0, result.realizedFees, 0);
        assertEquals(0, result.realizedNet, 0);
        assertTrue(result.latentNet < 0);
        assertEquals(300_000, result.openRiskAgeMs);
    }

    @Test public void realizedResearchCostIsUnifiedAt143PerEth() {
        SignalSafetyPolicies.RealizedAndLatentResult result =
                SignalSafetyPolicies.result(true, 2.80, 0, 5, 0);
        assertEquals(7.15, result.realizedFees, 1e-9);
        assertEquals(14.0 - 7.15, result.realizedNet, 1e-9);
    }

    @Test public void activeRiskActionChangesAfter120Seconds() {
        long confirmed = 1_000;
        assertEquals("À EXÉCUTER MAINTENANT",
                SignalSafetyPolicies.publicAction(confirmed, confirmed + 120_000, true));
        assertEquals("GÉRER LE PLAN ACTIF",
                SignalSafetyPolicies.publicAction(confirmed, confirmed + 120_001, true));
    }

    @Test public void automaticAndRealTradingAreDisabled() {
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
    }
}
