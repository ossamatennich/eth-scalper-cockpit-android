package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class AudibleFinalSignalAlertTest {
    @Test public void testAlertNeverBuildsASilentNotification() throws Exception {
        String method=method(service(),"private boolean notifyTestAlert()",
                "private AudiblePostResult postAudibleFinalSignalAlert");
        assertTrue(method.contains("postAudibleFinalSignalAlert("));
        assertFalse(method.contains("buildSignalNotification"));
        assertFalse(method.contains("false"));
    }

    @Test public void silentTestCopyIsCompletelyRemoved() throws Exception {
        String source=service();
        assertFalse(source.contains("Test silencieux"));
        assertTrue(source.contains("NMC · TEST ALERTE SONORE"));
        assertTrue(source.contains("Tu dois entendre exactement l’alerte d’un futur signal confirmé."));
    }

    @Test public void testAndRealSignalsShareOneAudiblePostingMethod() throws Exception {
        String source=service();
        assertTrue(method(source,"private boolean notifyTestAlert()",
                "private AudiblePostResult postAudibleFinalSignalAlert")
                .contains("postAudibleFinalSignalAlert("));
        assertTrue(method(source,"private void notifyObservationSignal",
                "private void notifyMarketPlan").contains("postAudibleFinalSignalAlert("));
        assertTrue(method(source,"private void notifyMarketPlan",
                "private void notifyRestoredMarketPlan").contains("postAudibleFinalSignalAlert("));
    }

    @Test public void loudChannelIsNewAndFullyConfigured() throws Exception {
        String source=service();
        assertEquals("nmc_final_signal_loud_v1",MarketWatchService.FINAL_SIGNAL_LOUD_CHANNEL_ID);
        assertTrue(source.contains("NMC · Alertes de signaux confirmés"));
        assertTrue(source.contains("Alerte sonore forte uniquement lors d’un nouveau signal final confirmé."));
        assertTrue(source.contains("AudioAttributes.USAGE_ALARM"));
        assertTrue(source.contains("AudioAttributes.CONTENT_TYPE_SONIFICATION"));
        assertTrue(source.contains("R.raw.eth_alert_loud"));
        assertTrue(source.contains("signals.setVibrationPattern(ALERT_VIBRATION)"));
    }

    @Test public void historicalChannelIsNotUsedForAudibleAlerts() throws Exception {
        assertFalse(service().contains("eth_scalper_signal_final_v2330"));
    }

    @Test public void testAlertNeverWritesBusinessDedupe() {
        assertFalse(FinalSignalAlertPolicy.shouldWriteBusinessDedupe(true,true));
        assertFalse(FinalSignalAlertPolicy.shouldWriteBusinessDedupe(true,false));
    }

    @Test public void twoSuccessiveTestsAreBothEligibleAndAudible() {
        assertTrue(FinalSignalAlertPolicy.shouldAttempt(true,false));
        assertTrue(FinalSignalAlertPolicy.shouldAttempt(true,true));
        assertEquals(23_411_001,MarketWatchService.NOTIF_TEST_AUDIBLE_ID);
        try {
            assertTrue(service().contains(".setOnlyAlertOnce(!audible)"));
        } catch (Exception error) {
            fail(error.getMessage());
        }
    }

    @Test public void firstEthFinalSignalUsesAudibleCentralPath() throws Exception {
        String method=method(service(),"private void notifyObservationSignal",
                "private void notifyMarketPlan");
        assertTrue(method.contains("MarketProfile.ETH_SYMBOL, false, signature"));
        assertTrue(method.contains("ETHUSDT · SIGNAL CONFIRMÉ"));
    }

    @Test public void firstSolOrFutureMarketSignalUsesAudibleCentralPath() throws Exception {
        String method=method(service(),"private void notifyMarketPlan",
                "private void notifyRestoredMarketPlan");
        assertTrue(method.contains("plan.symbol,false,plan.notificationSignature"));
        assertTrue(method.contains("LIMIT %.2f · TP %.2f · SL %.2f · %d %s"));
    }

    @Test public void repeatedPlanIsNotAudibleAgain() {
        assertTrue(FinalSignalAlertPolicy.shouldAttempt(false,false));
        assertFalse(FinalSignalAlertPolicy.shouldAttempt(false,true));
    }

    @Test public void restoredPlansRemainSilent() throws Exception {
        String source=service();
        assertTrue(method(source,"private void notifyRestoredMarketPlan",
                "private void notifyMarketTerminal").contains("notifyMarketPlan(runtime.activePlan,false)"));
        assertFalse(SignalSafetyPolicies.restoredPlanIsAudible());
    }

    @Test public void tpTouchedRemainsASilentLifecycleUpdate() throws Exception {
        String method=method(service(),"private void notifyMarketTerminal",
                "private void notifyRestoredActivePlan");
        assertTrue(method.contains("TP_TOUCHED"));
        assertTrue(method.contains("buildSignalNotification(title,body,false)"));
    }

    @Test public void slTouchedRemainsASilentLifecycleUpdate() throws Exception {
        String method=method(service(),"private void notifyMarketTerminal",
                "private void notifyRestoredActivePlan");
        assertTrue(method.contains("SL ATTEINT"));
        assertTrue(method.contains("buildSignalNotification(title,body,false)"));
    }

    @Test public void vibrationTestDoesNotBuildOrPostSound() throws Exception {
        String method=method(service(),"private void vibrateAlert()",
                "private Notification buildWatchNotification");
        assertTrue(method.contains("VibrationEffect.createWaveform(ALERT_VIBRATION"));
        assertFalse(method.contains("buildSignalNotification"));
        assertFalse(method.contains("R.raw.eth_alert_loud"));
    }

    @Test public void failedDeliveryNeverConsumesTheSignature() throws Exception {
        assertFalse(FinalSignalAlertPolicy.shouldWriteBusinessDedupe(false,false));
        String method=method(service(),"private AudiblePostResult postAudibleFinalSignalAlert",
                "private void postSilentSignalNotification");
        assertTrue(method.indexOf("manager.notify(notificationId,notification)")
                < method.indexOf("putBoolean(key,true)"));
        String failure=method.substring(method.indexOf("catch(RuntimeException error)"));
        assertFalse(failure.contains("putBoolean"));
        assertTrue(failure.contains("AudiblePostResult.failed()"));
    }

    @Test public void alertAttemptCannotMutatePublishedPlan() throws Exception {
        String method=method(service(),"private AudiblePostResult postAudibleFinalSignalAlert",
                "private void postSilentSignalNotification");
        assertFalse(method.contains("activePlan="));
        assertFalse(method.contains(".entry="));
        assertFalse(method.contains(".takeProfit="));
        assertFalse(method.contains(".stopLoss="));
        assertFalse(method.contains(".quantity="));
    }

    @Test public void channelHealthReportsEveryRequiredState() {
        int high=4;
        assertEquals(FinalSignalAlertChannelStatus.State.NOTIFICATIONS_DISABLED,
                FinalSignalAlertChannelStatus.evaluate(false,true,high,high,"sound"));
        assertEquals(FinalSignalAlertChannelStatus.State.CHANNEL_DISABLED,
                FinalSignalAlertChannelStatus.evaluate(true,false,0,high,null));
        assertEquals(FinalSignalAlertChannelStatus.State.CHANNEL_LOW_IMPORTANCE,
                FinalSignalAlertChannelStatus.evaluate(true,true,high-1,high,"sound"));
        assertEquals(FinalSignalAlertChannelStatus.State.CHANNEL_SOUND_MISSING,
                FinalSignalAlertChannelStatus.evaluate(true,true,high,high,null));
        assertEquals(FinalSignalAlertChannelStatus.State.CHANNEL_READY,
                FinalSignalAlertChannelStatus.evaluate(true,true,high,high,"android.resource://sound"));
    }

    @Test public void tradingSafetyAndTpSlOnlyLifecycleRemainIntact() {
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
        assertTrue(SignalSafetyPolicies.isTerminalStatus("TP_TOUCHED"));
        assertTrue(SignalSafetyPolicies.isTerminalStatus("SL_TOUCHED"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT_15M"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("SCENARIO_INVALIDATED"));
    }

    private static String service() throws Exception {
        return new String(Files.readAllBytes(Path.of(
                "src/main/java/com/ethscalper/cockpit/MarketWatchService.java")),
                StandardCharsets.UTF_8);
    }

    private static String method(String source,String start,String end) {
        int from=source.indexOf(start);int to=source.indexOf(end,from+start.length());
        assertTrue("missing method start: "+start,from>=0);
        assertTrue("missing method end: "+end,to>from);
        return source.substring(from,to);
    }
}
