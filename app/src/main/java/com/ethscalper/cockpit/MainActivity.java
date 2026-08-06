package com.ethscalper.cockpit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native-only NMC cockpit. Market cards are registry-driven and updated incrementally. */
public class MainActivity extends Activity {
    private static final int BG=Color.rgb(5,10,17),CARD=Color.rgb(14,23,36),CARD_ALT=Color.rgb(18,29,45);
    private static final int BORDER=Color.rgb(38,57,78),TEXT=Color.rgb(237,244,251),MUTED=Color.rgb(137,155,177);
    private static final int CYAN=Color.rgb(67,224,193),AMBER=Color.rgb(255,169,64),RED=Color.rgb(255,78,112);
    private static final int COCKPIT=0,PLANS=1,DIAGNOSTIC=2,TOOLS=3;

    private LinearLayout shell,bottomNavigation;
    private FrameLayout contentHost;
    private final Map<Integer,ScrollView> screens=new LinkedHashMap<>();
    private final Map<Integer,Integer> scrollPositions=new LinkedHashMap<>();
    private final Map<String,MarketViews> marketViews=new LinkedHashMap<>();
    private TextView connection,feedAge,generalState,btcContext,aggregateRisk,planHistory;
    private LinearLayout activePlansHost;
    private final Map<String,ActivePlanCardView> activePlanCards=new LinkedHashMap<>();
    private TextView diagnosticHealth,diagnosticRecent,diagnosticDetails,exportProgress,aiInfo,cvCoreInfo;
    private Button exportButton;
    private int selectedSection=COCKPIT;
    private JSONObject latestState=new JSONObject();
    private JSONObject latestValidState=new JSONObject();
    private DiagnosticExportHandshake exportHandshake;
    private static final long EXPORT_FLUSH_TIMEOUT_MS=12_000L;
    private final ExecutorService exportExecutor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean exportRunning=new AtomicBoolean(false),exportCancelled=new AtomicBoolean(false);
    private final AtomicBoolean resetAfterExport=new AtomicBoolean(false);
    private boolean receiverRegistered;
    private final Handler startupHandler=new Handler(Looper.getMainLooper());
    private int startupRecoveryAttempt;
    private boolean activityDestroyed;
    private final Runnable startupRecovery=new Runnable(){@Override public void run(){
        if(activityDestroyed||MarketServiceRecoveryPolicy.isOperational(latestState.optBoolean("nativeActive",false),
                latestState.optBoolean("connected",false),latestState.optLong("lastAgeSec",-1)))return;
        sendServiceAction(MarketWatchService.ACTION_START,null);
        startupRecoveryAttempt++;
        scheduleStartupRecovery();
    }};

    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
        String payload=intent.getStringExtra(MarketWatchService.EXTRA_PAYLOAD);if(payload!=null)render(payload);
        DiagnosticExportHandshake pending=exportHandshake;String requestId=intent.getStringExtra(MarketWatchService.EXTRA_REQUEST_ID);
        if(pending!=null&&pending.acknowledge(requestId,intent.getBooleanExtra(MarketWatchService.EXTRA_FLUSH_COMPLETED,false))){
            exportHandshake=null;startupHandler.removeCallbacks(exportTimeout);JSONObject snapshot=validatedSnapshot(payload);
            if(snapshot==null)snapshot=copy(latestValidState);startDiagnosticExport(snapshot,requestId,true,
                    intent.getStringExtra(MarketWatchService.EXTRA_STATUS_MODE),
                    intent.getLongExtra(MarketWatchService.EXTRA_SNAPSHOT_AT,System.currentTimeMillis()));}}
    };
    private final Runnable exportTimeout=()->{DiagnosticExportHandshake pending=exportHandshake;
        if(pending==null||!pending.timeout(System.currentTimeMillis()))return;exportHandshake=null;
        sendServiceAction(MarketWatchService.ACTION_RECORD_EXPORT_TIMEOUT,null,pending.requestId());
        JSONObject snapshot=copy(latestValidState);if(!usableSnapshot(snapshot)){finishExportError("Timeout du flush · aucun statut valide");return;}
        startDiagnosticExport(snapshot,pending.requestId(),false,"LAST_VALID",System.currentTimeMillis());};

    @Override protected void onCreate(Bundle savedInstanceState){
        setTheme(R.style.AppTheme);super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        MarketWatchService.ensureChannels(this);buildNativeNavigation();
        // Start the foreground service while the activity is unquestionably visible. On a fresh
        // install Android may display the notification permission UI immediately afterwards.
        sendServiceAction(MarketWatchService.ACTION_START,null);scheduleStartupRecovery();
        if(!requestNotificationPermission())startupHandler.postDelayed(this::askBatteryOptimizationOnce,1500L);
        String state=MarketWatchService.getLastStatusJson(this);if(!state.isEmpty())render(state);
    }

    private void buildNativeNavigation(){
        shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);
        contentHost=new FrameLayout(this);shell.addView(contentHost,new LinearLayout.LayoutParams(-1,0,1));
        bottomNavigation=new LinearLayout(this);bottomNavigation.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavigation.setPadding(dp(8),dp(6),dp(8),dp(6));bottomNavigation.setBackgroundColor(CARD);
        shell.addView(bottomNavigation,new LinearLayout.LayoutParams(-1,dp(64)));
        addNav("Cockpit",COCKPIT);addNav("Plans",PLANS);addNav("Diagnostic",DIAGNOSTIC);addNav("Outils",TOOLS);
        screens.put(COCKPIT,buildCockpitScreen());screens.put(PLANS,buildPlansScreen());
        screens.put(DIAGNOSTIC,buildDiagnosticScreen());screens.put(TOOLS,buildToolsScreen());
        setContentView(shell);applySystemInsets(shell);
        showSection("plans".equals(getIntent().getStringExtra("nmc_section"))?PLANS:COCKPIT);
    }

    private void applySystemInsets(View root){
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            Insets ime=insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;});
        ViewCompat.requestApplyInsets(root);
    }

    private void addNav(String label,int section){Button button=new Button(this);button.setText(label);button.setAllCaps(false);
        button.setTextColor(TEXT);button.setTextSize(12);button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setStateListAnimator(null);
        button.setBackground(rounded(CARD_ALT,BORDER,12,1));button.setOnClickListener(v->showSection(section));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(3),0,dp(3),0);bottomNavigation.addView(button,p);}

    private void showSection(int section){ScrollView current=screens.get(selectedSection);if(current!=null)scrollPositions.put(selectedSection,current.getScrollY());
        selectedSection=section;contentHost.removeAllViews();ScrollView target=screens.get(section);contentHost.addView(target,new FrameLayout.LayoutParams(-1,-1));
        target.post(()->target.scrollTo(0,scrollPositions.getOrDefault(section,0)));for(int i=0;i<bottomNavigation.getChildCount();i++){
            View child=bottomNavigation.getChildAt(i);child.setAlpha(i==section?1f:.62f);}}

    private ScrollView buildCockpitScreen(){LinearLayout root=screenRoot();
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(2),dp(4),dp(2),dp(8));root.addView(header);
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.HORIZONTAL);brand.setGravity(Gravity.CENTER_VERTICAL);header.addView(brand);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.nmc_logo);brand.addView(logo,new LinearLayout.LayoutParams(dp(54),dp(54)));
        LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        names.addView(text("NMC",28,TEXT,true));names.addView(text("Native Market Cockpit",14,CYAN,true));
        names.addView(text("Multi-Market Research Engine",11,MUTED,false));
        connection=text("RECONNEXION",12,AMBER,true);connection.setPadding(dp(12),dp(7),dp(12),dp(7));connection.setBackground(rounded(CARD_ALT,AMBER,999,1));
        LinearLayout.LayoutParams connectionParams=new LinearLayout.LayoutParams(-2,-2);connectionParams.setMargins(0,dp(12),0,0);header.addView(connection,connectionParams);
        header.addView(text("Version "+BuildConfig.VERSION_NAME,12,MUTED,false));
        feedAge=text("Âge du flux : —",12,MUTED,false);feedAge.setPadding(0,dp(3),0,0);header.addView(feedAge);
        LinearLayout stateCard=card(root,"ÉTAT GÉNÉRAL",AMBER);generalState=text("ANALYSE",30,AMBER,true);stateCard.addView(generalState);
        generalState.setContentDescription("État général du moteur");
        marketViews.clear();for(MarketUiCatalog.CardDescriptor descriptor:MarketUiCatalog.cards(MarketRegistry.production())){
            MarketViews views=new MarketViews();LinearLayout market=card(root,descriptor.symbol+" · "+descriptor.asset,CYAN);
            views.price=mono("—",30,TEXT,true);market.addView(views.price);views.quotes=mono("BID —   ASK —",14,MUTED,false);market.addView(views.quotes);
            views.feed=text("Flux : initialisation",12,MUTED,false);market.addView(views.feed);views.state=text("ANALYSE",15,CYAN,true);market.addView(views.state);
            views.plan=mono("Aucun plan actif",14,TEXT,false);views.plan.setPadding(0,dp(8),0,0);market.addView(views.plan);marketViews.put(descriptor.symbol,views);}
        LinearLayout btc=card(root,"BTCUSDT · CONTEXTE",CYAN);btcContext=mono("Prix —\nBID —   ASK —\nÉtat : initialisation",14,TEXT,false);btc.addView(btcContext);
        LinearLayout risk=card(root,"RISQUE ACTIF · INFORMATIF",RED);aggregateRisk=mono("Aucun risque actif",14,TEXT,false);risk.addView(aggregateRisk);risk.setTag("riskCard");
        return wrap(root);}

    private ScrollView buildPlansScreen(){LinearLayout root=screenRoot();addSectionHeader(root,"Plans","Plans manuels actifs et historique récent");
        activePlansHost=new LinearLayout(this);activePlansHost.setOrientation(LinearLayout.VERTICAL);root.addView(activePlansHost);
        LinearLayout history=card(root,"HISTORIQUE RÉCENT · 20 MAXIMUM",CYAN);
        planHistory=mono("Aucun événement de plan récent",12,TEXT,false);history.addView(planHistory);
        TextView info=text("PLAN_RESTORED conserve le plan existant et n’est jamais compté comme un nouveau trade.",12,MUTED,false);info.setPadding(0,dp(8),0,0);history.addView(info);
        return wrap(root);}

    private ScrollView buildDiagnosticScreen(){LinearLayout root=screenRoot();addSectionHeader(root,"Diagnostic","Santé des flux et recorder multi-marchés");
        LinearLayout health=card(root,"ÉTAT DE SANTÉ",CYAN);diagnosticHealth=mono("ETH —\nSOL —\nBTC —",14,TEXT,false);health.addView(diagnosticHealth);
        LinearLayout recent=card(root,"5 DERNIERS ÉVÉNEMENTS",AMBER);diagnosticRecent=mono("Aucun événement",12,TEXT,false);recent.addView(diagnosticRecent);
        LinearLayout details=card(root,"DÉTAILS TECHNIQUES",MUTED);diagnosticDetails=mono("Repliés",11,MUTED,false);diagnosticDetails.setVisibility(View.GONE);details.addView(diagnosticDetails);
        Button toggle=actionButton("Afficher / masquer les détails",MUTED,()->diagnosticDetails.setVisibility(diagnosticDetails.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));details.addView(toggle);
        exportProgress=text("Prêt",12,MUTED,false);root.addView(exportProgress);
        exportButton=actionButton("Télécharger diagnostic ZIP",CYAN,this::exportDiagnosticZip);root.addView(exportButton);
        return wrap(root);}

    private ScrollView buildToolsScreen(){LinearLayout root=screenRoot();addSectionHeader(root,"Outils","Tests et réglages non décisionnels");
        LinearLayout action=card(root,"MOTEUR CV CORE",CYAN);
        cvCoreInfo=text("MOTEUR : CV CORE V1\nÉTAT : ACTIF\nMODE : SIGNAUX MANUELS\nTRADING AUTOMATIQUE : NON",12,MUTED,false);action.addView(cvCoreInfo);
        LinearLayout ai=card(root,"IA INFORMATIVE",CYAN);aiInfo=text(aiStatusText(),12,MUTED,false);ai.addView(aiInfo);
        ai.addView(actionButton("Réglages IA OpenAI",CYAN,this::showAiSettingsDialog));ai.addView(actionButton("Tester clé IA",AMBER,this::testAiKeyNow));
        LinearLayout tests=card(root,"TESTS LOCAUX",AMBER);tests.addView(actionButton("Tester alerte forte",RED,()->sendServiceAction(MarketWatchService.ACTION_TEST_ALERT,"ALERTE SONORE DE TEST ENVOYÉE")));
        tests.addView(actionButton("Tester vibration",CYAN,()->sendServiceAction(MarketWatchService.ACTION_TEST_VIBRATION,"Vibration testée")));
        tests.addView(actionButton("Réglages Android de l’alerte sonore",AMBER,this::openAudibleChannelSettings));
        LinearLayout reset=card(root,"RECORDER",RED);reset.addView(actionButton("Réinitialiser diagnostic",RED,this::confirmDiagnosticReset));return wrap(root);}

    private void render(String payload){try{latestState=new JSONObject(payload);if(usableSnapshot(latestState))latestValidState=copy(latestState);UiState state=UiState.from(latestState);
        if(MarketServiceRecoveryPolicy.isOperational(latestState.optBoolean("nativeActive",false),
                latestState.optBoolean("connected",false),latestState.optLong("lastAgeSec",-1))){
            startupHandler.removeCallbacks(startupRecovery);startupRecoveryAttempt=0;}
        setChanged(connection,state.connection);connection.setTextColor(state.connected?CYAN:AMBER);
        setChanged(feedAge,"Âge du flux : "+state.feedAge);setChanged(generalState,state.generalState);
        JSONObject markets=latestState.optJSONObject("markets");if(markets!=null)for(Map.Entry<String,MarketViews> entry:marketViews.entrySet()){
            JSONObject market=markets.optJSONObject(entry.getKey());if(market==null)continue;MarketViews views=entry.getValue();
            setChanged(views.price,price(market.optDouble("last",Double.NaN)));setChanged(views.quotes,"BID "+price(market.optDouble("bid",Double.NaN))+"   ASK "+price(market.optDouble("ask",Double.NaN)));
            long age=market.optLong("feedAgeSec",-1);setChanged(views.feed,"Âge du feed : "+(age<0?"—":age+" s"));
            setChanged(views.state,market.optString("state",market.optString("status",market.optBoolean("activePlan",false)?"PLAN ACTIF":"ANALYSE")));
            setChanged(views.plan,marketPlanText(market));}
        JSONObject btc=latestState.optJSONObject("referenceMarket");if(btc!=null)setChanged(btcContext,"Prix "+price(btc.optDouble("last",Double.NaN))+"\nBID "+price(btc.optDouble("bid",Double.NaN))+"   ASK "+price(btc.optDouble("ask",Double.NaN))+"\nFraîcheur : "+age(btc.optLong("feedAgeSec",-1)));
        JSONArray plans=latestState.optJSONArray("activePlans");renderPlans(plans,markets);
        setChanged(planHistory,recentPlanHistory(latestState.optJSONArray("diagnostics"),20));
        setChanged(aggregateRisk,riskText(latestState.optJSONObject("aggregateRisk")));
        JSONObject recorder=latestState.optJSONObject("overnightRecorder");setChanged(diagnosticHealth,healthText(latestState,markets,btc,recorder));
        setChanged(diagnosticRecent,recentEvents(latestState.optJSONArray("diagnostics"),5));
        setChanged(diagnosticDetails,technicalDetailsText(latestState,recorder));
        setChanged(cvCoreInfo,"MOTEUR : CV CORE V1\nÉTAT : ACTIF\nMODE : SIGNAUX MANUELS\nTRADING AUTOMATIQUE : NON");
    }catch(Exception ignored){}}

    private void renderPlans(JSONArray plans,JSONObject markets){
        LinkedHashMap<String,PlanUiModel> next=new LinkedHashMap<>();long now=System.currentTimeMillis();
        if(plans!=null)for(int i=0;i<plans.length();i++){JSONObject p=plans.optJSONObject(i);if(p==null)continue;
            String symbol=p.optString("symbol","");JSONObject market=markets==null?null:markets.optJSONObject(symbol);
            PlanUiModel model=PlanUiMapper.from(p,market,now);if(!symbol.isEmpty())next.put(symbol,model);}
        for(String symbol:new java.util.ArrayList<>(activePlanCards.keySet()))if(!next.containsKey(symbol)){
            activePlansHost.removeView(activePlanCards.remove(symbol));}
        for(Map.Entry<String,PlanUiModel> entry:next.entrySet()){
            ActivePlanCardView view=activePlanCards.get(entry.getKey());if(view==null){view=new ActivePlanCardView(this);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,0);
                activePlansHost.addView(view,p);activePlanCards.put(entry.getKey(),view);}view.bind(entry.getValue());}
        if(next.isEmpty()&&activePlansHost.getChildCount()==0){TextView empty=mono("Aucun plan actif\nLe moteur analyse ETH et SOL silencieusement.",14,MUTED,false);
            empty.setTag("emptyPlans");empty.setPadding(dp(2),dp(18),0,dp(8));activePlansHost.addView(empty);}
        else{View empty=activePlansHost.findViewWithTag("emptyPlans");if(empty!=null)activePlansHost.removeView(empty);}
    }

    private static final class UiState{final boolean connected;final String connection,feedAge,generalState;
        UiState(boolean connected,String connection,String feedAge,String generalState){this.connected=connected;this.connection=connection;this.feedAge=feedAge;this.generalState=generalState;}
        static UiState from(JSONObject state){boolean connected=state.optBoolean("connected");long age=state.optLong("lastAgeSec",-1);JSONArray plans=state.optJSONArray("activePlans");int count=plans==null?0:plans.length();
            String connection=connected?"CONNECTÉ":age>=0?"FLUX RETARDÉ":"RECONNEXION";String general=count==2?"DEUX PLANS ACTIFS":count==1?"PLAN ACTIF":connected?"ANALYSE":"FLUX RETARDÉ";
            return new UiState(connected,connection,age<0?"—":age+" s",general);}}

    private void exportDiagnosticZip(){if(!exportRunning.compareAndSet(false,true))return;exportCancelled.set(false);exportButton.setEnabled(false);setChanged(exportProgress,"Préparation…");
        String requestId=UUID.randomUUID().toString();exportHandshake=new DiagnosticExportHandshake(requestId,System.currentTimeMillis(),EXPORT_FLUSH_TIMEOUT_MS);
        startupHandler.postDelayed(exportTimeout,EXPORT_FLUSH_TIMEOUT_MS);sendServiceAction(MarketWatchService.ACTION_FLUSH_DIAGNOSTICS,null,requestId);}

    private void startDiagnosticExport(final JSONObject snapshot,String requestId,boolean flushCompleted,
                                       String statusMode,long snapshotAt){
        if(!usableSnapshot(snapshot)){finishExportError("Statut export invalide");return;}
        exportExecutor.execute(()->{Uri uri=null;String name=DiagnosticExportContract.zipPrefix(BuildConfig.VERSION_NAME)+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.FRANCE).format(new Date())+".zip";
            try{try(OutputTarget target=openExportTarget(name)){uri=target.uri;
                Map<String,String> small=smallExportEntries(snapshot);String status=small.get("status.json");
                DiagnosticStreamingExporter.ExportSnapshotMetadata metadata=new DiagnosticStreamingExporter.ExportSnapshotMetadata(
                        snapshotAt,requestId,flushCompleted,statusMode==null?"LAST_VALID":statusMode,sha256(status));
                DiagnosticStreamingExporter.export(target.output,
                        MarketWatchService.persistentEventsFile(this),MarketWatchService.persistentFramesFile(this),small,
                        BuildConfig.VERSION_NAME,System.currentTimeMillis(),metadata,(percent,stage)->runOnUiThread(()->setChanged(exportProgress,stage+" · "+percent+" %")),exportCancelled::get);
                }runOnUiThread(()->{setChanged(exportProgress,"Export terminé");Toast.makeText(this,"Diagnostic exporté : "+name,Toast.LENGTH_LONG).show();
                    if(resetAfterExport.getAndSet(false))sendServiceAction(MarketWatchService.ACTION_RESET_DIAGNOSTICS,"Diagnostic réinitialisé — plans actifs conservés");});
            }catch(Exception error){if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Exception ignored){}resetAfterExport.set(false);
                runOnUiThread(()->{setChanged(exportProgress,"Erreur d’export : "+error.getClass().getSimpleName());Toast.makeText(this,"Export impossible",Toast.LENGTH_LONG).show();});}
            finally{exportRunning.set(false);runOnUiThread(()->exportButton.setEnabled(true));}});}

    private void finishExportError(String message){exportHandshake=null;startupHandler.removeCallbacks(exportTimeout);
        exportRunning.set(false);exportButton.setEnabled(true);setChanged(exportProgress,message);Toast.makeText(this,message,Toast.LENGTH_LONG).show();}

    private Map<String,String> smallExportEntries(JSONObject state)throws Exception{LinkedHashMap<String,String> out=new LinkedHashMap<>();
        SafeJsonNormalizer.Result normalized=SafeJsonNormalizer.normalizeAndSerialize(state);JSONObject safe=normalized.value;
        out.put("status.json",safe.toString(2));out.put("markets.json",json(safe.optJSONObject("markets"),"{}"));out.put("active_plans.json",json(safe.optJSONArray("activePlans"),"[]"));
        out.put("profiles_manifest.json",profilesManifest().toString(2));JSONObject summary=safe.optJSONObject("overnightRecorder");out.put("market_summary.json",json(summary,"{}"));
        out.put("market_summary.txt",summaryText(summary));out.put("feed_health.json",feedHealth(safe).toString(2));out.put("health_check.txt",healthCheck(safe));
        out.put("instructions.txt",DiagnosticExportContract.instructions(BuildConfig.VERSION_NAME));return out;}

    private static JSONObject validatedSnapshot(String payload){try{if(!SafeJsonNormalizer.isValidObject(payload))return null;
        JSONObject value=new JSONObject(payload);return usableSnapshot(value)?value:null;}catch(Exception ignored){return null;}}
    private static boolean usableSnapshot(JSONObject value){return value!=null&&value.length()>0
            &&value.optJSONObject("markets")!=null&&value.optJSONObject("referenceMarket")!=null;}
    private static JSONObject copy(JSONObject value){try{return value==null?new JSONObject():new JSONObject(value.toString());}
        catch(Exception ignored){return new JSONObject();}}
    private static String sha256(String value)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] bytes=digest.digest((value==null?"":value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();
        for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();}

    private OutputTarget openExportTarget(String name)throws Exception{if(Build.VERSION.SDK_INT>=29){ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,"application/zip");values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
            Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IllegalStateException("MediaStore");OutputStream out=getContentResolver().openOutputStream(uri);if(out==null)throw new IllegalStateException("output");return new OutputTarget(uri,out);}
        File dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Downloads");return new OutputTarget(Uri.fromFile(new File(dir,name)),new FileOutputStream(new File(dir,name),false));}

    private void confirmDiagnosticReset(){new AlertDialog.Builder(this).setTitle("Réinitialiser le diagnostic ?").setMessage("Les plans actifs, leurs niveaux et notifications seront conservés.")
        .setNegativeButton("Annuler",null).setNeutralButton("Exporter puis réinitialiser",(d,w)->{resetAfterExport.set(true);exportDiagnosticZip();})
        .setPositiveButton("Réinitialiser quand même",(d,w)->sendServiceAction(MarketWatchService.ACTION_RESET_DIAGNOSTICS,"Diagnostic réinitialisé — plans actifs conservés")).show();}

    private void testAiKeyNow(){if(!SecureAiStore.hasKey(this)){Toast.makeText(this,"Aucune clé IA enregistrée",Toast.LENGTH_LONG).show();return;}
        Toast.makeText(this,"Test IA en cours…",Toast.LENGTH_SHORT).show();new AiAdvisor(this).testKeyAsync(result->runOnUiThread(()->{
            String value=result!=null&&result.approved&&!result.fallback?"Clé IA OK":"Test IA échec : "+(result==null?"AI_EMPTY":result.reason);Toast.makeText(this,value,Toast.LENGTH_LONG).show();setChanged(aiInfo,aiStatusText());}));}

    private void showAiSettingsDialog(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),0);
        Switch enabled=new Switch(this);enabled.setText("IA informative après publication");enabled.setTextColor(TEXT);enabled.setChecked(SecureAiStore.isEnabled(this));box.addView(enabled);
        EditText key=new EditText(this);key.setHint("Clé OpenAI");key.setTextColor(TEXT);key.setHintTextColor(MUTED);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(key);
        new AlertDialog.Builder(this).setTitle("IA OpenAI · informative uniquement").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{
            String value=key.getText().toString().trim();if(!value.isEmpty())SecureAiStore.saveKey(this,value);SecureAiStore.setEnabled(this,enabled.isChecked());setChanged(aiInfo,aiStatusText());}).show();}

    private String aiStatusText(){return "IA OpenAI : "+(SecureAiStore.isEnabled(this)?"ON · "+SecureAiStore.maskedKey(this):"OFF")+"\nAvis asynchrone, jamais décisionnel.";}
    private void sendServiceAction(String action,String toast){sendServiceAction(action,toast,"");}
    private void sendServiceAction(String action,String toast,String requestId){try{Intent intent=new Intent(this,MarketWatchService.class);intent.setAction(action);
        if(requestId!=null&&!requestId.isEmpty())intent.putExtra(MarketWatchService.EXTRA_REQUEST_ID,requestId);
        ContextCompat.startForegroundService(this,intent);if(toast!=null)Toast.makeText(this,toast,Toast.LENGTH_SHORT).show();}catch(RuntimeException error){scheduleStartupRecovery();}}
    private void openAudibleChannelSettings(){try{Intent intent=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());intent.putExtra(Settings.EXTRA_CHANNEL_ID,
                MarketWatchService.FINAL_SIGNAL_LOUD_CHANNEL_ID);startActivity(intent);}catch(Exception error){
        Toast.makeText(this,"Réglages du canal indisponibles",Toast.LENGTH_LONG).show();}}
    private boolean requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=getPackageManager().PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1001);return true;}return false;}
    private void scheduleStartupRecovery(){if(activityDestroyed)return;startupHandler.removeCallbacks(startupRecovery);long delay=MarketServiceRecoveryPolicy.delayForAttempt(startupRecoveryAttempt);startupHandler.postDelayed(startupRecovery,delay);}
    private void askBatteryOptimizationOnce(){if(Build.VERSION.SDK_INT<23)return;PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);if(power==null||power.isIgnoringBatteryOptimizations(getPackageName()))return;
        try{Intent intent=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()));startActivity(intent);}catch(Exception ignored){}}

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==1001){sendServiceAction(MarketWatchService.ACTION_START,null);scheduleStartupRecovery();startupHandler.postDelayed(this::askBatteryOptimizationOnce,1200L);}}
    @Override protected void onStart(){super.onStart();if(!receiverRegistered){IntentFilter filter=new IntentFilter(MarketWatchService.BROADCAST_STATUS);ContextCompat.registerReceiver(this,statusReceiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED);receiverRegistered=true;}sendServiceAction(MarketWatchService.ACTION_START,null);scheduleStartupRecovery();}
    @Override protected void onResume(){super.onResume();sendServiceAction(MarketWatchService.ACTION_START,null);scheduleStartupRecovery();}
    @Override protected void onStop(){startupHandler.removeCallbacks(startupRecovery);if(receiverRegistered){unregisterReceiver(statusReceiver);receiverRegistered=false;}super.onStop();}
    @Override protected void onDestroy(){activityDestroyed=true;startupHandler.removeCallbacksAndMessages(null);exportHandshake=null;exportCancelled.set(true);exportExecutor.shutdownNow();super.onDestroy();}

    private LinearLayout screenRoot(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(14),dp(16),dp(28));root.setBackgroundColor(BG);return root;}
    private ScrollView wrap(LinearLayout root){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.addView(root,new ScrollView.LayoutParams(-1,-2));return scroll;}
    private void addSectionHeader(LinearLayout root,String title,String subtitle){root.addView(text(title,28,TEXT,true));TextView sub=text(subtitle,13,MUTED,false);sub.setPadding(0,dp(4),0,dp(6));root.addView(sub);}
    private LinearLayout card(LinearLayout root,String label,int accent){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);value.setPadding(dp(16),dp(14),dp(16),dp(16));value.setBackground(rounded(CARD,BORDER,16,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(10),0,0);root.addView(value,p);TextView title=text(label,11,accent,true);title.setLetterSpacing(.1f);title.setPadding(0,0,0,dp(9));value.addView(title);return value;}
    private TextView text(String value,int size,int color,boolean bold){TextView out=new TextView(this);out.setText(value);out.setTextSize(size);out.setTextColor(color);out.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);out.setLineSpacing(dp(2),1f);return out;}
    private TextView mono(String value,int size,int color,boolean bold){TextView out=text(value,size,color,bold);out.setTypeface(Typeface.MONOSPACE,bold?Typeface.BOLD:Typeface.NORMAL);return out;}
    private Button actionButton(String label,int accent,Runnable action){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(TEXT);button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setStateListAnimator(null);button.setBackground(rounded(CARD_ALT,accent,12,1));button.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(8),0,0);button.setLayoutParams(p);return button;}
    private GradientDrawable rounded(int fill,int stroke,int radius,int width){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(width),stroke);return d;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static void setChanged(TextView view,String value){if(view!=null&&!String.valueOf(view.getText()).equals(value))view.setText(value);}
    private static String price(double value){return Double.isFinite(value)&&value>0?String.format(Locale.FRANCE,"%.2f",value):"—";}
    private static String age(long seconds){return seconds<0?"—":seconds+" s";}
    private static String marketPlanText(JSONObject market){JSONObject plan=market.optJSONObject("signal");if(plan==null&&!market.optBoolean("active",market.optBoolean("activePlan",false)))return "Aucun plan actif";
        if(plan==null)return "PLAN ACTIF";String asset=market.optString("asset",market.optString("symbol","").replace("USDT",""));return plan.optString("side","PLAN ACTIF")+" · "+plan.optInt("quantity",0)+" "+asset+"\nLIMIT "+price(plan.optDouble("entry",Double.NaN))+"   TP "+price(plan.optDouble("takeProfit",plan.optDouble("tp",Double.NaN)))+"   SL "+price(plan.optDouble("stopLoss",plan.optDouble("sl",Double.NaN)))+"\nScore "+plan.optInt("score",0)+" · "+plan.optString("sleeve",plan.optString("family","—"));}
    private static String plansText(JSONArray plans){if(plans==null||plans.length()==0)return "Aucun plan actif";StringBuilder out=new StringBuilder();for(int i=0;i<plans.length();i++){JSONObject p=plans.optJSONObject(i);if(p==null)continue;if(out.length()>0)out.append("\n\n");String symbol=p.optString("symbol","ETHUSDT"),asset=p.optString("asset",symbol.replace("USDT",""));out.append(symbol).append(" · ").append(p.optString("side","PLAN ACTIF")).append(" · ").append(p.optInt("quantity",0)).append(' ').append(asset).append("\nLIMIT ").append(price(p.optDouble("entry",Double.NaN))).append("  TP ").append(price(p.optDouble("takeProfit",p.optDouble("tp",Double.NaN)))).append("  SL ").append(price(p.optDouble("stopLoss",p.optDouble("sl",Double.NaN)))).append("\nScore ").append(p.optInt("score",0)).append(" · risque ").append(price(p.optDouble("theoreticalMaximumLoss",Double.NaN))).append(" USDT");}return out.toString();}
    private static String riskText(JSONObject aggregate){if(aggregate==null||aggregate.optDouble("totalActiveRiskUsdt",0)<=0)return "Aucun risque actif";StringBuilder out=new StringBuilder();JSONObject by=aggregate.optJSONObject("riskBySymbol");if(by!=null){for(String symbol:new String[]{"ETHUSDT","SOLUSDT"})if(by.has(symbol))out.append("Risque ").append(symbol.replace("USDT","")).append(" actif : ").append(price(by.optDouble(symbol,0))).append(" USDT\n");}out.append("Risque total actif : ").append(price(aggregate.optDouble("totalActiveRiskUsdt",0))).append(" USDT");return out.toString();}
    private static String healthText(JSONObject state,JSONObject markets,JSONObject btc,JSONObject recorder){StringBuilder out=new StringBuilder();if(markets!=null)for(String symbol:new String[]{"ETHUSDT","SOLUSDT"}){JSONObject m=markets.optJSONObject(symbol);out.append(symbol).append(" : ").append(m==null?"—":age(m.optLong("feedAgeSec",-1))).append('\n');}out.append("BTCUSDT : ").append(btc==null?"—":age(btc.optLong("feedAgeSec",-1))).append('\n');out.append("Source : ").append(state.optString("marketDataSource","—")).append(state.optBoolean("executionFeedAuthoritative")?" · FUTURES VALIDÉ":" · SECOURS VISIBILITÉ").append('\n');JSONObject alert=state.optJSONObject("audibleAlertChannel");if(alert!=null)out.append("Alerte sonore : ").append(alert.optString("state","INCONNU")).append('\n');if(state.optBoolean("statusSerializationFallback"))out.append("Statut : MODE DE SECOURS · détails préservés\n");String error=state.optString("lastFeedError","");if(!error.isEmpty())out.append("Dernier incident : ").append(error).append('\n');if(recorder!=null)out.append("Événements : ").append(recorder.optLong("eventCount",recorder.optLong("observationEvents",0))).append(" · Frames : ").append(recorder.optLong("frameCount",recorder.optLong("marketFrames",0))).append("\nDurée : ").append(recorder.optLong("durationSec",0)).append(" s\nFichiers : ").append(recorder.optLong("eventFileBytes",0)+recorder.optLong("frameFileBytes",0)).append(" octets");return out.toString();}
    private static String technicalDetailsText(JSONObject state,JSONObject recorder){StringBuilder out=new StringBuilder();out.append("Version : ").append(BuildConfig.VERSION_NAME).append('\n');out.append("Statut complet : ").append(state.optBoolean("statusSerializationFallback")?"NON · MODE DE SECOURS":"OUI").append('\n');String statusError=state.optString("statusError","");if(!statusError.isEmpty())out.append("Erreur du statut : ").append(statusError).append('\n');out.append("\nINDEX RECORDER\n");if(recorder==null)out.append("Donnée indisponible");else out.append(recorder.toString());JSONObject alert=state.optJSONObject("audibleAlertChannel");out.append("\n\nALERTE SONORE\n");if(alert==null)out.append("Donnée indisponible");else out.append(alert.toString());out.append("\n\nSOURCE\n").append(state.optString("marketDataSource","Donnée indisponible"));return out.toString();}
    private static String recentEvents(JSONArray events,int maximum){if(events==null||events.length()==0)return "Aucun événement";StringBuilder out=new StringBuilder();int start=Math.max(0,events.length()-maximum);for(int i=start;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e==null)continue;if(out.length()>0)out.append('\n');out.append(e.optString("symbol","—")).append(" · ").append(e.optString("eventType",e.optString("code","EVENT"))).append(" · ").append(e.optString("reasonCode",e.optString("message","")));}return out.toString();}
    private static String recentPlanHistory(JSONArray events,int maximum){if(events==null)return "Aucun événement de plan récent";
        java.util.ArrayList<String> values=new java.util.ArrayList<>();for(int i=events.length()-1;i>=0&&values.size()<maximum;i--){JSONObject e=events.optJSONObject(i);if(e==null)continue;
            String type=e.optString("eventType",e.optString("event",""));if(!"PLAN_CONFIRMED".equals(type)&&!"PLAN_RESTORED".equals(type)&&!"TP_TOUCHED".equals(type)&&!"SL_TOUCHED".equals(type))continue;
            String symbol=e.optString("symbol","—"),side=e.optString("side","");long at=e.optLong("eventAt",e.optLong("at",0));
            values.add(symbol+" · "+type+(side.isEmpty()?"":" · "+side)+(at>0?" · "+new SimpleDateFormat("dd/MM HH:mm:ss",Locale.FRANCE).format(new Date(at)):""));}
        if(values.isEmpty())return "Aucun événement de plan récent";java.util.Collections.reverse(values);return String.join("\n",values);}
    private static String json(Object value,String fallback){return value==null?fallback:String.valueOf(value);}
    private static String summaryText(JSONObject summary){return summary==null?"Recorder indisponible":"Événements : "+summary.optLong("eventCount",0)+"\nFrames : "+summary.optLong("frameCount",0)+"\nTrades confirmés : "+summary.optLong("confirmedTrades",0)+"\nPlans restaurés : "+summary.optLong("restoredActivePlans",0);}
    private static JSONObject feedHealth(JSONObject state)throws Exception{JSONObject out=new JSONObject();out.put("version",BuildConfig.VERSION_NAME);out.put("markets",state.optJSONObject("markets"));out.put("referenceMarket",state.optJSONObject("referenceMarket"));out.put("connected",state.optBoolean("connected"));out.put("lastAgeSec",state.optLong("lastAgeSec",-1));return out;}
    private static String healthCheck(JSONObject state){return "NMC v"+BuildConfig.VERSION_NAME+"\nConnecté: "+state.optBoolean("connected")+"\nMode: RESEARCH_ONLY\nrealTradingAllowed=false\nLifecycle: TP/SL uniquement\n";}
    private static JSONObject profilesManifest()throws Exception{JSONObject out=new JSONObject();out.put("product","Native Market Cockpit");out.put("versionName",BuildConfig.VERSION_NAME);out.put("versionCode",BuildConfig.VERSION_CODE);JSONArray profiles=new JSONArray();for(MarketProfile profile:MarketRegistry.production().tradedMarkets()){JSONObject p=new JSONObject();p.put("symbol",profile.symbol);p.put("asset",profile.asset);p.put("profileVersion",profile.profileVersion);profiles.put(p);}out.put("profiles",profiles);return out;}
    private static final class MarketViews{TextView price,quotes,feed,state,plan;}
    private static final class OutputTarget implements AutoCloseable{final Uri uri;final OutputStream output;OutputTarget(Uri uri,OutputStream output){this.uri=uri;this.output=output;}public void close()throws Exception{output.flush();output.close();}}
}
