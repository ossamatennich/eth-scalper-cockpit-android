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
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if("app/build.gradle".equals(f.getString("path"))||"app/src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java".equals(f.getString("path")))continue;byte[] bytes=Files.readAllBytes(root.resolve(f.getString("path")));
            byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    @Test public void androidLaunchHotfixManifestMatchesCanonicalHashes()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_launch_6_2_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));assertFalse(m.getBoolean("realTradingAllowed"));assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",m.getString("modelSha256"));
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if("app/build.gradle".equals(f.getString("path"))||"app/src/main/java/com/ethscalper/cockpit/V4MainActivity.java".equals(f.getString("path")))continue;byte[] bytes=Files.readAllBytes(root.resolve(f.getString("path")));
            byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    @Test public void uiUxHotfixManifestMatchesCanonicalHashesWithoutChangingModel()throws Exception{JSONObject m=new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/v4_ui_6_3_manifest.json")),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("2.34.6.3",m.getString("versionName"));assertEquals("NMC_PROP_DAILY_HYBRID_V4",m.getString("engineId"));assertEquals("UI_UX_AND_RUNTIME_DISPLAY_STATUS_ONLY",m.getString("scope"));assertFalse(m.getBoolean("realTradingAllowed"));
        assertEquals("207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680",m.getString("modelSha256"));assertEquals("47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3",m.getString("parentFrozenManifestCanonicalSha256"));
        JSONArray files=m.getJSONArray("files");Path root=Path.of("..");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);byte[] bytes=Files.readAllBytes(root.resolve(f.getString("path")));byte[] canonical=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);assertEquals(f.getString("sha256"),sha(canonical));}}
    private static String sha(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format("%02x",b));return s.toString();}
}
