package com.ethscalper.cockpit;
import org.junit.Test;import java.nio.file.*;import static org.junit.Assert.*;
public final class V23449DeliveryConfigurationTest {
    @Test public void versionAndLabelExact()throws Exception{String s=read("app/build.gradle");assertTrue(s.contains("versionCode 23462"));assertTrue(s.contains("versionName '2.34.6.2'"));assertTrue(s.contains("NMC Stable 6.2"));}
    @Test public void workflowAndArtifactExact()throws Exception{String s=read(".github/workflows/nmc-ci.yml");assertTrue(s.contains("NMC v2.34.6.2 Stable CI"));assertTrue(s.contains("NMC-v2.34.6.2-stable-apk"));}
 @Test public void stableApplicationIdUnchanged()throws Exception{String s=read("app/build.gradle");assertTrue(s.contains("applicationId 'com.ethscalper.cockpit'"));assertTrue(s.contains("applicationIdSuffix '.stable'"));}
 @Test public void engineIdentityExact(){assertEquals("2.34.5.0",CvCorePolicy.VERSION_NAME);assertEquals("NMC_SCALP_CV_CORE_V1",CvCorePolicy.ENGINE_ID);}
 @Test public void tradingIsManualOnly(){assertFalse(SignalSafetyPolicies.realTradingAllowed());}
 private static String read(String p)throws Exception{Path root=Paths.get(System.getProperty("user.dir")).toAbsolutePath();Path file=root.resolve(p);if(!Files.exists(file)&&root.getParent()!=null)file=root.getParent().resolve(p);return new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8);}
}
