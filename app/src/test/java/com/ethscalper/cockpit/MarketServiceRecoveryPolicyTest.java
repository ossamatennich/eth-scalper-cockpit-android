package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarketServiceRecoveryPolicyTest {
    private static String source(String relative)throws Exception{
        Path root=Path.of(System.getProperty("user.dir"));
        Path path=root.resolve(relative);
        if(!Files.exists(path)&&root.getParent()!=null)path=root.getParent().resolve(relative);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
    @Test public void retryScheduleIsBoundedAndBecomesPeriodic(){
        assertEquals(1_500L,MarketServiceRecoveryPolicy.delayForAttempt(0));
        assertEquals(5_000L,MarketServiceRecoveryPolicy.delayForAttempt(1));
        assertEquals(12_000L,MarketServiceRecoveryPolicy.delayForAttempt(2));
        assertEquals(30_000L,MarketServiceRecoveryPolicy.delayForAttempt(3));
        assertEquals(30_000L,MarketServiceRecoveryPolicy.delayForAttempt(100));
    }

    @Test public void onlyACompleteFreshNativeFeedStopsRecovery(){
        assertTrue(MarketServiceRecoveryPolicy.isOperational(true,true,0));
        assertTrue(MarketServiceRecoveryPolicy.isOperational(true,true,10));
        assertFalse(MarketServiceRecoveryPolicy.isOperational(false,true,0));
        assertFalse(MarketServiceRecoveryPolicy.isOperational(true,false,0));
        assertFalse(MarketServiceRecoveryPolicy.isOperational(true,true,-1));
        assertFalse(MarketServiceRecoveryPolicy.isOperational(true,true,11));
    }

    @Test public void activityStartsBeforePermissionAndRetriesWithoutDiagnosticReset()throws Exception{
        String activity=source("app/src/main/java/com/ethscalper/cockpit/MainActivity.java");
        int create=activity.indexOf("@Override protected void onCreate");
        int start=activity.indexOf("sendServiceAction(MarketWatchService.ACTION_START,null)",create);
        int permission=activity.indexOf("requestNotificationPermission()",create);
        assertTrue(start>create&&permission>start);
        assertTrue(activity.contains("onRequestPermissionsResult"));
        assertTrue(activity.contains("@Override protected void onResume"));
        assertTrue(activity.contains("scheduleStartupRecovery()"));
    }

    @Test public void serviceRequestsImmediateRestFallbackOnEveryStart()throws Exception{
        String service=source("app/src/main/java/com/ethscalper/cockpit/MarketWatchService.java");
        int start=service.indexOf("@Override public int onStartCommand");
        int fallback=service.indexOf("maybeRefreshRestFallback(System.currentTimeMillis())",start);
        int reset=service.indexOf("ACTION_RESET_DIAGNOSTICS.equals(action)",start);
        assertTrue(fallback>start&&fallback<reset);
    }
}
