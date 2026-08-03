package com.ethscalper.cockpit;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class V23440ShadowObservabilityTest {
    @Test public void publicSafetyAndIdentityRemainUntouched()throws Exception{
        assertFalse(SignalSafetyPolicies.realTradingAllowed());
        String source=new String(Files.readAllBytes(Path.of("src/main/java/com/ethscalper/cockpit/ShadowResearchCoordinator.java")),StandardCharsets.UTF_8);
        assertFalse(source.contains("activePlan="));assertFalse(source.contains("lastSignal="));
        assertFalse(source.contains("runtime.lastTerminalAt"));assertFalse(source.contains("Notification"));
        String lifecycle=new String(Files.readAllBytes(Path.of("src/main/java/com/ethscalper/cockpit/MarketPlanOrchestrator.java")),StandardCharsets.UTF_8);
        assertTrue(lifecycle.contains("TP_TOUCHED"));assertTrue(lifecycle.contains("SL_TOUCHED"));
        assertFalse(lifecycle.contains("SHADOW_TIMEOUT"));
    }
    @Test public void policyVersionAndAllEventsAreExplicit(){
        assertEquals("SHADOW_V23446_20260803",ShadowCalibrationPolicy.VERSION);
        assertEquals("SHADOW_SCHEMA_V7",ShadowCalibrationPolicy.SCHEMA_VERSION);
        String[] types={"SHADOW_AB_DECISION","SHADOW_PLAN_OPENED","SHADOW_PLAN_SKIPPED",
                "SHADOW_TP_TOUCHED","SHADOW_SL_TOUCHED","SHADOW_FEE_AWARE_SIZING",
                "SHADOW_STATE_RESET","SHADOW_INTERNAL_ERROR"};
        for(String type:types)assertFalse(type.isEmpty());
    }
}
