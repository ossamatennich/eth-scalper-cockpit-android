package com.ethscalper.cockpit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.Assert.*;

public class V4ModelParityTest {
    @Test public void pythonAndJavaPredictionsMatch()throws Exception{byte[] modelBytes=Files.readAllBytes(Path.of("src/main/assets/v4_fallback_model.json"));V4ExtraTreesModel model=V4ExtraTreesModel.loadBytes(modelBytes);
        JSONObject fixture=new JSONObject(new String(Files.readAllBytes(Path.of("src/test/resources/v4_prediction_fixture.json")),java.nio.charset.StandardCharsets.UTF_8));JSONArray rows=fixture.getJSONArray("rows");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);JSONArray f=r.getJSONArray("features");double[] x=new double[f.length()];for(int j=0;j<x.length;j++)x[j]=f.getDouble(j);
            assertEquals(r.getDouble("long"),model.predictLong(x),1e-12);assertEquals(r.getDouble("short"),model.predictShort(x),1e-12);}}
    @Test public void manifestFreezesTrainingBefore2026()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_model_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));assertEquals("2025-12-31",m.getJSONArray("trainingDateRange").getString(1));
        assertEquals(120,m.getJSONObject("hyperparameters").getInt("n_estimators"));assertEquals(5,m.getJSONObject("hyperparameters").getInt("max_depth"));assertEquals(100,m.getJSONObject("hyperparameters").getInt("min_samples_leaf"));assertEquals(.7,m.getJSONObject("hyperparameters").getDouble("max_features"),0);assertEquals(23455,m.getJSONObject("hyperparameters").getInt("random_state"));}
    @Test public void frozenModelAndManifestRemainByteStable()throws Exception{byte[] model=Files.readAllBytes(Path.of("src/main/assets/v4_fallback_model.json"));
        byte[] manifest=Files.readAllBytes(Path.of("src/main/assets/v4_frozen_hash_manifest.json"));assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",sha(model));
        assertEquals("47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",sha(new String(manifest,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(new JSONObject(new String(manifest,java.nio.charset.StandardCharsets.UTF_8)).getBoolean("realTradingAllowed"));}
    @Test public void operationalFixManifestMatchesCanonicalHashes()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_operational_6_1_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",m.getString("parentFrozenManifestCanonicalSha256"));assertFalse(m.getBoolean("realTradingAllowed"));
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String path=f.getString("path");
            /*
             * Ces fichiers ont été volontairement modifiés par des releases
             * postérieures à 6.1. Le manifest 6.1 reste historique et immuable.
             * Leur état courant est désormais gelé par le manifest 6.8.
             */
            if("app/build.gradle".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4CreationPolicy.java".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4Engine.java".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4FallbackHistory.java".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4FeatureEngine.java".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4Plan.java".equals(path))continue;
            byte[] bytes=Files.readAllBytes(root.resolve(path));
            byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    @Test public void androidLaunchHotfixManifestMatchesCanonicalHashes()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_launch_6_2_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));assertFalse(m.getBoolean("realTradingAllowed"));assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",m.getString("modelSha256"));
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if("app/build.gradle".equals(f.getString("path"))||"app/src/main/java/com/ethscalper/cockpit/V4MainActivity.java".equals(f.getString("path")))continue;byte[] bytes=Files.readAllBytes(root.resolve(f.getString("path")));
            byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    @Test public void uiUxHotfixManifestMatchesCanonicalHashesWithoutChangingModel()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_ui_6_3_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("2.34.6.3",m.getString("versionName"));assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));assertEquals("UI_UX_AND_RUNTIME_DISPLAY_STATUS_ONLY",m.getString("scope"));assertFalse(m.getBoolean("realTradingAllowed"));
        assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",m.getString("modelSha256"));assertEquals("47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",m.getString("parentFrozenManifestCanonicalSha256"));
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if("app/build.gradle".equals(f.getString("path"))||"app/src/main/java/com/ethscalper/cockpit/V4MainActivity.java".equals(f.getString("path"))||"app/src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java".equals(f.getString("path")))continue;byte[] bytes=Files.readAllBytes(root.resolve(f.getString("path")));byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    @Test public void uiPolishManifestMatchesCanonicalHashesWithoutChangingModel()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_ui_6_4_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("2.34.6.4",m.getString("versionName"));assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));assertEquals("UI_UX_DISPLAY_AND_SCROLL_ONLY",m.getString("scope"));assertFalse(m.getBoolean("realTradingAllowed"));
        assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",m.getString("modelSha256"));assertEquals("47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",m.getString("parentFrozenManifestCanonicalSha256"));assertEquals("800364980b55c86748f1af6c60c4aec72a6ffdb4f500635354fd6c4c533341fa",m.getString("parentUiManifestCanonicalSha256"));
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if("app/build.gradle".equals(f.getString("path"))||"app/src/main/java/com/ethscalper/cockpit/V4MainActivity.java".equals(f.getString("path")))continue;byte[] bytes=Files.readAllBytes(root.resolve(f.getString("path")));byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    @Test public void operationalUxAlertManifestMatchesCanonicalHashesWithoutChangingModel()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_operational_6_5_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("2.34.6.5",m.getString("versionName"));assertEquals(23465,m.getInt("versionCode"));assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));assertEquals("UI_ALERTS_AND_PLAN_STATUS_PRESENTATION_ONLY",m.getString("scope"));assertFalse(m.getBoolean("realTradingAllowed"));
        assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",m.getString("modelSha256"));assertEquals("47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",m.getString("parentFrozenManifestCanonicalSha256"));
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String path=f.getString("path");if("app/build.gradle".equals(path)||"app/src/main/AndroidManifest.xml".equals(path)||"app/src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java".equals(path)||"app/src/main/java/com/ethscalper/cockpit/V4MainActivity.java".equals(path)||".github/scripts/android-launch-smoke.sh".equals(path)||".github/workflows/nmc-ci.yml".equals(path))continue;byte[] bytes=Files.readAllBytes(root.resolve(path));byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    @Test public void v4OnlyRuntimeManifestMatchesCanonicalHashesWithoutChangingModel()throws Exception{
        JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_runtime_6_6_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("2.34.6.6",m.getString("versionName"));
        assertEquals(23466,m.getInt("versionCode"));
        assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));
        assertEquals("FOREGROUND_HOST_MANIFEST_AND_NOTIFICATION_RUNTIME_ONLY",m.getString("scope"));
        assertFalse(m.getBoolean("realTradingAllowed"));
        assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",m.getString("modelSha256"));
        assertEquals("47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",m.getString("parentFrozenManifestCanonicalSha256"));

        JSONArray files=m.getJSONArray("files");
        Path root=Path.of("..");

        for(int i=0;i<files.length();i++){
            JSONObject f=files.getJSONObject(i);
            String path=f.getString("path");

            if("app/build.gradle".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4MainActivity.java".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java".equals(path)
                    ||".github/workflows/nmc-ci.yml".equals(path))continue;

            byte[] bytes=Files.readAllBytes(root.resolve(path));
            byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r\n","\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

            assertEquals(f.getString("sha256"),sha(canonical));
        }
    }

    @Test public void riskToggle67ManifestMatchesCanonicalHashesWithoutChangingModel()throws Exception{
        JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_risk_6_7_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));

        assertEquals("2.34.6.7",m.getString("versionName"));
        assertEquals(23467,m.getInt("versionCode"));
        assertEquals("NMC Stable 6.7",m.getString("label"));
        assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));
        assertEquals("SIMULTANEOUS_RISK_LIMIT_UI_AND_RUNTIME_ONLY",m.getString("scope"));
        assertFalse(m.getBoolean("realTradingAllowed"));

        assertEquals(
                "207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",
                m.getString("modelSha256")
        );

        assertEquals(
                "47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",
                m.getString("parentFrozenManifestCanonicalSha256")
        );

        assertEquals(
                "32427e195446e51b3de370ac86f87179fe6e98b34955614efa4fff22f63fdefa",
                m.getString("parentRuntime66ManifestCanonicalSha256")
        );

        byte[] parent=Files.readAllBytes(
                Path.of("src/main/assets/v4_runtime_6_6_manifest.json")
        );

        byte[] parentCanonical=new String(
                parent,
                java.nio.charset.StandardCharsets.UTF_8
        ).replace("\r\n","\n")
         .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals("32427e195446e51b3de370ac86f87179fe6e98b34955614efa4fff22f63fdefa",sha(parentCanonical));

        JSONArray files=m.getJSONArray("files");
        Path root=Path.of("..");

        for(int i=0;i<files.length();i++){
            JSONObject f=files.getJSONObject(i);
            String path=f.getString("path");

            /*
             * Stable 6.8 modifie volontairement ces quatre fichiers.
             * Le manifest 6.7 reste la photographie historique de 6.7.
             * Stable 6.8 possède son propre manifest de hash ci-dessous.
             */
            if("app/build.gradle".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4MainActivity.java".equals(path)
                    ||"app/src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java".equals(path)
                    ||".github/workflows/nmc-ci.yml".equals(path)){
                continue;
            }

            byte[] bytes=Files.readAllBytes(
                    root.resolve(path)
            );

            byte[] canonical=new String(
                    bytes,
                    java.nio.charset.StandardCharsets.UTF_8
            ).replace("\r\n","\n")
             .getBytes(java.nio.charset.StandardCharsets.UTF_8);

            assertEquals(f.getString("sha256"),sha(canonical));
        }
    }

    @Test public void qualityMultiSignal68ManifestMatchesCanonicalHashesWithoutChangingModel()throws Exception{
        JSONObject m=new JSONObject(new String(
                Files.readAllBytes(
                        Path.of("src/main/assets/v4_quality_multi_signal_6_8_manifest.json")
                ),
                java.nio.charset.StandardCharsets.UTF_8
        ));

        assertEquals("2.34.6.8",m.getString("versionName"));
        assertEquals(23468,m.getInt("versionCode"));
        assertEquals("NMC Stable 6.8",m.getString("label"));
        assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));
        assertEquals(
                "QUALITY_MULTI_SIGNAL_POLICY_WHEN_SIMULTANEOUS_RISK_LIMIT_OFF",
                m.getString("scope")
        );

        assertFalse(m.getBoolean("realTradingAllowed"));

        assertEquals(
                "207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",
                m.getString("modelSha256")
        );

        assertEquals(
                .675,
                m.getJSONObject("riskLimitOff").getDouble("qualityScoreQuantile"),
                0
        );

        assertEquals(
                .50,
                m.getJSONObject("riskLimitOff").getDouble("qualitySpreadQuantile"),
                0
        );

        assertEquals(
                90,
                m.getJSONObject("riskLimitOff").getInt("calibrationWindowUtcDays")
        );

        assertEquals(
                45,
                m.getJSONObject("riskLimitOff").getInt("minimumPriorObservations")
        );

        assertTrue(
                m.getJSONObject("riskLimitOff").isNull("numericSignalLimit")
        );

        assertTrue(
                m.getJSONObject("riskLimitOff").isNull("numericActivePlanLimit")
        );

        byte[] parent=Files.readAllBytes(
                Path.of("src/main/assets/v4_risk_6_7_manifest.json")
        );

        byte[] parentCanonical=new String(
                parent,
                java.nio.charset.StandardCharsets.UTF_8
        ).replace("\r\n","\n")
         .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(
                m.getString("parentRisk67ManifestCanonicalSha256"),
                sha(parentCanonical)
        );

        JSONArray files=m.getJSONArray("files");
        Path root=Path.of("..");

        for(int i=0;i<files.length();i++){
            JSONObject f=files.getJSONObject(i);

            byte[] bytes=Files.readAllBytes(
                    root.resolve(f.getString("path"))
            );

            byte[] canonical=new String(
                    bytes,
                    java.nio.charset.StandardCharsets.UTF_8
            ).replace("\r\n","\n")
             .getBytes(java.nio.charset.StandardCharsets.UTF_8);

            assertEquals(
                    f.getString("sha256"),
                    sha(canonical)
            );
        }
    }

    private static String sha(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format("%02x",b));return s.toString();}
}
