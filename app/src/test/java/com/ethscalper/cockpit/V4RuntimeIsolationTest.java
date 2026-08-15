package com.ethscalper.cockpit;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class V4RuntimeIsolationTest {
    @Test public void productionManifestExposesOnlyV4ActivityAndService()throws Exception{
        String manifest=source("src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:name=\".V4MainActivity\""));
        assertTrue(manifest.contains("android:name=\".V4ForegroundService\""));
        assertFalse(manifest.contains("android:name=\".MainActivity\""));
        assertFalse(manifest.contains("android:name=\".MarketWatchService\""));
        assertTrue(manifest.indexOf("android.intent.action.MAIN")>manifest.indexOf(".V4MainActivity"));
    }

    @Test public void activityAndBootStartOnlyV4ForegroundHost()throws Exception{
        String activity=source("src/main/java/com/ethscalper/cockpit/V4MainActivity.java");
        String boot=source("src/main/java/com/ethscalper/cockpit/BootReceiver.java");
        assertTrue(activity.contains("new Intent(this,V4ForegroundService.class)"));
        assertFalse(activity.contains("new Intent(this,MarketWatchService.class)"));
        assertTrue(boot.contains("Intent.ACTION_BOOT_COMPLETED"));
        assertTrue(boot.contains("Intent.ACTION_MY_PACKAGE_REPLACED"));
        assertTrue(boot.contains("new Intent(context, V4ForegroundService.class)"));
        assertFalse(boot.contains("MarketWatchService"));
    }

    @Test public void foregroundHostOwnsOneSingletonV4RuntimeAndNoLegacyFeed()throws Exception{
        String host=source("src/main/java/com/ethscalper/cockpit/V4ForegroundService.java");
        String coordinator=source("src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java");
        assertTrue(host.contains("V4RuntimeCoordinator.start(this)"));
        assertTrue(host.contains("START_STICKY"));
        assertFalse(host.contains("MarketWatchService"));
        assertTrue(coordinator.contains("if(instance==null)instance=new V4RuntimeCoordinator"));
        assertTrue(coordinator.contains("market=new V4MarketDataClient"));
        assertFalse(coordinator.contains("MarketWatchService."));
    }

    @Test public void foregroundNotificationIsLowRealV4AndTargetsV4Activity()throws Exception{
        String host=source("src/main/java/com/ethscalper/cockpit/V4ForegroundService.java");
        String channels=source("src/main/java/com/ethscalper/cockpit/V4NotificationChannels.java");
        assertTrue(channels.contains("MONITOR_CHANNEL_ID = \"nmc_v4_monitor_v1\""));
        assertTrue(channels.contains("NotificationManager.IMPORTANCE_LOW"));
        assertTrue(host.contains("TITLE = \"NMC · Surveillance V4\""));
        assertTrue(host.contains("new Intent(this,V4MainActivity.class)"));
        assertFalse(host.contains("new Intent(this,MainActivity.class)"));
        assertTrue(host.contains("monitorContent(status)"));
        assertFalse(host.contains("ETHUSDT"));assertFalse(host.contains("BTCUSDT"));assertFalse(host.contains("CV Core"));
    }

    @Test public void monitorContentReflectsRuntimeState()throws Exception{
        JSONObject active=new JSONObject().put("scannerState","ACTIF").put("marketsConfigured",53).put("lastAnalysisAt",1_700_000_000_000L);
        assertTrue(V4ForegroundService.monitorContent(active).startsWith("ACTIF · 53 marchés · analyse "));
        assertEquals("SYNCHRO · données V4 en cours",V4ForegroundService.monitorContent(new JSONObject().put("scannerState","SYNCHRO")));
        assertEquals("HORS LIGNE · reconnexion en attente",V4ForegroundService.monitorContent(new JSONObject().put("scannerState","HORS LIGNE")));
    }

    @Test public void migrationRetiresOnlyLegacyMonitor()throws Exception{
        String host=source("src/main/java/com/ethscalper/cockpit/V4ForegroundService.java");
        assertTrue(host.contains("LEGACY_NOTIFICATION_ID = 22_801"));
        assertTrue(host.contains("LEGACY_CHANNEL_ID = \"eth_scalper_watch_v22801\""));
        assertTrue(host.contains("manager.cancel(LEGACY_NOTIFICATION_ID)"));
        assertTrue(host.contains("manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)"));
        assertFalse(host.contains("deleteNotificationChannel(V4NotificationChannels.LOUD_CHANNEL_ID)"));
    }

    private static String source(String path)throws Exception{return new String(Files.readAllBytes(Path.of(path)),StandardCharsets.UTF_8);}
}
