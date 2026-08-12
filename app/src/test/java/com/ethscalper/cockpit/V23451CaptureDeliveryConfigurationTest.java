package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class V23451CaptureDeliveryConfigurationTest {
    @Test public void releaseIdentityAndCaptureSchemaAreExact()throws Exception{String gradle=read(
            "app/build.gradle"),workflow=read(".github/workflows/nmc-ci.yml");
        assertTrue(gradle.contains("versionCode 23455"));assertTrue(gradle.contains(
                "versionName '2.34.5.5'"));assertTrue(gradle.contains("NMC Stable 5.5"));
        assertTrue(workflow.contains("NMC v2.34.5.5 Stable CI"));assertTrue(workflow.contains(
                "NMC-v2.34.5.5-stable-apk"));assertEquals("NMC_CAUSAL_MARKET_CAPTURE_V4",
                MicrostructureMarketRecord.SCHEMA);assertEquals(4,MicrostructureMarketRecord.FORMAT_VERSION);}

    @Test public void publicEnginePolicyAndSafetyRemainFrozen(){assertEquals("NMC_SCALP_CV_CORE_V1",
            CvCorePolicy.ENGINE_ID);assertEquals("SCALP_CV_CORE_V1_20260806",CvCorePolicy.POLICY_ID);
        assertEquals(1.43,CvCorePolicy.RESULT_COST_PER_UNIT,0);assertRoute(CvCorePolicy.DUAL_EXHAUSTION_SHORT,
                1,4,1.75,14.55);assertRoute(CvCorePolicy.CAPITULATION_LONG,2,2.5,1.5,14.55);
        assertRoute(CvCorePolicy.P02_BALANCED_SHORT,3,3,1.25,7.275);
        assertFalse(SignalSafetyPolicies.realTradingAllowed());}

    @Test public void captureUsesOnlyFuturesPublicEndpoints()throws Exception{String source=read(
            "app/src/main/java/com/ethscalper/cockpit/MarketFeedEndpointPool.java").toLowerCase();
        assertTrue(source.contains("fstream.binance.com"));assertTrue(source.contains(
                "fapi.binance.com/fapi/v1"));assertFalse(source.contains("api-key"));
        assertFalse(source.contains("/order"));assertFalse(source.contains("/account"));}

    private static void assertRoute(CvCorePolicy.Route route,int priority,double target,double stop,
            double budget){assertEquals(priority,route.priority);assertEquals(target,route.targetMultiple,0);
        assertEquals(stop,route.stopMultiple,0);assertEquals(budget,route.riskBudgetUsdt,0);}
    private static String read(String value)throws Exception{java.nio.file.Path root=Paths.get(
            System.getProperty("user.dir")).toAbsolutePath();java.nio.file.Path file=root.resolve(value);
        if(!Files.exists(file)&&root.getParent()!=null)file=root.getParent().resolve(value);
        return new String(Files.readAllBytes(file),StandardCharsets.UTF_8);}
}
