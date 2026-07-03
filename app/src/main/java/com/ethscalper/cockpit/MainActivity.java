package com.ethscalper.cockpit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String payload = intent.getStringExtra("payload");
            if (payload == null || webView == null) return;
            String safe = payload.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            webView.post(() -> webView.evaluateJavascript(
                    "(function(p){if(window.ethScalperNativeStatusUpdate){window.ethScalperNativeStatusUpdate(p);}window.dispatchEvent(new CustomEvent('ethScalperNativeStatus',{detail:p}));})(JSON.parse('" + safe + "'));",
                    null
            ));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MarketWatchService.ensureChannels(this);
        requestNotificationPermissionIfNeeded();
        startNativeWatch();
        setupWebView();
        promptBatteryWhitelistOnce();
    }

    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF050608);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                injectNativeBanner();
                pushLastNativeStatusToWeb();
                syncNativeWatch();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(), "AndroidCockpit");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2190);
        }
    }

    private void promptBatteryWhitelistOnce() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("batteryPromptSeen", true).apply();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        new AlertDialog.Builder(this)
                .setTitle("Autoriser la surveillance permanente")
                .setMessage("Pour garder ETH Scalper connecté écran verrouillé, autorise l'app à ne pas être optimisée par la batterie.")
                .setPositiveButton("Ouvrir réglage", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Plus tard", null)
                .show();
    }


    private void syncNativeWatch() {
        Intent i = new Intent(this, MarketWatchService.class);
        i.setAction(MarketWatchService.ACTION_SYNC_NOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
        if (webView != null) webView.postDelayed(this::pushLastNativeStatusToWeb, 900);
    }

    private void injectNativeBanner() {
        if (webView == null) return;
        String js = "(function(){" +
                "if(window.__ethNativeBanner2192)return;window.__ethNativeBanner2192=true;" +
                "var b=document.createElement('div');b.id='nativeWatchBanner2192';" +
                "b.style.cssText='position:fixed;left:10px;right:10px;bottom:10px;z-index:999999;border:1px solid rgba(255,170,0,.55);background:rgba(2,12,16,.96);color:#e8fff7;border-radius:14px;padding:10px 12px;font:800 12px system-ui;box-shadow:0 8px 30px rgba(0,0,0,.35);letter-spacing:.3px';" +
                "b.innerHTML='🟠 SURVEILLANCE ANDROID ACTIVE · attente statut natif';document.body.appendChild(b);" +
                "window.ethScalperNativeStatusUpdate=function(p){try{var ok=p&&(p.connected||p.type==='connected'||p.type==='live'||p.type==='signal');var age=(p&&p.lastAgeSec!=null&&p.lastAgeSec>=0)?(' · dernier flux '+p.lastAgeSec+'s'):'';var eth=(p&&p.eth&&Number(p.eth)>0)?(' · ETH '+Number(p.eth).toFixed(2)):'';var msg=p&&p.message?(' · '+p.message):'';b.style.borderColor=ok?'rgba(0,255,170,.65)':'rgba(255,170,0,.65)';b.style.color=ok?'#e8fff7':'#ffe4b5';b.innerHTML=(ok?'🟢':'🟠')+' SURVEILLANCE ANDROID '+(ok?'CONNECTÉE':'ACTIVE')+eth+age+msg;}catch(e){}};" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void startNativeWatch() {
        Intent i = new Intent(this, MarketWatchService.class);
        i.setAction(MarketWatchService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
    }

    private void stopNativeWatch() {
        Intent i = new Intent(this, MarketWatchService.class);
        i.setAction(MarketWatchService.ACTION_STOP);
        startService(i);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, new IntentFilter(MarketWatchService.BROADCAST_STATUS), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, new IntentFilter(MarketWatchService.BROADCAST_STATUS));
        }
        injectNativeBanner();
        pushLastNativeStatusToWeb();
        syncNativeWatch();
    }

    @Override protected void onPause() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public class NativeBridge {
        @JavascriptInterface public void startPermanentWatch() { runOnUiThread(MainActivity.this::startNativeWatch); }
        @JavascriptInterface public void stopPermanentWatch() { runOnUiThread(MainActivity.this::stopNativeWatch); }
        @JavascriptInterface public String getMode() { return "android-native-v2.19.2"; }
    }
}
