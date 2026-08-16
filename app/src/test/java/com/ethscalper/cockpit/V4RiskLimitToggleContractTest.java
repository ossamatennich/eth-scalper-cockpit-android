package com.ethscalper.cockpit;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class V4RiskLimitToggleContractTest {

    @Test public void cumulativeRiskTogglePreservesFrozenSizing() throws Exception {
        String runtime=source("src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java");
        String ui=source("src/main/java/com/ethscalper/cockpit/V4MainActivity.java");

        assertTrue(runtime.contains("getBoolean(KEY_SIMULTANEOUS_RISK_LIMIT,true)"));
        assertTrue(runtime.contains("desired=effectiveRiskForNewPlan(desired);"));
        assertTrue(runtime.contains("double risk=effectiveRiskForNewPlan(p.allocatedRiskFraction);"));
        assertTrue(runtime.contains("?Math.min(desired,V4RiskSizer.remainingRisk(account.equity(),store.active()))"));

        assertTrue(ui.contains("Limite de risque simultané"));
        assertTrue(ui.contains("Limite de risque désactivée"));
        assertTrue(ui.contains("riskLimit.setChecked(runtime.simultaneousRiskLimitEnabled())"));
        assertTrue(runtime.contains("putBoolean(KEY_SIMULTANEOUS_RISK_LIMIT,enabled).commit()"));
        assertTrue(ui.contains("boolean riskLimitEnabled=riskLimit.isChecked()"));
        assertTrue(ui.contains("runtime.setSimultaneousRiskLimitEnabled(riskLimitEnabled)"));
        assertTrue(ui.contains("parseLocalizedNumber"));
        assertTrue(ui.indexOf("runtime.setSimultaneousRiskLimitEnabled(riskLimitEnabled)")
                < ui.indexOf("runtime.account().update("));

        assertEquals(.0225,V4RiskSizer.CORE_RISK,0);
        assertEquals(.0075,V4RiskSizer.FALLBACK_RISK,0);
        assertEquals(.024,V4RiskSizer.COMBINED_CAP,0);
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(Path.of(path)),StandardCharsets.UTF_8);
    }
}
