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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(5, 10, 17);
    private static final int CARD = Color.rgb(14, 23, 36);
    private static final int CARD_ALT = Color.rgb(18, 29, 45);
    private static final int BORDER = Color.rgb(38, 57, 78);
    private static final int TEXT = Color.rgb(237, 244, 251);
    private static final int MUTED = Color.rgb(137, 155, 177);
    private static final int CYAN = Color.rgb(67, 224, 193);
    private static final int ORANGE = Color.rgb(255, 169, 64);
    private static final int RED = Color.rgb(255, 78, 112);

    private LinearLayout root;
    private TextView statusPill, feedAge, decisionValue, decisionReason, actionValue, actionDetails;
    private TextView ethPrice, ethQuotes, btcPrice, btcQuotes, movementValue, signalValue;
    private TextView diagnosticValue, serviceInfo;
    private boolean showingLegacyCockpit;
    private boolean receiverRegistered;
    private WebView legacyWebView;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String payload = intent.getStringExtra(MarketWatchService.EXTRA_PAYLOAD);
            if (payload != null && !showingLegacyCockpit) render(payload);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        MarketWatchService.ensureChannels(this);
        buildNativeScreen();
        requestNotificationPermission();
        sendServiceAction(MarketWatchService.ACTION_START, null);
        askBatteryOptimizationOnce();
    }

    private void buildNativeScreen() {
        showingLegacyCockpit = false;
        if (legacyWebView != null) {
            legacyWebView.destroy();
            legacyWebView = null;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        buildHeader();
        buildDecisionCard();
        buildActionCard();
        buildPriceCard();
        buildMovementCard();
        buildSignalCard();
        buildDiagnosticCard();
        buildQuickSettingsCard();
        buildLegacyFooter();

        String lastState = MarketWatchService.getLastStatusJson(this);
        if (!lastState.isEmpty()) render(lastState);
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(4), dp(4), dp(4), dp(8));
        root.addView(header);

        TextView eyebrow = text("NATIVE MARKET INTELLIGENCE", 11, CYAN, true);
        eyebrow.setLetterSpacing(0.12f);
        header.addView(eyebrow);
        header.addView(text("ETH SCALPER\nCOCKPIT", 29, TEXT, true));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(10), 0, 0);
        header.addView(statusRow);

        statusPill = text("RECONNEXION NATIVE", 12, ORANGE, true);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusPill.setBackground(rounded(Color.rgb(39, 31, 23), ORANGE, 999, 1));
        statusRow.addView(statusPill);

        feedAge = text("flux —", 12, MUTED, false);
        LinearLayout.LayoutParams ageParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ageParams.setMargins(dp(10), 0, 0, 0);
        feedAge.setLayoutParams(ageParams);
        statusRow.addView(feedAge);

        TextView version = text("v2.22.0 · Android natif", 12, MUTED, true);
        version.setGravity(Gravity.END);
        statusRow.addView(version);
    }

    private void buildDecisionCard() {
        LinearLayout card = card("DÉCISION UNIQUE", ORANGE);
        decisionValue = text("ATTENDRE", 38, ORANGE, true);
        decisionValue.setLetterSpacing(0.04f);
        card.addView(decisionValue);
        decisionReason = text("Initialisation du moteur natif", 14, MUTED, false);
        decisionReason.setPadding(0, dp(7), 0, 0);
        card.addView(decisionReason);
    }

    private void buildActionCard() {
        LinearLayout card = card("ACTION IMMÉDIATE · EXÉCUTION MANUELLE", ORANGE);
        actionValue = text("NE PAS ENTRER", 27, TEXT, true);
        card.addView(actionValue);
        actionDetails = text("Aucun ordre automatique · attendre un signal confirmé", 14, MUTED, false);
        actionDetails.setPadding(0, dp(8), 0, 0);
        card.addView(actionDetails);
    }

    private void buildPriceCard() {
        LinearLayout card = card("PRIX FUTURES EN TEMPS RÉEL", CYAN);
        card.addView(marketLabel("ETH / USDT PERP"));
        ethPrice = text("—", 34, TEXT, true);
        card.addView(ethPrice);
        ethQuotes = text("BID —    ·    ASK —", 14, MUTED, true);
        card.addView(ethQuotes);
        View separator = new View(this);
        separator.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        separatorParams.setMargins(0, dp(15), 0, dp(14));
        card.addView(separator, separatorParams);
        card.addView(marketLabel("BTC / USDT PERP · CONTEXTE / VETO"));
        btcPrice = text("—", 27, TEXT, true);
        card.addView(btcPrice);
        btcQuotes = text("BID —    ·    ASK —", 14, MUTED, true);
        card.addView(btcQuotes);
    }

    private TextView marketLabel(String value) {
        TextView label = text(value, 12, CYAN, true);
        label.setLetterSpacing(0.08f);
        return label;
    }

    private void buildMovementCard() {
        LinearLayout card = card("ÉTAT DU MOUVEMENT", CYAN);
        movementValue = text("Impulsion : NEUTRE\nReset : NON\nOrigine : — · Extrême : —\nDistance : —\nMouvement consommé : NON", 15, TEXT, false);
        movementValue.setLineSpacing(dp(3), 1f);
        card.addView(movementValue);
    }

    private void buildSignalCard() {
        LinearLayout card = card("SIGNAL NATIF", RED);
        signalValue = text("Aucun signal natif pour le moment.", 16, TEXT, true);
        signalValue.setLineSpacing(dp(3), 1f);
        card.addView(signalValue);
    }

    private void buildDiagnosticCard() {
        LinearLayout card = card("DIAGNOSTIC MOTEUR", ORANGE);
        diagnosticValue = text("• NO_DATA · Initialisation", 13, TEXT, false);
        diagnosticValue.setTypeface(Typeface.MONOSPACE);
        diagnosticValue.setLineSpacing(dp(3), 1f);
        card.addView(diagnosticValue);
        serviceInfo = text("Source : MarketWatchService natif", 12, MUTED, false);
        serviceInfo.setPadding(0, dp(12), 0, 0);
        card.addView(serviceInfo);
    }

    private void buildQuickSettingsCard() {
        LinearLayout card = card("PARAMÈTRES RAPIDES", CYAN);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        card.addView(buttons);
        buttons.addView(actionButton("Tester alerte forte", RED,
                () -> sendServiceAction(MarketWatchService.ACTION_TEST_ALERT, "Alerte forte envoyée")));
        buttons.addView(actionButton("Tester vibration", CYAN,
                () -> sendServiceAction(MarketWatchService.ACTION_TEST_VIBRATION, "Vibration testée")));
        buttons.addView(actionButton("Réinitialiser diagnostic", ORANGE,
                () -> sendServiceAction(MarketWatchService.ACTION_RESET_DIAGNOSTICS, "Diagnostic réinitialisé")));
    }

    private void buildLegacyFooter() {
        TextView legacy = text("Vue legacy · cockpit complet", 12, MUTED, true);
        legacy.setGravity(Gravity.CENTER);
        legacy.setPadding(dp(12), dp(12), dp(12), dp(12));
        legacy.setBackground(rounded(Color.TRANSPARENT, BORDER, 12, 1));
        legacy.setOnClickListener(view -> showLegacyCockpit());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(18), 0, 0);
        root.addView(legacy, params);
        TextView note = text("La vue legacy est secondaire. La connexion et les signaux restent 100 % natifs.",
                11, MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(12), dp(8), dp(12), 0);
        root.addView(note);
    }

    private LinearLayout card(String label, int accent) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(18), dp(16), dp(18), dp(17));
        container.setBackground(rounded(CARD, BORDER, 18, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(13), 0, 0);
        root.addView(container, params);
        TextView title = text(label, 11, accent, true);
        title.setLetterSpacing(0.12f);
        title.setPadding(0, 0, 0, dp(10));
        container.addView(title);
        return container;
    }

    private Button actionButton(String label, int accent, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setBackground(rounded(CARD_ALT, accent, 12, 1));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, 0, 0, dp(9));
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatPrice(double value) {
        return Double.isFinite(value) && value > 0 ? String.format(Locale.US, "%.2f", value) : "—";
    }

    private double number(JSONObject object, String key) {
        return object.isNull(key) ? Double.NaN : object.optDouble(key, Double.NaN);
    }

    private void render(String payload) {
        try {
            JSONObject state = new JSONObject(payload);
            boolean connected = state.optBoolean("connected", false);
            int ageSeconds = state.optInt("lastAgeSec", -1);
            String decision = state.optString("decision", "ATTENDRE");
            String reason = state.optString("decisionReason", "Moteur natif en attente");
            String action = state.optString("action", "NE PAS ENTRER");
            double eth = number(state, "eth"), bid = number(state, "bid"), ask = number(state, "ask");
            double btc = number(state, "btc"), btcBid = number(state, "btcBid"), btcAsk = number(state, "btcAsk");
            JSONObject movement = state.optJSONObject("movement");
            JSONObject lastSignal = state.optJSONObject("lastSignal");
            JSONArray diagnostics = state.optJSONArray("diagnostics");
            long signalAt = state.optLong("lastSignalAt", 0);
            int ethCount = state.optInt("ethCandles", state.optInt("candles", 0));
            int btcCount = state.optInt("btcCandles", 0);

            runOnUiThread(() -> {
                if (statusPill == null) return;
                renderConnection(connected, ageSeconds);
                renderDecision(decision, reason);
                renderPrices(eth, bid, ask, btc, btcBid, btcAsk);
                renderMovement(movement);
                renderSignal(lastSignal, signalAt);
                renderAction(action, decision, lastSignal);
                renderDiagnostics(diagnostics, state.optString("engineReason", "NO_DATA"), reason);
                serviceInfo.setText("Source : MarketWatchService natif · bougies ETH " + ethCount
                        + " / BTC " + btcCount + " · trading manuel uniquement");
            });
        } catch (Exception ignored) {}
    }

    private void renderConnection(boolean connected, int age) {
        if (connected && age >= 0 && age <= 8) {
            statusPill.setText("CONNECTÉ"); statusPill.setTextColor(CYAN);
            statusPill.setBackground(rounded(Color.rgb(18, 45, 42), CYAN, 999, 1));
        } else if (connected) {
            statusPill.setText("FLUX RETARDÉ"); statusPill.setTextColor(ORANGE);
            statusPill.setBackground(rounded(Color.rgb(48, 35, 20), ORANGE, 999, 1));
        } else {
            statusPill.setText("RECONNEXION NATIVE"); statusPill.setTextColor(ORANGE);
            statusPill.setBackground(rounded(Color.rgb(48, 35, 20), ORANGE, 999, 1));
        }
        feedAge.setText(age >= 0 ? "âge du flux : " + age + "s" : "âge du flux : —");
    }

    private void renderDecision(String decision, String reason) {
        int color = "ENTRER".equals(decision) ? CYAN
                : "GARDER".equals(decision) ? CYAN
                : "FERMER".equals(decision) ? RED : ORANGE;
        decisionValue.setText(decision);
        decisionValue.setTextColor(color);
        decisionReason.setText(reason);
    }

    private void renderPrices(double eth, double bid, double ask, double btc, double btcBid, double btcAsk) {
        ethPrice.setText(formatPrice(eth));
        ethQuotes.setText("BID " + formatPrice(bid) + "    ·    ASK " + formatPrice(ask));
        btcPrice.setText(formatPrice(btc));
        btcQuotes.setText("BID " + formatPrice(btcBid) + "    ·    ASK " + formatPrice(btcAsk));
    }

    private void renderMovement(JSONObject movement) {
        if (movement == null) {
            movementValue.setText("Impulsion : NEUTRE\nReset : NON\nOrigine : — · Extrême : —\nDistance : —\nMouvement consommé : NON");
            return;
        }
        movementValue.setText("Impulsion : " + movement.optString("impulse", "NEUTRE")
                + "\nReset : " + (movement.optBoolean("reset", false) ? "OK" : "NON")
                + "\nOrigine : " + formatPrice(number(movement, "origin"))
                + " · Extrême : " + formatPrice(number(movement, "extreme"))
                + "\nDistance parcourue : " + formatPrice(number(movement, "distance")) + " $"
                + "\nMouvement consommé : " + (movement.optBoolean("consumed", false) ? "OUI" : "NON"));
    }

    private void renderSignal(JSONObject signal, long signalAt) {
        if (signal == null) {
            signalValue.setText("Aucun signal natif pour le moment.");
            signalValue.setTextColor(TEXT);
            return;
        }
        boolean active = signalAt > 0 && System.currentTimeMillis() - signalAt <= MarketWatchService.SIGNAL_DISPLAY_TTL_MS;
        signalValue.setText((active ? "SIGNAL " : "DERNIER SIGNAL ") + signal.optString("side", "—")
                + " · score " + signal.optInt("score", 0) + "/100"
                + "\n" + signal.optString("family", "Signal natif")
                + "\nLIMIT " + formatPrice(number(signal, "entry"))
                + " · TP " + formatPrice(number(signal, "tp"))
                + " · SL " + formatPrice(number(signal, "sl"))
                + " · " + signal.optInt("qty", 0) + " ETH");
        signalValue.setTextColor(active ? CYAN : TEXT);
    }

    private void renderAction(String action, String decision, JSONObject signal) {
        actionValue.setText(action);
        actionValue.setTextColor("ENTRER".equals(decision) ? CYAN : TEXT);
        if ("ENTRER".equals(decision) && signal != null) {
            actionDetails.setText("Prix : " + formatPrice(number(signal, "entry"))
                    + "\nTP : " + formatPrice(number(signal, "tp"))
                    + " · SL : " + formatPrice(number(signal, "sl"))
                    + "\nQuantité : " + signal.optInt("qty", 0) + " ETH · validation manuelle");
        } else {
            actionDetails.setText("Aucun ordre automatique · " + decision.toLowerCase(Locale.FRANCE));
        }
    }

    private void renderDiagnostics(JSONArray diagnostics, String fallbackCode, String fallbackReason) {
        if (diagnostics == null || diagnostics.length() == 0) {
            diagnosticValue.setText("• " + fallbackCode + " · " + fallbackReason);
            return;
        }
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
        StringBuilder lines = new StringBuilder();
        int shown = 0;
        for (int i=diagnostics.length()-1; i>=0 && shown<8; i--, shown++) {
            JSONObject item = diagnostics.optJSONObject(i);
            if (item == null) continue;
            if (lines.length() > 0) lines.append('\n');
            lines.append("• ").append(clock.format(new Date(item.optLong("at", 0))))
                    .append(" · ").append(item.optString("code", "—"))
                    .append(" · ").append(item.optString("message", ""));
        }
        diagnosticValue.setText(lines.toString());
    }

    private void sendServiceAction(String action, String toast) {
        Intent intent = new Intent(this, MarketWatchService.class).setAction(action);
        startForegroundService(intent);
        if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private void showLegacyCockpit() {
        showingLegacyCockpit = true;
        legacyWebView = new WebView(this);
        legacyWebView.setBackgroundColor(BG);
        WebSettings settings = legacyWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        legacyWebView.setWebViewClient(new WebViewClient());
        legacyWebView.setWebChromeClient(new WebChromeClient());
        setContentView(legacyWebView);
        legacyWebView.loadUrl("file:///android_asset/www/index.html?v=2220");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2220);
        }
    }

    private void askBatteryOptimizationOnce() {
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager != null && manager.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("batteryAsked2210", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("batteryAsked2210", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Surveillance permanente")
                .setMessage("Autorise le moteur natif à rester actif lorsque l’écran est verrouillé.")
                .setPositiveButton("Ouvrir", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:" + getPackageName()));
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
            ContextCompat.registerReceiver(this, statusReceiver,
                    new IntentFilter(MarketWatchService.BROADCAST_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        sendServiceAction(MarketWatchService.ACTION_START, null);
        if (!showingLegacyCockpit) {
            String lastState = MarketWatchService.getLastStatusJson(this);
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
