package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StableDeliveryConfigurationTest {
    private static String read(String relative) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path candidate = root.resolve(relative);
        if (!Files.exists(candidate) && root.getParent() != null) {
            candidate = root.getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }

    @Test public void stableBuildHasDurableIndependentIdentity() throws Exception {
        String gradle = read("app/build.gradle");
        assertTrue(gradle.contains("versionCode 23448"));
        assertTrue(gradle.contains("versionName '2.34.4.8'"));
        assertTrue(gradle.contains("applicationIdSuffix '.stable'"));
        assertTrue(gradle.contains("manifestPlaceholders = [appLabel: 'NMC Stable 4.8']"));
        assertTrue(gradle.contains("NMC_SIGNING_STORE_FILE"));
        assertFalse(gradle.contains("storePassword '"));
        assertFalse(gradle.contains("keyPassword '"));
    }

    @Test public void ciPublishesOnlyTheDurablySignedStableApk() throws Exception {
        String workflow = read(".github/workflows/nmc-ci.yml");
        assertTrue(workflow.contains("Prepare durable NMC signing key"));
        assertTrue(workflow.contains("gradle assembleStable"));
        assertTrue(workflow.contains("NMC-v2.34.4.8-stable-apk"));
        assertTrue(workflow.contains("app-stable.apk"));
        assertTrue(workflow.contains("apksigner\" verify --print-certs"));
        assertFalse(workflow.contains("NMC-v2.34.3.4-debug-apk"));
    }
}
