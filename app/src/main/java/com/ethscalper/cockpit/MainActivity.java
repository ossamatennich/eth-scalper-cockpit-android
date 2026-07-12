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
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.MediaStore;
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
import android.widget.EditText;
import android.widget.Switch;
import android.text.InputType;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    private TextView diagnosticValue, serviceInfo, aiInfo;
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
        root.setPadding(dp(18), dp(18), dp(18), dp(110));
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
        header.addView(text("ETH SCALPER\nCOCKPIT", 27, TEXT, true));

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

        TextView version = text("v2.30.7 · Android natif", 12, MUTED, true);
        version.setGravity(Gravity.END);
        statusRow.addView(version);
    }

    private void buildDecisionCard() {
        LinearLayout card = card("DÉCISION UNIQUE", ORANGE);
        decisionValue = text("ATTENDRE", 36, ORANGE, true);
        decisionValue.setLetterSpacing(0.04f);
        card.addView(decisionValue);
        decisionReason = text("Initialisation du moteur natif", 14, MUTED, false);
        decisionReason.setPadding(0, dp(7), 0, 0);
        card.addView(decisionReason);
    }

    private void buildActionCard() {
        LinearLayout card = card("ACTION IMMÉDIATE · EXÉCUTION MANUELLE", ORANGE);
        actionValue = text("NE PAS ENTRER", 26, TEXT, true);
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
        diagnosticValue = text("• NO_DATA · Initialisation", 12, TEXT, false);
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
        aiInfo = text("IA OpenAI : " + (SecureAiStore.isEnabled(this) ? "ON · " + SecureAiStore.maskedKey(this) : "OFF"), 12, MUTED, false);
        aiInfo.setPadding(0, 0, 0, dp(10));
        card.addView(aiInfo);
        buttons.addView(actionButton("Réglages IA OpenAI", CYAN,
                this::showAiSettingsDialog));
        buttons.addView(actionButton("Tester clé IA", ORANGE,
                this::testAiKeyNow));
        buttons.addView(actionButton("Tester alerte forte", RED,
                () -> sendServiceAction(MarketWatchService.ACTION_TEST_ALERT, "Alerte forte envoyée")));
        buttons.addView(actionButton("Tester vibration", CYAN,
                () -> sendServiceAction(MarketWatchService.ACTION_TEST_VIBRATION, "Vibration testée")));
        buttons.addView(actionButton("Réinitialiser diagnostic", ORANGE,
                () -> sendServiceAction(MarketWatchService.ACTION_RESET_DIAGNOSTICS, "Diagnostic réinitialisé")));
        buttons.addView(actionButton("Télécharger diagnostic ZIP", CYAN,
                this::exportDiagnosticZip));
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
        container.setPadding(dp(18), dp(14), dp(18), dp(15));
        container.setBackground(rounded(CARD, BORDER, 18, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(11), 0, 0);
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

    private void testAiKeyNow() {
        if (!SecureAiStore.hasKey(this)) {
            Toast.makeText(this, "Aucune clé IA enregistrée", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Test IA en cours…", Toast.LENGTH_SHORT).show();

        new AiAdvisor(this).testKeyAsync(result -> runOnUiThread(() -> {
            if (result != null && result.approved && !result.fallback) {
                Toast.makeText(this, "Clé IA OK", Toast.LENGTH_LONG).show();
                if (aiInfo != null) aiInfo.setText("IA OpenAI : ON · TEST OK · " + SecureAiStore.maskedKey(this));
            } else {
                String reason = result == null ? "AI_EMPTY" : result.reason;
                Toast.makeText(this, "Test IA échec : " + reason, Toast.LENGTH_LONG).show();
                if (aiInfo != null) aiInfo.setText("IA OpenAI : problème · " + reason);
            }
        }));
    }

    private void showAiSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);

        Switch enabled = new Switch(this);
        enabled.setText("IA automatique OpenAI");
        enabled.setTextColor(TEXT);
        enabled.setTextSize(15);
        enabled.setChecked(SecureAiStore.isEnabled(this));
        box.addView(enabled);

        EditText keyInput = new EditText(this);
        keyInput.setHint(SecureAiStore.hasKey(this) ? SecureAiStore.maskedKey(this) + " · laisser vide pour conserver" : "Coller clé OpenAI ici");
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setTextColor(TEXT);
        keyInput.setHintTextColor(MUTED);
        box.addView(keyInput);

        EditText modelInput = new EditText(this);
        modelInput.setHint("Modèle OpenAI");
        modelInput.setSingleLine(true);
        modelInput.setText(SecureAiStore.getModel(this));
        modelInput.setTextColor(TEXT);
        modelInput.setHintTextColor(MUTED);
        box.addView(modelInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Réglages IA OpenAI")
                .setMessage("La clé est stockée localement. Elle n’est pas envoyée dans GitHub.")
                .setView(box)
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton("Annuler", null)
                .setNeutralButton("Effacer clé", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String key = keyInput.getText() == null ? "" : keyInput.getText().toString().trim();
                String model = modelInput.getText() == null ? "" : modelInput.getText().toString().trim();

                if (!key.isEmpty()) SecureAiStore.saveKey(this, key);
                SecureAiStore.saveModel(this, model);
                SecureAiStore.setEnabled(this, enabled.isChecked());

                if (aiInfo != null) {
                    aiInfo.setText("IA OpenAI : " + (SecureAiStore.isEnabled(this) ? "ON · " + SecureAiStore.maskedKey(this) : "OFF"));
                }

                sendServiceAction(MarketWatchService.ACTION_SYNC_NOW, "Réglages IA enregistrés");
                dialog.dismiss();
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                SecureAiStore.clear(this);
                if (aiInfo != null) aiInfo.setText("IA OpenAI : OFF");
                sendServiceAction(MarketWatchService.ACTION_SYNC_NOW, "Clé IA effacée");
                dialog.dismiss();
            });
        });

        dialog.show();
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
            final boolean warmingUp = ethCount < 30 || btcCount < 10;
            final String visibleReason = warmingUp
                    ? "Pré-chauffage moteur : ETH " + ethCount + "/30 · BTC " + btcCount + "/10"
                    : reason;
            final String visibleServiceInfo = warmingUp
                    ? "Pré-chauffage moteur : ETH " + ethCount + "/30 · BTC " + btcCount + "/10\nSource : MarketWatchService natif · trading manuel uniquement"
                    : "Moteur prêt · bougies ETH " + ethCount + " / BTC " + btcCount + "\nSource : MarketWatchService natif · trading manuel uniquement";

            runOnUiThread(() -> {
                if (statusPill == null) return;
                renderConnection(connected, ageSeconds);
                renderDecision(decision, visibleReason);
                renderPrices(eth, bid, ask, btc, btcBid, btcAsk);
                renderMovement(movement);
                renderSignal(lastSignal, signalAt, decision, visibleReason, state.optBoolean("activeSignal", false), state.optString("activeSignalStatus", "NONE"));
                renderAction(action, decision, lastSignal, eth, signalAt,
                        state.optBoolean("activeSignal", false),
                        state.optString("activeSignalStatus", "NONE"),
                        state.optString("signalExecutionState", "ATTENDRE"));
                renderDiagnostics(diagnostics, state.optString("engineReason", "NO_DATA"), reason);
                if (aiInfo != null) aiInfo.setText("IA OpenAI : " + (state.optBoolean("aiEnabled", false) ? "ON" : "OFF") + " · " + state.optString("aiStatus", "AI_OFF"));
                serviceInfo.setText(visibleServiceInfo);
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

    private void renderSignal(JSONObject signal, long signalAt, String currentDecision, String currentReason,
                              boolean activeSignal, String activeStatus) {
        if (signal == null) {
            signalValue.setText("Aucun signal natif pour le moment.\nMoteur actif · attendre un setup confirmé.");
            signalValue.setTextColor(TEXT);
            return;
        }

        long ageMs = signalAt > 0 ? System.currentTimeMillis() - signalAt : -1;
        long ageSec = ageMs >= 0 ? Math.max(0, ageMs / 1000) : -1;

        String ageText = ageSec >= 0 ? "Signal reçu il y a " + formatDuration(ageSec) : "Âge du signal : —";
        String plan = signal.optString("side", "—")
                + " · score " + signal.optInt("score", 0) + "/100"
                + "\n" + signal.optString("family", "Signal natif")
                + "\nLIMIT " + formatPrice(number(signal, "entry"))
                + " · TP " + formatPrice(number(signal, "tp"))
                + " · SL " + formatPrice(number(signal, "sl"))
                + " · " + signal.optInt("qty", 0) + " ETH";

        if (activeSignal) {
            signalValue.setText("SIGNAL LIMIT VALIDE — ORDRE À PRIX FIXE"
                    + "\n" + ageText
                    + "\nValidité : jusqu’à TP / SL / invalidation marché"
                    + "\nNe pas entrer au marché : utiliser seulement le prix LIMIT."
                    + "\n" + plan);
            signalValue.setTextColor(CYAN);
        } else {
            signalValue.setText("SIGNAL PASSÉ — NE PAS ENTRER MAINTENANT"
                    + "\n" + ageText
                    + "\nInvalidation : " + humanSignalStatus(activeStatus)
                    + "\n\nDernier plan reçu :"
                    + "\n" + plan);
            signalValue.setTextColor(ORANGE);
        }
    }

    private String humanSignalStatus(String status) {
        if ("TP_TOUCHED".equals(status)) return "objectif touché";
        if ("SL_TOUCHED".equals(status)) return "stop touché";
        if ("SCENARIO_INVALIDATED".equals(status)) return "scénario invalidé";
        if ("TIMEOUT_45M".equals(status)) return "temps maximum ordre limit dépassé";
        if ("ENTRY_TOO_FAR".equals(status)) return "entrée trop tardive / scénario suivi";
        if ("SCENARIO_MEMORY_VETO".equals(status)) return "signal inverse bloqué par mémoire scénario";
        if ("BTC_VETO".equals(status)) return "BTC opposé";
        if ("REVERSAL_FLOW".equals(status)) return "flow opposé";
        if ("REVERSAL_MOVE".equals(status)) return "mouvement inversé";
        if ("NO_PRICE".equals(status)) return "prix indisponible";
        if ("NONE".equals(status)) return "aucun signal actif";
        return status == null || status.trim().isEmpty() ? "raison inconnue" : status;
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) return minutes + "min " + rest + "s";
        long hours = minutes / 60;
        long remMin = minutes % 60;
        return hours + "h " + remMin + "min";
    }

    private void renderAction(String action, String decision, JSONObject signal, double eth, long signalAt,
                              boolean activeSignal, String activeStatus, String executionState) {
        if (("LIMIT_VALIDE".equals(executionState) || "LIMIT_EN_ATTENTE".equals(executionState) || "ENTRER_MAINTENANT".equals(executionState))
                && "ENTRER".equals(decision) && signal != null && activeSignal) {
            String side = signal.optString("side", "");
            double entry = number(signal, "entry");
            double tp = number(signal, "tp");
            double sl = number(signal, "sl");

            long ageSec = signalAt > 0 ? Math.max(0, (System.currentTimeMillis() - signalAt) / 1000) : 0;

            actionValue.setText("ORDRE LIMIT VALIDE — " + side);
            actionValue.setTextColor(CYAN);
            actionDetails.setText("Prix LIMIT à poser ou garder : " + formatPrice(entry)
                    + "\nPrix actuel : " + formatPrice(eth)
                    + "\nTP : " + formatPrice(tp) + " · SL : " + formatPrice(sl)
                    + "\nÂge du signal : " + formatDuration(ageSec)
                    + "\nNe pas entrer au marché. Ne pas poursuivre le prix."
                    + "\nGarder seulement tant que l’app ne dit pas ANNULÉ / TP / SL.");
            return;
        }

        if ("TROP_TARD".equals(executionState) || "ENTRY_TOO_FAR".equals(activeStatus)) {
            actionValue.setText("TROP TARD — NE PAS ENTRER");
            actionValue.setTextColor(ORANGE);
            actionDetails.setText("Entrée trop tardive : ne poursuis pas. Le scénario peut rester suivi en mémoire si le prix était proche TP.");
            return;
        }

        if ("ANNULE".equals(executionState) || "SCENARIO_INVALIDATED".equals(activeStatus)) {
            actionValue.setText("SIGNAL ANNULÉ — NE PAS ENTRER");
            actionValue.setTextColor(RED);
            actionDetails.setText("Le scénario marché n’est plus valide. Annuler l’ordre limit s’il était posé.");
            return;
        }

        if ("TERMINE".equals(executionState) || "TP_TOUCHED".equals(activeStatus) || "SL_TOUCHED".equals(activeStatus)) {
            actionValue.setText("SIGNAL TERMINÉ — NE PAS ENTRER");
            actionValue.setTextColor(RED);
            actionDetails.setText("Résultat : " + activeStatus + ". Attendre le prochain signal.");
            return;
        }

        if ("V230_AI_PENDING".equals(decision) || "AI_PENDING".equals(executionState)) {
            actionValue.setText("ANALYSE IA EN COURS — ATTENDRE");
            actionValue.setTextColor(ORANGE);
            actionDetails.setText("Ne rien faire tant que l’IA n’a pas confirmé.");
            return;
        }

        actionValue.setText("NE PAS ENTRER");
        actionValue.setTextColor(TEXT);
        actionDetails.setText("Aucun signal exécutable maintenant · attendre un signal validé.");
    }

    private void renderDiagnostics(JSONArray diagnostics, String fallbackCode, String fallbackReason) {
        if (diagnostics == null || diagnostics.length() == 0) {
            diagnosticValue.setText("• " + fallbackCode + " · " + fallbackReason);
            return;
        }
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
        StringBuilder lines = new StringBuilder();
        int shown = 0;
        for (int i=diagnostics.length()-1; i>=0 && shown<5; i--, shown++) {
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


    private void exportDiagnosticZip() {
        try {
            String raw = MarketWatchService.getLastStatusJson(this);
            if (raw == null || raw.trim().isEmpty()) {
                Toast.makeText(this, "Diagnostic vide : laisse le moteur tourner quelques secondes.", Toast.LENGTH_LONG).show();
                return;
            }

            JSONObject state = new JSONObject(raw);
            String fileName = "ETH_Scalper_Diagnostic_v2_30_7_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date()) + ".zip";

            ByteArrayOutputStream memory = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(memory)) {
                JSONObject metrics = state.optJSONObject("engineMetrics");
                JSONArray observed = state.optJSONArray("observedSignals");
                String marketFramesRaw = MarketWatchService.getLastMarketFramesJson();
                String marketSummaryRaw = MarketWatchService.getLastMarketSummaryJson();
                JSONArray marketFrames = new JSONArray(marketFramesRaw == null || marketFramesRaw.trim().isEmpty() ? "[]" : marketFramesRaw);
                JSONObject marketSummary = new JSONObject(marketSummaryRaw == null || marketSummaryRaw.trim().isEmpty() ? "{}" : marketSummaryRaw);
                addZipText(zip, "status.json", state.toString(2));
                addZipText(zip, "engine_metrics.json", metrics == null ? "{}" : metrics.toString(2));
                addZipText(zip, "engine_metrics.txt", buildEngineMetricsText(state));
                addZipText(zip, "observation_journal.json", observed == null ? "[]" : observed.toString(2));
                addZipText(zip, "observation_journal.csv", buildObservationJournalCsv(observed));
                addZipText(zip, "observation_summary.txt", buildObservationSummaryText(state));
                addZipText(zip, "market_frames.json", marketFrames.toString(2));
                addZipText(zip, "market_frames.csv", buildMarketFramesCsv(marketFrames));
                addZipText(zip, "market_summary.json", marketSummary.toString(2));
                addZipText(zip, "market_summary.txt", buildMarketSummaryText(marketSummary));
                addZipText(zip, "summary.txt", buildDiagnosticSummary(state));
                addZipText(zip, "health_check.txt", buildHealthCheck(state));
                addZipText(zip, "diagnostics.csv", buildDiagnosticsCsv(state.optJSONArray("diagnostics")));
                addZipText(zip, "instructions.txt",
                        "Envoyer ce ZIP à ChatGPT pour analyse du moteur ETH Scalper.\n" +
                        "Ce fichier ne contient aucune clé API et aucun ordre automatique.\n" +
                        "Le trading reste manuel.\n");
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Impossible de créer le fichier dans Téléchargements");

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("Impossible d’écrire le fichier");
                memory.writeTo(out);
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);

            Toast.makeText(this, "Diagnostic téléchargé dans Téléchargements : " + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Téléchargement impossible : " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String buildDiagnosticSummary(JSONObject s) {
        StringBuilder b = new StringBuilder();
        b.append("ETH SCALPER COCKPIT — DIAGNOSTIC\n");
        b.append("Version app: v2.30.7 Android natif\n");
        b.append("Version service: ").append(s.optString("version", "—")).append("\n");
        b.append("Mode: V230_HYBRID_AI_SCALP_ENGINE — recherche uniquement, aucun trade réel\n\n");

        b.append("STATUT\n");
        b.append("- connected: ").append(s.optBoolean("connected", false)).append("\n");
        b.append("- nativeActive: ").append(s.optBoolean("nativeActive", false)).append("\n");
        b.append("- lastAgeSec: ").append(s.optInt("lastAgeSec", -1)).append("\n");
        b.append("- type: ").append(s.optString("type", "—")).append("\n");
        b.append("- message: ").append(s.optString("message", "—")).append("\n\n");

        b.append("MARCHÉ\n");
        b.append("- ETH: ").append(s.optString("eth", "—")).append("\n");
        b.append("- ETH bid/ask: ").append(s.optString("bid", "—")).append(" / ").append(s.optString("ask", "—")).append("\n");
        b.append("- BTC: ").append(s.optString("btc", "—")).append("\n");
        b.append("- BTC bid/ask: ").append(s.optString("btcBid", "—")).append(" / ").append(s.optString("btcAsk", "—")).append("\n");
        b.append("- ethCandles: ").append(s.optInt("ethCandles", 0)).append("\n");
        b.append("- btcCandles: ").append(s.optInt("btcCandles", 0)).append("\n");
        b.append("- bookTickerMessages: ").append(s.optLong("bookTickerMessages", 0)).append("\n");
        b.append("- klineMessages: ").append(s.optLong("klineMessages", 0)).append("\n");
        b.append("- aggTradeMessages: ").append(s.optLong("aggTradeMessages", 0)).append("\n");
        b.append("- restKlineRefreshes: ").append(s.optLong("restKlineRefreshes", 0)).append("\n");
        b.append("- restTradeRefreshes: ").append(s.optLong("restTradeRefreshes", 0)).append("\n");
        b.append("- tradeFlowSamples: ").append(s.optInt("tradeFlowSamples", 0)).append("\n");
        b.append("- lastAggTradeAgeSec: ").append(s.optInt("lastAggTradeAgeSec", -1)).append("\n");
        b.append("- lastRestKlineAgeSec: ").append(s.optInt("lastRestKlineAgeSec", -1)).append("\n");
        b.append("- lastRestTradeAgeSec: ").append(s.optInt("lastRestTradeAgeSec", -1)).append("\n\n");

        b.append("MOTEUR\n");
        b.append("- decision: ").append(s.optString("decision", "—")).append("\n");
        b.append("- action: ").append(s.optString("action", "—")).append("\n");
        b.append("- engineReason: ").append(s.optString("engineReason", "—")).append("\n");
        b.append("- decisionReason: ").append(s.optString("decisionReason", "—")).append("\n");
        b.append("- score: ").append(s.optInt("score", 0)).append("\n\n");

        JSONObject metrics = s.optJSONObject("engineMetrics");
        if (metrics != null) {
            b.append("MÉTRIQUES MOTEUR EXPERTES\n");
            b.append("- setupCandidate: ").append(metrics.optString("setupCandidate", "—")).append("\n");
            b.append("- threshold: ").append(metrics.optString("threshold", "—")).append("\n");
            b.append("- move1: ").append(metrics.optString("move1", "—")).append("\n");
            b.append("- move3: ").append(metrics.optString("move3", "—")).append("\n");
            b.append("- move8: ").append(metrics.optString("move8", "—")).append("\n");
            b.append("- avgRange20: ").append(metrics.optString("avgRange20", "—")).append("\n");
            b.append("- volumeRatio: ").append(metrics.optString("volumeRatio", "—")).append("\n");
            b.append("- flowNorm: ").append(metrics.optString("flowNorm", "—")).append("\n");
            b.append("- btcMove5: ").append(metrics.optString("btcMove5", "—")).append("\n");
            b.append("- spread: ").append(metrics.optString("spread", "—")).append("\n\n");
        }

        JSONObject movement = s.optJSONObject("movement");
        if (movement != null) {
            b.append("MOUVEMENT\n");
            b.append("- impulse: ").append(movement.optString("impulse", "—")).append("\n");
            b.append("- reset: ").append(movement.optBoolean("reset", false)).append("\n");
            b.append("- origin: ").append(movement.optString("origin", "—")).append("\n");
            b.append("- extreme: ").append(movement.optString("extreme", "—")).append("\n");
            b.append("- distance: ").append(movement.optString("distance", "—")).append("\n");
            b.append("- consumed: ").append(movement.optBoolean("consumed", false)).append("\n\n");
        }

        b.append("RÈGLES PROJET\n");
        b.append("- Une seule décision à la fois.\n");
        b.append("- BTC = contexte/veto, jamais déclencheur autonome.\n");
        b.append("- Aucun ordre automatique.\n");
        b.append("- Ne jamais poursuivre un mouvement consommé.\n");

        return b.toString();
    }

    private String buildHealthCheck(JSONObject s) {
        StringBuilder b = new StringBuilder();
        boolean connected = s.optBoolean("connected", false);
        int age = s.optInt("lastAgeSec", -1);
        int ethCandles = s.optInt("ethCandles", s.optInt("candles", 0));
        int btcCandles = s.optInt("btcCandles", 0);
        boolean ethOk = !s.isNull("eth") && s.optDouble("eth", 0) > 0;
        boolean btcOk = !s.isNull("btc") && s.optDouble("btc", 0) > 0;
        boolean bidAskOk = !s.isNull("bid") && !s.isNull("ask") && s.optDouble("bid", 0) > 0 && s.optDouble("ask", 0) > 0;
        boolean btcBidAskOk = !s.isNull("btcBid") && !s.isNull("btcAsk") && s.optDouble("btcBid", 0) > 0 && s.optDouble("btcAsk", 0) > 0;
        boolean candlesOk = ethCandles >= 30 && btcCandles >= 10;
        String reason = s.optString("engineReason", "—");

        b.append("AUTO-CHECK ETH SCALPER\n\n");
        b.append(line(connected, "Service natif connecté"));
        b.append(line(age >= 0 && age <= 8, "Flux récent <= 8s, âge actuel " + age + "s"));
        b.append(line(ethOk, "Prix ETH reçu"));
        b.append(line(bidAskOk, "BID/ASK ETH reçus"));
        b.append(line(btcOk, "Prix BTC reçu"));
        b.append(line(btcBidAskOk, "BID/ASK BTC reçus"));
        JSONObject metrics = s.optJSONObject("engineMetrics");
        int evalAge = s.optInt("lastEvaluationAgeSec", -1);
        int tradeFlowSamples = s.optInt("tradeFlowSamples", -1);
        long aggTradeMessages = s.optLong("aggTradeMessages", -1);
        long restTradeRefreshes = s.optLong("restTradeRefreshes", 0);
        int aggTradeAge = s.optInt("lastAggTradeAgeSec", -1);
        int restTradeAge = s.optInt("lastRestTradeAgeSec", -1);
        boolean metricsOk = metrics != null;
        boolean evalOk = evalAge >= 0 && evalAge <= 5;
        boolean wsTradesOk = tradeFlowSamples > 0 && aggTradeMessages > 0 && aggTradeAge >= 0 && aggTradeAge <= 120;
        boolean restTradesOk = tradeFlowSamples > 0 && restTradeRefreshes > 0 && restTradeAge >= 0 && restTradeAge <= 120;
        boolean metricFlowOk = metrics != null && metrics.optBoolean("flowDataOk", false);
        boolean tradesOk = wsTradesOk || restTradesOk || metricFlowOk;

        b.append(line(candlesOk, "Bougies suffisantes ETH " + ethCandles + "/30 · BTC " + btcCandles + "/10"));
        b.append(line(!"NO_DATA".equals(reason), "Moteur sorti de NO_DATA, raison actuelle " + reason));
        b.append(line(evalOk, "Dernière évaluation moteur récente : " + evalAge + "s"));
        b.append(line(tradesOk, "Trades/flow ETH reçus : samples=" + tradeFlowSamples + ", wsMessages=" + aggTradeMessages + ", restRefreshes=" + restTradeRefreshes + ", wsAge=" + aggTradeAge + "s, restAge=" + restTradeAge + "s"));
        b.append(line(metricsOk, "Métriques expertes incluses dans le ZIP"));
        if (metrics != null) {
            b.append(line(metrics.optBoolean("volumeDataOk", false), "Volume moyen disponible"));
            b.append(line(metrics.optBoolean("rangeOk", false), "Range suffisant pour scalp"));
            b.append("INFO setupCandidate: ").append(metrics.optString("setupCandidate", "—")).append("\n");
            b.append("INFO threshold/move1/move3: ")
                    .append(metrics.optString("threshold", "—")).append(" / ")
                    .append(metrics.optString("move1", "—")).append(" / ")
                    .append(metrics.optString("move3", "—")).append("\n");
        }
        b.append("\nConclusion automatique: ");
        if (connected && age >= 0 && age <= 8 && ethOk && btcOk && candlesOk && tradesOk) {
            b.append("OK — moteur alimenté. Si aucun signal, c'est un refus logique du moteur.\n");
        } else {
            b.append("À vérifier — une donnée moteur manque ou le flux est retardé.\n");
        }
        return b.toString();
    }

    private String line(boolean ok, String text) {
        return (ok ? "OK  " : "ERR ") + text + "\n";
    }


    private String buildEngineMetricsText(JSONObject s) {
        JSONObject m = s.optJSONObject("engineMetrics");
        if (m == null) return "Aucune métrique experte disponible.\n";

        StringBuilder b = new StringBuilder();
        b.append("ENGINE METRICS — ETH SCALPER v2.30.7\n\n");
        b.append("setupCandidate=").append(m.optString("setupCandidate", "—")).append("\n");
        b.append("decisionCode=").append(m.optString("decisionCode", "—")).append("\n");
        b.append("decisionText=").append(m.optString("decisionText", "—")).append("\n\n");

        b.append("MOUVEMENT\n");
        b.append("threshold=").append(m.optString("threshold", "—")).append("\n");
        b.append("move1=").append(m.optString("move1", "—")).append("\n");
        b.append("move3=").append(m.optString("move3", "—")).append("\n");
        b.append("move8=").append(m.optString("move8", "—")).append("\n");
        b.append("recentRange=").append(m.optString("recentRange", "—")).append("\n\n");

        b.append("VOLUME / FLOW / BTC\n");
        b.append("avgRange20=").append(m.optString("avgRange20", "—")).append("\n");
        b.append("avgVolume20=").append(m.optString("avgVolume20", "—")).append("\n");
        b.append("lastVolume=").append(m.optString("lastVolume", "—")).append("\n");
        b.append("volumeRatio=").append(m.optString("volumeRatio", "—")).append("\n");
        b.append("flowNorm=").append(m.optString("flowNorm", "—")).append("\n");
        b.append("btcMove5=").append(m.optString("btcMove5", "—")).append("\n");
        b.append("spread=").append(m.optString("spread", "—")).append("\n\n");

        b.append("FLUX REÇUS\n");
        b.append("bookTickerMessages=").append(m.optString("bookTickerMessages", "—")).append("\n");
        b.append("klineMessages=").append(m.optString("klineMessages", "—")).append("\n");
        b.append("aggTradeMessages=").append(m.optString("aggTradeMessages", "—")).append("\n");
        b.append("restKlineRefreshes=").append(m.optString("restKlineRefreshes", "—")).append("\n");
        b.append("restTradeRefreshes=").append(m.optString("restTradeRefreshes", "—")).append("\n");
        b.append("flowSamples=").append(m.optString("flowSamples", "—")).append("\n");
        b.append("lastAggTradeAgeSec=").append(m.optString("lastAggTradeAgeSec", "—")).append("\n");
        b.append("lastRestKlineAgeSec=").append(m.optString("lastRestKlineAgeSec", "—")).append("\n");
        b.append("lastRestTradeAgeSec=").append(m.optString("lastRestTradeAgeSec", "—")).append("\n");
        b.append("flowDataOk=").append(m.optBoolean("flowDataOk", false)).append("\n");
        b.append("flowSource=").append(m.optString("flowSource", "—")).append("\n");
        b.append("klineSource=").append(m.optString("klineSource", "—")).append("\n\n");

        b.append("FLAGS\n");
        b.append("c1Long=").append(m.optBoolean("c1Long", false)).append("\n");
        b.append("c1Short=").append(m.optBoolean("c1Short", false)).append("\n");
        b.append("c2Long=").append(m.optBoolean("c2Long", false)).append("\n");
        b.append("c2Short=").append(m.optBoolean("c2Short", false)).append("\n");
        b.append("btcLongVeto=").append(m.optBoolean("btcLongVeto", false)).append("\n");
        b.append("btcShortVeto=").append(m.optBoolean("btcShortVeto", false)).append("\n");
        b.append("flowLongOk=").append(m.optBoolean("flowLongOk", false)).append("\n");
        b.append("flowShortOk=").append(m.optBoolean("flowShortOk", false)).append("\n");

        return b.toString();
    }


    private String buildObservationSummaryText(JSONObject s) {
        JSONObject summary = s.optJSONObject("observationSummary");
        JSONArray observed = s.optJSONArray("observedSignals");
        StringBuilder b = new StringBuilder();
        b.append("PRO LABEL LAB — ETH SCALPER v2.30.7\n\n");
        if (summary != null) {
            b.append("totalSignalsObserved=").append(summary.optInt("totalSignalsObserved", 0)).append("\n");
            b.append("active=").append(summary.optInt("active", 0)).append("\n");
            b.append("tpTouched=").append(summary.optInt("tpTouched", 0)).append("\n");
            b.append("slTouched=").append(summary.optInt("slTouched", 0)).append("\n");
            b.append("invalidated=").append(summary.optInt("invalidated", 0)).append("\n\n");
        }
        if (observed == null || observed.length() == 0) {
            b.append("Aucun signal observé dans ce lancement.\n");
            return b.toString();
        }
        for (int i = 0; i < observed.length(); i++) {
            JSONObject o = observed.optJSONObject(i);
            if (o == null) continue;
            b.append("#").append(o.optLong("id", i + 1))
                    .append(" ").append(o.optString("side", "—"))
                    .append(" ").append(o.optString("family", "—"))
                    .append(" status=").append(o.optString("status", "—"))
                    .append(" score=").append(o.optInt("score", 0))
                    .append(" entry=").append(o.optString("entry", "—"))
                    .append(" tp=").append(o.optString("tp", "—"))
                    .append(" sl=").append(o.optString("sl", "—"))
                    .append(" mfe=").append(o.optString("mfe", "—"))
                    .append(" mae=").append(o.optString("mae", "—"))
                    .append("\n");
        }
        return b.toString();
    }

    private String buildMarketSummaryText(JSONObject s) {
        StringBuilder b = new StringBuilder("PRO LABEL LAB — MARKET RECORDER v2.30.7\n\n");
        b.append("mode=").append(s.optString("mode", "—")).append("\n");
        b.append("frames=").append(s.optInt("frames", 0)).append("\n");
        b.append("durationSec=").append(s.optInt("durationSec", 0)).append("\n");
        b.append("signals=").append(s.optInt("signals", 0)).append("\n");
        b.append("c1LongCandidates=").append(s.optInt("c1LongCandidates", 0)).append("\n");
        b.append("c1ShortCandidates=").append(s.optInt("c1ShortCandidates", 0)).append("\n");
        b.append("c2LongCandidates=").append(s.optInt("c2LongCandidates", 0)).append("\n");
        b.append("c2ShortCandidates=").append(s.optInt("c2ShortCandidates", 0)).append("\n\n");
        b.append("But : analyser les endroits où il fallait entrer LONG/SHORT, les entrées ratées et les faux signaux.\n");
        return b.toString();
    }

    private String buildMarketFramesCsv(JSONArray arr) {
        StringBuilder b = new StringBuilder("at,eth,bid,ask,spread,btc,avgRange20,avgVolume20,lastVolume,volumeRatio,flowNorm,btcMove5,move1,move3,move8,recentHigh,recentLow,recentRange,move1Norm,move3Norm,move8Norm,moveAccel13,moveAccel38,rangePosition,distanceToHigh,distanceToLow,roomLong,roomShort,pullbackFromHigh,pullbackFromLow,flow15,flow30,flow60,flow120,deltaFlow15_60,deltaFlow30_120,flowAccel,btcMove1,btcMove3,btcMove8,btcAccel1_5,btcAccel3_8,breakoutHighDistance,breakoutLowDistance,antiBurstScore,longMfe5,shortMfe5,longMfe10,shortMfe10,longMfe15,shortMfe15,bestSide5,bestSide10,bestSide15,longHit2Sec,longHit22Sec,longHit28Sec,longHit35Sec,shortHit2Sec,shortHit22Sec,shortHit28Sec,shortHit35Sec,longAdverseBefore2,longAdverseBefore22,longAdverseBefore28,longAdverseBefore35,shortAdverseBefore2,shortAdverseBefore22,shortAdverseBefore28,shortAdverseBefore35,oracleLongClean28,oracleShortClean28,learnedCandidateSide,learnedCandidateType,learnedCandidateScore,learnedOppositeMove8,learnedDirectionalMove3,learnedBtcDir,learnedRecentRangeRatio,hypothesisPrimarySide,hypothesisPrimaryType,hypothesisPrimaryScore,hypEngineInverseSide,hypEngineInverseScore,hypC1InverseSide,hypC1InverseScore,hypC2InverseSide,hypC2InverseScore,hypRangeFadeSide,hypRangeFadeScore,hypMove1ReversalSide,hypMove1ReversalScore,hypContinuationSide,hypContinuationScore,setupCandidate,decision,decisionCode,isSignal,side,family,score,qty,entry,tp,sl,targetMove,stopDistance\n");
        if (arr == null) return b.toString();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            b.append(o.optLong("at", 0)).append(',')
                    .append(o.optString("eth", "")).append(',')
                    .append(o.optString("bid", "")).append(',')
                    .append(o.optString("ask", "")).append(',')
                    .append(o.optString("spread", "")).append(',')
                    .append(o.optString("btc", "")).append(',')
                    .append(o.optString("avgRange20", "")).append(',')
                    .append(o.optString("avgVolume20", "")).append(',')
                    .append(o.optString("lastVolume", "")).append(',')
                    .append(o.optString("volumeRatio", "")).append(',')
                    .append(o.optString("flowNorm", "")).append(',')
                    .append(o.optString("btcMove5", "")).append(',')
                    .append(o.optString("move1", "")).append(',')
                    .append(o.optString("move3", "")).append(',')
                    .append(o.optString("move8", "")).append(',')
                    .append(o.optString("recentHigh", "")).append(',')
                    .append(o.optString("recentLow", "")).append(',')
                    .append(o.optString("recentRange", "")).append(',')
                    .append(o.optString("move1Norm", "")).append(',')
                    .append(o.optString("move3Norm", "")).append(',')
                    .append(o.optString("move8Norm", "")).append(',')
                    .append(o.optString("moveAccel13", "")).append(',')
                    .append(o.optString("moveAccel38", "")).append(',')
                    .append(o.optString("rangePosition", "")).append(',')
                    .append(o.optString("distanceToHigh", "")).append(',')
                    .append(o.optString("distanceToLow", "")).append(',')
                    .append(o.optString("roomLong", "")).append(',')
                    .append(o.optString("roomShort", "")).append(',')
                    .append(o.optString("pullbackFromHigh", "")).append(',')
                    .append(o.optString("pullbackFromLow", "")).append(',')
                    .append(o.optString("flow15", "")).append(',')
                    .append(o.optString("flow30", "")).append(',')
                    .append(o.optString("flow60", "")).append(',')
                    .append(o.optString("flow120", "")).append(',')
                    .append(o.optString("deltaFlow15_60", "")).append(',')
                    .append(o.optString("deltaFlow30_120", "")).append(',')
                    .append(o.optString("flowAccel", "")).append(',')
                    .append(o.optString("btcMove1", "")).append(',')
                    .append(o.optString("btcMove3", "")).append(',')
                    .append(o.optString("btcMove8", "")).append(',')
                    .append(o.optString("btcAccel1_5", "")).append(',')
                    .append(o.optString("btcAccel3_8", "")).append(',')
                    .append(o.optString("breakoutHighDistance", "")).append(',')
                    .append(o.optString("breakoutLowDistance", "")).append(',')
                    .append(o.optString("antiBurstScore", "")).append(',')
                    .append(o.optString("longMfe5", "")).append(',')
                    .append(o.optString("shortMfe5", "")).append(',')
                    .append(o.optString("longMfe10", "")).append(',')
                    .append(o.optString("shortMfe10", "")).append(',')
                    .append(o.optString("longMfe15", "")).append(',')
                    .append(o.optString("shortMfe15", "")).append(',')
                    .append(csv(o.optString("bestSide5", ""))).append(',')
                    .append(csv(o.optString("bestSide10", ""))).append(',')
                    .append(csv(o.optString("bestSide15", ""))).append(',')
                    .append(o.optLong("longHit2Sec", -1)).append(',')
                    .append(o.optLong("longHit22Sec", -1)).append(',')
                    .append(o.optLong("longHit28Sec", -1)).append(',')
                    .append(o.optLong("longHit35Sec", -1)).append(',')
                    .append(o.optLong("shortHit2Sec", -1)).append(',')
                    .append(o.optLong("shortHit22Sec", -1)).append(',')
                    .append(o.optLong("shortHit28Sec", -1)).append(',')
                    .append(o.optLong("shortHit35Sec", -1)).append(',')
                    .append(o.optString("longAdverseBefore2", "")).append(',')
                    .append(o.optString("longAdverseBefore22", "")).append(',')
                    .append(o.optString("longAdverseBefore28", "")).append(',')
                    .append(o.optString("longAdverseBefore35", "")).append(',')
                    .append(o.optString("shortAdverseBefore2", "")).append(',')
                    .append(o.optString("shortAdverseBefore22", "")).append(',')
                    .append(o.optString("shortAdverseBefore28", "")).append(',')
                    .append(o.optString("shortAdverseBefore35", "")).append(',')
                    .append(o.optBoolean("oracleLongClean28", false)).append(',')
                    .append(o.optBoolean("oracleShortClean28", false)).append(',')
                    .append(csv(o.optString("learnedCandidateSide", ""))).append(',')
                    .append(csv(o.optString("learnedCandidateType", ""))).append(',')
                    .append(o.optInt("learnedCandidateScore", 0)).append(',')
                    .append(o.optString("learnedOppositeMove8", "")).append(',')
                    .append(o.optString("learnedDirectionalMove3", "")).append(',')
                    .append(o.optString("learnedBtcDir", "")).append(',')
                    .append(o.optString("learnedRecentRangeRatio", "")).append(',')
                    .append(csv(o.optString("hypothesisPrimarySide", ""))).append(',')
                    .append(csv(o.optString("hypothesisPrimaryType", ""))).append(',')
                    .append(o.optInt("hypothesisPrimaryScore", 0)).append(',')
                    .append(csv(o.optString("hypEngineInverseSide", ""))).append(',')
                    .append(o.optInt("hypEngineInverseScore", 0)).append(',')
                    .append(csv(o.optString("hypC1InverseSide", ""))).append(',')
                    .append(o.optInt("hypC1InverseScore", 0)).append(',')
                    .append(csv(o.optString("hypC2InverseSide", ""))).append(',')
                    .append(o.optInt("hypC2InverseScore", 0)).append(',')
                    .append(csv(o.optString("hypRangeFadeSide", ""))).append(',')
                    .append(o.optInt("hypRangeFadeScore", 0)).append(',')
                    .append(csv(o.optString("hypMove1ReversalSide", ""))).append(',')
                    .append(o.optInt("hypMove1ReversalScore", 0)).append(',')
                    .append(csv(o.optString("hypContinuationSide", ""))).append(',')
                    .append(o.optInt("hypContinuationScore", 0)).append(',')
                    .append(csv(o.optString("setupCandidate", ""))).append(',')
                    .append(csv(o.optString("decision", ""))).append(',')
                    .append(csv(o.optString("decisionCode", ""))).append(',')
                    .append(o.optBoolean("isSignal", false)).append(',')
                    .append(csv(o.optString("side", ""))).append(',')
                    .append(csv(o.optString("family", ""))).append(',')
                    .append(o.optInt("score", 0)).append(',')
                    .append(o.optInt("qty", 0)).append(',')
                    .append(o.optString("entry", "")).append(',')
                    .append(o.optString("tp", "")).append(',')
                    .append(o.optString("sl", "")).append(',')
                    .append(o.optString("targetMove", "")).append(',')
                    .append(o.optString("stopDistance", "")).append('\n');
        }
        return b.toString();
    }

    private String buildObservationJournalCsv(JSONArray arr) {
        StringBuilder b = new StringBuilder("id,side,family,status,score,qty,entry,tp,sl,lastPrice,maxPrice,minPrice,mfe,mae,unrealizedMove,ageSec,updates\n");
        if (arr == null) return b.toString();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            b.append(o.optLong("id", 0)).append(',')
                    .append(csv(o.optString("side", ""))).append(',')
                    .append(csv(o.optString("family", ""))).append(',')
                    .append(csv(o.optString("status", ""))).append(',')
                    .append(o.optInt("score", 0)).append(',')
                    .append(o.optInt("qty", 0)).append(',')
                    .append(o.optString("entry", "")).append(',')
                    .append(o.optString("tp", "")).append(',')
                    .append(o.optString("sl", "")).append(',')
                    .append(o.optString("lastPrice", "")).append(',')
                    .append(o.optString("maxPrice", "")).append(',')
                    .append(o.optString("minPrice", "")).append(',')
                    .append(o.optString("mfe", "")).append(',')
                    .append(o.optString("mae", "")).append(',')
                    .append(o.optString("unrealizedMove", "")).append(',')
                    .append(o.optLong("ageSec", 0)).append(',')
                    .append(o.optInt("updates", 0)).append('\n');
        }
        return b.toString();
    }

    private String buildDiagnosticsCsv(JSONArray arr) {
        StringBuilder b = new StringBuilder("time,code,message\n");
        if (arr == null) return b.toString();
        SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            b.append(csv(clock.format(new Date(item.optLong("at", 0))))).append(',')
                    .append(csv(item.optString("code", ""))).append(',')
                    .append(csv(item.optString("message", ""))).append('\n');
        }
        return b.toString();
    }

    private String csv(String value) {
        if (value == null) value = "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void addZipText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        zip.write(bytes, 0, bytes.length);
        zip.closeEntry();
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
