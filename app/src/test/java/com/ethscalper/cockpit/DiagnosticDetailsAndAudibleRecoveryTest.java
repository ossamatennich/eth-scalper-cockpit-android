package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class DiagnosticDetailsAndAudibleRecoveryTest {
    @Test public void minimalStatusPreservesRecorderEventsPlansAndAlertHealth() throws Exception {
        String service=source("MarketWatchService.java");
        String minimal=method(service,"private void publishMinimalStatus",
                "private JSONObject marketStatusJson");
        assertTrue(minimal.contains("currentRecorderSummaryJson()"));
        assertTrue(minimal.contains("StatusPayloadPolicy.recentDiagnostics("));
        assertTrue(minimal.contains("audibleChannelJson()"));
        assertTrue(minimal.contains("activePlans.put"));
        assertTrue(minimal.contains("statusSerializationFallback\",true"));
        assertFalse(minimal.contains("state.put(\"activePlans\",new JSONArray())"));
    }

    @Test public void optionalResearchSectionCannotCollapseWholeStatus() throws Exception {
        String service=source("MarketWatchService.java");
        String full=method(service,"private synchronized void broadcastStatus",
                "private void recordStatusSerializationFailure");
        assertTrue(full.contains("statusSerializationFallback\",false"));
        assertTrue(full.contains("putOptionalStatusObject(state,\"engineMetrics\""));
        assertTrue(full.contains("state.put(\"overnightRecorder\",currentRecorderSummaryJson())"));
        assertTrue(full.contains("state.put(\"audibleAlertChannel\",audibleChannelJson())"));
    }

    @Test public void technicalDetailsExplainFallbackRecorderAndAlertChannel() throws Exception {
        String activity=source("MainActivity.java");
        assertTrue(activity.contains("technicalDetailsText(latestState,recorder)"));
        String details=method(activity,"private static String technicalDetailsText",
                "private static String recentEvents");
        assertTrue(details.contains("INDEX RECORDER"));
        assertTrue(details.contains("ALERTE SONORE"));
        assertTrue(details.contains("statusSerializationFallback"));
        assertTrue(details.contains("statusError"));
        assertFalse(activity.contains("recorder==null?\"Index indisponible\""));
    }

    @Test public void newChannelDoesNotReusePersistedV1Settings() throws Exception {
        String service=source("MarketWatchService.java");
        assertEquals("nmc_final_signal_loud_v2",MarketWatchService.FINAL_SIGNAL_LOUD_CHANNEL_ID);
        assertFalse(service.contains("FINAL_SIGNAL_LOUD_CHANNEL_ID = \"nmc_final_signal_loud_v1\""));
        assertTrue(service.contains("signals.setSound(sound, audio)"));
        assertTrue(service.contains("signals.setVibrationPattern(ALERT_VIBRATION)"));
    }

    @Test public void businessDedupeRequiresBothPostAndReadyChannel() {
        assertFalse(FinalSignalAlertPolicy.shouldWriteBusinessDedupe(false,false,true));
        assertFalse(FinalSignalAlertPolicy.shouldWriteBusinessDedupe(false,true,false));
        assertTrue(FinalSignalAlertPolicy.shouldWriteBusinessDedupe(false,true,true));
        assertFalse(FinalSignalAlertPolicy.shouldWriteBusinessDedupe(true,true,true));
    }

    @Test public void tradingRulesRemainUntouched() {
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
        assertTrue(SignalSafetyPolicies.isTerminalStatus("TP_TOUCHED"));
        assertTrue(SignalSafetyPolicies.isTerminalStatus("SL_TOUCHED"));
        assertFalse(SignalSafetyPolicies.isTerminalStatus("TIMEOUT"));
    }

    private static String source(String name)throws Exception{
        return new String(Files.readAllBytes(Path.of(
                "src/main/java/com/ethscalper/cockpit/"+name)),StandardCharsets.UTF_8);
    }

    private static String method(String source,String start,String end){
        int from=source.indexOf(start),to=source.indexOf(end,from+start.length());
        assertTrue("missing "+start,from>=0);assertTrue("missing "+end,to>from);
        return source.substring(from,to);
    }
}
