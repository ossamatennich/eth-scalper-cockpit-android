package com.ethscalper.cockpit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status, price, decision, action, signal, info;
    private boolean legacy = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String p = i.getStringExtra("payload");
            if (p != null) render(p);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MarketWatchService.ensureChannels(this);
        requestNotif();
        startWatch();
        buildNativeScreen();
        askBatteryOnce();
        askOverlayOnce();
    }

    private void buildNativeScreen() {
        legacy = false;
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 42);
        root.setBackgroundColor(Color.rgb(5, 9, 14));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("ETH SCALPER COCKPIT", 30, Color.WHITE, true);
        TextView sub = text("v2.21.0 Android natif · surveillance permanente", 14, Color.rgb(158,170,184), false);
        Button full = new Button(this);
        full.setText("Cockpit complet");
        full.setAllCaps(false);
        full.setOnClickListener(v -> showLegacy());

        root.addView(title);
        root.addView(sub);
        root.addView(full);

        status = card("SURVEILLANCE", "🟠 Connexion native Binance…", Color.rgb(255,190,120), 18);
        price = card("PRIX", "ETH — · BTC — · flux —", Color.WHITE, 20);
        decision = card("DÉCISION UNIQUE", "ATTENDRE", Color.rgb(255,159,45), 38);
        action = card("ACTION IMMÉDIATE", "NE PAS ENTRER", Color.WHITE, 32);
        signal = card("SIGNAL", "Aucun signal natif pour le moment.", Color.rgb(220,230,240), 18);
        info = card("INFO", "Tu peux quitter ou verrouiller l’écran. Le service Android continue derrière.", Color.rgb(200,212,224), 16);

        String last = MarketWatchService.getLastStatusJson();
        if (last != null && last.length() > 0) render(last);
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, 6, 0, 6);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView card(String label, String value, int color, int size) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(26, 22, 26, 22);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 16, 0, 0);
        box.setLayoutParams(lp);
        box.setBackgroundColor(Color.rgb(17, 23, 35));

        TextView l = text(label, 12, Color.rgb(150,160,176), true);
        TextView v = text(value, size, color, true);
        box.addView(l);
        box.addView(v);
        root.addView(box);
        return v;
    }

    private String fmt(double v) {
        return v > 0 ? String.format(Locale.US, "%.2f", v) : "—";
    }

    private void render(String payload) {
        try {
            JSONObject j = new JSONObject(payload);
            boolean connected = j.optBoolean("connected", false);
            int age = j.optInt("lastAgeSec", -1);
            double eth = j.optDouble("eth", 0);
            double bid = j.optDouble("bid", 0);
            double ask = j.optDouble("ask", 0);
            int candles = j.optInt("candles", 0);
            String msg = j.optString("message", "");

            runOnUiThread(() -> {
                status.setText(connected ? "🟢 Android natif connecté · flux " + Math.max(0, age) + "s" : "🟠 Service actif · reconnexion native");
                status.setTextColor(connected ? Color.rgb(125,255,217) : Color.rgb(255,190,120));
                price.setText("ETH " + fmt(eth) + " · BID " + fmt(bid) + " · ASK " + fmt(ask));
                decision.setText("ATTENDRE");
                action.setText("NE PAS ENTRER");
                signal.setText("Aucun signal natif pour le moment.");
                info.setText("Bougies natives : " + candles + "\nMessage : " + (msg.length() > 0 ? msg : "surveillance active") + "\nSource : service Android natif.");
            });
        } catch (Exception ignored) {}
    }

    private void showLegacy() {
        legacy = true;
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient());
        setContentView(w);
        w.loadUrl("file:///android_asset/www/index.html?v=2210");
    }

    private void startWatch() {
        Intent i = new Intent(this, MarketWatchService.class);
        i.setAction(MarketWatchService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2210);
    }

    private void askBatteryOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("batteryAsked2210", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("batteryAsked2210", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Surveillance permanente")
                .setMessage("Autorise ETH Scalper à rester actif en arrière-plan.")
                .setPositiveButton("Ouvrir", (d,w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch(Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Plus tard", null)
                .show();
    }

    private void askOverlayOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (Settings.canDrawOverlays(this)) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("overlayAsked2210", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("overlayAsked2210", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Superposition optionnelle")
                .setMessage("Tu peux autoriser l’affichage par-dessus les autres applications. Ce n’est pas obligatoire pour la surveillance.")
                .setPositiveButton("Ouvrir", (d,w) -> {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                })
                .setNegativeButton("Ignorer", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, new IntentFilter(MarketWatchService.BROADCAST_STATUS), Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(receiver, new IntentFilter(MarketWatchService.BROADCAST_STATUS));

        startWatch();
        String last = MarketWatchService.getLastStatusJson();
        if (last != null && last.length() > 0) render(last);
    }

    @Override protected void onPause() {
        try { unregisterReceiver(receiver); } catch(Exception ignored) {}
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (legacy) buildNativeScreen();
        else super.onBackPressed();
    }
}
