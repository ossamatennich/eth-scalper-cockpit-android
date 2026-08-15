package com.ethscalper.cockpit;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class V4UiContractTest {
    @Test public void mainNavigationIsMinimal()throws Exception{String s=source("src/main/java/com/ethscalper/cockpit/V4MainActivity.java");assertTrue(s.contains("\"ACCUEIL\",\"PLANS\",\"HISTORIQUE\""));assertFalse(s.contains("DIAGNOSTIC\",\"TOOLS"));}
    @Test public void actionableCardHasOperationalFields()throws Exception{String s=source("src/main/java/com/ethscalper/cockpit/V4MainActivity.java");for(String x:new String[]{"QTÉ","ENTRY","TP","SL","ORDRE POSÉ","TRADE PRIS"})assertTrue(s.contains(x));}
    @Test public void noUserLeverageOrPotentialPnlControls()throws Exception{String s=source("src/main/java/com/ethscalper/cockpit/V4MainActivity.java");assertFalse(s.contains("Levier"));assertFalse(s.contains("Gain potentiel"));assertFalse(s.contains("Perte potentielle"));assertFalse(s.contains("PF"));assertFalse(s.contains("Score ML"));}
    @Test public void onlyV4ActivityIsLauncher()throws Exception{String m=source("src/main/AndroidManifest.xml");int main=m.indexOf("android.intent.action.MAIN");assertTrue(main>m.indexOf(".V4MainActivity"));assertTrue(main<m.indexOf(".MainActivity",m.indexOf(".V4MainActivity")+1));}
    @Test public void nativeMaterialThemeUsesNativeActivityNotAppCompat()throws Exception{String activity=source("src/main/java/com/ethscalper/cockpit/V4MainActivity.java"),styles=source("src/main/res/values/styles.xml");
        assertTrue(styles.contains("parent=\"android:style/Theme.Material.NoActionBar\""));assertTrue(activity.contains("import android.app.Activity;"));assertTrue(activity.contains("V4MainActivity extends Activity"));
        assertFalse(activity.contains("AppCompatActivity"));}
    private static String source(String path)throws Exception{return new String(Files.readAllBytes(Path.of(path)),java.nio.charset.StandardCharsets.UTF_8);}
}
