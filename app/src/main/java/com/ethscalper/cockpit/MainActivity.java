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

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status;
    private TextView price;
    private TextView decision;
    private TextView action;
    private TextView signal;
    private TextView info;
    private boolean showingLegacyCockpit;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String payload = intent.getStringExtra("payload");
            if (payload != null) render(payload);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MarketWatchService.ensureChannels(this);
        buildNativeScreen();
        requestNotificationPermission();
        startWatchService();
        askBatteryOptimizationOnce();
    }

    private void buildNativeScreen() {
        showingLegacyCockpit = false;
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 42);
        root.setBackgroundColor(Color.rgb(5, 9, 14));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("ETH SCALPER COCKPIT", 30, Color.WHITE, true));
        root.addView(text("v2.21.0 Android natif · surveillance permanente", 14, Color.rgb(158, 170, 184), false));

        Button fullCockpit = new Button(this);
        fullCockpit.setText("Cockpit complet");
        fullCockpit.setAllCaps(false);
        fullCockpit.setOnClickListener(view -> showLegacyCockpit());
        root.addView(fullCockpit);

        status = card("STATUT", "🟠 Service actif · reconnexion native", Color.rgb(255, 190, 120), 18);
        price = card("PRIX ETH", "ETH — · BID — · ASK —", Color.WHITE, 20);
        decision = card("DÉCISION", "ATTENDRE", Color.rgb(255, 159, 45), 38);
        action = card("ACTION", "NE PAS ENTRER", Color.WHITE, 32);
        signal = card("SIGNAL NATIF", "Aucun signal natif pour le moment.", Color.rgb(220, 230, 240), 18);
        info = card("INFO", "Source : service Android natif.", Color.rgb(200, 212, 224), 16);

        String lastState = MarketWatchService.getLastStatusJson();
        if (!lastState.isEmpty()) render(lastState);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, 6, 0, 6);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView card(String label, String value, int color, int size) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(26, 22, 26, 22);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.setMargins(0, 16, 0, 0);
        box.setLayoutParams(layout);
        box.setBackgroundColor(Color.rgb(17, 23, 35));

        box.addView(text(label, 12, Color.rgb(150, 160, 176), true));
        TextView content = text(value, size, color, true);
        box.addView(content);
        root.addView(box);
        return content;
    }

    private String formatPrice(double value) {
        return value > 0 ? String.format(Locale.US, "%.2f", value) : "—";
    }

    private void render(String payload) {
        try {
            JSONObject state = new JSONObject(payload);
            boolean connected = state.optBoolean("connected", false);
            int ageSeconds = state.optInt("lastAgeSec", -1);
            double eth = state.optDouble("eth", 0);
            double bid = state.optDouble("bid", 0);
            double ask = state.optDouble("ask", 0);
            int candles = state.optInt("candles", 0);
            String message = state.optString("message", "");
            boolean justDetected = "signal".equals(state.optString("type", ""));
            JSONObject plan = state.optJSONObject("lastPlan");

            runOnUiThread(() -> {
                if (status == null || price == null) return;
                status.setText(connected
                        ? "🟢 Android natif connecté · flux " + Math.max(0, ageSeconds) + "s"
                        : "🟠 Service actif · reconnexion native");
                status.setTextColor(connected ? Color.rgb(125, 255, 217) : Color.rgb(255, 190, 120));
                price.setText("ETH " + formatPrice(eth) + " · BID " + formatPrice(bid) + " · ASK " + formatPrice(ask));

                if (plan != null) {
                    String side = plan.optString("side", "—");
                    decision.setText(justDetected ? "SIGNAL " + side : "ATTENDRE");
                    action.setText(justDetected ? "SIGNAL NATIF DÉTECTÉ" : "NE PAS ENTRER");
                    signal.setText(String.format(Locale.US,
                            "%s%s · score %d/100 · quantité %d ETH\nEntrée %.2f · TP %.2f · SL %.2f\n%s",
                            justDetected ? "" : "Dernier signal · ",
                            side,
                            plan.optInt("score", 0),
                            plan.optInt("qty", 0),
                            plan.optDouble("entry", 0),
                            plan.optDouble("tp", 0),
                            plan.optDouble("sl", 0),
                            plan.optString("family", "Signal natif")));
                } else {
                    decision.setText("ATTENDRE");
                    action.setText("NE PAS ENTRER");
                    signal.setText("Aucun signal natif pour le moment.");
                }

                info.setText("Source : service Android natif.\nBougies natives : " + candles
                        + "\nÉtat : " + (message.isEmpty() ? "surveillance active" : message));
            });
        } catch (Exception ignored) {
            // Conserver le dernier état lisible si un payload incomplet est reçu.
        }
    }

    private void showLegacyCockpit() {
        showingLegacyCockpit = true;
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html?v=2210");
    }

    private void startWatchService() {
        Intent intent = new Intent(this, MarketWatchService.class);
        intent.setAction(MarketWatchService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2210);
        }
    }

    private void askBatteryOptimizationOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("batteryAsked2210", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("batteryAsked2210", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Surveillance permanente")
                .setMessage("Autorise ETH Scalper à rester actif en arrière-plan.")
                .setPositiveButton("Ouvrir", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception error) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Plus tard", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(MarketWatchService.BROADCAST_STATUS);
            ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        startWatchService();
        if (!showingLegacyCockpit) {
            String lastState = MarketWatchService.getLastStatusJson();
            if (!lastState.isEmpty()) render(lastState);
        }
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (showingLegacyCockpit) buildNativeScreen();
        else super.onBackPressed();
    }
}
