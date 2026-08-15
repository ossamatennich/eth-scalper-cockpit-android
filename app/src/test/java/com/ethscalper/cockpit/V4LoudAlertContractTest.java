package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class V4LoudAlertContractTest {
    @Test public void v4UsesEstablishedNmcLoudChannelOnly()throws Exception{
        String coordinator=source("src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java");
        assertTrue(coordinator.contains("MarketWatchService.ensureChannels(context)"));
        assertTrue(coordinator.contains("MarketWatchService.FINAL_SIGNAL_LOUD_CHANNEL_ID"));
        assertTrue(coordinator.contains("R.raw.eth_alert_loud"));
        assertTrue(coordinator.contains("MarketWatchService.ALERT_VIBRATION"));
        assertTrue(coordinator.contains("NotificationCompat.PRIORITY_MAX"));
        assertTrue(coordinator.contains("NotificationCompat.CATEGORY_ALARM"));
        assertFalse(coordinator.contains("nmc_v4_plans"));
        assertTrue(coordinator.contains("legacy_statuses_seeded"));
        assertTrue(coordinator.contains("notifyUndeliveredActiveState"));
        assertTrue(coordinator.contains("notifyUndeliveredTerminalState"));
    }
    @Test public void establishedChannelIsHighCustomSoundAndLongVibration()throws Exception{
        String service=source("src/main/java/com/ethscalper/cockpit/MarketWatchService.java");
        assertEquals("nmc_final_signal_loud_v2",MarketWatchService.FINAL_SIGNAL_LOUD_CHANNEL_ID);
        assertTrue(service.contains("NotificationManager.IMPORTANCE_HIGH"));
        assertTrue(service.contains("R.raw.eth_alert_loud"));
        assertTrue(service.contains("signals.enableVibration(true)"));
        assertTrue(service.contains("signals.setVibrationPattern(ALERT_VIBRATION)"));
        assertArrayEquals(new long[]{0,750,180,750,180,1200},MarketWatchService.ALERT_VIBRATION);
    }
    @Test public void android13PermissionIsDeclaredAndRequestedOnce()throws Exception{
        String manifest=source("src/main/AndroidManifest.xml"),activity=source("src/main/java/com/ethscalper/cockpit/V4MainActivity.java");
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"));
        assertTrue(activity.contains("requestNotificationPermissionOnce()"));
        assertTrue(activity.contains("Manifest.permission.POST_NOTIFICATIONS"));
        assertTrue(activity.contains("post_notifications_requested"));
    }
    @Test public void notificationTapReturnsToV4AndCannotTrade()throws Exception{
        String coordinator=source("src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java");
        assertTrue(coordinator.contains("new Intent(context,V4MainActivity.class)"));
        assertFalse(coordinator.contains("privateKey"));assertFalse(coordinator.contains("placeOrder"));assertFalse(coordinator.contains("cancelOrder"));
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
    }
    private static String source(String path)throws Exception{return new String(Files.readAllBytes(Path.of(path)),StandardCharsets.UTF_8);}
}
