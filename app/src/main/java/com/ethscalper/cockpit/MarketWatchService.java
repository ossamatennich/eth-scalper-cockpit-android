package com.ethscalper.cockpit;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MarketWatchService extends Service {
    public static final String ACTION_START = "com.ethscalper.cockpit.START";
    public static final String ACTION_STOP = "com.ethscalper.cockpit.STOP";
    public static final String ACTION_SYNC_NOW = "com.ethscalper.cockpit.SYNC_NOW";
    public static final String ACTION_TEST_ALERT = "com.ethscalper.cockpit.TEST_ALERT";
    public static final String ACTION_TEST_VIBRATION = "com.ethscalper.cockpit.TEST_VIBRATION";
    public static final String ACTION_RESET_DIAGNOSTICS = "com.ethscalper.cockpit.RESET_DIAGNOSTICS";
    public static final String BROADCAST_STATUS = "com.ethscalper.cockpit.STATUS";
    public static final String EXTRA_PAYLOAD = "payload";
    public static final long SIGNAL_DISPLAY_TTL_MS = 120_000L;

    private static final String CH_WATCH = "eth_scalper_watch_v22801";
    private static final String CH_SIGNAL = "eth_scalper_signal_final_v2330";
    private static final String CH_SIGNAL_SILENT = "eth_scalper_signal_updates_v2330";
    private static final String STATE_PREFERENCES = "market_watch_state";
    private static final String STATE_JSON = "last_status_json";
    private static final int NOTIF_WATCH_ID = 22801;
    private static final long[] ALERT_VIBRATION = {0, 750, 180, 750, 180, 1200};
    private static final long OBSERVATION_MAX_AGE_MS = 15 * 60 * 1000L;
    private static final long LIMIT_ORDER_MAX_AGE_MS = 45 * 60 * 1000L;
    // Kept in diagnostics for historical comparison; v2.33.0 enforces this window for P01.
    private static final long LEGACY_LIMIT_MANUAL_ENTRY_DELAY_MS = 15 * 1000L;
    private static final long ETH_BOOK_MAX_AGE_MS = 8_000L;
    private static final String PERSISTENT_DIR = "eth_scalper_overnight_recorder";
    private static final String PERSISTENT_OBSERVATIONS_FILE = "persistent_observation_journal.jsonl";
    private static final String PERSISTENT_MARKET_FRAMES_FILE = "persistent_market_frames.jsonl";
    private static final long PERSISTENT_MARKET_FRAME_INTERVAL_MS = 5 * 1000L;
    private static final String ALERT_DEDUPE_PREFERENCES = "confirmed_signal_alerts_v2330";
    private static final String BINANCE_STREAM = buildBinanceStream();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MarketRegistry marketRegistry = MarketRegistry.production();
    private final MultiMarketCoordinator marketCoordinator =
            new MultiMarketCoordinator(marketRegistry);
    private final SharedReferenceContext sharedBtc = new SharedReferenceContext();
    private final MarketPlanOrchestrator marketOrchestrator = new MarketPlanOrchestrator();
    private MarketDataRouter marketDataRouter;
    private final Deque<Candle> ethCandles = new ArrayDeque<>();
    private final Deque<Candle> btcCandles = new ArrayDeque<>();
    private final Deque<TradeFlow> flows = new ArrayDeque<>();
    private final SignalEngine signalEngine = new SignalEngine();
    private AiAdvisor aiAdvisor;
    private String aiStatus = "AI_OFF";
    private String lastAiDecisionJson = "{}";
    private final Deque<ObservedSignal> observedSignals = new ArrayDeque<>();
    private final PendingCandidateIndex<ObservedSignal> pendingCandidateIndex =
            new PendingCandidateIndex<>();
    private final CandidateTombstones candidateTombstones = new CandidateTombstones();
    private ActivePlanPersistence activePlanPersistence;
    private TerminalRearmPersistence terminalRearmPersistence;
    private final P02SleeveFilter.SetupTracker p02SetupTracker =
            new P02SleeveFilter.SetupTracker();
    private final Deque<MarketFrame> marketFrames = new ArrayDeque<>();
    private long observedSignalId;
    private long lastMarketFrameAt;
    private long lastMarketFrameJsonRefreshAt;
    private long lastPersistentMarketFrameAt;

    private OkHttpClient client;
    private WebSocket socket;
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private boolean healthScheduled;
    private long lastMessageAt;
    private long lastSignalAt;
    private long lastP01ConfirmedAt;
    private long lastTerminalAt;
    private long lastActivePlanPersistAt;
    private long lastStatusAt;
    private long lastEvaluationAt;
    private long lastWatchNotificationAt;
    private long bookTickerMessages;
    private long klineMessages;
    private long aggTradeMessages;
    private long lastBookTickerAt;
    private long lastEthBookTickerAt;
    private long lastKlineAt;
    private long lastAggTradeAt;
    private long restKlineRefreshes;
    private long restTradeRefreshes;
    private long lastRestKlineRefreshAt;
    private long lastRestTradeRefreshAt;
    private long lastRestKlineOkAt;
    private long lastRestTradeOkAt;
    private long lastRestAggTradeId = -1;
    private int reconnectAttempt;
    private boolean historyPrefillRequested;
    private int signalNotificationId = 3000;

    private double ethBid, ethAsk, ethLast;
    private double btcBid, btcAsk, btcLast;
    private SignalDecision lastDecision;
    private SignalDecision lastSignal;

    private static String buildBinanceStream() {
        StringBuilder value=new StringBuilder("wss://fstream.binance.com/stream?streams=");
        for(MarketProfile profile:MarketRegistry.production().tradedMarkets()) {
            if(value.charAt(value.length()-1)!='=')value.append('/');
            String symbol=profile.symbol.toLowerCase(Locale.ROOT);
            value.append(symbol).append("@kline_1m/").append(symbol)
                    .append("@aggTrade/").append(symbol).append("@bookTicker");
        }
        value.append("/btcusdt@kline_1m/btcusdt@bookTicker");
        return value.toString();
    }
    public static volatile String LAST_STATUS_JSON = "";
    public static volatile String LAST_MARKET_FRAMES_JSON = "[]";
    public static volatile String LAST_MARKET_SUMMARY_JSON = "{}";
    public static volatile String LAST_PERSISTENT_OBSERVATIONS_JSON = "[]";
    public static volatile String LAST_PERSISTENT_MARKET_FRAMES_JSON = "[]";
    public static volatile String LAST_OVERNIGHT_RECORDER_SUMMARY_JSON = "{}";

    public static String getLastStatusJson(Context context) {
        String memory = LAST_STATUS_JSON == null ? "" : LAST_STATUS_JSON;
        if (!memory.isEmpty()) return memory;
        return context.getSharedPreferences(STATE_PREFERENCES, MODE_PRIVATE).getString(STATE_JSON, "");
    }

    public static String getLastStatusJson() {
        return LAST_STATUS_JSON == null ? "" : LAST_STATUS_JSON;
    }

    public static String getLastMarketFramesJson() {
        return LAST_MARKET_FRAMES_JSON == null ? "[]" : LAST_MARKET_FRAMES_JSON;
    }

    public static String getLastMarketSummaryJson() {
        return LAST_MARKET_SUMMARY_JSON == null ? "{}" : LAST_MARKET_SUMMARY_JSON;
    }

    public static String getPersistentObservationJournalJson(Context context) {
        try {
            String json = jsonlFileToJsonArrayString(persistentFile(context, PERSISTENT_OBSERVATIONS_FILE));
            LAST_PERSISTENT_OBSERVATIONS_JSON = json;
            return json;
        } catch (Exception ignored) {
            return LAST_PERSISTENT_OBSERVATIONS_JSON == null ? "[]" : LAST_PERSISTENT_OBSERVATIONS_JSON;
        }
    }

    public static String getPersistentObservationJournalJsonl(Context context) {
        return readTextFile(persistentFile(context, PERSISTENT_OBSERVATIONS_FILE));
    }

    public static String getPersistentMarketFramesJson(Context context) {
        try {
            String json = jsonlFileToJsonArrayString(persistentFile(context, PERSISTENT_MARKET_FRAMES_FILE));
            LAST_PERSISTENT_MARKET_FRAMES_JSON = json;
            return json;
        } catch (Exception ignored) {
            return LAST_PERSISTENT_MARKET_FRAMES_JSON == null ? "[]" : LAST_PERSISTENT_MARKET_FRAMES_JSON;
        }
    }

    public static String getPersistentMarketFramesJsonl(Context context) {
        return readTextFile(persistentFile(context, PERSISTENT_MARKET_FRAMES_FILE));
    }

    public static String getOvernightRecorderSummaryJson(Context context) {
        try {
            String json = persistentRecorderSummaryJson(context).toString(2);
            LAST_OVERNIGHT_RECORDER_SUMMARY_JSON = json;
            return json;
        } catch (Exception ignored) {
            return LAST_OVERNIGHT_RECORDER_SUMMARY_JSON == null ? "{}" : LAST_OVERNIGHT_RECORDER_SUMMARY_JSON;
        }
    }

    private static File persistentDir(Context context) {
        File dir = new File(context.getFilesDir(), PERSISTENT_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File persistentFile(Context context, String name) {
        return new File(persistentDir(context), name);
    }

    private static String readTextFile(File file) {
        if (file == null || !file.exists()) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Exception ignored) {}
        return out.toString();
    }

    private static String jsonlFileToJsonArrayString(File file) throws Exception {
        JSONArray arr = new JSONArray();
        if (file == null || !file.exists()) return arr.toString();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                try { arr.put(new JSONObject(line)); } catch (Exception ignored) {}
            }
        }

        return arr.toString();
    }

    private static JSONObject persistentRecorderSummaryJson(Context context) throws Exception {
        JSONObject o = new JSONObject();
        File obs = persistentFile(context, PERSISTENT_OBSERVATIONS_FILE);
        File frames = persistentFile(context, PERSISTENT_MARKET_FRAMES_FILE);

        JSONObject obsStats = jsonlStats(obs);
        JSONObject frameStats = jsonlStats(frames);

        long oldest = 0;
        long newest = 0;

        long obsOldest = obsStats.optLong("oldestAt", 0);
        long obsNewest = obsStats.optLong("newestAt", 0);
        long frameOldest = frameStats.optLong("oldestAt", 0);
        long frameNewest = frameStats.optLong("newestAt", 0);

        if (obsOldest > 0) oldest = obsOldest;
        if (frameOldest > 0 && (oldest == 0 || frameOldest < oldest)) oldest = frameOldest;

        if (obsNewest > 0) newest = obsNewest;
        if (frameNewest > newest) newest = frameNewest;

        o.put("mode", "PERSISTENT_OVERNIGHT_RECORDER");
        o.put("version", "2.34.0");
        o.put("description", "Journal persistant: conserve les signaux et les frames même si l'écran/app est fermé, jusqu'à réinitialisation diagnostic.");
        o.put("observationEvents", obsStats.optInt("count", 0));
        o.put("marketFrames", frameStats.optInt("count", 0));
        o.put("observationFileBytes", obs.exists() ? obs.length() : 0);
        o.put("marketFileBytes", frames.exists() ? frames.length() : 0);
        o.put("oldestAt", oldest);
        o.put("newestAt", newest);
        o.put("durationSec", oldest > 0 && newest > oldest ? (newest - oldest) / 1000 : 0);
        o.put("resetByButton", "ACTION_RESET_DIAGNOSTICS");
        o.put("marketSampleIntervalSec", PERSISTENT_MARKET_FRAME_INTERVAL_MS / 1000);
        return o;
    }

    private static JSONObject jsonlStats(File file) throws Exception {
        JSONObject o = new JSONObject();
        int count = 0;
        long oldest = 0;
        long newest = 0;

        if (file != null && file.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || !line.startsWith("{")) continue;
                    try {
                        JSONObject item = new JSONObject(line);
                        long at = item.optLong("at", item.optLong("eventAt", item.optLong("createdAt", 0)));
                        if (at > 0) {
                            if (oldest == 0 || at < oldest) oldest = at;
                            if (at > newest) newest = at;
                        }
                        count++;
                    } catch (Exception ignored) {}
                }
            }
        }

        o.put("count", count);
        o.put("oldestAt", oldest);
        o.put("newestAt", newest);
        o.put("durationSec", oldest > 0 && newest > oldest ? (newest - oldest) / 1000 : 0);
        return o;
    }

    @Override public void onCreate() {
        super.onCreate();
        p02SetupTracker.reset();
        LinkedHashMap<String, MarketDataRouter.LegacyMirror> legacyMirrors =
                new LinkedHashMap<>();
        legacyMirrors.put(MarketProfile.ETH_SYMBOL, new MarketDataRouter.LegacyMirror() {
            @Override public void onBookTicker(double last, double bid, double ask, long now) {
                ethLast=last;ethBid=bid;ethAsk=ask;lastEthBookTickerAt=now;
            }
            @Override public void onKline(MarketRuntime.MarketBar bar, long receivedAt) {
                ethLast=bar.close;upsert(ethCandles,toLegacyCandle(bar),180);
            }
            @Override public void onAggTrade(MarketRuntime.AggTrade trade,long receivedAt) {
                if(trade.id>lastRestAggTradeId)lastRestAggTradeId=trade.id;
                flows.addLast(new TradeFlow(trade.at,trade.buyerMaker
                        ?-trade.quantity:trade.quantity));
                lastAggTradeAt=trade.at;pruneFlows(receivedAt);
            }
            @Override public void onAggTradeBatch(int accepted,long receivedAt) {
                if(accepted>0){restTradeRefreshes++;lastRestTradeOkAt=receivedAt;}
            }
            @Override public void onCandleBatch(List<MarketRuntime.MarketBar> bars,
                                                boolean replace,long receivedAt) {
                if(replace)ethCandles.clear();
                for(MarketRuntime.MarketBar bar:bars)upsert(ethCandles,toLegacyCandle(bar),180);
                if(!bars.isEmpty()){
                    ethLast=bars.get(bars.size()-1).close;restKlineRefreshes++;
                    lastRestKlineOkAt=receivedAt;
                }
            }
        });
        marketDataRouter=new MarketDataRouter(marketRegistry,marketCoordinator,legacyMirrors);
        ensureChannels(this);
        activePlanPersistence = new ActivePlanPersistence(
                new SharedPreferencesActivePlanBackend(this));
        terminalRearmPersistence = new TerminalRearmPersistence(
                new SharedPreferencesTerminalRearmBackend(this));
        lastTerminalAt = terminalRearmPersistence.restore();
        for(MarketRuntime runtime:marketCoordinator.runtimes().values()) {
            runtime.p02SetupTracker.reset();
            runtime.lastTerminalAt=terminalRearmPersistence.restore(runtime.profile.symbol);
        }
        restoreActiveFinalPlan();
        restoreSecondaryFinalPlans();
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        aiAdvisor = new AiAdvisor(this);
        acquireWakeLock();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            running = false;
            stopSocket();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        startForeground(NOTIF_WATCH_ID, buildWatchNotification("Initialisation du moteur natif…"));
        if (ACTION_TEST_ALERT.equals(action)) {
            notifyTestAlert();
            broadcastStatus("test_alert", "Alerte forte de test envoyée");
        } else if (ACTION_TEST_VIBRATION.equals(action)) {
            vibrateAlert();
            broadcastStatus("test_vibration", "Vibration longue testée");
        } else if (ACTION_RESET_DIAGNOSTICS.equals(action)) {
            ObservedSignal activePlan = activeFinalSignal();
            for(MarketRuntime runtime:marketCoordinator.runtimes().values()) {
                runtime.resetDiagnosticsPreservingActivePlan();
            }
            p02SetupTracker.reset();
            signalEngine.clearDiagnostics();
            observedSignals.clear();
            pendingCandidateIndex.clear();
            candidateTombstones.clear();
            marketFrames.clear();
            lastPersistentMarketFrameAt = 0;
            resetPersistentRecorder();
            for(MarketRuntime runtime:marketCoordinator.runtimes().values())
                if(runtime.hasActivePlan())persistRuntimePlanEvent(runtime.activePlan,
                        "V23401_DIAGNOSTICS_RESET_ACTIVE_PLAN_REINSERTED",
                        System.currentTimeMillis());
            if (activePlan != null) {
                observedSignals.addLast(activePlan);
                observedSignalId = Math.max(observedSignalId, activePlan.id);
                lastSignal = activePlan.signal;
                lastSignalAt = activePlan.finalConfirmedAt;
                lastDecision = activePlan.signal;
                activePlanPersistence.resetDiagnostics(true);
                persistObservedSignalEvent(activePlan,
                        "V23281_DIAGNOSTICS_RESET_ACTIVE_PLAN_PRESERVED",
                        restoredSnapshot(activePlan.lastPrice, activePlan.signalBid,
                                activePlan.signalAsk, activePlan.avgRange20,
                                System.currentTimeMillis()),
                        System.currentTimeMillis());
                broadcastStatus("diagnostics_reset_active_plan_preserved",
                        "Diagnostic réinitialisé — plan actif conservé jusqu’au TP ou au SL.");
            } else {
                lastDecision = null;
                lastSignal = null;
                lastSignalAt = 0;
                lastP01ConfirmedAt = 0;
                lastActivePlanPersistAt = 0;
                observedSignalId = 0;
                boolean secondaryActive=marketCoordinator.activePlanCount()>0;
                activePlanPersistence.resetDiagnostics(secondaryActive);
                broadcastStatus(secondaryActive?"diagnostics_reset_active_plan_preserved":"diagnostics_reset",
                        secondaryActive?"Diagnostic réinitialisé — plan actif conservé jusqu’au TP ou au SL."
                                :"Diagnostic moteur + recorder persistant réinitialisés");
            }
        } else if (ACTION_SYNC_NOW.equals(action)) {
            broadcastStatus("sync", "État du service natif resynchronisé");
        }
        connectIfNeeded();
        prefillHistoricalCandlesIfNeeded();
        scheduleHealthCheck();
        return START_STICKY;
    }

    private void restoreActiveFinalPlan() {
        ActivePlanPersistence.RestoreResult restored = activePlanPersistence.restore(MarketProfile.ETH_SYMBOL);
        long now = System.currentTimeMillis();
        if (restored.invalid) {
            signalEngine.recordExternalDiagnostic(now, ActivePlanPersistence.RESTORE_INVALID,
                    "État persistant du plan actif incomplet ou invalide : restauration ignorée.");
            activePlanPersistence.clear(MarketProfile.ETH_SYMBOL);
            return;
        }
        ActivePlanState state = restored.state;
        if (state == null) return;

        SignalDecision signal = state.toSignalDecision();
        if (signal == null) {
            signalEngine.recordExternalDiagnostic(now, ActivePlanPersistence.RESTORE_INVALID,
                    "Plan actif persistant impossible à reconstruire : restauration ignorée.");
            activePlanPersistence.clear(MarketProfile.ETH_SYMBOL);
            return;
        }

        MarketSnapshot snapshot = restoredSnapshot(state.lastPrice, state.lastBid, state.lastAsk,
                state.avgRange20, now);
        ObservedSignal item = new ObservedSignal(++observedSignalId, state.createdAt,
                signal, state.lastPrice, snapshot);
        item.status = "ACTIVE";
        item.entryTriggered = true;
        item.entryTriggeredAt = state.entryTriggeredAt;
        item.entryTriggerPrice = signal.entry;
        item.simulatedFillAt = state.entryTriggeredAt;
        item.simulatedFillPrice = signal.entry;
        item.finalConfirmedAt = state.finalConfirmedAt;
        item.premium15m = state.premium15m;
        item.notificationSignature = state.notificationSignature;
        item.notificationId = SignalSafetyPolicies.restoredNotificationId(
                state.notificationId, state.notificationSignature);
        item.alertSent = true;
        item.lastPrice = state.lastPrice;
        item.maxPrice = state.lastPrice;
        item.minPrice = state.lastPrice;
        item.lastUpdateAt = now;
        item.candidateSignature = SignalSafetyPolicies.candidateSignature(signal);
        item.replayRiskReasonCode = state.replayRiskReasonCode;
        item.replayRiskDetail = state.replayRiskDetail;
        item.p01Move1Aligned = state.p01Move1Aligned;
        item.p01Move3Aligned = state.p01Move3Aligned;
        item.p01Move8Aligned = state.p01Move8Aligned;
        item.p01Move15Aligned = state.p01Move15Aligned;
        item.p01Flow30Aligned = state.p01Flow30Aligned;
        item.restoredSizingDiagnostic = state.sizingDiagnostic;
        item.lastConfirmationCode = ActivePlanPersistence.RESTORED;
        observedSignals.addLast(item);

        lastSignal = signal;
        lastSignalAt = state.finalConfirmedAt;
        lastP01ConfirmedAt = state.lastP01ConfirmedAt;
        lastDecision = signal;
        lastActivePlanPersistAt = now;
        signalEngine.recordExternalDiagnostic(now, ActivePlanPersistence.RESTORED,
                "Plan final actif restauré ; surveillance TP/SL et verrou de publication maintenus.");
        persistObservedSignalEvent(item, ActivePlanPersistence.RESTORED, snapshot, now);
        notifyRestoredActivePlan(item);
    }

    private void restoreSecondaryFinalPlans() {
        for(MarketProfile profile:marketRegistry.tradedMarkets()) {
            if(MarketProfile.ETH_SYMBOL.equals(profile.symbol))continue;
            ActivePlanPersistence.RestoreResult restored=activePlanPersistence.restore(profile.symbol);
            MarketRuntime runtime=marketCoordinator.runtime(profile.symbol);
            if(restored.invalid) {
                runtime.signalEngine.recordExternalDiagnostic(System.currentTimeMillis(),
                        ActivePlanPersistence.RESTORE_INVALID,
                        "État persistant invalide isolé pour "+profile.symbol);
                continue;
            }
            if(restored.state==null)continue;
            runtime.activePlan=restored.state;
            runtime.lastSignal=restored.state.toSignalDecision();
            runtime.lastP01ConfirmedAt=restored.state.lastP01ConfirmedAt;
            notifyRestoredMarketPlan(runtime);
        }
    }

    private boolean persistActiveFinalPlan(ObservedSignal item, long confirmedP01At,
                                           MarketSnapshot snapshot) {
        if (item == null || item.signal == null || snapshot == null) return false;
        try {
            Object sizing = confirmedSizingJson(item);
            ActivePlanState state = ActivePlanState.builder()
                    .status("ACTIVE")
                    .side(item.signal.side).family(item.signal.family)
                    .reasonCode(item.signal.reasonCode).reasonText(item.signal.reasonText)
                    .score(item.signal.score).quantity(item.signal.quantity)
                    .prices(item.signal.entry, item.signal.takeProfit, item.signal.stopLoss)
                    .risk(item.signal.targetMove, item.signal.stopDistance)
                    .times(item.createdAt, item.entryTriggeredAt, item.finalConfirmedAt)
                    .premium15m(item.premium15m)
                    .notification(item.notificationSignature, item.notificationId)
                    .lastMarket(snapshot.ethLast, snapshot.ethBid, snapshot.ethAsk,
                            Math.max(0.35, snapshot.avgRange20))
                    .lastP01ConfirmedAt(confirmedP01At)
                    .movement(item.signal.impulse, item.signal.resetConfirmed,
                            item.signal.movementOrigin, item.signal.movementExtreme,
                            item.signal.movementDistance)
                    .replayRisk(item.replayRiskReasonCode, item.replayRiskDetail)
                    .p01(item.p01Move1Aligned, item.p01Move3Aligned, item.p01Move8Aligned,
                            item.p01Move15Aligned, item.p01Flow30Aligned)
                    .sizingDiagnostic(sizing == null ? "" : sizing.toString())
                    .build();
            return activePlanPersistence.saveForMarket(state);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static MarketSnapshot restoredSnapshot(double price, double bid, double ask,
                                                    double avgRange20, long now) {
        double avg = Math.max(0.35, avgRange20);
        return MarketSnapshot.builder(now)
                .lastSignalAt(now)
                .eth(price, bid, ask)
                .averages(avg, 1.0)
                .movement(0.0, 0.0, 0.0, price + avg, price - avg)
                .move15(0.0)
                .flow(0.0, 1.0)
                .flowWindows(0.0, 0.0, 0.0, 0.0)
                .btcMoves(0.0, 0.0, 0.0, 0.0)
                .build();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (running) {
            try {
                Intent restart = new Intent(getApplicationContext(), MarketWatchService.class);
                restart.setAction(ACTION_START);
                PendingIntent pending = PendingIntent.getService(getApplicationContext(), 2220, restart,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (alarm != null) alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 5000, pending);
            } catch (Exception ignored) {}
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopSocket();
        releaseWakeLock();
        if (client != null) client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public static void ensureChannels(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel watch = new NotificationChannel(CH_WATCH, "Moteur ETH permanent",
                NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("Maintient la surveillance ETH/BTC native en arrière-plan.");
        watch.setShowBadge(false);
        manager.createNotificationChannel(watch);

        NotificationChannel signals = new NotificationChannel(CH_SIGNAL, "Signaux ETH + SOL confirmés — v2.34.0",
                NotificationManager.IMPORTANCE_HIGH);
        signals.setDescription("Un son signifie exclusivement qu'un signal final manuel est confirmé.");
        signals.enableVibration(true);
        signals.setVibrationPattern(ALERT_VIBRATION);
        signals.enableLights(true);
        signals.setLightColor(0xffff315f);
        signals.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        Uri sound = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.eth_alert_loud);
        AudioAttributes audio = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        signals.setSound(sound, audio);
        manager.createNotificationChannel(signals);

        NotificationChannel silentUpdates = new NotificationChannel(
                CH_SIGNAL_SILENT, "Mises à jour silencieuses des signaux",
                NotificationManager.IMPORTANCE_LOW);
        silentUpdates.setDescription("TP, SL et lifecycle sans nouvelle alerte.");
        silentUpdates.setSound(null, null);
        silentUpdates.enableVibration(false);
        silentUpdates.setShowBadge(false);
        manager.createNotificationChannel(silentUpdates);
    }

    private void acquireWakeLock() {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ETHScalper:MarketWatch");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(10 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void connectIfNeeded() {
        if (!running || socket != null || client == null) return;
        Request request = new Request.Builder().url(BINANCE_STREAM).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (socket != webSocket) return;
                reconnectAttempt = 0;
                lastMessageAt = System.currentTimeMillis();
                updateWatch("Connecté · surveillance ETH/BTC active", true);
                broadcastStatus("connected", "Flux Binance Futures connecté");
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (socket != webSocket) return;
                lastMessageAt = System.currentTimeMillis();
                handleMessage(text);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (socket != webSocket) return;
                socket = null;
                updateWatch("Flux interrompu · reconnexion native", true);
                broadcastStatus("reconnect", "Reconnexion native en cours");
                scheduleReconnect();
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (socket != webSocket) return;
                socket = null;
                if (running) scheduleReconnect();
            }
        });
    }

    private void stopSocket() {
        WebSocket current = socket;
        socket = null;
        try { if (current != null) current.close(1000, "service stop"); } catch (Exception ignored) {}
    }

    private void scheduleReconnect() {
        if (!running) return;
        reconnectAttempt = Math.min(reconnectAttempt + 1, 6);
        long delay = Math.min(30_000L, 1000L << Math.max(0, reconnectAttempt - 1));
        handler.postDelayed(this::connectIfNeeded, delay);
    }

    private void scheduleHealthCheck() {
        if (healthScheduled) return;
        healthScheduled = true;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running) { healthScheduled = false; return; }
                acquireWakeLock();
                long now = System.currentTimeMillis();
                maybeRefreshRestFallback(now);
                long age = lastMessageAt == 0 ? Long.MAX_VALUE : now - lastMessageAt;
                if (age > 65_000L) {
                    stopSocket();
                    updateWatch("Flux retardé · reconnexion forcée", true);
                    broadcastStatus("reconnect", "Flux retardé, reconnexion forcée");
                    connectIfNeeded();
                } else {
                    updateWatch(String.format(Locale.US, "Connecté · ETH %.2f · flux %ds",
                            ethLast, Math.max(0, age / 1000)), false);
                    broadcastStatus("live", "Moteur natif actif");
                }
                handler.postDelayed(this, 3000);
            }
        }, 3000);
    }



    private void maybeRefreshRestFallback(long now) {
        if (client == null) return;

        long liveKlineAge = ageSeconds(now, lastKlineAt);
        boolean tradedKlineMissing=false;
        for(MarketRuntime runtime:marketCoordinator.runtimes().values()) {
            if(runtime.candles.size()<30||runtime.lastKlineAt==0
                    ||now-runtime.lastKlineAt>120_000L)tradedKlineMissing=true;
        }
        boolean needKlines = lastRestKlineRefreshAt == 0
                || now - lastRestKlineRefreshAt >= 15_000L
                || btcCandles.size() < 10
                || tradedKlineMissing
                || liveKlineAge < 0
                || liveKlineAge > 120;

        if (needKlines) {
            lastRestKlineRefreshAt = now;
            for(MarketRuntime runtime:marketCoordinator.runtimes().values())
                fetchRuntimeKlines(runtime,60,false);
            fetchReferenceKlines(60,false);
        }

        long liveTradeAge = ageSeconds(now, lastAggTradeAt);
        boolean tradedTradeMissing=false;
        for(MarketRuntime runtime:marketCoordinator.runtimes().values()) {
            if(runtime.aggTrades.isEmpty()||runtime.lastAggTradeAt==0
                    ||now-runtime.lastAggTradeAt>120_000L)tradedTradeMissing=true;
        }
        boolean needTrades = lastRestTradeRefreshAt == 0
                || now - lastRestTradeRefreshAt >= 7_000L
                || tradedTradeMissing
                || liveTradeAge < 0
                || liveTradeAge > 120;

        if (needTrades) {
            lastRestTradeRefreshAt = now;
            for(MarketRuntime runtime:marketCoordinator.runtimes().values())
                fetchRuntimeAggTrades(runtime);
        }
    }

    private void fetchRestKlines(String symbol, boolean isEth) {
        Request request = new Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol + "&interval=1m&limit=60")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                handler.post(() -> broadcastStatus("rest_kline_failed", "Fallback bougies " + symbol + " impossible"));
            }

            @Override public void onResponse(Call call, Response response) {
                try {
                    String raw = response.body() == null ? "" : response.body().string();
                    JSONArray rows = new JSONArray(raw);
                    List<Candle> loaded = new ArrayList<>();

                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray row = rows.optJSONArray(i);
                        if (row == null || row.length() < 6) continue;
                        loaded.add(new Candle(
                                row.optLong(0),
                                row.optDouble(1),
                                row.optDouble(2),
                                row.optDouble(3),
                                row.optDouble(4),
                                row.optDouble(5)
                        ));
                    }

                    handler.post(() -> {
                        Deque<Candle> target = isEth ? ethCandles : btcCandles;
                        for (Candle candle : loaded) upsert(target, candle, 180);

                        if (!loaded.isEmpty()) {
                            Candle last = loaded.get(loaded.size() - 1);
                            if (isEth && ethLast <= 0) ethLast = last.close;
                            if (!isEth && btcLast <= 0) btcLast = last.close;
                            restKlineRefreshes++;
                            lastRestKlineOkAt = System.currentTimeMillis();
                        }

                        evaluateSignal(System.currentTimeMillis());
                    });
                } catch (Exception ignored) {
                    handler.post(() -> broadcastStatus("rest_kline_failed", "Erreur fallback bougies " + symbol));
                } finally {
                    try { response.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private void fetchRestAggTrades(String symbol) {
        Request request = new Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/aggTrades?symbol=" + symbol + "&limit=500")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                handler.post(() -> broadcastStatus("rest_trade_failed", "Fallback trades ETH impossible"));
            }

            @Override public void onResponse(Call call, Response response) {
                try {
                    String raw = response.body() == null ? "" : response.body().string();

                    handler.post(() -> {
                        try {
                            JSONArray rows = new JSONArray(raw);
                            int added = 0;
                            long maxId = lastRestAggTradeId;

                            for (int i = 0; i < rows.length(); i++) {
                                JSONObject row = rows.optJSONObject(i);
                                if (row == null) continue;

                                long id = row.optLong("a", -1);
                                if (id >= 0 && id <= lastRestAggTradeId) continue;

                                long time = row.optLong("T", System.currentTimeMillis());
                                double quantity = row.optDouble("q", 0);
                                boolean maker = row.optBoolean("m", false);

                                if (quantity > 0) {
                                    flows.addLast(new TradeFlow(time, maker ? -quantity : quantity));
                                    added++;
                                }

                                if (id > maxId) maxId = id;
                            }

                            if (maxId > lastRestAggTradeId) lastRestAggTradeId = maxId;

                            if (added > 0) {
                                restTradeRefreshes++;
                                lastRestTradeOkAt = System.currentTimeMillis();
                                lastAggTradeAt = lastRestTradeOkAt;
                                pruneFlows(lastRestTradeOkAt);
                                evaluateSignal(lastRestTradeOkAt);
                                broadcastStatus("rest_trade", "Fallback trades ETH ajouté : " + added);
                            }
                        } catch (Exception ignored) {
                            broadcastStatus("rest_trade_failed", "Erreur analyse fallback trades ETH");
                        }
                    });
                } catch (Exception ignored) {
                    handler.post(() -> broadcastStatus("rest_trade_failed", "Erreur fallback trades ETH"));
                } finally {
                    try { response.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private void prefillHistoricalCandlesIfNeeded() {
        if (historyPrefillRequested || client == null) return;
        historyPrefillRequested = true;
        for(MarketRuntime runtime:marketCoordinator.runtimes().values())
            fetchRuntimeKlines(runtime,180,true);
        fetchReferenceKlines(180,true);
    }

    private void fetchHistoricalKlines(String symbol, boolean isEth) {
        Request request = new Request.Builder()
                .url("https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol + "&interval=1m&limit=180")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                handler.post(() -> broadcastStatus("warmup_failed", "Préchargement " + symbol + " impossible"));
            }

            @Override public void onResponse(Call call, Response response) {
                try {
                    String raw = response.body() == null ? "" : response.body().string();
                    JSONArray rows = new JSONArray(raw);
                    List<Candle> loaded = new ArrayList<>();

                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray row = rows.optJSONArray(i);
                        if (row == null || row.length() < 6) continue;
                        loaded.add(new Candle(
                                row.optLong(0),
                                row.optDouble(1),
                                row.optDouble(2),
                                row.optDouble(3),
                                row.optDouble(4),
                                row.optDouble(5)
                        ));
                    }

                    handler.post(() -> {
                        Deque<Candle> target = isEth ? ethCandles : btcCandles;
                        target.clear();
                        for (Candle candle : loaded) upsert(target, candle, 180);

                        if (!loaded.isEmpty()) {
                            Candle last = loaded.get(loaded.size() - 1);
                            if (isEth) {
                                if (ethLast <= 0) ethLast = last.close;
                            } else {
                                if (btcLast <= 0) btcLast = last.close;
                            }
                        }

                        evaluateSignal(System.currentTimeMillis());
                        broadcastStatus("warmup", "Historique " + symbol + " chargé : " + loaded.size() + " bougies");
                    });
                } catch (Exception ignored) {
                    handler.post(() -> broadcastStatus("warmup_failed", "Erreur préchargement " + symbol));
                } finally {
                    try { response.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private void fetchRuntimeKlines(MarketRuntime runtime,int limit,boolean replace) {
        Request request=new Request.Builder().url("https://fapi.binance.com/fapi/v1/klines?symbol="
                +runtime.profile.symbol+"&interval=1m&limit="+limit).build();
        client.newCall(request).enqueue(new Callback(){
            @Override public void onFailure(Call call,IOException error){handler.post(()->broadcastStatus("rest_kline_failed","Fallback "+runtime.profile.symbol+" impossible"));}
            @Override public void onResponse(Call call,Response response){
                try{JSONArray rows=new JSONArray(response.body()==null?"":response.body().string());List<Candle> loaded=new ArrayList<>();
                    for(int i=0;i<rows.length();i++){JSONArray row=rows.optJSONArray(i);if(row!=null&&row.length()>=6)loaded.add(new Candle(row.optLong(0),row.optDouble(1),row.optDouble(2),row.optDouble(3),row.optDouble(4),row.optDouble(5)));}
                    handler.post(()->{long receivedAt=System.currentTimeMillis();
                        List<MarketRuntime.MarketBar> bars=new ArrayList<>();
                        for(Candle candle:loaded)bars.add(toRuntimeBar(candle));
                        if(replace)marketDataRouter.replacePreloadedCandles(
                                runtime.profile.symbol,bars,receivedAt);
                        else marketDataRouter.mergeFallbackCandles(
                                runtime.profile.symbol,bars,receivedAt);
                        evaluateSignal(receivedAt);});
                }catch(Exception ignored){}finally{try{response.close();}catch(Exception ignored){}}
            }});
    }

    private void fetchRuntimeAggTrades(MarketRuntime runtime) {
        Request request=new Request.Builder().url("https://fapi.binance.com/fapi/v1/aggTrades?symbol="+runtime.profile.symbol+"&limit=500").build();
        client.newCall(request).enqueue(new Callback(){
            @Override public void onFailure(Call call,IOException error){}
            @Override public void onResponse(Call call,Response response){try{String raw=response.body()==null?"":response.body().string();handler.post(()->{try{JSONArray rows=new JSONArray(raw);List<MarketRuntime.AggTrade> trades=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;long id=row.optLong("a",-1);double q=row.optDouble("q",0),price=row.optDouble("p",runtime.last);long at=row.optLong("T",System.currentTimeMillis());trades.add(new MarketRuntime.AggTrade(id,at,price,q,row.optBoolean("m",false)));}long receivedAt=System.currentTimeMillis();marketDataRouter.mergeFallbackAggTrades(runtime.profile.symbol,trades,receivedAt);evaluateSignal(receivedAt);}catch(Exception ignored){}});}catch(Exception ignored){}finally{try{response.close();}catch(Exception ignored){}}}
        });
    }

    private void fetchReferenceKlines(int limit,boolean replace) {
        if(replace)fetchHistoricalKlines(MarketProfile.BTC_SYMBOL,false);
        else fetchRestKlines(MarketProfile.BTC_SYMBOL,false);
    }

    private void handleMessage(String text) {
        try {
            JSONObject root = new JSONObject(text);
            String stream = root.optString("stream", "");
            JSONObject data = root.optJSONObject("data");
            if (data == null) return;
            String normalizedStream = stream.toLowerCase(Locale.ROOT);
            if (normalizedStream.contains("bookticker")) handleBookTicker(stream, data);
            else if (normalizedStream.contains("kline_1m")) handleKline(stream, data);
            else if (normalizedStream.contains("aggtrade")) handleAggTrade(stream, data);

            long now = System.currentTimeMillis();
            if (now - lastEvaluationAt >= 1000) {
                lastEvaluationAt = now;
                evaluateSignal(now);
            }
            if (now - lastStatusAt >= 1500) {
                lastStatusAt = now;
                broadcastStatus("live", "Prix natifs actualisés");
            }
        } catch (Exception ignored) {}
    }

    private void handleBookTicker(String stream, JSONObject data) {
        bookTickerMessages++;
        long now = System.currentTimeMillis();
        lastBookTickerAt = now;
        String symbol=MarketDataRouter.symbolFromStream(stream);
        if (MarketProfile.BTC_SYMBOL.equals(symbol)) {
            btcBid = data.optDouble("b", btcBid);
            btcAsk = data.optDouble("a", btcAsk);
            if (btcBid > 0 && btcAsk > 0) btcLast = (btcBid + btcAsk) / 2.0;
            sharedBtc.bid=btcBid;sharedBtc.ask=btcAsk;sharedBtc.last=btcLast;
            sharedBtc.lastTickerAt=now;sharedBtc.bookTickerMessages++;
        } else if(marketRegistry.contains(symbol)) {
            MarketRuntime runtime=marketCoordinator.runtime(symbol);
            marketDataRouter.routeBookTicker(symbol,data.optDouble("b",runtime.bid),
                    data.optDouble("a",runtime.ask),now);
        }
    }

    private void handleKline(String stream, JSONObject data) {
        klineMessages++;
        lastKlineAt = System.currentTimeMillis();
        JSONObject kline = data.optJSONObject("k");
        if (kline == null) return;
        Candle candle = new Candle(kline.optLong("t"), kline.optDouble("o"), kline.optDouble("h"),
                kline.optDouble("l"), kline.optDouble("c"), kline.optDouble("v"));
        String symbol=MarketDataRouter.symbolFromStream(stream);
        if (MarketProfile.BTC_SYMBOL.equals(symbol)) {
            btcLast = candle.close;
            upsert(btcCandles, candle, 180);
            sharedBtc.last=candle.close;sharedBtc.lastKlineAt=System.currentTimeMillis();sharedBtc.klineMessages++;
            upsertSharedBtc(candle);
        } else if(marketRegistry.contains(symbol)) {
            marketDataRouter.routeKline(symbol,toRuntimeBar(candle),System.currentTimeMillis());
        }
    }

    private void handleAggTrade(String stream, JSONObject data) {
        aggTradeMessages++;
        String symbol=MarketDataRouter.symbolFromStream(stream);
        if(!marketRegistry.contains(symbol))return;
        MarketRuntime runtime=marketCoordinator.runtime(symbol);
        long time=data.optLong("T",System.currentTimeMillis());
        marketDataRouter.routeAggTrade(symbol,new MarketRuntime.AggTrade(
                data.optLong("a",-1),time,data.optDouble("p",runtime.last),
                data.optDouble("q",0),data.optBoolean("m",false)),System.currentTimeMillis());
    }

    private synchronized void evaluateSignal(long now) {
        evaluateSecondaryMarkets(now);
        MarketSnapshot snapshot = buildSnapshot(now);
        boolean feedFresh = ethExecutionFeedFresh(now);

        // C01: an old feed blocks only creation; existing candidates and risks still advance.
        updateObservedSignals(snapshot, now, feedFresh);
        snapshot = buildSnapshot(now);
        feedFresh = ethExecutionFeedFresh(now);
        String currentSetup = setupCandidateFor(snapshot);
        boolean activeFinalPlan = activeFinalSignal() != null;
        boolean terminalRearmComplete = TerminalRearmPersistence.allowsNewCandidate(
                now, lastTerminalAt);
        boolean p02Appearance = P02SleeveFilter.canObserveSetup(
                feedFresh, activeFinalPlan, terminalRearmComplete)
                && p02SetupTracker.observe(currentSetup);

        if (!feedFresh) {
            SignalDecision staleFeed = SignalDecision.waiting(
                    "V2326_ETH_FEED_STALE",
                    "Nouvelles entrées bloquées — gestion des risques existants maintenue.",
                    0,
                    "",
                    false,
                    0,
                    0,
                    0,
                    false);

            recordMarketFrame(snapshot, staleFeed, now);
            lastDecision = staleFeed;

            broadcastStatus(
                    "eth_feed_stale",
                    "Nouvelles entrées bloquées — gestion des risques existants maintenue.");

            return;
        }

        SignalDecision rawDecision = signalEngine.evaluate(snapshot);
        boolean oppositeScenarioActive = rawDecision != null && rawDecision.isSignal()
                && shouldBlockByScenarioMemory(rawDecision, snapshot, now);
        String replayRiskDiagnostic = rawDecision != null && rawDecision.isSignal()
                ? replayRiskVetoCode(snapshot, rawDecision, -1) : "";
        CandidateLifecycle.AdmissionResult admission = CandidateLifecycle.admit(
                rawDecision, feedFresh, oppositeScenarioActive, replayRiskDiagnostic);
        SignalDecision decision = admission.decision;
        aiStatus = AiAdvisor.isEnabled(this)
                ? "AI_INFORMATIONAL_POST_SIGNAL"
                : "AI_OFF_ENGINE_COMPLETE";

        recordMarketFrame(snapshot, decision, now);
        if (p02Appearance) {
            activateP02Appearance(currentSetup, snapshot, rawDecision, now, feedFresh);
        }

        if (decision != null && !decision.isSignal() && isLastSignalStillActionable(snapshot, now)) {
            lastDecision = lastSignal;
            broadcastStatus("signal_active", "Signal actif conservé : marché encore valide");
            return;
        }

        lastDecision = decision;
        if (admission.observed && decision != null && decision.isSignal()) {
            activateSignalDecision(decision, snapshot, now,
                    admission.replayRiskReasonCode, admission.replayRiskDetail);
        }
    }

    private boolean ethExecutionFeedFresh(long now) {
        if (ethBid <= 0 || ethAsk <= 0 || ethLast <= 0) return false;
        if (lastEthBookTickerAt <= 0) return false;
        return now - lastEthBookTickerAt <= ETH_BOOK_MAX_AGE_MS
                && sharedBtc.fresh(now,ETH_BOOK_MAX_AGE_MS);
    }

    private void evaluateSecondaryMarkets(long now) {
        syncSharedBtcFromLegacy();
        for(MarketProfile profile:marketRegistry.tradedMarkets()) {
            if(MarketProfile.ETH_SYMBOL.equals(profile.symbol))continue;
            MarketRuntime runtime=marketCoordinator.runtime(profile.symbol);
            boolean marketFresh=runtime.bid>0&&runtime.ask>0&&runtime.last>0
                    &&runtime.lastTickerAt>0&&now-runtime.lastTickerAt<=ETH_BOOK_MAX_AGE_MS;
            MarketPlanOrchestrator.Event event=marketOrchestrator.evaluate(runtime,sharedBtc,now,
                    marketFresh,sharedBtc.fresh(now,ETH_BOOK_MAX_AGE_MS));
            if("CONFIRMED".equals(event.type)) {
                if(activePlanPersistence.saveForMarket(event.plan)) notifyMarketPlan(event.plan,true);
                else {runtime.activePlan=null;runtime.lastSignal=null;}
            } else if("TERMINAL".equals(event.type)) {
                terminalRearmPersistence.save(profile.symbol,now);runtime.lastTerminalAt=now;
                activePlanPersistence.clearForTerminal(profile.symbol,event.status);
                notifyMarketTerminal(event.plan,event.status);
            }
        }
    }

    private void syncSharedBtcFromLegacy() {
        sharedBtc.last=btcLast;sharedBtc.bid=btcBid;sharedBtc.ask=btcAsk;
        if(sharedBtc.lastTickerAt==0&&lastBookTickerAt>0)sharedBtc.lastTickerAt=lastBookTickerAt;
        if(sharedBtc.candles.size()!=btcCandles.size()) {
            sharedBtc.candles.clear();for(Candle c:btcCandles)upsertSharedBtc(c);
        }
    }

    private synchronized void handleAiResult(String signature, AiAdvisor.AiResult result) {
        lastAiDecisionJson = result == null ? "{}" : result.rawJson;
        aiStatus = result == null ? "AI_INFORMATIONAL_EMPTY" : "AI_INFORMATIONAL_RECORDED";
        broadcastStatus("ai_informational_recorded",
                result == null ? "Avis IA informatif indisponible" : result.reason);
    }

    private String replayRiskVetoCode(MarketSnapshot snapshot, SignalDecision decision, int aiConfidence) {
        if (snapshot == null || decision == null || !decision.isSignal()) return "NO_SIGNAL";

        String family = decision.family == null ? "" : decision.family;

        if (family.contains("CONTINUATION")) {
            if (aiApprovedExhaustedContinuationTrap(decision, snapshot)) {
                return "CONTINUATION_TROP_TARDIVE";
            }

            if (continuationStaleOrConflictedTrap(snapshot, decision)) {
                return "CONTINUATION_SANS_ALIGNEMENT_8M_OU_FLOW";
            }

            if (continuationValidatedReplayTrap(snapshot, decision, aiConfidence)) {
                return "CONTINUATION_REPLAY_QUALITE_INSUFFISANTE";
            }
        }

        if (family.contains("RANGE_FADE")) {
            if (aiConfidence >= 0 && aiConfidence < 75) {
                return "RANGE_FADE_IA_TROP_BASSE";
            }

            if (rangeFadeValidatedReplayTrap(snapshot, decision, aiConfidence)) {
                return "RANGE_FADE_REJET_INSUFFISANT";
            }

            if (aiApprovedRangeFadeAgainstLiveC2Trap(decision, snapshot)) {
                return "RANGE_FADE_CONTRE_C2_ACTIF";
            }

            if (aiApprovedRangeFadeCounterTrendTrap(decision, snapshot)) {
                return "RANGE_FADE_CONTRE_TENDANCE_FORTE";
            }
        }

        return "";
    }

    private boolean continuationStaleOrConflictedTrap(MarketSnapshot s, SignalDecision decision) {
        if (s == null || decision == null) return true;

        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("CONTINUATION")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);

        double move3Aligned = side * s.move3;
        double move8Aligned = side * s.move8;
        double flow15Aligned = side * s.flow15;
        double flow30Aligned = side * s.flow30;
        double flow60Aligned = side * s.flow60;
        double btc3Aligned = side * s.btcMove3;

        // Le problème détecté dans le ZIP v2.32.6 :
        // SCALP_CONTINUATION déclenché sur move3 fort mais move8 pas aligné,
        // puis retournement rapide en SL. Ce n'est pas une vraie continuation.
        boolean move3Strong = move3Aligned > avg * 1.15;
        boolean move8Missing = move8Aligned < avg * 0.75;
        boolean move8Opposite = move8Aligned < -avg * 0.20;

        boolean freshFlowWeak = flow15Aligned < 0.02 && flow30Aligned < 0.02;
        boolean mediumFlowWeak = flow60Aligned < 0.30;
        boolean btcAgainst = btc3Aligned < -0.00020;

        if (move8Opposite) return true;
        if (move3Strong && move8Missing) return true;

        return move8Aligned < avg * 1.00
                && freshFlowWeak
                && (mediumFlowWeak || btcAgainst);
    }

    private boolean continuationValidatedReplayTrap(MarketSnapshot s, SignalDecision decision, int aiConfidence) {
        if (s == null || decision == null) return true;

        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("CONTINUATION")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double move1 = side * s.move1;
        double move8 = side * s.move8;
        double flow30 = side * s.flow30;
        double flow60 = side * s.flow60;
        double btc3 = side * s.btcMove3;
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;

        // Replay v2.32.2/v2.32.6 :
        // les continuations prises en plein burst donnaient souvent un SL rapide.
        // V2326_CONTINUATION_QUALITY_FLOOR
        if (move1 < avg * 0.50) return true;
        if (move8 < avg * 1.20) return true;

        if (s.volumeRatio > 0 && s.volumeRatio < 0.35) {
            return true;
        }

        if (move1 > avg * 1.15 && s.volumeRatio > 1.50) return true;

        // Ancienne tendance sans flux frais :
        // continuation probablement déjà consommée.
        if (move8 > avg * 2.50 && flow30 < 0.02 && flow60 < 0.02) return true;

        // SHORT continuation trop haut dans la range sans rejet net.
        if (side < 0 && rp > 0.72 && flow30 < 0.80) return true;

        // LONG continuation trop haut sans flux frais suffisant.
        if (side > 0 && rp > 0.74 && flow30 < 0.05) return true;

        // Avec une confiance IA de 78 ou 79,
        // conserver uniquement les continuations vraiment propres.
        if (aiConfidence >= 0 && aiConfidence < 80) {
            boolean cleanLowAiContinuation =
                    flow60 > 0.45
                    && move8 > avg * 1.90
                    && move1 <= avg * 0.90
                    && btc3 > -0.00020;

            if (!cleanLowAiContinuation) return true;
        }

        return false;
    }

    private boolean rangeFadeValidatedReplayTrap(MarketSnapshot s, SignalDecision decision, int aiConfidence) {
        if (s == null || decision == null) return true;

        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("RANGE_FADE")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;
        double move1 = side * s.move1;
        double flow30 = side * s.flow30;
        double flow60 = side * s.flow60;
        double btc3 = side * s.btcMove3;

        // V2326_RANGE_FADE_PRICE_REJECTION
        if (move1 < avg * 0.50) {
            return true;
        }

        // Le fade doit venir d'une vraie zone extrême.
        if (side < 0 && rp < 0.78) return true;
        if (side > 0 && rp > 0.26) return true;

        // IA inférieure à 82 et BTC opposé : fade fragile.
        if (aiConfidence >= 0 && aiConfidence < 82 && btc3 < -0.00035) {
            return true;
        }

        // Exiger un rejet immédiat du prix ou du flow.
        boolean immediateRejection =
                move1 > avg * 0.20
                || flow30 > 0.05
                || flow60 > 0.50;

        return !immediateRejection;
    }

    private boolean weakRangeFadeContext(MarketSnapshot s, SignalDecision decision) {
        if (s == null || decision == null) return true;
        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("RANGE_FADE")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;

        if (side < 0) {
            boolean notHighEnough = rp < 0.86;
            boolean oldLongExtension = s.move8 > avg * 1.60;
            boolean higherFrameStillLong = s.flow120 > 0.25 || s.btcMove5 > 0.00035 || s.btcMove8 > 0.00035;
            boolean rejectionWeak = !(s.flow15 < -0.10 && s.flow30 < -0.03);
            return notHighEnough && oldLongExtension && higherFrameStillLong && rejectionWeak;
        }

        if (side > 0) {
            boolean notLowEnough = rp > 0.14;
            boolean oldShortExtension = s.move8 < -avg * 1.60;
            boolean higherFrameStillShort = s.flow120 < -0.25 || s.btcMove5 < -0.00035 || s.btcMove8 < -0.00035;
            boolean reboundWeak = !(s.flow15 > 0.10 && s.flow30 > 0.03);
            return notLowEnough && oldShortExtension && higherFrameStillShort && reboundWeak;
        }

        return false;
    }

    private boolean weakContinuationSizingRisk(MarketSnapshot s, SignalDecision decision) {
        if (s == null || decision == null) return true;
        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;
        double target = Math.max(2.80, decision.targetMove);
        double room = side > 0 ? s.roomLong : s.roomShort;

        boolean roomWeak = room < Math.max(1.75, target * 0.88);
        boolean badZone = side > 0 ? rp > 0.68 : rp < 0.32;
        boolean extended = side * s.move3 > avg * 1.45 && side * s.move8 > avg * 1.45;
        boolean freshFlowWeak = side * s.flow15 < 0.08 && side * s.flow30 < 0.25;
        boolean lowVolume = s.volumeRatio > 0 && s.volumeRatio < 0.35;

        return roomWeak && badZone && extended && (freshFlowWeak || lowVolume);
    }

    private boolean aiApprovedRangeFadeAgainstLiveC2Trap(SignalDecision decision, MarketSnapshot s) {
        if (decision == null || s == null) return true;
        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("RANGE_FADE")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double threshold = Math.max(0.75, avg * 0.55);
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;

        if (side > 0) {
            boolean c2ShortActive = s.move3 < -threshold * 1.35 && s.move1 < avg * 0.25;
            boolean nearLowNotReversal = rp <= 0.10 && s.roomShort < Math.max(0.65, avg * 0.45);
            boolean flowStillShort = s.flow30 < -0.045 && (s.flow60 < -0.060 || s.flow120 < -0.080);
            boolean btcStillShort = s.btcMove3 < -0.00022 || s.btcMove8 < -0.00035 || s.btcMove5 < -0.00035;
            boolean noRealBounceYet = s.move1 < avg * 0.10 && !(s.flow15 > 0.10 && s.flow30 > 0.03);

            return c2ShortActive && nearLowNotReversal && (flowStillShort || btcStillShort) && noRealBounceYet;
        }

        if (side < 0) {
            boolean c2LongActive = s.move3 > threshold * 1.35 && s.move1 > -avg * 0.25;
            boolean nearHighNotReversal = rp >= 0.90 && s.roomLong < Math.max(0.65, avg * 0.45);
            boolean flowStillLong = s.flow30 > 0.045 && (s.flow60 > 0.060 || s.flow120 > 0.080);
            boolean btcStillLong = s.btcMove3 > 0.00022 || s.btcMove8 > 0.00035 || s.btcMove5 > 0.00035;
            boolean noRealRejectionYet = s.move1 > -avg * 0.10 && !(s.flow15 < -0.10 && s.flow30 < -0.03);

            return c2LongActive && nearHighNotReversal && (flowStillLong || btcStillLong) && noRealRejectionYet;
        }

        return false;
    }

    private boolean shouldRejectAiFallback(SignalDecision decision) {
        return true;
    }

    private boolean shouldRejectAiApprovedFresh(SignalDecision decision, MarketSnapshot s) {
        if (decision == null || s == null) return true;

        String family = decision.family == null ? "" : decision.family;
        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        if (family.contains("CONTINUATION") && aiApprovedExhaustedContinuationTrap(decision, s)) {
            return true;
        }

        if (family.contains("RANGE_FADE") && (aiApprovedRangeFadeCounterTrendTrap(decision, s) || aiApprovedWeakExtremeRangeFadeTrap(decision, s) || aiApprovedRangeFadeAgainstLiveC2Trap(decision, s))) {
            return true;
        }

        if (family.contains("RANGE_FADE")) {
            if (side > 0) {
                boolean fallingKnife = s.rangePosition < 0.02
                        || (s.move1 < -s.avgRange20 * 0.45 && s.move3 < -s.avgRange20 * 0.85 && s.flow30 < -0.04)
                        || (s.flow15 < -0.10 && s.flow30 < -0.08)
                        || (s.btcMove1 < -0.00045 && s.btcMove3 < -0.00040);
                if (fallingKnife) return true;
            } else {
                boolean risingKnife = s.rangePosition > 0.98
                        || (s.move1 > s.avgRange20 * 0.45 && s.move3 > s.avgRange20 * 0.85 && s.flow30 > 0.04)
                        || (s.flow15 > 0.10 && s.flow30 > 0.08)
                        || (s.btcMove1 > 0.00045 && s.btcMove3 > 0.00040);
                if (risingKnife) return true;
            }
        }

        return false;
    }

    private boolean aiApprovedExhaustedContinuationTrap(SignalDecision decision, MarketSnapshot s) {
        if (decision == null || s == null) return true;
        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("CONTINUATION")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;
        double room = side > 0 ? s.roomLong : s.roomShort;
        double target = Math.max(2.80, decision.targetMove);

        boolean extension = side * s.move3 > avg * 2.05 && side * s.move8 > avg * 1.75;
        boolean badZone = side > 0 ? rp >= 0.72 : rp <= 0.28;

        boolean microStall = side * s.move1 <= avg * 0.04;
        boolean flowCrowded = side * s.flow60 > 0.45 && side * s.flow120 > 0.45;
        boolean flowDivergence = side * s.flow15 < 0.08 || side * s.flowAccel < -0.45;

        boolean roomWeak = room < Math.max(1.75, target * 0.82);
        boolean lowFreshVolume = s.volumeRatio > 0 && s.volumeRatio < 0.25;

        return extension && badZone && flowCrowded && (microStall || flowDivergence) && (roomWeak || lowFreshVolume);
    }

    private boolean aiApprovedWeakExtremeRangeFadeTrap(SignalDecision decision, MarketSnapshot s) {
        if (decision == null || s == null) return true;
        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("RANGE_FADE")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;

        if (side < 0) {
            boolean notHighEnough = rp < 0.86;
            boolean oldLongExtension = s.move8 > avg * 2.40;
            boolean higherFrameStillLong = s.flow120 > 0.35 || s.btcMove5 > 0.00035 || s.btcMove8 > 0.00035;
            boolean rejectionNotBroadEnough = !(s.flow15 < -0.02 && s.flow30 < -0.02) && s.flow60 > -0.35;
            return notHighEnough && oldLongExtension && higherFrameStillLong && rejectionNotBroadEnough;
        }

        if (side > 0) {
            boolean notLowEnough = rp > 0.14;
            boolean oldShortExtension = s.move8 < -avg * 2.40;
            boolean higherFrameStillShort = s.flow120 < -0.35 || s.btcMove5 < -0.00035 || s.btcMove8 < -0.00035;
            boolean rejectionNotBroadEnough = !(s.flow15 > 0.02 && s.flow30 > 0.02) && s.flow60 < 0.35;
            return notLowEnough && oldShortExtension && higherFrameStillShort && rejectionNotBroadEnough;
        }

        return false;
    }

    private boolean aiApprovedRangeFadeCounterTrendTrap(SignalDecision decision, MarketSnapshot s) {
        if (decision == null || s == null) return true;
        String family = decision.family == null ? "" : decision.family;
        if (!family.contains("RANGE_FADE")) return false;

        int side = "LONG".equals(decision.side) ? 1 : "SHORT".equals(decision.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double rp = Double.isFinite(s.rangePosition) ? s.rangePosition : 0.5;

        if (side < 0) {
            boolean ethLongPush = s.move3 > avg * 1.25 && s.move8 > avg * 2.35 && rp >= 0.88;
            boolean btcLongConfirm = s.btcMove3 > 0.00022 || s.btcMove8 > 0.00035;
            boolean mediumFlowLong = s.flow60 > 0.03 || s.flow120 > 0.30;
            boolean noImmediateRejection = s.move1 > -avg * 0.35 && s.flow15 > -0.24;
            return ethLongPush && btcLongConfirm && mediumFlowLong && noImmediateRejection;
        }

        if (side > 0) {
            boolean ethShortPush = s.move3 < -avg * 1.25 && s.move8 < -avg * 2.35 && rp <= 0.12;
            boolean btcShortConfirm = s.btcMove3 < -0.00022 || s.btcMove8 < -0.00035;
            boolean mediumFlowShort = s.flow60 < -0.03 || s.flow120 < -0.30;
            boolean noImmediateRejection = s.move1 < avg * 0.35 && s.flow15 < 0.24;
            return ethShortPush && btcShortConfirm && mediumFlowShort && noImmediateRejection;
        }

        return false;
    }

    private boolean isAiSignalTooLate(SignalDecision decision, MarketSnapshot snapshot) {
        double price = snapshot.ethLast;
        if (!Double.isFinite(price) || price <= 0) return true;

        double favorable = "LONG".equals(decision.side)
                ? price - decision.entry
                : decision.entry - price;

        double adverse = "LONG".equals(decision.side)
                ? decision.entry - price
                : price - decision.entry;

        if (favorable > Math.max(0.55, decision.targetMove * 0.28)) return true;
        return adverse > Math.max(0.45, decision.stopDistance * 0.40);
    }

    private String aiSignature(SignalDecision decision, MarketSnapshot snapshot) {
        long entryBucket = Math.round(decision.entry * 10.0);
        long rpBucket = Math.round(snapshot.rangePosition * 20.0);
        return decision.side + "|" + entryBucket + "|" + Math.round(decision.targetMove * 10.0)
                + "|" + Math.round(decision.stopDistance * 10.0) + "|" + rpBucket + "|" + decision.family;
    }

    private void activateP02Appearance(String setup, MarketSnapshot snapshot,
                                       SignalDecision engineDecision, long now,
                                       boolean feedFresh) {
        String side = P02SleeveFilter.sideFor(setup);
        if (side.isEmpty() || snapshot == null || !feedFresh) return;
        double entry = "LONG".equals(side)
                ? (snapshot.ethAsk > 0 ? snapshot.ethAsk : snapshot.ethLast)
                : (snapshot.ethBid > 0 ? snapshot.ethBid : snapshot.ethLast);
        if (!Double.isFinite(entry) || entry <= 0.0) return;
        entry = round2(entry);
        double target = 2.80;
        double stop = 1.35;
        int direction = "LONG".equals(side) ? 1 : -1;
        int score = engineDecision == null ? 0 : engineDecision.score;
        String impulse = engineDecision == null ? "" : engineDecision.impulse;
        boolean reset = engineDecision != null && engineDecision.resetConfirmed;
        double origin = engineDecision == null ? 0.0 : engineDecision.movementOrigin;
        double extreme = engineDecision == null ? 0.0 : engineDecision.movementExtreme;
        double distance = engineDecision == null ? 0.0 : engineDecision.movementDistance;
        SignalDecision candidate = SignalDecision.signal(side,
                "v2.33 P02 CONTINUATION " + setup, score, ConfirmedSizing.BASE_QUANTITY,
                entry, round2(entry + direction * target),
                round2(entry - direction * stop), target, stop, impulse,
                reset, origin, extreme, distance);

        NormalizedSignalMetrics.Result metrics = NormalizedSignalMetrics.calculate(
                side, candidate, snapshot, 0.0);
        P02SleeveFilter.Result prefilter = P02SleeveFilter.prefilter(metrics);
        if (!prefilter.accepted) {
            signalEngine.recordExternalDiagnostic(now, prefilter.reasonCode,
                    "P02 exact appearance rejected by the fixed creation prefilter.");
            return;
        }
        boolean oppositeScenarioActive = shouldBlockByScenarioMemory(candidate, snapshot, now);
        CandidateLifecycle.AdmissionResult admission = CandidateLifecycle.admit(
                candidate, true, oppositeScenarioActive, "");
        if (admission.observed) {
            activateSignalDecision(candidate, snapshot, now, "", "");
        } else if (admission.decision != null) {
            signalEngine.recordExternalDiagnostic(now, admission.decision.reasonCode,
                    "P02 exact appearance rejected before silent observation.");
        }
    }

    private void activateSignalDecision(SignalDecision decision, MarketSnapshot snapshot, long now,
                                        String replayRiskReasonCode,
                                        String replayRiskDetail) {
        lastDecision = SignalDecision.waiting("V2327_ANALYSIS_IN_PROGRESS",
                "Analyse du marché en cours", 0, "", false, 0, 0, 0, false);
        boolean continuation = decision != null && ContinuationConfirmation.requiresP01(
                decision.family);
        if (continuation && !TerminalRearmPersistence.allowsNewCandidate(now, lastTerminalAt)) {
            signalEngine.recordExternalDiagnostic(now, "V2330_TERMINAL_REARM_ACTIVE",
                    "Candidate kept silent during the three-minute post-terminal rearm.");
            broadcastStatus("analysis", "Analyse du marché en cours");
            return;
        }
        String candidateSignature = SignalSafetyPolicies.candidateSignature(decision);
        if (candidateTombstones.blocks(candidateSignature)) {
            lastDecision = SignalDecision.waiting(CandidateLifecycle.TARGET_BEFORE_FILL,
                    "Analyse du marché en cours", 0, "", false, 0, 0, 0, false);
            broadcastStatus("analysis", "Analyse du marché en cours");
            return;
        }
        PendingCandidateIndex.UpsertResult<ObservedSignal> upsert = pendingCandidateIndex.upsert(
                candidateSignature, now,
                () -> recordObservedSignal(decision, snapshot, now,
                        replayRiskReasonCode, replayRiskDetail, candidateSignature));
        ObservedSignal item = upsert.value;
        if (item != null) {
            item.sleeve = item.signal.family != null && item.signal.family.contains("P02")
                    ? CandidateLifecycle.SLEEVE_P02 : CandidateLifecycle.SLEEVE_P01;
        }
        if (!upsert.created) {
            updateExistingCandidate(item, decision, snapshot, now,
                    replayRiskReasonCode, replayRiskDetail);
            persistObservedSignalEvent(item, "V2328_EXISTING_CANDIDATE_UPDATED", snapshot, now);
        }

        // Raw plans are internal candidates. RANGE_FADE remains calibration-only.
        if (item != null && item.signal.family != null
                && item.signal.family.contains("RANGE_FADE")) {
            item.status = "DIAGNOSTIC_ONLY";
            item.lastConfirmationCode = CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY;
            if (upsert.created) {
                persistObservedSignalEvent(item,
                        CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_ONLY, snapshot, now);
            }
        } else if (item != null && upsert.created) {
            item.lastConfirmationCode = CandidateLifecycle.SLEEVE_P02.equals(item.sleeve)
                    ? P02SleeveFilter.SILENT_WINDOW
                    : CandidateLifecycle.SILENT_CONFIRMATION_WINDOW;
            persistObservedSignalEvent(item,
                    item.lastConfirmationCode, snapshot, now);
        }
        broadcastStatus("analysis", "Analyse du marché en cours");
    }

    private void updateExistingCandidate(ObservedSignal item, SignalDecision decision,
                                         MarketSnapshot snapshot, long now,
                                         String replayRiskReasonCode,
                                         String replayRiskDetail) {
        if (item == null || decision == null || snapshot == null) return;
        item.signal = decision;
        item.lastUpdateAt = now;
        item.updates++;
        double price = snapshot.ethLast;
        if (Double.isFinite(price) && price > 0) {
            item.lastPrice = price;
            item.maxPrice = Math.max(item.maxPrice, price);
            item.minPrice = Math.min(item.minPrice, price);
        }
        observeAdverseExcursion60(item, snapshot, now);
        if (replayRiskReasonCode != null && !replayRiskReasonCode.isEmpty()) {
            item.replayRiskReasonCode = replayRiskReasonCode;
            item.replayRiskDetail = replayRiskDetail == null ? "" : replayRiskDetail;
        }
        item.lastConfirmationCode = "V2328_EXISTING_CANDIDATE_UPDATED";
    }

    private void requestPostPublicationAiAdvisory(MarketSnapshot snapshot, SignalDecision published) {
        if (!AiAdvisor.isEnabled(this) || snapshot == null || published == null) {
            aiStatus = "AI_OFF_ENGINE_COMPLETE";
            return;
        }
        if (aiAdvisor == null) aiAdvisor = new AiAdvisor(this);
        aiStatus = "AI_INFORMATIONAL_ASYNC";
        String signature = aiSignature(published, snapshot);
        aiAdvisor.confirmAsync(snapshot, published, aiMicroContextJson(), result ->
                handler.post(() -> handleAiResult(signature, result)));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private File persistentFile(String name) {
        return persistentFile(this, name);
    }

    private void appendPersistentJsonLine(String fileName, JSONObject object) {
        if (object == null) return;
        try {
            File file = persistentFile(fileName);
            String line = object.toString() + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception ignored) {}
    }

    private void resetPersistentRecorder() {
        try { persistentFile(PERSISTENT_OBSERVATIONS_FILE).delete(); } catch (Exception ignored) {}
        try { persistentFile(PERSISTENT_MARKET_FRAMES_FILE).delete(); } catch (Exception ignored) {}
        LAST_PERSISTENT_OBSERVATIONS_JSON = "[]";
        LAST_PERSISTENT_MARKET_FRAMES_JSON = "[]";
        LAST_OVERNIGHT_RECORDER_SUMMARY_JSON = "{}";
    }

    private void persistMarketFrame(MarketFrame frame, long now, boolean force) {
        if (frame == null) return;
        if (!force && lastPersistentMarketFrameAt > 0 && now - lastPersistentMarketFrameAt < PERSISTENT_MARKET_FRAME_INTERVAL_MS) {
            return;
        }

        try {
            JSONObject json = marketFrameJson(frame);
            json.put("persistedAt", now);
            appendPersistentJsonLine(PERSISTENT_MARKET_FRAMES_FILE, json);
            lastPersistentMarketFrameAt = now;
        } catch (Exception ignored) {}
    }

    private void persistObservedSignalEvent(ObservedSignal item, String event, MarketSnapshot snapshot, long now) {
        if (item == null || item.signal == null) return;

        try {
            JSONObject o = new JSONObject();
            o.put("event", event);
            o.put("eventAt", now);
            o.put("id", item.id);
            o.put("candidateSignature", item.candidateSignature);
            o.put("signalKey", item.createdAt + "|" + item.signal.side + "|" + item.signal.entry + "|" + item.signal.takeProfit + "|" + item.signal.stopLoss);
            o.put("createdAt", item.createdAt);
            o.put("lastUpdateAt", item.lastUpdateAt);
            o.put("closedAt", item.closedAt);
            o.put("status", item.status);
            o.put("exitAt", item.exitAt > 0 ? item.exitAt : JSONObject.NULL);
            putMetric(o, "exitPrice", item.exitPrice);
            o.put("exitReason", item.exitReason);
            o.put("entryState", item.entryTriggered ? "TRIGGERED" : "PENDING");
            o.put("entryTriggered", item.entryTriggered);
            o.put("entryTriggeredAt", item.entryTriggeredAt);
            putMetric(o, "entryTriggerPrice", item.entryTriggerPrice);
            o.put("marketableAtCreation", item.marketableAtCreation);
            putMetric(o, "creationBid", item.creationBid);
            putMetric(o, "creationAsk", item.creationAsk);
            putMetric(o, "creationLast", item.creationLast);
            putMetric(o, "plannedEntry", item.plannedEntry);
            o.put("firstEntryTouchAt", item.firstEntryTouchAt > 0 ? item.firstEntryTouchAt : JSONObject.NULL);
            putMetric(o, "firstEntryTouchPrice", item.firstEntryTouchPrice);
            putMetric(o, "firstEntryTouchBid", item.firstEntryTouchBid);
            putMetric(o, "firstEntryTouchAsk", item.firstEntryTouchAsk);
            o.put("simulatedFillAt", item.simulatedFillAt > 0 ? item.simulatedFillAt : JSONObject.NULL);
            putMetric(o, "simulatedFillPrice", item.simulatedFillPrice);
            o.put("manualEntryConfirmed", item.manualEntryConfirmed);
            o.put("manualEntryPrice", item.manualEntryConfirmed ? item.manualEntryPrice : JSONObject.NULL);
            o.put("manualEntryAt", item.manualEntryConfirmed && item.manualEntryAt > 0
                    ? item.manualEntryAt : JSONObject.NULL);
            o.put("finalConfirmedAt", item.finalConfirmedAt > 0 ? item.finalConfirmedAt : JSONObject.NULL);
            o.put("confirmationAt", item.confirmationAt > 0 ? item.confirmationAt : JSONObject.NULL);
            o.put("candidateAgeMs", item.confirmationAt > 0
                    ? Math.max(0L, item.confirmationAt - item.createdAt) : Math.max(0L, now - item.createdAt));
            o.put("executableAtConfirmation", item.executableAtConfirmation);
            putMetric(o, "finalConfirmationBid", item.finalConfirmationBid);
            putMetric(o, "finalConfirmationAsk", item.finalConfirmationAsk);
            putMetric(o, "A", item.finalConfirmationA);
            putMetric(o, "E60", item.adverseExcursion60);
            putMetric(o, "R", item.finalConfirmationR);
            o.put("legacyManualEntryDelayMs", LEGACY_LIMIT_MANUAL_ENTRY_DELAY_MS);
            o.put("silentP01ConfirmationWindowMs", CandidateLifecycle.MIN_CONFIRMATION_AGE_MS);
            o.put("confirmationWindowSatisfied", item.confirmationAt > 0
                    && item.confirmationAt - item.createdAt >= CandidateLifecycle.MIN_CONFIRMATION_AGE_MS);
            o.put("replayRiskReasonCode", item.replayRiskReasonCode);
            o.put("replayRiskDetail", item.replayRiskDetail);
            o.put("replayRiskVetoBlocking", item.replayRiskBlocking);
            o.put("executionClassification", executionClassification(item));
            o.put("entryRevalidationCode", item.entryRevalidationCode);
            o.put("confirmationReasonCode", item.lastConfirmationCode);
            o.put("sleeve", item.sleeve);
            o.put("p02Mode", item.p02Mode);
            o.put("sleeveDiagnostics", sleeveDiagnosticsJson(item));
            o.put("terminalAt", lastTerminalAt > 0 ? lastTerminalAt : JSONObject.NULL);
            o.put("rearmRemainingMs", TerminalRearmPersistence.remainingMs(now, lastTerminalAt));
            o.put("diagnosticText", "DIAGNOSTIC_ONLY".equals(item.status)
                    ? CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_TEXT : "");
            o.put("activeSignalPublicationBlocked", item.blockedByActiveSignalRecorded);
            o.put("activeSignalPublicationBlockDetail", item.activeSignalPublicationBlockDetail);
            o.put("p01Premium15m", item.premium15m);
            putMetric(o, "p01Move1Aligned", item.p01Move1Aligned);
            putMetric(o, "p01Move3Aligned", item.p01Move3Aligned);
            putMetric(o, "p01Move8Aligned", item.p01Move8Aligned);
            putMetric(o, "p01Move15Aligned", item.p01Move15Aligned);
            putMetric(o, "p01Flow30Aligned", item.p01Flow30Aligned);
            o.put("confirmedSizing", confirmedSizingJson(item));
            o.put("dynamicTradePlan", dynamicTradePlanJson(item.dynamicTradePlan));
            o.put("entryAgeSec", item.entryTriggeredAt > 0 ? Math.max(0, (now - item.entryTriggeredAt) / 1000) : -1);
            o.put("timeoutExtended", item.timeoutExtended);
            o.put("timeoutDecisionAt", item.timeoutDecisionAt);
            o.put("timeoutExtensionUntil", item.timeoutExtensionUntil);
            double unresolvedMove = terminalResolved(item)
                    ? 0.0 : favorableMoveFor(item.signal, item.lastPrice);
            putMetric(o, "unrealizedMove", unresolvedMove);
            putMetric(o, "unrealizedNetAfterFees", terminalResolved(item)
                    ? 0.0 : unresolvedMove
                            - SignalSafetyPolicies.RESEARCH_ROUND_TRIP_COST_PER_ETH);
            o.put("updates", item.updates);

            o.put("side", item.signal.side);
            o.put("family", item.signal.family);
            o.put("score", item.signal.score);
            o.put("qty", ConfirmedSignalPayload.from(item.signal).quantityForDiagnostic());
            o.put("entry", item.signal.entry);
            o.put("tp", item.signal.takeProfit);
            o.put("sl", item.signal.stopLoss);
            o.put("targetMove", item.signal.targetMove);
            o.put("stopDistance", item.signal.stopDistance);

            putMetric(o, "lastPrice", item.lastPrice);
            putMetric(o, "maxPrice", item.maxPrice);
            putMetric(o, "minPrice", item.minPrice);
            putMetric(o, "mfe", item.mfe);
            putMetric(o, "mae", item.mae);
            putMetric(o, "scenarioProgress", scenarioProgress(item));
            putMetric(o, "scenarioRisk", scenarioRisk(item));
            appendSeparatedResultFields(o, item, now);

            if (snapshot != null) {
                putMetric(o, "snapshotEth", snapshot.ethLast);
                putMetric(o, "snapshotBid", snapshot.ethBid);
                putMetric(o, "snapshotAsk", snapshot.ethAsk);
                putMetric(o, "snapshotRangePosition", snapshot.rangePosition);
                putMetric(o, "snapshotMove1", snapshot.move1);
                putMetric(o, "snapshotMove3", snapshot.move3);
                putMetric(o, "snapshotMove8", snapshot.move8);
                putMetric(o, "snapshotMove15", snapshot.move15);
                putMetric(o, "snapshotFlow15", snapshot.flow15);
                putMetric(o, "snapshotFlow30", snapshot.flow30);
                putMetric(o, "snapshotFlow60", snapshot.flow60);
                putMetric(o, "snapshotFlow120", snapshot.flow120);
                putMetric(o, "snapshotBtcMove1", snapshot.btcMove1);
                putMetric(o, "snapshotBtcMove3", snapshot.btcMove3);
                putMetric(o, "snapshotBtcMove8", snapshot.btcMove8);
                putMetric(o, "snapshotVolumeRatio", snapshot.volumeRatio);
                putMetric(o, "snapshotAntiBurstScore", snapshot.antiBurstScore);
                putMetric(o, "snapshotFlowAccel", snapshot.flowAccel);
                putMetric(o, "snapshotRoomLong", snapshot.roomLong);
                putMetric(o, "snapshotRoomShort", snapshot.roomShort);
            }

            try { o.put("signalMetrics", signalMetricsJson(item)); } catch (Exception ignored) {}
            appendPersistentJsonLine(PERSISTENT_OBSERVATIONS_FILE, o);
        } catch (Exception ignored) {}
    }

    private void recordMarketFrame(MarketSnapshot snapshot, SignalDecision decision, long now) {
        if (lastMarketFrameAt > 0 && now - lastMarketFrameAt < 1000
                && (decision == null || !decision.isSignal())) return;
        lastMarketFrameAt = now;

        MarketFrame frame = new MarketFrame(now, snapshot, decision, setupCandidateFor(snapshot));
        marketFrames.addLast(frame);
        updateMarketFrameFutureLabels(snapshot.ethLast, now);
        persistMarketFrame(frame, now, decision != null && decision.isSignal());

        while (marketFrames.size() > 7200) marketFrames.removeFirst();

        if (now - lastMarketFrameJsonRefreshAt >= 5000) {
            try {
                LAST_MARKET_FRAMES_JSON = marketFramesJson().toString();
                LAST_MARKET_SUMMARY_JSON = marketRecorderSummaryJson().toString();
                lastMarketFrameJsonRefreshAt = now;
            } catch (Exception ignored) {}
        }
    }

    private String setupCandidateFor(MarketSnapshot s) {
        return P02SleeveFilter.setupCandidateFor(s);
    }

    private void persistRuntimePlanEvent(ActivePlanState plan,String event,long now) {
        if(plan==null)return;
        try {
            JSONObject value=new JSONObject(plan.toMap());
            value.put("event",event);value.put("at",now);
            appendPersistentJsonLine(PERSISTENT_OBSERVATIONS_FILE,value);
        } catch(Exception ignored) {}
    }

    private void updateMarketFrameFutureLabels(double price, long now) {
        if (!Double.isFinite(price) || price <= 0) return;

        for (MarketFrame frame : marketFrames) {
            long ageSec = Math.max(0, (now - frame.at) / 1000);

            double longMove = price - frame.ethLast;
            double shortMove = frame.ethLast - price;

            if (ageSec <= 300) {
                frame.futureMax5 = Math.max(frame.futureMax5, price);
                frame.futureMin5 = Math.min(frame.futureMin5, price);
            }

            if (ageSec <= 600) {
                frame.futureMax10 = Math.max(frame.futureMax10, price);
                frame.futureMin10 = Math.min(frame.futureMin10, price);
            }

            if (ageSec <= 900) {
                frame.futureMax15 = Math.max(frame.futureMax15, price);
                frame.futureMin15 = Math.min(frame.futureMin15, price);

                updateLongTarget(frame, 2.00, longMove, shortMove, ageSec);
                updateLongTarget(frame, 2.20, longMove, shortMove, ageSec);
                updateLongTarget(frame, 2.80, longMove, shortMove, ageSec);
                updateLongTarget(frame, 3.50, longMove, shortMove, ageSec);

                updateShortTarget(frame, 2.00, shortMove, longMove, ageSec);
                updateShortTarget(frame, 2.20, shortMove, longMove, ageSec);
                updateShortTarget(frame, 2.80, shortMove, longMove, ageSec);
                updateShortTarget(frame, 3.50, shortMove, longMove, ageSec);
            } else {
                frame.futureClosed15 = true;
            }
        }
    }

    private void updateLongTarget(MarketFrame f, double target, double favorable, double adverse, long ageSec) {
        if (target == 2.00) {
            if (f.longHit2Sec < 0) {
                f.longAdverseBefore2 = Math.max(f.longAdverseBefore2, adverse);
                if (favorable >= target) f.longHit2Sec = ageSec;
            }
        } else if (target == 2.20) {
            if (f.longHit22Sec < 0) {
                f.longAdverseBefore22 = Math.max(f.longAdverseBefore22, adverse);
                if (favorable >= target) f.longHit22Sec = ageSec;
            }
        } else if (target == 2.80) {
            if (f.longHit28Sec < 0) {
                f.longAdverseBefore28 = Math.max(f.longAdverseBefore28, adverse);
                if (favorable >= target) f.longHit28Sec = ageSec;
            }
        } else if (target == 3.50) {
            if (f.longHit35Sec < 0) {
                f.longAdverseBefore35 = Math.max(f.longAdverseBefore35, adverse);
                if (favorable >= target) f.longHit35Sec = ageSec;
            }
        }
    }

    private void updateShortTarget(MarketFrame f, double target, double favorable, double adverse, long ageSec) {
        if (target == 2.00) {
            if (f.shortHit2Sec < 0) {
                f.shortAdverseBefore2 = Math.max(f.shortAdverseBefore2, adverse);
                if (favorable >= target) f.shortHit2Sec = ageSec;
            }
        } else if (target == 2.20) {
            if (f.shortHit22Sec < 0) {
                f.shortAdverseBefore22 = Math.max(f.shortAdverseBefore22, adverse);
                if (favorable >= target) f.shortHit22Sec = ageSec;
            }
        } else if (target == 2.80) {
            if (f.shortHit28Sec < 0) {
                f.shortAdverseBefore28 = Math.max(f.shortAdverseBefore28, adverse);
                if (favorable >= target) f.shortHit28Sec = ageSec;
            }
        } else if (target == 3.50) {
            if (f.shortHit35Sec < 0) {
                f.shortAdverseBefore35 = Math.max(f.shortAdverseBefore35, adverse);
                if (favorable >= target) f.shortHit35Sec = ageSec;
            }
        }
    }

    private JSONArray marketFramesJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (MarketFrame frame : marketFrames) arr.put(marketFrameJson(frame));
        return arr;
    }

    private String bestSide(double longMfe, double shortMfe) {
        if (!Double.isFinite(longMfe) || !Double.isFinite(shortMfe)) return "UNKNOWN";
        double diff = longMfe - shortMfe;
        if (diff > 0.35) return "LONG";
        if (diff < -0.35) return "SHORT";
        return "NEUTRAL";
    }

    private JSONObject marketFrameJson(MarketFrame f) throws Exception {
        JSONObject o = new JSONObject();
        o.put("at", f.at);
        putMetric(o, "eth", f.ethLast);
        putMetric(o, "bid", f.ethBid);
        putMetric(o, "ask", f.ethAsk);
        putMetric(o, "spread", f.spread);
        putMetric(o, "btc", f.btcLast);
        putMetric(o, "avgRange20", f.avgRange20);
        putMetric(o, "avgVolume20", f.avgVolume20);
        putMetric(o, "lastVolume", f.lastVolume);
        putMetric(o, "volumeRatio", f.volumeRatio);
        putMetric(o, "flowNorm", f.flowNorm);
        putMetric(o, "btcMove5", f.btcMove5);
        putMetric(o, "move1", f.move1);
        putMetric(o, "move3", f.move3);
        putMetric(o, "move8", f.move8);
        putMetric(o, "move15", f.move15);
        putMetric(o, "recentHigh", f.recentHigh);
        putMetric(o, "recentLow", f.recentLow);
        putMetric(o, "recentRange", f.recentRange);

        putMetric(o, "move1Norm", f.move1Norm);
        putMetric(o, "move3Norm", f.move3Norm);
        putMetric(o, "move8Norm", f.move8Norm);
        putMetric(o, "moveAccel13", f.moveAccel13);
        putMetric(o, "moveAccel38", f.moveAccel38);
        putMetric(o, "rangePosition", f.rangePosition);
        putMetric(o, "distanceToHigh", f.distanceToHigh);
        putMetric(o, "distanceToLow", f.distanceToLow);
        putMetric(o, "roomLong", f.roomLong);
        putMetric(o, "roomShort", f.roomShort);
        putMetric(o, "pullbackFromHigh", f.pullbackFromHigh);
        putMetric(o, "pullbackFromLow", f.pullbackFromLow);
        putMetric(o, "flow15", f.flow15);
        putMetric(o, "flow30", f.flow30);
        putMetric(o, "flow60", f.flow60);
        putMetric(o, "flow120", f.flow120);
        putMetric(o, "deltaFlow15_60", f.deltaFlow15_60);
        putMetric(o, "deltaFlow30_120", f.deltaFlow30_120);
        putMetric(o, "flowAccel", f.flowAccel);
        putMetric(o, "btcMove1", f.btcMove1);
        putMetric(o, "btcMove3", f.btcMove3);
        putMetric(o, "btcMove8", f.btcMove8);
        putMetric(o, "btcAccel1_5", f.btcAccel1_5);
        putMetric(o, "btcAccel3_8", f.btcAccel3_8);
        putMetric(o, "breakoutHighDistance", f.breakoutHighDistance);
        putMetric(o, "breakoutLowDistance", f.breakoutLowDistance);
        putMetric(o, "antiBurstScore", f.antiBurstScore);

        putMetric(o, "longMfe5", f.futureMax5 - f.ethLast);
        putMetric(o, "shortMfe5", f.ethLast - f.futureMin5);
        putMetric(o, "longMfe10", f.futureMax10 - f.ethLast);
        putMetric(o, "shortMfe10", f.ethLast - f.futureMin10);
        putMetric(o, "longMfe15", f.futureMax15 - f.ethLast);
        putMetric(o, "shortMfe15", f.ethLast - f.futureMin15);
        o.put("futureClosed15", f.futureClosed15);

        o.put("bestSide5", bestSide(f.futureMax5 - f.ethLast, f.ethLast - f.futureMin5));
        o.put("bestSide10", bestSide(f.futureMax10 - f.ethLast, f.ethLast - f.futureMin10));
        o.put("bestSide15", bestSide(f.futureMax15 - f.ethLast, f.ethLast - f.futureMin15));

        o.put("longHit2Sec", f.longHit2Sec);
        o.put("longHit22Sec", f.longHit22Sec);
        o.put("longHit28Sec", f.longHit28Sec);
        o.put("longHit35Sec", f.longHit35Sec);
        o.put("shortHit2Sec", f.shortHit2Sec);
        o.put("shortHit22Sec", f.shortHit22Sec);
        o.put("shortHit28Sec", f.shortHit28Sec);
        o.put("shortHit35Sec", f.shortHit35Sec);

        putMetric(o, "longAdverseBefore2", f.longAdverseBefore2);
        putMetric(o, "longAdverseBefore22", f.longAdverseBefore22);
        putMetric(o, "longAdverseBefore28", f.longAdverseBefore28);
        putMetric(o, "longAdverseBefore35", f.longAdverseBefore35);
        putMetric(o, "shortAdverseBefore2", f.shortAdverseBefore2);
        putMetric(o, "shortAdverseBefore22", f.shortAdverseBefore22);
        putMetric(o, "shortAdverseBefore28", f.shortAdverseBefore28);
        putMetric(o, "shortAdverseBefore35", f.shortAdverseBefore35);

        o.put("oracleLongClean28", f.longHit28Sec >= 0 && f.longAdverseBefore28 <= 1.35);
        o.put("oracleShortClean28", f.shortHit28Sec >= 0 && f.shortAdverseBefore28 <= 1.35);

        o.put("learnedCandidateSide", f.learnedCandidateSide);
        o.put("learnedCandidateType", f.learnedCandidateType);
        o.put("learnedCandidateScore", f.learnedCandidateScore);
        putMetric(o, "learnedOppositeMove8", f.learnedOppositeMove8);
        putMetric(o, "learnedDirectionalMove3", f.learnedDirectionalMove3);
        putMetric(o, "learnedBtcDir", f.learnedBtcDir);
        putMetric(o, "learnedRecentRangeRatio", f.learnedRecentRangeRatio);

        o.put("hypothesisPrimarySide", f.hypothesisPrimarySide);
        o.put("hypothesisPrimaryType", f.hypothesisPrimaryType);
        o.put("hypothesisPrimaryScore", f.hypothesisPrimaryScore);

        o.put("hypEngineInverseSide", f.hypEngineInverseSide);
        o.put("hypEngineInverseScore", f.hypEngineInverseScore);
        o.put("hypC1InverseSide", f.hypC1InverseSide);
        o.put("hypC1InverseScore", f.hypC1InverseScore);
        o.put("hypC2InverseSide", f.hypC2InverseSide);
        o.put("hypC2InverseScore", f.hypC2InverseScore);
        o.put("hypRangeFadeSide", f.hypRangeFadeSide);
        o.put("hypRangeFadeScore", f.hypRangeFadeScore);
        o.put("hypMove1ReversalSide", f.hypMove1ReversalSide);
        o.put("hypMove1ReversalScore", f.hypMove1ReversalScore);
        o.put("hypContinuationSide", f.hypContinuationSide);
        o.put("hypContinuationScore", f.hypContinuationScore);

        o.put("setupCandidate", f.setupCandidate);
        o.put("decision", f.decision);
        o.put("decisionCode", f.decisionCode);
        o.put("decisionText", f.decisionText);
        o.put("isSignal", f.isSignal);
        o.put("side", f.side);
        o.put("family", f.family);
        o.put("score", f.score);
        putMetric(o, "entry", f.entry);
        putMetric(o, "tp", f.tp);
        putMetric(o, "sl", f.sl);
        putMetric(o, "targetMove", f.targetMove);
        putMetric(o, "stopDistance", f.stopDistance);
        o.put("qty", f.qty);
        return o;
    }

    private JSONObject marketRecorderSummaryJson() throws Exception {
        JSONObject o = new JSONObject();
        long oldest = 0, newest = 0;
        int frames = 0, signals = 0;
        int c1Long = 0, c1Short = 0, c2Long = 0, c2Short = 0;

        for (MarketFrame f : marketFrames) {
            frames++;
            if (oldest == 0) oldest = f.at;
            newest = f.at;
            if (f.isSignal) signals++;
            if ("C1_LONG".equals(f.setupCandidate)) c1Long++;
            else if ("C1_SHORT".equals(f.setupCandidate)) c1Short++;
            else if ("C2_LONG".equals(f.setupCandidate)) c2Long++;
            else if ("C2_SHORT".equals(f.setupCandidate)) c2Short++;
        }

        o.put("mode", "PLAYBACK_LAB");
        o.put("frames", frames);
        o.put("signals", signals);
        o.put("oldestAt", oldest);
        o.put("newestAt", newest);
        o.put("durationSec", oldest > 0 && newest > oldest ? (newest - oldest) / 1000 : 0);
        o.put("maxStoredFrames", 7200);
        o.put("c1LongCandidates", c1Long);
        o.put("c1ShortCandidates", c1Short);
        o.put("c2LongCandidates", c2Long);
        o.put("c2ShortCandidates", c2Short);
        o.put("purpose", "Full market playback: find missed LONG/SHORT opportunities and false entries");
        return o;
    }

    private ObservedSignal recordObservedSignal(SignalDecision decision, MarketSnapshot snapshot,
                                                long now, String replayRiskReasonCode,
                                                String replayRiskDetail,
                                                String candidateSignature) {
        double price = Double.isFinite(snapshot.ethLast) && snapshot.ethLast > 0 ? snapshot.ethLast : decision.entry;
        ObservedSignal item = new ObservedSignal(++observedSignalId, now, decision, price, snapshot);
        item.candidateSignature = candidateSignature == null ? "" : candidateSignature;
        item.replayRiskReasonCode = replayRiskReasonCode == null ? "" : replayRiskReasonCode;
        item.replayRiskDetail = replayRiskDetail == null ? "" : replayRiskDetail;
        item.replayRiskBlocking = false;
        observedSignals.addLast(item);
        trimObservedSignals();
        persistObservedSignalEvent(item, "CREATED", snapshot, now);
        return item;
    }

    private void trimObservedSignals() {
        while (observedSignals.size() > 200) {
            boolean removed = false;
            Iterator<ObservedSignal> iterator = observedSignals.iterator();
            while (iterator.hasNext()) {
                ObservedSignal candidate = iterator.next();
                if (SignalSafetyPolicies.blocksNewFinalSignal(candidate.status,
                        candidate.entryTriggered, candidate.finalConfirmedAt)) continue;
                iterator.remove();
                pendingCandidateIndex.remove(candidate.candidateSignature);
                removed = true;
                break;
            }
            if (!removed) return;
        }
    }

    private void markFirstEntryTouch(ObservedSignal item, MarketSnapshot snapshot, long now) {
        if (item == null || snapshot == null || item.firstEntryTouchAt > 0) return;
        item.firstEntryTouchAt = now;
        item.firstEntryTouchPrice = snapshot.ethLast;
        item.firstEntryTouchBid = snapshot.ethBid;
        item.firstEntryTouchAsk = snapshot.ethAsk;
        persistObservedSignalEvent(item, "FIRST_ENTRY_TOUCH", snapshot, now);
    }

    private static void observeAdverseExcursion60(ObservedSignal item, MarketSnapshot snapshot,
                                                   long now) {
        if (item == null || item.signal == null || snapshot == null
                || now - item.createdAt > 60_000L) return;
        item.adverseExcursion60 = DynamicTradePlan.updateAdverseExcursion60(
                item.signal.side, item.signal.entry, snapshot.ethBid, snapshot.ethAsk,
                item.adverseExcursion60);
        item.prefillFavorableExcursion = DynamicTradePlan.updateFavorableExcursionBeforeFill(
                item.signal.side, item.signal.entry, snapshot.ethBid, snapshot.ethAsk,
                item.prefillFavorableExcursion);
    }

    private boolean attemptCandidateFill(ObservedSignal item, MarketSnapshot snapshot,
                                         long now, boolean feedFresh) {
        if (item == null || snapshot == null || !"LIMIT_PENDING".equals(item.status)) return false;
        return confirmCandidateAtFill(item, snapshot, now, feedFresh);
    }

    private void updateObservedSignals(MarketSnapshot snapshot, long now, boolean feedFresh) {
        for (ObservedSignal item : observedSignals) {
            if (!"ACTIVE".equals(item.status) && !"LIMIT_PENDING".equals(item.status)
                    && !"DIAGNOSTIC_ONLY".equals(item.status)) continue;

            double price = snapshot.ethLast;
            if (!Double.isFinite(price) || price <= 0) continue;

            item.lastPrice = price;
            item.lastUpdateAt = now;
            item.updates++;
            item.maxPrice = Math.max(item.maxPrice, price);
            item.minPrice = Math.min(item.minPrice, price);
            if (!"ACTIVE".equals(item.status)) observeAdverseExcursion60(item, snapshot, now);

            if ("DIAGNOSTIC_ONLY".equals(item.status)) continue;

            if ("LIMIT_PENDING".equals(item.status)) {
                item.normalizedMetrics = NormalizedSignalMetrics.calculate(
                        item.signal.side, item.signal, snapshot, item.adverseExcursion60);
                if (feedFresh && CandidateLifecycle.targetReachedBeforeConfirmedFill(item.signal, snapshot)) {
                    resetEarlyP01Stability(item, CandidateLifecycle.TARGET_BEFORE_FILL);
                    if (item.departureAt <= 0) item.departureAt = now;
                    item.lastConfirmationCode = CandidateLifecycle.TARGET_BEFORE_FILL;
                    closeObservedSignal(item, "MISSED_NO_FILL", snapshot, now);
                    pendingCandidateIndex.remove(item.candidateSignature);
                    candidateTombstones.markMissed(item.candidateSignature);
                    persistObservedSignalEvent(item, CandidateLifecycle.TARGET_BEFORE_FILL,
                            snapshot, now);
                    continue;
                }

                long candidateAge = Math.max(0L, now - item.createdAt);
                long maximumAge = CandidateLifecycle.SLEEVE_P02.equals(item.sleeve)
                        ? CandidateLifecycle.P02_MAX_PENDING_AGE_MS
                        : CandidateLifecycle.MAX_PENDING_AGE_MS;
                if (candidateAge > maximumAge) {
                    resetEarlyP01Stability(item, P01EarlyConfirmation.REJECTED);
                    item.lastConfirmationCode = CandidateLifecycle.SLEEVE_P02.equals(item.sleeve)
                            ? P02SleeveFilter.EXPIRED : P01SleeveFilter.AGE_EXPIRED;
                    closeObservedSignal(item, "PENDING_CANDIDATE_EXPIRED", snapshot, now);
                    pendingCandidateIndex.remove(item.candidateSignature);
                    persistObservedSignalEvent(item, item.lastConfirmationCode, snapshot, now);
                    continue;
                }
                if (CandidateLifecycle.SLEEVE_P01.equals(item.sleeve)
                        && candidateAge < CandidateLifecycle.MIN_CONFIRMATION_AGE_MS) {
                    if (!processEarlyP01Candidate(item, now)) continue;
                } else {
                    boolean silentWindow = CandidateLifecycle.SLEEVE_P02.equals(item.sleeve)
                        ? candidateAge <= CandidateLifecycle.P02_MIN_CONFIRMATION_AGE_MS
                        : candidateAge < CandidateLifecycle.MIN_CONFIRMATION_AGE_MS;
                    if (silentWindow) {
                        item.lastConfirmationCode = CandidateLifecycle.SLEEVE_P02.equals(item.sleeve)
                                ? P02SleeveFilter.SILENT_WINDOW
                                : CandidateLifecycle.SILENT_CONFIRMATION_WINDOW;
                        continue;
                    }
                    if (CandidateLifecycle.SLEEVE_P01.equals(item.sleeve)) {
                        resetEarlyP01Stability(item,
                                CandidateLifecycle.SILENT_CONFIRMATION_WINDOW);
                    }
                    if (!feedFresh || !CandidateLifecycle.currentlyExecutable(item.signal, snapshot)) {
                        item.lastConfirmationCode = feedFresh
                                ? CandidateLifecycle.LIMIT_NOT_EXECUTABLE
                                : ContinuationConfirmation.P01_STALE_REJECT;
                        continue;
                    }

                    // Rebuild after the normal window and a current LIMIT touch.
                    MarketSnapshot fillSnapshot = buildSnapshot(now);
                    if (CandidateLifecycle.currentlyExecutable(item.signal, fillSnapshot)) {
                        markFirstEntryTouch(item, fillSnapshot, now);
                        if (!attemptCandidateFill(item, fillSnapshot, now,
                                ethExecutionFeedFresh(now))) continue;
                    } else {
                        item.lastConfirmationCode = CandidateLifecycle.LIMIT_NOT_EXECUTABLE;
                        continue;
                    }
                }
            }

            if ("LONG".equals(item.signal.side)) {
                item.mfe = Math.max(0, item.maxPrice - item.signal.entry);
                item.mae = Math.max(0, item.signal.entry - item.minPrice);
            } else if ("SHORT".equals(item.signal.side)) {
                item.mfe = Math.max(0, item.signal.entry - item.minPrice);
                item.mae = Math.max(0, item.maxPrice - item.signal.entry);
            }

            String status = SignalSafetyPolicies.liveStatusUntilTpOrSl(
                    marketStatusForSignal(item.signal, snapshot));
            if ("ACTIVE".equals(status)
                    && (lastActivePlanPersistAt <= 0 || now - lastActivePlanPersistAt >= 15_000L)) {
                if (persistActiveFinalPlan(item, lastP01ConfirmedAt, snapshot)) {
                    lastActivePlanPersistAt = now;
                }
            }
            if (SignalSafetyPolicies.isTerminalStatus(status)) {
                closeObservedSignal(item, status, snapshot, now);
                p02SetupTracker.reset();
                lastTerminalAt = now;
                if (!terminalRearmPersistence.save(MarketProfile.ETH_SYMBOL,lastTerminalAt)) {
                    signalEngine.recordExternalDiagnostic(now,
                            "V2330_TERMINAL_REARM_PERSIST_FAILED",
                            "Terminal timestamp could not be persisted atomically.");
                }
                persistObservedSignalEvent(item, status, snapshot, now);
                notifyObservationStatus(item, status);
                if (!activePlanPersistence.clearForTerminal(MarketProfile.ETH_SYMBOL,status)) {
                    signalEngine.recordExternalDiagnostic(now,
                            ActivePlanPersistence.PERSIST_FAILED,
                            "Plan terminé, mais suppression de l’état actif persistant impossible.");
                } else {
                    lastActivePlanPersistAt = 0;
                }
            }
        }
    }

    private boolean confirmCandidateAtFill(ObservedSignal item, MarketSnapshot snapshot,
                                           long now, boolean feedFresh) {
        if (hasActiveFinalSignal(item)) {
            item.lastConfirmationCode = SignalSafetyPolicies.blockedCandidateReasonCode();
            item.activeSignalPublicationBlockDetail =
                    SignalSafetyPolicies.blockedCandidateDiagnosticText();
            if (!item.blockedByActiveSignalRecorded) {
                item.blockedByActiveSignalRecorded = true;
                persistObservedSignalEvent(item,
                        SignalSafetyPolicies.blockedCandidateReasonCode(), snapshot, now);
            }
            return false;
        }
        if (!TerminalRearmPersistence.allowsNewCandidate(now, lastTerminalAt)) {
            item.lastConfirmationCode = "V2330_TERMINAL_REARM_ACTIVE";
            item.activeSignalPublicationBlockDetail =
                    "Final publication blocked during the three-minute post-terminal rearm.";
            return false;
        }

        SignalDecision candidate = item.signal;
        boolean continuation = ContinuationConfirmation.requiresP01(candidate.family);
        item.confirmationAt = now;
        item.executableAtConfirmation = CandidateLifecycle.currentlyExecutable(candidate, snapshot);
        item.finalConfirmationBid = snapshot.ethBid;
        item.finalConfirmationAsk = snapshot.ethAsk;
        item.finalConfirmationA = Math.max(0.35, snapshot.avgRange20);
        item.finalConfirmationR = "LONG".equals(candidate.side)
                ? Math.max(0.0, snapshot.recentHigh - candidate.entry)
                : Math.max(0.0, candidate.entry - snapshot.recentLow);
        TrendRegime60.Result trendRegime = CandidateLifecycle.SLEEVE_P02.equals(item.sleeve)
                ? trendRegime60(item, snapshot, now) : null;
        if (trendRegime != null) item.trendRegime60 = trendRegime;
        CandidateLifecycle.FillResult fill = CandidateLifecycle.processPendingCandidate(
                candidate, snapshot, feedFresh, item.createdAt, now,
                pendingProgressBeforeFill(item), item.adverseExcursion60,
                !item.replayRiskReasonCode.isEmpty(), item.sleeve, trendRegime);
        return applyCandidateFillResult(item, candidate, snapshot, now, fill);
    }

    private boolean processEarlyP01Candidate(ObservedSignal item, long now) {
        MarketSnapshot current = buildSnapshot(now);
        boolean feedFresh = ethExecutionFeedFresh(now);
        boolean noActivePlan = !hasActiveFinalSignal(item);
        boolean rearmComplete = TerminalRearmPersistence.allowsNewCandidate(now, lastTerminalAt);
        boolean executable = CandidateLifecycle.currentlyExecutable(item.signal, current);
        if (!feedFresh || !noActivePlan || !rearmComplete || !executable
                || current.now != now) {
            resetEarlyP01Stability(item, P01EarlyConfirmation.REJECTED);
        }
        if (executable) markFirstEntryTouch(item, current, now);

        CandidateLifecycle.FillResult quality = CandidateLifecycle.processEarlyP01Candidate(
                item.signal, current, feedFresh, item.createdAt, now,
                pendingProgressBeforeFill(item), item.adverseExcursion60,
                !item.replayRiskReasonCode.isEmpty(), noActivePlan, rearmComplete,
                item.plannedEntry, false);
        updateEarlyP01Diagnostics(item, current, now, quality);
        P01EarlyConfirmation.StabilityResult stability = P01EarlyConfirmation.advance(
                now, item.p01EarlyQualitySince, item.p01EarlyQualityMode, quality.earlyP01);
        item.p01EarlyQualitySince = stability.qualitySince;
        item.p01EarlyQualityMode = stability.mode;
        item.p01EarlyStabilityMs = stability.stabilityMs;
        item.p01EarlyLastReasonCode = stability.reasonCode;
        item.p01EarlyEligible = quality.earlyP01 != null && quality.earlyP01.accepted;
        if (!stability.confirmed) {
            item.lastConfirmationCode = stability.reasonCode;
            return false;
        }

        CandidateLifecycle.FillResult confirmed = CandidateLifecycle.processEarlyP01Candidate(
                item.signal, current, feedFresh, item.createdAt, now,
                pendingProgressBeforeFill(item), item.adverseExcursion60,
                !item.replayRiskReasonCode.isEmpty(), noActivePlan, rearmComplete,
                item.plannedEntry, true);
        updateEarlyP01Diagnostics(item, current, now, confirmed);
        if (!confirmed.confirmed) {
            resetEarlyP01Stability(item, confirmed.reasonCode);
            item.lastConfirmationCode = confirmed.reasonCode;
            return false;
        }
        item.p01EarlyAgeAtConfirmationMs = now - item.createdAt;
        item.p01EarlyLastReasonCode = P01EarlyConfirmation.CONFIRMED;
        return applyCandidateFillResult(item, item.signal, current, now, confirmed);
    }

    private static void updateEarlyP01Diagnostics(ObservedSignal item, MarketSnapshot snapshot,
                                                  long now,
                                                  CandidateLifecycle.FillResult fill) {
        if (fill.normalizedMetrics != null) item.normalizedMetrics = fill.normalizedMetrics;
        item.p01SleeveFilter = fill.p01SleeveFilter;
        item.confirmedSizing = fill.sizing;
        item.dynamicTradePlan = fill.dynamicPlan;
        item.p01EarlyTriggerBid = snapshot.ethBid;
        item.p01EarlyTriggerAsk = snapshot.ethAsk;
        item.p01EarlyQuoteDistance = "LONG".equals(item.signal.side)
                ? snapshot.ethAsk - item.plannedEntry : item.plannedEntry - snapshot.ethBid;
        item.p01EarlySecondsSaved = Math.max(0.0,
                (item.createdAt + CandidateLifecycle.MIN_CONFIRMATION_AGE_MS - now) / 1000.0);
        if (fill.earlyP01 != null) {
            item.p01EarlyEligible = fill.earlyP01.accepted;
            item.p01EarlyLastReasonCode = fill.earlyP01.reasonCode;
        }
    }

    private static void resetEarlyP01Stability(ObservedSignal item, String reasonCode) {
        if (item == null) return;
        item.p01EarlyQualitySince = 0L;
        item.p01EarlyQualityMode = "";
        item.p01EarlyStabilityMs = 0L;
        item.p01EarlyEligible = false;
        item.p01EarlyLastReasonCode = reasonCode == null
                ? P01EarlyConfirmation.REJECTED : reasonCode;
    }

    private boolean applyCandidateFillResult(ObservedSignal item, SignalDecision candidate,
                                             MarketSnapshot snapshot, long now,
                                             CandidateLifecycle.FillResult fill) {
        boolean continuation = ContinuationConfirmation.requiresP01(candidate.family);
        ContinuationConfirmation.Result confirmation = fill.continuationConfirmation;
        String previousConfirmationCode = item.lastConfirmationCode;
        item.lastConfirmationCode = fill.reasonCode;
        item.confirmedSizing = fill.sizing;
        item.dynamicTradePlan = fill.dynamicPlan;
        if (fill.normalizedMetrics != null) item.normalizedMetrics = fill.normalizedMetrics;
        item.p01SleeveFilter = fill.p01SleeveFilter;
        item.p02SleeveFilter = fill.p02SleeveFilter;
        if (fill.trendRegime60 != null) item.trendRegime60 = fill.trendRegime60;
        item.p02Mode = item.trendRegime60 == null ? "" : item.trendRegime60.mode;
        if (confirmation != null) {
            item.p01Move1Aligned = confirmation.move1Aligned;
            item.p01Move3Aligned = confirmation.move3Aligned;
            item.p01Move8Aligned = confirmation.move8Aligned;
            item.p01Move15Aligned = confirmation.move15Aligned;
            item.p01Flow30Aligned = confirmation.flow30Aligned;
        }
        if (!fill.confirmed) {
            if (continuation) {
                item.status = "LIMIT_PENDING";
                item.entryRevalidationCode = fill.reasonCode;
                if (!fill.reasonCode.equals(previousConfirmationCode)) {
                    persistObservedSignalEvent(item,
                            "P01_REEVALUATION_" + fill.reasonCode, snapshot, now);
                }
                return false;
            }
            item.status = "ENTRY_REVALIDATION_REJECTED";
            item.entryRevalidationCode = fill.reasonCode;
            item.closedAt = now;
            pendingCandidateIndex.remove(item.candidateSignature);
            persistObservedSignalEvent(item,
                    "ENTRY_REVALIDATION_REJECTED_" + fill.reasonCode, snapshot, now);
            return false;
        }

        SignalDecision published = fill.publishedSignal;
        item.signal = published;
        item.status = "ACTIVE";
        item.entryTriggered = true;
        item.entryTriggeredAt = now;
        item.entryTriggerPrice = snapshot.ethLast;
        item.simulatedFillAt = now;
        item.simulatedFillPrice = theoreticalFillPrice(published, snapshot);
        item.finalConfirmedAt = now;
        item.premium15m = fill.premium15m;
        item.maxPrice = snapshot.ethLast;
        item.minPrice = snapshot.ethLast;
        item.mfe = 0;
        item.mae = 0;
        item.notificationSignature = SignalSafetyPolicies.deterministicSignature(
                published, item.createdAt / 60_000L);
        item.notificationId = confirmedNotificationId(item.notificationSignature);

        long confirmedP01At = continuation ? now : lastP01ConfirmedAt;
        if (!persistActiveFinalPlan(item, confirmedP01At, snapshot)) {
            item.signal = candidate;
            item.status = "LIMIT_PENDING";
            item.entryTriggered = false;
            item.entryTriggeredAt = 0;
            item.entryTriggerPrice = Double.NaN;
            item.simulatedFillAt = 0;
            item.simulatedFillPrice = Double.NaN;
            item.finalConfirmedAt = 0;
            item.notificationSignature = "";
            item.notificationId = 0;
            item.alertSent = false;
            item.lastConfirmationCode = ActivePlanPersistence.PERSIST_FAILED;
            item.confirmedSizing = null;
            item.dynamicTradePlan = null;
            resetEarlyP01Stability(item, ActivePlanPersistence.PERSIST_FAILED);
            persistObservedSignalEvent(item, ActivePlanPersistence.PERSIST_FAILED, snapshot, now);
            signalEngine.recordExternalDiagnostic(now, ActivePlanPersistence.PERSIST_FAILED,
                    "Signal final non publié : persistance atomique du plan actif impossible.");
            return false;
        }
        lastActivePlanPersistAt = now;
        pendingCandidateIndex.remove(item.candidateSignature);

        lastSignal = published;
        lastSignalAt = now;
        // P01 cooldown starts only here, never at candidate creation or rejection.
        lastP01ConfirmedAt = confirmedP01At;
        lastDecision = published;

        persistObservedSignalEvent(item, ActivePlanPersistence.PERSISTED, snapshot, now);
        persistObservedSignalEvent(item, fill.reasonCode, snapshot, now);
        notifyObservationSignal(item);
        requestPostPublicationAiAdvisory(snapshot, published);
        broadcastStatus("signal_final_confirmed", fill.reasonCode);
        return true;
    }

    private boolean hasActiveFinalSignal(ObservedSignal candidate) {
        for (ObservedSignal item : observedSignals) {
            if (item == null || item == candidate) continue;
            if (SignalSafetyPolicies.blocksNewFinalSignal(
                    item.status, item.entryTriggered, item.finalConfirmedAt)) return true;
        }
        return false;
    }

    private ObservedSignal activeFinalSignal() {
        for (ObservedSignal item : observedSignals) {
            if (item != null && SignalSafetyPolicies.blocksNewFinalSignal(
                    item.status, item.entryTriggered, item.finalConfirmedAt)) return item;
        }
        return null;
    }

    private TrendRegime60.Result trendRegime60(ObservedSignal item,
                                               MarketSnapshot snapshot, long now) {
        List<TrendRegime60.MinuteClose> minuteCloses = new ArrayList<>();
        for (Candle candle : ethCandles) {
            minuteCloses.add(new TrendRegime60.MinuteClose(candle.openTime, candle.close));
        }
        List<TrendRegime60.Point> points = TrendRegime60.pointsFromMinuteCloses(
                minuteCloses, now, snapshot.ethLast);
        NormalizedSignalMetrics.Result metrics = NormalizedSignalMetrics.calculate(
                item.signal.side, item.signal, snapshot, item.adverseExcursion60);
        return TrendRegime60.evaluate(item.signal.side, Math.max(0.35, snapshot.avgRange20),
                metrics, points, now);
    }

    private static double theoreticalFillPrice(SignalDecision signal, MarketSnapshot snapshot) {
        if (signal == null || snapshot == null) return Double.NaN;
        if ("LONG".equals(signal.side) && snapshot.ethAsk > 0) {
            return Math.min(signal.entry, snapshot.ethAsk);
        }
        if ("SHORT".equals(signal.side) && snapshot.ethBid > 0) {
            return Math.max(signal.entry, snapshot.ethBid);
        }
        return snapshot.ethLast;
    }

    private static double pendingProgressBeforeFill(ObservedSignal item) {
        if (item == null || item.signal == null) return 0.0;
        return Math.max(0.0, item.prefillFavorableExcursion)
                / Math.max(0.10, item.signal.targetMove);
    }

    private static long observationClockStart(ObservedSignal item) {
        if (item == null) return 0;
        if (item.entryTriggered && item.entryTriggeredAt > 0) return item.entryTriggeredAt;
        return item.createdAt;
    }

    private static double favorableMoveFor(SignalDecision signal, double price) {
        if (signal == null || !Double.isFinite(price) || price <= 0) return -99.0;
        if ("LONG".equals(signal.side)) return price - signal.entry;
        if ("SHORT".equals(signal.side)) return signal.entry - price;
        return -99.0;
    }

    private boolean shouldExtendAfterRealEntryTimeout(ObservedSignal item, MarketSnapshot snapshot) {
        if (item == null || item.signal == null || snapshot == null || !item.entryTriggered) return false;
        String family = item.signal.family == null ? "" : item.signal.family;
        if (!family.contains("RANGE_FADE")) return false;
        if (hardTimeoutExtensionInvalidation(item, snapshot)) return false;

        double progress = scenarioProgress(item);
        double risk = scenarioRisk(item);
        int side = "LONG".equals(item.signal.side) ? 1 : -1;
        double move3Aligned = side * snapshot.move3;
        double move8Aligned = side * snapshot.move8;

        return SignalSafetyPolicies.shouldExtendRangeFade(risk, progress,
                move3Aligned, move8Aligned, snapshot.avgRange20);
    }

    private boolean timeoutContextStillAcceptable(ObservedSignal item, MarketSnapshot s) {
        if (item == null || item.signal == null || s == null) return false;
        int side = "LONG".equals(item.signal.side) ? 1 : "SHORT".equals(item.signal.side) ? -1 : 0;
        if (side == 0) return false;

        double avg = Math.max(0.35, s.avgRange20);
        double move1 = side * s.move1;
        double move3 = side * s.move3;
        double move8 = side * s.move8;
        double flow15 = side * s.flow15;
        double flow30 = side * s.flow30;
        double flow60 = side * s.flow60;
        double btc1 = side * s.btcMove1;
        double btc3 = side * s.btcMove3;

        boolean strongOppositeMove = move3 < -avg * 1.65 && move8 < -avg * 2.10;
        boolean strongOppositeFlow = flow30 < -0.13 && flow60 < -0.08;
        boolean strongOppositeBtc = btc1 < -0.00100 && btc3 < -0.00090;
        boolean violentMicroTurn = move1 < -avg * 0.80 && flow15 < -0.12;

        return !(strongOppositeMove && strongOppositeFlow)
                && !(strongOppositeBtc && strongOppositeFlow)
                && !(violentMicroTurn && strongOppositeFlow);
    }

    private boolean hardTimeoutExtensionInvalidation(ObservedSignal item, MarketSnapshot s) {
        if (item == null || item.signal == null || s == null) return true;

        double stop = Math.max(0.10, item.signal.stopDistance);
        double adverseNow = adverseMoveFor(item.signal, s.ethLast);
        if (adverseNow >= stop * 0.92) return true;

        int side = "LONG".equals(item.signal.side) ? 1 : "SHORT".equals(item.signal.side) ? -1 : 0;
        if (side == 0) return true;

        double avg = Math.max(0.35, s.avgRange20);
        double move1 = side * s.move1;
        double move3 = side * s.move3;
        double move8 = side * s.move8;
        double flow15 = side * s.flow15;
        double flow30 = side * s.flow30;
        double flow60 = side * s.flow60;
        double btc1 = side * s.btcMove1;
        double btc3 = side * s.btcMove3;

        boolean violentOppositeMove = move1 < -avg * 0.85
                && move3 < -avg * 1.80
                && move8 < -avg * 2.20;
        boolean violentOppositeFlow = flow15 < -0.14
                && flow30 < -0.16
                && flow60 < -0.12;
        boolean btcAlsoOpposite = btc1 < -0.00110 || btc3 < -0.00100;

        return violentOppositeMove && violentOppositeFlow && btcAlsoOpposite;
    }

    private boolean limitEntryTouched(SignalDecision signal, MarketSnapshot snapshot) {
        if (signal == null || snapshot == null) return false;
        double price = snapshot.ethLast;
        if (!Double.isFinite(price) || price <= 0) return false;

        double tolerance = Math.max(0.03, Math.max(0.35, snapshot.avgRange20) * 0.03);

        if ("LONG".equals(signal.side)) {
            return price <= signal.entry + tolerance;
        }

        if ("SHORT".equals(signal.side)) {
            return price >= signal.entry - tolerance;
        }

        return false;
    }

    private boolean targetTouchedBeforeManualFill(SignalDecision signal, MarketSnapshot snapshot) {
        if (signal == null || snapshot == null) return false;
        double price = snapshot.ethLast;
        if (!Double.isFinite(price) || price <= 0) return false;

        if ("LONG".equals(signal.side)) {
            return price >= signal.takeProfit;
        }

        if ("SHORT".equals(signal.side)) {
            return price <= signal.takeProfit;
        }

        return false;
    }

    private String marketStatusForSignal(SignalDecision signal, MarketSnapshot snapshot) {
        if (signal == null) return "NONE";

        double price = snapshot.ethLast;
        if (!Double.isFinite(price) || price <= 0) return "NO_PRICE";

        if ("LONG".equals(signal.side)) {
            if (price <= signal.stopLoss) return "SL_TOUCHED";
            if (price >= signal.takeProfit) return "TP_TOUCHED";
            return "ACTIVE";
        }

        if ("SHORT".equals(signal.side)) {
            if (price >= signal.stopLoss) return "SL_TOUCHED";
            if (price <= signal.takeProfit) return "TP_TOUCHED";
            return "ACTIVE";
        }

        return "NONE";
    }

    private boolean shouldKeepScenarioAlive(ObservedSignal item, MarketSnapshot snapshot, long now) {
        if (item == null || item.signal == null) return false;

        long age = now - item.createdAt;
        if (age < 0 || age > LIMIT_ORDER_MAX_AGE_MS) return false;

        String marketStatus = marketStatusForSignal(item.signal, snapshot);
        if ("TP_TOUCHED".equals(marketStatus) || "SL_TOUCHED".equals(marketStatus)) return false;
        if (!"ACTIVE".equals(marketStatus) && !"NO_PRICE".equals(marketStatus)) return false;

        if (hardScenarioInvalidation(item, snapshot)) return false;

        double progress = scenarioProgress(item);
        double risk = scenarioRisk(item);

        boolean almostTpClean = progress >= 0.82 && risk <= 0.50;
        boolean strongProgressClean = progress >= 0.70 && item.mfe >= 2.00 && risk <= 0.55;

        boolean midProgressStillValid = progress >= 0.55
                && item.mfe >= 1.45
                && risk <= 0.82
                && directionalContextStillAcceptable(item, snapshot);

        boolean limitStillClean = risk <= 0.45 && directionalContextStillAcceptable(item, snapshot);

        return almostTpClean || strongProgressClean || midProgressStillValid || limitStillClean;
    }

    private boolean directionalContextStillAcceptable(ObservedSignal item, MarketSnapshot snapshot) {
        if (item == null || item.signal == null || snapshot == null) return false;

        long age = Math.max(0, System.currentTimeMillis() - item.createdAt);
        double avg = Math.max(0.35, snapshot.avgRange20);
        double adverseNow = adverseMoveFor(item.signal, snapshot.ethLast);
        double stop = Math.max(0.10, item.signal.stopDistance);

        // Très important : pendant les premières minutes, un ordre LIMIT peut flotter.
        // On ne l'annule pas sur un simple push temporaire tant que le SL n'est pas touché.
        if (age <= 180_000L && adverseNow <= stop * 0.82) {
            return true;
        }

        if ("LONG".equals(item.signal.side)) {
            boolean strongOppositeMove = snapshot.move3 < -avg * 1.65 && snapshot.move8 < -avg * 2.10;
            boolean strongOppositeFlow = snapshot.flow30 < -0.13 && snapshot.flow60 < -0.08;
            boolean strongOppositeBtc = snapshot.btcMove1 < -0.00100 && snapshot.btcMove3 < -0.00090;
            boolean noRoom = snapshot.roomLong < 0.45 && snapshot.rangePosition > 0.92;

            return !(strongOppositeMove && strongOppositeFlow)
                    && !(strongOppositeBtc && strongOppositeFlow)
                    && !noRoom;
        }

        if ("SHORT".equals(item.signal.side)) {
            boolean strongOppositeMove = snapshot.move3 > avg * 1.65 && snapshot.move8 > avg * 2.10;
            boolean strongOppositeFlow = snapshot.flow30 > 0.13 && snapshot.flow60 > 0.08;
            boolean strongOppositeBtc = snapshot.btcMove1 > 0.00100 && snapshot.btcMove3 > 0.00090;
            boolean noRoom = snapshot.roomShort < 0.45 && snapshot.rangePosition < 0.08;

            return !(strongOppositeMove && strongOppositeFlow)
                    && !(strongOppositeBtc && strongOppositeFlow)
                    && !noRoom;
        }

        return false;
    }

    private boolean shouldBlockByScenarioMemory(SignalDecision candidate, MarketSnapshot snapshot, long now) {
        if (candidate == null || !candidate.isSignal()) return false;

        for (ObservedSignal item : observedSignals) {
            if (item == null || item.signal == null) continue;
            if (!SignalSafetyPolicies.isOpenActiveRisk(item.status, item.entryTriggered)) continue;
            if (!oppositeSide(item.signal.side, candidate.side)) continue;

            if (shouldKeepScenarioAlive(item, snapshot, now)) {
                return true;
            }
        }

        return false;
    }

    private boolean isLastScenarioProtected(MarketSnapshot snapshot, long now) {
        if (lastSignalAt <= 0) return false;

        for (ObservedSignal item : observedSignals) {
            if (item == null || item.signal == null) continue;
            if (item.createdAt == lastSignalAt) {
                return shouldKeepScenarioAlive(item, snapshot, now);
            }
        }

        return false;
    }

    private boolean hardScenarioInvalidation(ObservedSignal item, MarketSnapshot snapshot) {
        if (item == null || item.signal == null || snapshot == null) return true;

        long age = Math.max(0, System.currentTimeMillis() - item.createdAt);
        double avg = Math.max(0.35, snapshot.avgRange20);
        double adverseNow = adverseMoveFor(item.signal, snapshot.ethLast);
        double stop = Math.max(0.10, item.signal.stopDistance);
        String family = item.signal.family == null ? "" : item.signal.family;

        // Le SL réel reste la vraie invalidation principale.
        // Ici on évite l'annulation nerveuse avant SL.
        if (adverseNow >= stop * 0.96) return true;

        // Pour un RANGE_FADE, les premiers pushs contre le signal sont normaux.
        // Exemple corrigé : SHORT invalidé à ~57s avec seulement ~0.90$ contre lui,
        // puis TP touché ensuite. Cette règle doit rester valide.
        if (family.contains("RANGE_FADE") && age <= 240_000L && adverseNow <= stop * 0.85) {
            return false;
        }

        // Même hors RANGE_FADE, on ne tue pas un ordre LIMIT trop vite.
        if (age <= 150_000L && adverseNow <= stop * 0.78) {
            return false;
        }

        if (failedAfterPartialProgress(item, snapshot)) return true;

        if ("LONG".equals(item.signal.side)) {
            boolean strongOppositeMove = snapshot.move3 < -avg * 1.75 && snapshot.move8 < -avg * 2.25;
            boolean strongOppositeFlow = snapshot.flow30 < -0.14 && snapshot.flow60 < -0.10;
            boolean btcOpposite = snapshot.btcMove1 < -0.00110 && snapshot.btcMove3 < -0.00100;
            boolean noRecovery = snapshot.move1 < -avg * 0.75 && snapshot.flow15 < -0.10;

            return (strongOppositeMove && strongOppositeFlow && noRecovery)
                    || (btcOpposite && strongOppositeFlow && age > 120_000L);
        }

        if ("SHORT".equals(item.signal.side)) {
            boolean strongOppositeMove = snapshot.move3 > avg * 1.75 && snapshot.move8 > avg * 2.25;
            boolean strongOppositeFlow = snapshot.flow30 > 0.14 && snapshot.flow60 > 0.10;
            boolean btcOpposite = snapshot.btcMove1 > 0.00110 && snapshot.btcMove3 > 0.00100;
            boolean noRecovery = snapshot.move1 > avg * 0.75 && snapshot.flow15 > 0.10;

            return (strongOppositeMove && strongOppositeFlow && noRecovery)
                    || (btcOpposite && strongOppositeFlow && age > 120_000L);
        }

        return true;
    }

    private boolean failedAfterPartialProgress(ObservedSignal item, MarketSnapshot snapshot) {
        if (item == null || item.signal == null || snapshot == null) return false;

        double progress = scenarioProgress(item);
        double adverseNow = adverseMoveFor(item.signal, snapshot.ethLast);
        double stop = Math.max(0.10, item.signal.stopDistance);
        double avg = Math.max(0.35, snapshot.avgRange20);

        boolean hadRealProgress = progress >= 0.35 && item.mfe >= 0.85;
        boolean gaveBackHard = adverseNow >= stop * 0.35;
        if (!hadRealProgress || !gaveBackHard) return false;

        if ("SHORT".equals(item.signal.side)) {
            boolean marketTurnedLong = snapshot.move1 > avg * 0.55
                    && snapshot.move3 > avg * 1.25
                    && (snapshot.flow30 > 0.10 || snapshot.flow60 > 0.10 || snapshot.btcMove3 > 0.00020 || snapshot.rangePosition > 1.00);
            return marketTurnedLong;
        }

        if ("LONG".equals(item.signal.side)) {
            boolean marketTurnedShort = snapshot.move1 < -avg * 0.55
                    && snapshot.move3 < -avg * 1.25
                    && (snapshot.flow30 < -0.10 || snapshot.flow60 < -0.10 || snapshot.btcMove3 < -0.00020 || snapshot.rangePosition < 0.00);
            return marketTurnedShort;
        }

        return false;
    }

    private static boolean oppositeSide(String a, String b) {
        return ("LONG".equals(a) && "SHORT".equals(b)) || ("SHORT".equals(a) && "LONG".equals(b));
    }

    private static double scenarioProgress(ObservedSignal item) {
        if (item == null || item.signal == null) return 0.0;
        double target = Math.max(0.10, item.signal.targetMove);
        return item.mfe / target;
    }

    private static double scenarioRisk(ObservedSignal item) {
        if (item == null || item.signal == null) return 99.0;
        double stop = Math.max(0.10, item.signal.stopDistance);
        return item.mae / stop;
    }

    private static void closeObservedSignal(ObservedSignal item, String status,
                                            MarketSnapshot snapshot, long now) {
        if (item == null) return;
        item.status = status;
        item.closedAt = now;
        CandidateLifecycle.TerminalResolution resolution = CandidateLifecycle.resolveTerminal(
                status, item.signal, item.entryTriggered, now,
                snapshot == null ? item.lastPrice : snapshot.ethLast);
        if (resolution.terminalResolved) {
            item.exitAt = resolution.exitAt;
            item.exitPrice = resolution.exitPrice;
            item.exitReason = resolution.exitReason;
        }
    }

    private static boolean terminalResolved(ObservedSignal item) {
        return item != null && SignalSafetyPolicies.isTerminalStatus(item.status);
    }

    private static double realizedMovePerEth(ObservedSignal item) {
        if (item == null || item.signal == null || !item.entryTriggered
                || !terminalResolved(item) || !Double.isFinite(item.exitPrice)) return 0.0;
        return favorableMoveFor(item.signal, item.exitPrice);
    }

    private static String executionClassification(ObservedSignal item) {
        if (item == null) return "NONE";
        if ("ENTRY_REVALIDATION_REJECTED".equals(item.status)) {
            return "ENTRY_REVALIDATION_REJECTED";
        }
        return SignalSafetyPolicies.executionClassification(
                item.marketableAtCreation,
                item.createdAt,
                item.departureAt,
                item.firstEntryTouchAt,
                pendingProgressBeforeFill(item),
                item.entryTriggered,
                item.status);
    }

    private static void appendSeparatedResultFields(JSONObject object, ObservedSignal item, long now)
            throws Exception {
        boolean terminal = terminalResolved(item);
        double markedMove = favorableMoveFor(item.signal, item.lastPrice);
        long riskAge = SignalSafetyPolicies.isOpenActiveRisk(item.status, item.entryTriggered)
                && item.entryTriggeredAt > 0 ? Math.max(0L, now - item.entryTriggeredAt) : 0L;
        SignalSafetyPolicies.RealizedAndLatentResult result = SignalSafetyPolicies.result(
                terminal, item.entryTriggered, realizedMovePerEth(item), markedMove,
                item.signal.quantity, riskAge);
        object.put("terminalResolved", result.terminalResolved);
        object.put("realizedResult", terminal && item.entryTriggered);
        putMetric(object, "realizedGross", result.realizedGross);
        putMetric(object, "realizedFees", result.realizedFees);
        putMetric(object, "realizedNet", result.realizedNet);
        putMetric(object, "markedPrice", item.lastPrice);
        putMetric(object, "latentGross", result.latentGross);
        putMetric(object, "latentNet", result.latentNet);
        object.put("openRiskAgeSec", result.openRiskAgeMs / 1000L);
        object.put("researchRoundTripCostPerEth",
                SignalSafetyPolicies.RESEARCH_ROUND_TRIP_COST_PER_ETH);
    }

    private static Object confirmedSizingJson(ConfirmedSizing.Result sizing) throws Exception {
        if (sizing == null) return JSONObject.NULL;
        JSONObject o = new JSONObject();
        o.put("policy", "QUALITY_CAP_EVIDENCE_V2330");
        o.put("engineScoreDiagnosticOnly", sizing.engineScoreDiagnosticOnly);
        o.put("sizingFamily", sizing.sizingFamily);
        o.put("baseQuantity", sizing.baseQuantity);
        o.put("evidencePoints", sizing.evidencePoints);
        o.put("maxAllowedQuantity", sizing.maxAllowedQuantity);
        o.put("qualityCapQuantity", sizing.finalQuantity);
        putMetric(o, "avgRange20", sizing.avgRange20);
        putMetric(o, "move1Aligned", sizing.move1Aligned);
        putMetric(o, "move1BonusThreshold", sizing.move1BonusThreshold);
        o.put("move1Bonus", sizing.move1Bonus);
        putMetric(o, "move3Aligned", sizing.move3Aligned);
        putMetric(o, "move3BonusThreshold", sizing.move3BonusThreshold);
        o.put("move3Bonus", sizing.move3Bonus);
        putMetric(o, "move8Aligned", sizing.move8Aligned);
        putMetric(o, "move15Aligned", sizing.move15Aligned);
        putMetric(o, "flow30Aligned", sizing.flow30Aligned);
        putMetric(o, "flow60Aligned", sizing.flow60Aligned);
        putMetric(o, "btcMove3Aligned", sizing.btcMove3Aligned);
        putMetric(o, "volumeRatio", sizing.volumeRatio);
        o.put("premium15mBonus", sizing.premium15mBonus);
        o.put("cleanContextBonus", sizing.cleanContextBonus);
        o.put("historicalReplayRiskVeto", sizing.historicalReplayRiskVeto);
        o.put("replayRiskCapApplied", sizing.replayRiskCapApplied);
        o.put("rangeFadeCapApplied", sizing.rangeFadeCapApplied);
        return o;
    }

    private static Object dynamicTradePlanJson(DynamicTradePlan.Result plan) throws Exception {
        if (plan == null) return JSONObject.NULL;
        JSONObject o = new JSONObject();
        o.put("policy", "TIMELY_P01_QUANTITY_UPLIFT_V2332");
        o.put("valid", plan.valid);
        o.put("reasonCode", plan.reasonCode);
        putMetric(o, "A", plan.a);
        putMetric(o, "E60", plan.adverseExcursion60);
        putMetric(o, "R", plan.structuralRoom);
        putMetric(o, "slRequired", plan.stopRequired);
        putMetric(o, "slMaximum", plan.stopMaximum);
        putMetric(o, "tpFloor", plan.targetFloor);
        putMetric(o, "tpRaw", plan.targetRaw);
        putMetric(o, "tpDistance", plan.targetDistance);
        putMetric(o, "stopLoss", plan.stopLoss);
        putMetric(o, "takeProfit", plan.takeProfit);
        putMetric(o, "roundedStopDistance", plan.roundedStopDistance);
        putMetric(o, "roundedTargetDistance", plan.roundedTargetDistance);
        putMetric(o, "slFinal", plan.roundedStopDistance);
        putMetric(o, "tpFinal", plan.roundedTargetDistance);
        putMetric(o, "grossRewardRisk", plan.grossRewardRisk);
        putMetric(o, "estimatedRoundTripCostPerEth", plan.estimatedRoundTripCostPerEth);
        putMetric(o, "riskExecutionAllowancePerEth", plan.riskExecutionAllowancePerEth);
        putMetric(o, "resultCost", plan.estimatedRoundTripCostPerEth);
        putMetric(o, "riskAllowance", plan.riskExecutionAllowancePerEth);
        putMetric(o, "riskBudgetUsdt", plan.riskBudgetUsdt);
        putMetric(o, "riskPerEth", plan.riskPerEth);
        o.put("riskQuantity", plan.riskQuantity);
        o.put("qualityCap", plan.qualityCap);
        o.put("finalQuantity", plan.finalQuantity);
        putMetric(o, "theoreticalMaximumLoss", plan.theoreticalMaximumLoss);
        putMetric(o, "legacyRiskBudgetUsdt", plan.legacyRiskBudgetUsdt);
        o.put("legacyRiskQuantity", plan.legacyRiskQuantity);
        o.put("baselineFinalQuantity", plan.baselineFinalQuantity);
        o.put("quantityUpliftApplied", plan.quantityUpliftApplied);
        o.put("upliftedQuantity", plan.upliftedQuantity);
        putMetric(o, "upliftedRiskBudgetUsdt", plan.upliftedRiskBudgetUsdt);
        o.put("upliftedRiskQuantity", plan.upliftedRiskQuantity);
        putMetric(o, "theoreticalMaximumLossBeforeUplift",
                plan.theoreticalMaximumLossBeforeUplift);
        putMetric(o, "theoreticalMaximumLossAfterUplift",
                plan.theoreticalMaximumLossAfterUplift);
        putMetric(o, "priceTick", plan.priceTick);
        return o;
    }

    private static Object confirmedSizingJson(ObservedSignal item) throws Exception {
        if (item == null) return JSONObject.NULL;
        if (item.confirmedSizing != null || item.dynamicTradePlan != null) {
            JSONObject o = new JSONObject();
            o.put("qualityEvidence", confirmedSizingJson(item.confirmedSizing));
            o.put("dynamicPlan", dynamicTradePlanJson(item.dynamicTradePlan));
            o.put("sleeveDiagnostics", sleeveDiagnosticsJson(item));
            return o;
        }
        if (item.restoredSizingDiagnostic != null
                && item.restoredSizingDiagnostic.trim().startsWith("{")) {
            return new JSONObject(item.restoredSizingDiagnostic);
        }
        return JSONObject.NULL;
    }

    private static Object sleeveDiagnosticsJson(ObservedSignal item) throws Exception {
        if (item == null) return JSONObject.NULL;
        JSONObject o = new JSONObject();
        o.put("sleeve", item.sleeve);
        o.put("p02Mode", item.p02Mode);
        o.put("earlyP01Eligible", item.p01EarlyEligible);
        o.put("earlyP01Mode", item.p01EarlyQualityMode);
        o.put("earlyP01QualitySince", item.p01EarlyQualitySince > 0
                ? item.p01EarlyQualitySince : JSONObject.NULL);
        o.put("earlyP01StabilityMs", item.p01EarlyStabilityMs);
        o.put("earlyP01ReasonCode", item.p01EarlyLastReasonCode);
        o.put("ageAtEarlyConfirmationMs", item.p01EarlyAgeAtConfirmationMs >= 0
                ? item.p01EarlyAgeAtConfirmationMs : JSONObject.NULL);
        putMetric(o, "earlyP01TriggerBid", item.p01EarlyTriggerBid);
        putMetric(o, "earlyP01TriggerAsk", item.p01EarlyTriggerAsk);
        putMetric(o, "candidateEntry", item.plannedEntry);
        putMetric(o, "quoteDistanceToLimit", item.p01EarlyQuoteDistance);
        putMetric(o, "secondsSavedVsNormalConfirmation", item.p01EarlySecondsSaved);
        NormalizedSignalMetrics.Result m = item.normalizedMetrics;
        if (m != null) {
            putMetric(o, "A", m.a);
            putMetric(o, "E", m.adverseExcursion);
            putMetric(o, "e", m.e);
            putMetric(o, "R", m.r);
            putMetric(o, "room", m.room);
            putMetric(o, "m1", m.m1);
            putMetric(o, "m3", m.m3);
            putMetric(o, "m8", m.m8);
            putMetric(o, "f30", m.f30);
            putMetric(o, "f60", m.f60);
            putMetric(o, "volumeRatio", m.volumeRatio);
            putMetric(o, "directionalEdge", m.directionalEdge);
        }
        if (item.p01SleeveFilter != null) {
            o.put("p01Phase", item.p01SleeveFilter.phase);
            o.put("p01FilterReason", item.p01SleeveFilter.reasonCode);
            o.put("flowBacked", item.p01SleeveFilter.flowBacked);
            o.put("priceLed", item.p01SleeveFilter.priceLed);
            o.put("rejectConsumed", item.p01SleeveFilter.rejectConsumed);
        }
        if (item.p02SleeveFilter != null) {
            o.put("p02FilterReason", item.p02SleeveFilter.reasonCode);
        }
        if (item.trendRegime60 != null) {
            o.put("olsCount", item.trendRegime60.count);
            putMetric(o, "olsSlope", item.trendRegime60.slope);
            putMetric(o, "T60", item.trendRegime60.t60);
            o.put("olsMode", item.trendRegime60.mode);
            o.put("olsLastPointAt", item.trendRegime60.lastPointAt);
            o.put("olsReasonCode", item.trendRegime60.reasonCode);
        }
        return o;
    }

    private static double adverseMoveFor(SignalDecision signal, double price) {
        if (signal == null || !Double.isFinite(price) || price <= 0) return 99.0;
        if ("LONG".equals(signal.side)) return Math.max(0, signal.entry - price);
        if ("SHORT".equals(signal.side)) return Math.max(0, price - signal.entry);
        return 99.0;
    }

    private JSONArray observedSignalsJson() throws Exception {
        JSONArray out = new JSONArray();
        for (ObservedSignal item : observedSignals) {
            JSONObject o = new JSONObject();
            o.put("id", item.id);
            o.put("createdAt", item.createdAt);
            o.put("ageSec", Math.max(0, (System.currentTimeMillis() - item.createdAt) / 1000));
            o.put("lastUpdateAt", item.lastUpdateAt);
            o.put("closedAt", item.closedAt);
            o.put("status", item.status);
            o.put("exitAt", item.exitAt > 0 ? item.exitAt : JSONObject.NULL);
            putMetric(o, "exitPrice", item.exitPrice);
            o.put("exitReason", item.exitReason);
            o.put("entryState", item.entryTriggered ? "TRIGGERED" : "PENDING");
            o.put("entryTriggered", item.entryTriggered);
            o.put("entryTriggeredAt", item.entryTriggeredAt);
            putMetric(o, "entryTriggerPrice", item.entryTriggerPrice);
            o.put("marketableAtCreation", item.marketableAtCreation);
            putMetric(o, "creationBid", item.creationBid);
            putMetric(o, "creationAsk", item.creationAsk);
            putMetric(o, "creationLast", item.creationLast);
            putMetric(o, "plannedEntry", item.plannedEntry);
            o.put("firstEntryTouchAt", item.firstEntryTouchAt > 0 ? item.firstEntryTouchAt : JSONObject.NULL);
            putMetric(o, "firstEntryTouchPrice", item.firstEntryTouchPrice);
            putMetric(o, "firstEntryTouchBid", item.firstEntryTouchBid);
            putMetric(o, "firstEntryTouchAsk", item.firstEntryTouchAsk);
            o.put("simulatedFillAt", item.simulatedFillAt > 0 ? item.simulatedFillAt : JSONObject.NULL);
            putMetric(o, "simulatedFillPrice", item.simulatedFillPrice);
            o.put("manualEntryConfirmed", item.manualEntryConfirmed);
            o.put("manualEntryPrice", item.manualEntryConfirmed ? item.manualEntryPrice : JSONObject.NULL);
            o.put("manualEntryAt", item.manualEntryConfirmed && item.manualEntryAt > 0
                    ? item.manualEntryAt : JSONObject.NULL);
            o.put("confirmationAt", item.confirmationAt > 0 ? item.confirmationAt : JSONObject.NULL);
            o.put("candidateAgeMs", item.confirmationAt > 0
                    ? Math.max(0L, item.confirmationAt - item.createdAt)
                    : Math.max(0L, System.currentTimeMillis() - item.createdAt));
            o.put("executableAtConfirmation", item.executableAtConfirmation);
            putMetric(o, "finalConfirmationBid", item.finalConfirmationBid);
            putMetric(o, "finalConfirmationAsk", item.finalConfirmationAsk);
            putMetric(o, "A", item.finalConfirmationA);
            putMetric(o, "E60", item.adverseExcursion60);
            putMetric(o, "R", item.finalConfirmationR);
            o.put("legacyManualEntryDelayMs", LEGACY_LIMIT_MANUAL_ENTRY_DELAY_MS);
            o.put("silentP01ConfirmationWindowMs", CandidateLifecycle.MIN_CONFIRMATION_AGE_MS);
            o.put("confirmationWindowSatisfied", item.confirmationAt > 0
                    && item.confirmationAt - item.createdAt >= CandidateLifecycle.MIN_CONFIRMATION_AGE_MS);
            o.put("replayRiskReasonCode", item.replayRiskReasonCode);
            o.put("replayRiskDetail", item.replayRiskDetail);
            o.put("replayRiskVetoBlocking", item.replayRiskBlocking);
            o.put("executionClassification", executionClassification(item));
            o.put("confirmationReasonCode", item.lastConfirmationCode);
            o.put("sleeve", item.sleeve);
            o.put("p02Mode", item.p02Mode);
            o.put("sleeveDiagnostics", sleeveDiagnosticsJson(item));
            o.put("terminalAt", lastTerminalAt > 0 ? lastTerminalAt : JSONObject.NULL);
            o.put("rearmRemainingMs", TerminalRearmPersistence.remainingMs(
                    System.currentTimeMillis(), lastTerminalAt));
            o.put("diagnosticText", "DIAGNOSTIC_ONLY".equals(item.status)
                    ? CandidateLifecycle.RANGE_FADE_DIAGNOSTIC_TEXT : "");
            o.put("p01Premium15m", item.premium15m);
            o.put("confirmedSizing", confirmedSizingJson(item));
            o.put("dynamicTradePlan", dynamicTradePlanJson(item.dynamicTradePlan));
            o.put("updates", item.updates);

            o.put("side", item.signal.side);
            o.put("family", item.signal.family);
            o.put("score", item.signal.score);
            o.put("qty", ConfirmedSignalPayload.from(item.signal).quantityForDiagnostic());
            o.put("entry", item.signal.entry);
            o.put("tp", item.signal.takeProfit);
            o.put("sl", item.signal.stopLoss);
            o.put("targetMove", item.signal.targetMove);
            o.put("stopDistance", item.signal.stopDistance);
            o.put("signalMetrics", signalMetricsJson(item));

            putMetric(o, "lastPrice", item.lastPrice);
            putMetric(o, "maxPrice", item.maxPrice);
            putMetric(o, "minPrice", item.minPrice);
            putMetric(o, "mfe", item.mfe);
            putMetric(o, "mae", item.mae);
            putMetric(o, "scenarioProgress", scenarioProgress(item));
            putMetric(o, "scenarioRisk", scenarioRisk(item));
            appendSeparatedResultFields(o, item, System.currentTimeMillis());
            o.put("scenarioMemoryProtected", shouldKeepScenarioAlive(item, buildSnapshot(System.currentTimeMillis()), System.currentTimeMillis()));

            if ("LONG".equals(item.signal.side)) {
                putMetric(o, "unrealizedMove",
                        terminalResolved(item) ? 0.0 : item.lastPrice - item.signal.entry);
            } else if ("SHORT".equals(item.signal.side)) {
                putMetric(o, "unrealizedMove",
                        terminalResolved(item) ? 0.0 : item.signal.entry - item.lastPrice);
            }

            out.put(o);
        }
        return out;
    }

    private JSONObject signalMetricsJson(ObservedSignal item) throws Exception {
        JSONObject m = new JSONObject();
        putMetric(m, "signalEthLast", item.signalEthLast);
        putMetric(m, "signalBid", item.signalBid);
        putMetric(m, "signalAsk", item.signalAsk);
        putMetric(m, "signalSpread", item.signalSpread);
        putMetric(m, "avgRange20", item.avgRange20);
        putMetric(m, "avgVolume20", item.avgVolume20);
        putMetric(m, "lastVolume", item.lastVolume);
        putMetric(m, "volumeRatio", item.volumeRatio);
        putMetric(m, "flowNorm", item.flowNorm);
        putMetric(m, "btcMove5", item.btcMove5);
        putMetric(m, "move1", item.move1);
        putMetric(m, "move3", item.move3);
        putMetric(m, "move8", item.move8);
        putMetric(m, "recentHigh", item.recentHigh);
        putMetric(m, "recentLow", item.recentLow);
        putMetric(m, "recentRange", item.recentRange);

        putMetric(m, "move1Norm", item.move1Norm);
        putMetric(m, "move3Norm", item.move3Norm);
        putMetric(m, "move8Norm", item.move8Norm);
        putMetric(m, "moveAccel13", item.moveAccel13);
        putMetric(m, "moveAccel38", item.moveAccel38);
        putMetric(m, "rangePosition", item.rangePosition);
        putMetric(m, "distanceToHigh", item.distanceToHigh);
        putMetric(m, "distanceToLow", item.distanceToLow);
        putMetric(m, "roomLong", item.roomLong);
        putMetric(m, "roomShort", item.roomShort);
        putMetric(m, "flow15", item.flow15);
        putMetric(m, "flow30", item.flow30);
        putMetric(m, "flow60", item.flow60);
        putMetric(m, "flow120", item.flow120);
        putMetric(m, "deltaFlow15_60", item.deltaFlow15_60);
        putMetric(m, "deltaFlow30_120", item.deltaFlow30_120);
        putMetric(m, "flowAccel", item.flowAccel);
        putMetric(m, "btcMove1", item.btcMove1);
        putMetric(m, "btcMove3", item.btcMove3);
        putMetric(m, "btcMove8", item.btcMove8);
        putMetric(m, "btcAccel1_5", item.btcAccel1_5);
        putMetric(m, "btcAccel3_8", item.btcAccel3_8);
        putMetric(m, "antiBurstScore", item.antiBurstScore);

        putMetric(m, "targetNetPerEthAfterFees",
                item.signal.targetMove - SignalSafetyPolicies.RESEARCH_ROUND_TRIP_COST_PER_ETH);
        putMetric(m, "stopCostPerEthAfterFees",
                item.signal.stopDistance + SignalSafetyPolicies.RESEARCH_ROUND_TRIP_COST_PER_ETH);
        return m;
    }

    private JSONObject observationSummaryJson() throws Exception {
        JSONObject o = new JSONObject();
        int total = 0, pending = 0, active = 0, triggered = 0, tp = 0, sl = 0, invalid = 0, timeout15 = 0, timeout45 = 0, missedNoFill = 0;

        for (ObservedSignal item : observedSignals) {
            total++;
            if (item.entryTriggered) triggered++;

            if ("LIMIT_PENDING".equals(item.status)) pending++;
            else if ("ACTIVE".equals(item.status)) active++;
            else if ("TP_TOUCHED".equals(item.status)) tp++;
            else if ("SL_TOUCHED".equals(item.status)) sl++;
            else if ("TIMEOUT_15M".equals(item.status)) timeout15++;
            else if ("TIMEOUT_45M".equals(item.status)) timeout45++;
            else if ("MISSED_NO_FILL".equals(item.status)) missedNoFill++;
            else invalid++;
        }

        o.put("totalSignalsObserved", total);
        o.put("limitPending", pending);
        o.put("triggered", triggered);
        o.put("activeTriggered", active);
        o.put("tpTouched", tp);
        o.put("slTouched", sl);
        o.put("timeout15", timeout15);
        o.put("timeout45", timeout45);
        o.put("missedNoFill", missedNoFill);
        o.put("invalidated", invalid);
        o.put("mode", "RESEARCH_JOURNAL_LIMIT_AWARE");
        o.put("maxStoredSignals", 200);
        return o;
    }

    private JSONObject calibrationSummaryJson() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray recommendations = new JSONArray();

        int total = 0, rangeFade = 0, rangeFadeSl = 0, rangeFadeInvalid = 0;
        int continuation = 0, continuationSl = 0, continuationLateSl = 0;
        int timeoutTooEarlyRisk = 0, pendingNeverTriggered = 0;

        for (ObservedSignal item : observedSignals) {
            if (item == null || item.signal == null) continue;
            total++;

            String family = item.signal.family == null ? "" : item.signal.family;
            boolean isRangeFade = family.contains("RANGE_FADE");
            boolean isContinuation = family.contains("CONTINUATION");

            if (isRangeFade) {
                rangeFade++;
                if ("SL_TOUCHED".equals(item.status)) rangeFadeSl++;
                if ("SCENARIO_INVALIDATED".equals(item.status)) rangeFadeInvalid++;
            }

            if (isContinuation) {
                continuation++;
                if ("SL_TOUCHED".equals(item.status)) continuationSl++;

                double avg = Math.max(0.35, item.avgRange20);
                boolean lateLong = "LONG".equals(item.signal.side)
                        && item.move3 > avg * 2.05
                        && item.move8 > avg * 1.75
                        && item.rangePosition >= 0.72
                        && item.roomLong < Math.max(1.75, item.signal.targetMove * 0.82)
                        && item.flow60 > 0.45
                        && item.flow120 > 0.45
                        && (item.flow15 < 0.08 || item.flowAccel < -0.45 || item.volumeRatio < 0.25);

                boolean lateShort = "SHORT".equals(item.signal.side)
                        && item.move3 < -avg * 2.05
                        && item.move8 < -avg * 1.75
                        && item.rangePosition <= 0.28
                        && item.roomShort < Math.max(1.75, item.signal.targetMove * 0.82)
                        && item.flow60 < -0.45
                        && item.flow120 < -0.45
                        && (item.flow15 > -0.08 || item.flowAccel > 0.45 || item.volumeRatio < 0.25);

                if ("SL_TOUCHED".equals(item.status) && (lateLong || lateShort)) {
                    continuationLateSl++;
                }
            }

            if ("TIMEOUT_15M".equals(item.status) && scenarioProgress(item) >= 0.55 && scenarioRisk(item) <= 0.85) {
                timeoutTooEarlyRisk++;
            }

            if ("LIMIT_PENDING".equals(item.status) && System.currentTimeMillis() - item.createdAt > OBSERVATION_MAX_AGE_MS) {
                pendingNeverTriggered++;
            }
        }

        if (rangeFadeSl > 0) {
            recommendations.put("v2.32 : RANGE_FADE faible = cap 3 ou veto si C2 actif / contre-tendance forte.");
        }

        if (continuationLateSl > 0) {
            recommendations.put("v2.32 : CONTINUATION tardive = veto si mouvement consommé, flow court ralentit, room TP faible.");
        } else if (continuationSl > 0) {
            recommendations.put("Surveiller les CONTINUATION en SL : limiter quantité si IA < 80% ou room TP faible.");
        }

        if (timeoutTooEarlyRisk > 0) {
            recommendations.put("Ne pas fermer à 15 minutes si le signal a déjà avancé vers TP avec risque acceptable.");
        }

        if (pendingNeverTriggered > 0) {
            recommendations.put("Surveiller les LIMIT en attente trop longtemps : annuler si scénario confirmé mauvais.");
        }

        if (recommendations.length() == 0) {
            recommendations.put("Continuer collecte : pas assez de nouveaux cas pour modifier les seuils.");
        }

        o.put("mode", "LOCAL_REPLAY_CALIBRATION_STABLE_V2321_MANUAL_FILL");
        o.put("total", total);
        o.put("rangeFade", rangeFade);
        o.put("rangeFadeSl", rangeFadeSl);
        o.put("rangeFadeInvalidated", rangeFadeInvalid);
        o.put("continuation", continuation);
        o.put("continuationSl", continuationSl);
        o.put("continuationLateSl", continuationLateSl);
        o.put("timeoutTooEarlyRisk", timeoutTooEarlyRisk);
        o.put("pendingNeverTriggered15m", pendingNeverTriggered);
        o.put("recommendations", recommendations);
        return o;
    }

    private boolean isLastSignalStillActionable(MarketSnapshot snapshot, long now) {
        return "ACTIVE".equals(activeSignalStatus(snapshot, now));
    }

    private String observedStatusForLastSignal() {
        ObservedSignal item = observedForLastSignal();
        return item == null || item.status == null ? "NONE" : item.status;
    }

    private ObservedSignal observedForLastSignal() {
        if (lastSignalAt <= 0) return null;
        for (ObservedSignal item : observedSignals) {
            if (item.finalConfirmedAt == lastSignalAt) return item;
        }
        return null;
    }

    private String activeSignalStatus(MarketSnapshot snapshot, long now) {
        if (lastSignal == null || lastSignalAt <= 0) return "NONE";

        ObservedSignal observed = observedForLastSignal();
        String journalStatus = observed == null || observed.status == null ? "NONE" : observed.status;

        if ("LIMIT_PENDING".equals(journalStatus)) {
            return "LIMIT_PENDING";
        }

        if (!"NONE".equals(journalStatus) && !"ACTIVE".equals(journalStatus)) {
            return journalStatus;
        }

        double price = snapshot.ethLast;
        if (!Double.isFinite(price) || price <= 0) {
            return observed != null && SignalSafetyPolicies.blocksNewFinalSignal(
                    observed.status, observed.entryTriggered, observed.finalConfirmedAt)
                    ? "ACTIVE" : "NO_PRICE";
        }

        if ("LONG".equals(lastSignal.side)) {
            if (price <= lastSignal.stopLoss) return "SL_TOUCHED";
            if (price >= lastSignal.takeProfit) return "TP_TOUCHED";
        }

        if ("SHORT".equals(lastSignal.side)) {
            if (price >= lastSignal.stopLoss) return "SL_TOUCHED";
            if (price <= lastSignal.takeProfit) return "TP_TOUCHED";
        }

        return "ACTIVE";
    }

    private String executionStateForLastSignal(MarketSnapshot snapshot, long now) {
        String status = activeSignalStatus(snapshot, now);
        if ("MISSED_NO_FILL".equals(status)) return "NON_EXECUTE";
        if ("LIMIT_PENDING".equals(status)) return "LIMIT_EN_ATTENTE";
        if ("ACTIVE".equals(status)) return "LIMIT_DECLENCHE";
        if ("ENTRY_REVALIDATION_REJECTED".equals(status)) return "ANNULE";
        if ("ENTRY_TOO_FAR".equals(status)) return "LIMIT_EN_ATTENTE";
        if ("TP_TOUCHED".equals(status) || "SL_TOUCHED".equals(status)) return "TERMINE";
        if ("NO_PRICE".equals(status)) return "PRIX_INDISPONIBLE";
        return "ATTENDRE";
    }

    private long activeSignalAgeSec(long now) {
        ObservedSignal observed = observedForLastSignal();
        long start = observed == null ? lastSignalAt : observationClockStart(observed);
        return start <= 0 ? -1 : Math.max(0, (now - start) / 1000);
    }

    private long activeSignalRemainingSec(long now) {
        return -1;
    }

    private String aiMicroContextJson() {
        JSONArray out = new JSONArray();
        try {
            int size = marketFrames.size();
            int skip = Math.max(0, size - 18);
            int index = 0;

            for (MarketFrame f : marketFrames) {
                if (index++ < skip) continue;

                JSONObject o = new JSONObject();
                o.put("ageMs", Math.max(0, System.currentTimeMillis() - f.at));
                putMetric(o, "eth", f.ethLast);
                putMetric(o, "spread", f.spread);
                putMetric(o, "move1", f.move1);
                putMetric(o, "move3", f.move3);
                putMetric(o, "move8", f.move8);
                putMetric(o, "rangePosition", f.rangePosition);
                putMetric(o, "roomLong", f.roomLong);
                putMetric(o, "roomShort", f.roomShort);
                putMetric(o, "flow15", f.flow15);
                putMetric(o, "flow30", f.flow30);
                putMetric(o, "flow60", f.flow60);
                putMetric(o, "flowAccel", f.flowAccel);
                putMetric(o, "btcMove1", f.btcMove1);
                putMetric(o, "btcMove3", f.btcMove3);
                putMetric(o, "antiBurstScore", f.antiBurstScore);
                o.put("setupCandidate", f.setupCandidate);
                o.put("decisionCode", f.decisionCode);
                out.put(o);
            }
        } catch (Exception ignored) {}

        return out.toString();
    }

    private MarketSnapshot buildSnapshot(long now) {
        List<Candle> ethList = new ArrayList<>(ethCandles);
        List<Candle> btcList = new ArrayList<>(btcCandles);

        double averageRange = avgRange(ethList, 20);
        double averageVolume = avgVolume(ethList, 20);
        double avg = Math.max(0.35, averageRange);

        double move1 = 0, move3 = 0, move8 = 0, move15 = 0, lastVolume = 0, recentHigh = 0, recentLow = 0;
        double previousHigh8 = 0, previousLow8 = 0;

        if (!ethList.isEmpty()) lastVolume = ethList.get(ethList.size() - 1).volume;

        if (ethList.size() >= 9) {
            Candle last = ethList.get(ethList.size() - 1);
            move1 = last.close - ethList.get(ethList.size() - 2).close;
            move3 = last.close - ethList.get(ethList.size() - 4).close;
            move8 = last.close - ethList.get(ethList.size() - 9).close;
            recentHigh = high(ethList, 8);
            recentLow = low(ethList, 8);
            previousHigh8 = highBeforeLast(ethList, 8);
            previousLow8 = lowBeforeLast(ethList, 8);
        }
        if (ethList.size() >= 16) {
            Candle last = ethList.get(ethList.size() - 1);
            move15 = last.close - ethList.get(ethList.size() - 16).close;
        }

        double btcMove1 = percentMove(btcList, 1);
        double btcMove3 = percentMove(btcList, 3);
        double btcMove5 = percentMove(btcList, 5);
        double btcMove8 = percentMove(btcList, 8);

        double flow15 = averageVolume > 0 ? signedFlow(now, 15_000) / averageVolume : 0;
        double flow30 = averageVolume > 0 ? signedFlow(now, 30_000) / averageVolume : 0;
        double flow60 = averageVolume > 0 ? signedFlow(now, 60_000) / averageVolume : 0;
        double flow120 = averageVolume > 0 ? signedFlow(now, 120_000) / averageVolume : 0;
        double flowNorm = flow60;

        double recentRange = Math.max(0, recentHigh - recentLow);
        double volumeRatio = averageVolume > 0 ? lastVolume / averageVolume : 0;
        double rangePosition = recentRange > 0 && Double.isFinite(ethLast)
                ? (ethLast - recentLow) / recentRange : 0.5;
        rangePosition = Math.max(-2.0, Math.min(3.0, rangePosition));

        double distanceToHigh = Double.isFinite(ethLast) ? recentHigh - ethLast : Double.NaN;
        double distanceToLow = Double.isFinite(ethLast) ? ethLast - recentLow : Double.NaN;
        double roomLong = distanceToHigh;
        double roomShort = distanceToLow;
        double pullbackFromHigh = distanceToHigh;
        double pullbackFromLow = distanceToLow;

        double move1Norm = move1 / avg;
        double move3Norm = move3 / avg;
        double move8Norm = move8 / avg;
        double moveAccel13 = move1 - (move3 / 3.0);
        double moveAccel38 = (move3 / 3.0) - (move8 / 8.0);

        double breakoutHighDistance = Double.isFinite(ethLast) && previousHigh8 > 0 ? ethLast - previousHigh8 : 0;
        double breakoutLowDistance = Double.isFinite(ethLast) && previousLow8 > 0 ? previousLow8 - ethLast : 0;

        double antiBurstScore = 0;
        antiBurstScore += Math.max(0, Math.abs(move1Norm) - 1.0);
        antiBurstScore += Math.max(0, volumeRatio - 2.0) * 0.35;
        antiBurstScore += Math.max(0, Math.abs(flow15 - flow60)) * 0.50;
        antiBurstScore += recentRange > 0 ? Math.max(0, Math.abs(rangePosition - 0.5) - 0.35) * 3.0 : 0;

        return MarketSnapshot.builder(now)
                .lastSignalAt(lastP01ConfirmedAt)
                .eth(ethLast, ethBid, ethAsk)
                .btc(btcLast, btcBid, btcAsk)
                .candleCounts(ethList.size(), btcList.size())
                .averages(averageRange, averageVolume)
                .movement(move1, move3, move8, recentHigh, recentLow)
                .move15(move15)
                .flow(flowNorm, lastVolume)
                .btcMove5(btcMove5)
                .flowWindows(flow15, flow30, flow60, flow120)
                .btcMoves(btcMove1, btcMove3, btcMove5, btcMove8)
                .professionalFeatures(recentRange, volumeRatio, rangePosition,
                        distanceToHigh, distanceToLow, roomLong, roomShort,
                        pullbackFromHigh, pullbackFromLow,
                        move1Norm, move3Norm, move8Norm, moveAccel13, moveAccel38,
                        breakoutHighDistance, breakoutLowDistance, antiBurstScore)
                .build();
    }

    private void upsert(Deque<Candle> candles, Candle candle, int max) {
        if (!candles.isEmpty() && candles.peekLast().openTime == candle.openTime) candles.removeLast();
        candles.addLast(candle);
        while (candles.size() > max) candles.removeFirst();
    }

    private void pruneFlows(long now) {
        while (!flows.isEmpty() && now - flows.peekFirst().time > 120_000) flows.removeFirst();
    }

    private double signedFlow(long now, long window) {
        pruneFlows(now);
        double total = 0;
        for (TradeFlow flow : flows) if (now - flow.time <= window) total += flow.signedQuantity;
        return total;
    }


    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "—";
    }

    private void notifyObservationStatus(ObservedSignal item, String status) {
        if (item == null || item.signal == null || item.finalConfirmedAt <= 0
                || !SignalSafetyPolicies.isTerminalStatus(status)) return;

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        String title = "TP_TOUCHED".equals(status)
                ? "TP ATTEINT — PLAN TERMINÉ"
                : "SL ATTEINT — PLAN TERMINÉ";
        String body = String.format(Locale.US,
                "LIMIT %.2f · TP %.2f · SL %.2f · %d ETH · mise à jour silencieuse",
                item.signal.entry, item.signal.takeProfit, item.signal.stopLoss,
                item.signal.quantity);

        int id = item.notificationId > 0 ? item.notificationId
                : confirmedNotificationId(item.notificationSignature);
        item.notificationId = id;
        manager.notify(id, buildSignalNotification(title, body, false));
    }

    private static void syncTicker(MarketRuntime runtime,double last,double bid,double ask,long now){runtime.last=last;runtime.bid=bid;runtime.ask=ask;runtime.lastTickerAt=now;runtime.bookTickerMessages++;}
    private static MarketRuntime.MarketBar toRuntimeBar(Candle c){return new MarketRuntime.MarketBar(c.openTime,c.open,c.high,c.low,c.close,c.volume);}
    private static Candle toLegacyCandle(MarketRuntime.MarketBar b){return new Candle(b.openTime,b.open,b.high,b.low,b.close,b.volume);}
    private static void upsertRuntime(MarketRuntime runtime,Candle c){
        if(!runtime.candles.isEmpty()&&runtime.candles.peekLast().openTime==c.openTime)runtime.candles.removeLast();
        runtime.candles.addLast(new MarketRuntime.MarketBar(c.openTime,c.open,c.high,c.low,c.close,c.volume));
        while(runtime.candles.size()>180)runtime.candles.removeFirst();
    }
    private void upsertSharedBtc(Candle c){
        if(!sharedBtc.candles.isEmpty()&&sharedBtc.candles.peekLast().openTime==c.openTime)sharedBtc.candles.removeLast();
        sharedBtc.candles.addLast(new MarketRuntime.MarketBar(c.openTime,c.open,c.high,c.low,c.close,c.volume));
        while(sharedBtc.candles.size()>180)sharedBtc.candles.removeFirst();
    }
    private static void pruneRuntimeTrades(MarketRuntime runtime,long now){while(!runtime.aggTrades.isEmpty()&&now-runtime.aggTrades.peekFirst().at>120_000L)runtime.aggTrades.removeFirst();}

    private void notifyObservationSignal(ObservedSignal item) {
        if (item == null || item.signal == null || item.finalConfirmedAt <= 0) return;
        SignalDecision decision = item.signal;
        ConfirmedSignalPayload payload = ConfirmedSignalPayload.from(decision);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String title = "🚨 SIGNAL ETH CONFIRMÉ — " + decision.side;
        String body = payload.notificationBody(item.premium15m);

        if (manager != null) {
            String signature = item.notificationSignature;
            String key = "signature_" + signature;
            boolean alreadyAlerted = getSharedPreferences(ALERT_DEDUPE_PREFERENCES, MODE_PRIVATE)
                    .getBoolean(key, false);
            int id = confirmedNotificationId(signature);
            item.notificationId = id;
            item.alertSent = SignalSafetyPolicies.finalSignalIsAudible(alreadyAlerted);
            if (!alreadyAlerted) {
                getSharedPreferences(ALERT_DEDUPE_PREFERENCES, MODE_PRIVATE)
                        .edit().putBoolean(key, true).apply();
            }
            manager.notify(id, buildSignalNotification(title, body,
                    SignalSafetyPolicies.finalSignalIsAudible(alreadyAlerted)));
        }

        updateWatch("Signal final confirmé : " + decision.side + " · ordre manuel uniquement", true);
    }

    private void notifyMarketPlan(ActivePlanState plan, boolean audible) {
        if (plan == null) return;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager==null)return;
        String key="signature_"+plan.notificationSignature;
        boolean sent=getSharedPreferences(ALERT_DEDUPE_PREFERENCES,MODE_PRIVATE).getBoolean(key,false);
        boolean sound=audible&&!sent;
        if(sound)getSharedPreferences(ALERT_DEDUPE_PREFERENCES,MODE_PRIVATE).edit().putBoolean(key,true).apply();
        String title=plan.symbol+" · SIGNAL CONFIRMÉ — "+plan.side;
        String body=String.format(Locale.US,"LIMIT %.2f · TP %.2f · SL %.2f · %d %s",
                plan.entry,plan.takeProfit,plan.stopLoss,plan.quantity,plan.asset);
        manager.notify(plan.notificationId,buildSignalNotification(title,body,sound));
    }

    private void notifyRestoredMarketPlan(MarketRuntime runtime) {
        if(runtime!=null&&runtime.activePlan!=null)notifyMarketPlan(runtime.activePlan,false);
    }

    private void notifyMarketTerminal(ActivePlanState plan,String status) {
        if(plan==null)return;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager==null)return;
        String title=("TP_TOUCHED".equals(status)?"TP ATTEINT":"SL ATTEINT")
                +" — "+plan.symbol+" PLAN TERMINÉ";
        String body=String.format(Locale.US,"%s · %d %s · mise à jour silencieuse",
                plan.side,plan.quantity,plan.asset);
        manager.notify(plan.notificationId,buildSignalNotification(title,body,false));
    }

    private void notifyRestoredActivePlan(ObservedSignal item) {
        if (item == null || item.signal == null) return;
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            ConfirmedSignalPayload payload = ConfirmedSignalPayload.from(item.signal);
            int id = SignalSafetyPolicies.restoredNotificationId(
                    item.notificationId, item.notificationSignature);
            item.notificationId = id;
            item.alertSent = false;
            manager.notify(id, buildSignalNotification(
                    "PLAN ACTIF RESTAURÉ — " + item.signal.side,
                    payload.notificationBody(item.premium15m),
                    SignalSafetyPolicies.restoredPlanIsAudible()));
            updateWatch("Plan actif restauré · surveillance TP/SL maintenue", true);
        } catch (RuntimeException ignored) {
            // The restored lock remains active even if Android temporarily rejects notifications.
        }
    }

    private static int confirmedNotificationId(String signature) {
        return SignalSafetyPolicies.confirmedNotificationId(signature);
    }

    private void notifyTestAlert() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(signalNotificationId++, buildSignalNotification(
                "TEST NOTIFICATION ETH + SOL", "Test silencieux v2.34.0 · aucun ordre n’est envoyé", false));
    }

    private Notification buildSignalNotification(String title, String body, boolean audible) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Uri sound = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.eth_alert_loud);
        AudioAttributes audio = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        Notification.Builder builder = new Notification.Builder(
                this, audible ? CH_SIGNAL : CH_SIGNAL_SILENT)
                .setSmallIcon(R.drawable.ic_stat_eth)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setPriority(Notification.PRIORITY_MAX)
                .setLights(0xffff315f, 1000, 500)
                .setContentIntent(pending)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
        if (audible) {
            builder.setSound(sound, audio).setVibrate(ALERT_VIBRATION);
        } else {
            builder.setSound(null).setVibrate(null);
        }
        return builder.build();
    }

    private void vibrateAlert() {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator())
                vibrator.vibrate(VibrationEffect.createWaveform(ALERT_VIBRATION, -1));
        } catch (Exception ignored) {}
    }

    private Notification buildWatchNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat_eth)
                .setContentTitle("ETH Scalper · moteur natif actif")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .build();
    }

    private void updateWatch(String text, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastWatchNotificationAt < 15_000) return;
        lastWatchNotificationAt = now;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIF_WATCH_ID, buildWatchNotification(text));
    }

    private void broadcastStatus(String type, String message) {
        try {
            long now = System.currentTimeMillis();
            long age = lastMessageAt == 0 ? -1 : Math.max(0, (now - lastMessageAt) / 1000);
            boolean connected = socket != null && age >= 0 && age < 70;
            SignalDecision decision = lastDecision;
            MarketSnapshot statusSnapshot = buildSnapshot(now);
            String activeStatus = activeSignalStatus(statusSnapshot, now);
            boolean activeSignal = "ACTIVE".equals(activeStatus);
            if (activeSignal && lastSignal != null) decision = lastSignal;

            JSONObject state = new JSONObject();
            state.put("version", "2.34.0.1-android-hardened-multi-market-research");
            state.put("nativeActive", running);
            state.put("connected", connected);
            state.put("lastAgeSec", age);
            state.put("type", type);
            state.put("message", message);
            putPrice(state, "eth", ethLast); putPrice(state, "bid", ethBid); putPrice(state, "ask", ethAsk);
            putPrice(state, "btc", btcLast); putPrice(state, "btcBid", btcBid); putPrice(state, "btcAsk", btcAsk);
            state.put("ethCandles", ethCandles.size());
            state.put("btcCandles", btcCandles.size());
            state.put("candles", ethCandles.size());
            state.put("tradeFlowSamples", flows.size());
            JSONObject markets=new JSONObject();
            for(MarketProfile profile:marketRegistry.tradedMarkets()) {
                if(MarketProfile.ETH_SYMBOL.equals(profile.symbol)) {
                    markets.put(profile.symbol,marketStatusJson(profile,ethLast,ethBid,ethAsk,
                            ethCandles.size(),lastEthBookTickerAt,lastSignal,activeSignal,
                            lastTerminalAt,activeStatus,now));
                } else {
                    MarketRuntime runtime=marketCoordinator.runtime(profile.symbol);
                    markets.put(profile.symbol,marketStatusJson(profile,runtime.last,runtime.bid,
                            runtime.ask,runtime.candles.size(),runtime.lastTickerAt,
                            runtime.lastSignal,runtime.hasActivePlan(),runtime.lastTerminalAt,
                            runtime.lastTerminalStatus,now));
                }
            }
            state.put("markets",markets);
            JSONObject reference=new JSONObject();reference.put("symbol",MarketProfile.BTC_SYMBOL);
            putPrice(reference,"last",btcLast);putPrice(reference,"bid",btcBid);putPrice(reference,"ask",btcAsk);
            reference.put("feedAgeSec",ageSeconds(now,sharedBtc.lastTickerAt));reference.put("tradable",false);
            state.put("referenceMarket",reference);
            JSONArray activePlans=new JSONArray();
            if(activeSignal&&lastSignal!=null)activePlans.put(signalJson(lastSignal));
            for(MarketProfile profile:marketRegistry.tradedMarkets()) {
                if(MarketProfile.ETH_SYMBOL.equals(profile.symbol))continue;
                MarketRuntime runtime=marketCoordinator.runtime(profile.symbol);
                if(runtime.hasActivePlan())activePlans.put(activePlanJson(runtime.activePlan));
            }
            state.put("activePlans",activePlans);
            double ethRisk=activeSignal&&lastSignal!=null?lastSignal.quantity*(lastSignal.stopDistance+2.35):0;
            double totalRisk=ethRisk;
            JSONObject riskBySymbol=new JSONObject();riskBySymbol.put(MarketProfile.ETH_SYMBOL,ethRisk);
            for(MarketProfile profile:marketRegistry.tradedMarkets()) {
                if(MarketProfile.ETH_SYMBOL.equals(profile.symbol))continue;
                MarketRuntime runtime=marketCoordinator.runtime(profile.symbol);
                double risk=runtime.hasActivePlan()?runtime.activePlan.theoreticalMaximumLoss:0;
                riskBySymbol.put(profile.symbol,risk);totalRisk+=risk;
            }
            JSONObject aggregate=new JSONObject();aggregate.put("riskBySymbol",riskBySymbol);
            aggregate.put("totalActiveRiskUsdt",totalRisk);
            aggregate.put("informationalOnly",true);state.put("aggregateRisk",aggregate);
            state.put("bookTickerMessages", bookTickerMessages);
            state.put("klineMessages", klineMessages);
            state.put("aggTradeMessages", aggTradeMessages);
            state.put("restKlineRefreshes", restKlineRefreshes);
            state.put("restTradeRefreshes", restTradeRefreshes);
            state.put("lastBookTickerAgeSec", ageSeconds(now, lastBookTickerAt));
            state.put("lastKlineAgeSec", ageSeconds(now, lastKlineAt));
            state.put("lastAggTradeAgeSec", ageSeconds(now, lastAggTradeAt));
            state.put("lastRestKlineAgeSec", ageSeconds(now, lastRestKlineOkAt));
            state.put("lastRestTradeAgeSec", ageSeconds(now, lastRestTradeOkAt));
            state.put("lastEvaluationAgeSec", lastEvaluationAt == 0 ? -1 : Math.max(0, (now - lastEvaluationAt) / 1000));
            MarketSnapshot snapshot = statusSnapshot;
            state.put("activeSignal", activeSignal);
            state.put("activeSignalStatus", activeStatus);
            state.put("signalExecutionState", executionStateForLastSignal(statusSnapshot, now));
            state.put("activeSignalAgeSec", activeSignalAgeSec(now));
            state.put("activeSignalRemainingSec", activeSignalRemainingSec(now));
            state.put("activeSignalValidity", "TP_SL_ONLY_NO_TIMEOUT");
            state.put("executionMode", "RESEARCH_ONLY");
            state.put("realTradingAllowed", SignalSafetyPolicies.realTradingAllowed());
            state.put("aiEnabled", AiAdvisor.isEnabled(this));
            state.put("aiMode", AiAdvisor.isEnabled(this)
                    ? "INFORMATIONAL_POST_SIGNAL_ONLY" : "ENGINE_COMPLETE_AI_OFF");
            state.put("aiStatus", aiStatus);
            try { state.put("aiLastDecision", new JSONObject(lastAiDecisionJson)); } catch (Exception ignored) { state.put("aiLastDecision", JSONObject.NULL); }
            state.put("marketFramesInMemory", marketFrames.size());
            state.put("overnightRecorder", persistentRecorderSummaryJson(this));
            state.put("marketRecorderSummary", marketRecorderSummaryJson());
            state.put("observationSummary", observationSummaryJson());
            state.put("calibrationSummary", calibrationSummaryJson());
            state.put("observedSignals", observedSignalsJson());
            state.put("engineMetrics", engineMetricsJson(snapshot, decision));
            state.put("lastSignalAt", lastSignalAt);
            state.put("lastTerminalAt", lastTerminalAt > 0 ? lastTerminalAt : JSONObject.NULL);
            state.put("rearmRemainingMs",
                    TerminalRearmPersistence.remainingMs(now, lastTerminalAt));

            String publicDecision = decision == null ? "ATTENDRE" : decision.decision;
            String publicReason = decision == null ? "Initialisation du moteur" : decision.reasonText;
            String publicEngineReason = decision == null ? "NO_DATA" : decision.reasonCode;
            int publicScore = decision == null ? 0 : decision.score;

            if (lastSignal != null && "TP_TOUCHED".equals(activeStatus)) {
                publicDecision = "TP ATTEINT";
                publicReason = "Plan terminé au take profit";
                publicEngineReason = "TP_TOUCHED";
                publicScore = lastSignal.score;
            } else if (lastSignal != null && "SL_TOUCHED".equals(activeStatus)) {
                publicDecision = "SL ATTEINT";
                publicReason = "Plan terminé au stop loss";
                publicEngineReason = "SL_TOUCHED";
                publicScore = lastSignal.score;
            }

            String publicAction = "Analyse du marché en cours";
            ObservedSignal publicObserved = observedForLastSignal();
            if (lastSignal != null && "ACTIVE".equals(activeStatus) && publicObserved != null) {
                publicAction = SignalSafetyPolicies.publicAction(
                        publicObserved.finalConfirmedAt, now, true);
                publicDecision = "GÉRER";
                publicReason = "PLAN ACTIF — fin uniquement au TP ou au SL";
                publicEngineReason = "V2326_ACTIVE_RISK_MANAGEMENT";
            } else if (lastSignal != null && "TP_TOUCHED".equals(activeStatus)) {
                publicAction = "TP ATTEINT — PLAN TERMINÉ";
            } else if (lastSignal != null && "SL_TOUCHED".equals(activeStatus)) {
                publicAction = "SL ATTEINT — PLAN TERMINÉ";
            }

            state.put("decision", publicDecision);
            state.put("decisionReason", publicReason);
            state.put("engineReason", publicEngineReason);
            state.put("score", publicScore);
            state.put("action", publicAction);
            if (decision != null) state.put("movement", movementJson(decision));
            if (lastSignal != null) {
                JSONObject signal = signalJson(lastSignal);
                state.put("lastSignal", signal);
                state.put("lastPlan", signal);
            }
            JSONArray recent = new JSONArray();
            for (DiagnosticEntry entry : signalEngine.recentDiagnostics(80)) {
                JSONObject item = new JSONObject();
                item.put("at", entry.timestamp); item.put("code", entry.code); item.put("message", entry.message);
                recent.put(item);
            }
            state.put("diagnostics", recent);
            String output = state.toString();
            LAST_STATUS_JSON = output;
            getSharedPreferences(STATE_PREFERENCES, MODE_PRIVATE).edit().putString(STATE_JSON, output).apply();
            Intent broadcast = new Intent(BROADCAST_STATUS).setPackage(getPackageName());
            broadcast.putExtra(EXTRA_PAYLOAD, output);
            sendBroadcast(broadcast);
        } catch (Exception ignored) {}
    }

    private JSONObject marketStatusJson(MarketProfile profile,double last,double bid,double ask,
                                        int candles,long tickerAt,SignalDecision signal,
                                        boolean active,long terminalAt,String terminal,long now)throws Exception{
        JSONObject value=new JSONObject();value.put("symbol",profile.symbol);value.put("asset",profile.asset);
        value.put("profileVersion",profile.profileVersion);putPrice(value,"last",last);putPrice(value,"bid",bid);putPrice(value,"ask",ask);
        value.put("candles",candles);value.put("feedAgeSec",ageSeconds(now,tickerAt));value.put("active",active);
        value.put("rearmRemainingMs",TerminalRearmPersistence.remainingMs(now,terminalAt));
        value.put("state",active?"PLAN ACTIF":!terminal.isEmpty()?terminal:tickerAt<=0||now-tickerAt>ETH_BOOK_MAX_AGE_MS?"STALE":"ANALYSE");
        double modeledRisk=0;
        if(active&&signal!=null)modeledRisk=signal.quantity*(signal.stopDistance
                +profile.scaledMinimum(profile.riskExecutionAllowanceReference,signal.entry));
        value.put("modeledRiskUsdt",modeledRisk);
        if(signal!=null)value.put("signal",signalJson(signal));return value;
    }

    private JSONObject activePlanJson(ActivePlanState p)throws Exception{
        JSONObject value=new JSONObject();value.put("symbol",p.symbol);value.put("asset",p.asset);
        value.put("profileVersion",p.profileVersion);value.put("side",p.side);value.put("family",p.family);
        value.put("quantity",p.quantity);putPrice(value,"entry",p.entry);putPrice(value,"takeProfit",p.takeProfit);
        putPrice(value,"stopLoss",p.stopLoss);value.put("modeledRiskUsdt",p.theoreticalMaximumLoss);return value;
    }


    private JSONObject engineMetricsJson(MarketSnapshot s, SignalDecision decision) throws Exception {
        JSONObject m = new JSONObject();

        double spread = Double.isFinite(s.ethBid) && Double.isFinite(s.ethAsk) && s.ethBid > 0 && s.ethAsk > 0
                ? s.ethAsk - s.ethBid : Double.NaN;
        double threshold = Math.max(0.75, s.avgRange20 * 0.55);
        double volumeRatio = s.avgVolume20 > 0 ? s.lastVolume / s.avgVolume20 : Double.NaN;
        double recentRange = Math.max(0, s.recentHigh - s.recentLow);

        boolean c1Long = s.move1 > threshold && s.move3 > threshold * 1.15;
        boolean c1Short = s.move1 < -threshold && s.move3 < -threshold * 1.15;
        boolean c2Long = s.move3 > threshold * 1.35 && s.move1 > -s.avgRange20 * 0.25;
        boolean c2Short = s.move3 < -threshold * 1.35 && s.move1 < s.avgRange20 * 0.25;

        String candidate = c1Long ? "C1_LONG"
                : c1Short ? "C1_SHORT"
                : c2Long ? "C2_LONG"
                : c2Short ? "C2_SHORT"
                : "NONE";

        putMetric(m, "spread", spread);
        putMetric(m, "avgRange20", s.avgRange20);
        putMetric(m, "avgVolume20", s.avgVolume20);
        putMetric(m, "lastVolume", s.lastVolume);
        putMetric(m, "volumeRatio", volumeRatio);
        putMetric(m, "threshold", threshold);
        putMetric(m, "move1", s.move1);
        putMetric(m, "move3", s.move3);
        putMetric(m, "move8", s.move8);
        putMetric(m, "recentHigh", s.recentHigh);
        putMetric(m, "recentLow", s.recentLow);
        putMetric(m, "recentRange", recentRange);
        putMetric(m, "flowNorm", s.flowNorm);
        putMetric(m, "btcMove5", s.btcMove5);

        m.put("ethCandlesOk", s.ethCandles >= 30);
        m.put("btcCandlesOk", s.btcCandles >= 10);
        m.put("rangeOk", s.avgRange20 >= 0.15);
        m.put("volumeDataOk", s.avgVolume20 > 0);
        m.put("volumeRatioOk", Double.isFinite(volumeRatio) && volumeRatio >= 0.60);
        m.put("flowLongOk", s.flowNorm >= 0.05);
        m.put("flowShortOk", s.flowNorm <= -0.05);
        m.put("btcLongVeto", s.btcMove5 < -0.0012);
        m.put("btcShortVeto", s.btcMove5 > 0.0012);
        m.put("c1Long", c1Long);
        m.put("c1Short", c1Short);
        m.put("c2Long", c2Long);
        m.put("c2Short", c2Short);
        m.put("setupCandidate", candidate);
        long aggAge = ageSeconds(s.now, lastAggTradeAt);
        long restTradeAge = ageSeconds(s.now, lastRestTradeOkAt);
        long restKlineAge = ageSeconds(s.now, lastRestKlineOkAt);
        boolean wsFlowOk = aggTradeMessages > 0 && !flows.isEmpty() && aggAge >= 0 && aggAge <= 120;
        boolean restFlowOk = restTradeRefreshes > 0 && !flows.isEmpty() && restTradeAge >= 0 && restTradeAge <= 120;

        m.put("flowSamples", flows.size());
        m.put("bookTickerMessages", bookTickerMessages);
        m.put("klineMessages", klineMessages);
        m.put("aggTradeMessages", aggTradeMessages);
        m.put("restKlineRefreshes", restKlineRefreshes);
        m.put("restTradeRefreshes", restTradeRefreshes);
        m.put("lastBookTickerAgeSec", ageSeconds(s.now, lastBookTickerAt));
        m.put("lastKlineAgeSec", ageSeconds(s.now, lastKlineAt));
        m.put("lastAggTradeAgeSec", aggAge);
        m.put("lastRestKlineAgeSec", restKlineAge);
        m.put("lastRestTradeAgeSec", restTradeAge);
        m.put("flowDataOk", wsFlowOk || restFlowOk);
        m.put("flowSource", wsFlowOk ? "WEBSOCKET" : restFlowOk ? "REST_FALLBACK" : "NONE");
        m.put("klineSource", klineMessages > 0 ? "WEBSOCKET" : restKlineRefreshes > 0 ? "REST_FALLBACK" : "PREFILL_ONLY");
        m.put("decisionCode", decision == null ? "NO_DECISION" : decision.reasonCode);
        m.put("decisionText", decision == null ? "Initialisation" : decision.reasonText);
        m.put("rulesProfile", "ETH + SOL Scalper Cockpit v2.34.0 — Multi-Market Research");
        m.put("aiEnabled", AiAdvisor.isEnabled(this));
        m.put("aiStatus", aiStatus);

        return m;
    }

    private static long ageSeconds(long now, long at) {
        return at <= 0 ? -1 : Math.max(0, (now - at) / 1000);
    }

    private static void putMetric(JSONObject object, String key, double value) throws Exception {
        object.put(key, Double.isFinite(value) ? Math.round(value * 1_000_000.0) / 1_000_000.0 : JSONObject.NULL);
    }

    private static void putPrice(JSONObject object, String key, double value) throws Exception {
        object.put(key, Double.isFinite(value) && value > 0 ? value : JSONObject.NULL);
    }

    private static JSONObject movementJson(SignalDecision decision) throws Exception {
        JSONObject movement = new JSONObject();
        movement.put("impulse", decision.impulse);
        movement.put("reset", decision.resetConfirmed);
        movement.put("origin", finiteOrNull(decision.movementOrigin));
        movement.put("extreme", finiteOrNull(decision.movementExtreme));
        movement.put("distance", finiteOrNull(decision.movementDistance));
        movement.put("consumed", decision.movementConsumed);
        return movement;
    }

    private static JSONObject signalJson(SignalDecision decision) throws Exception {
        ConfirmedSignalPayload payload = ConfirmedSignalPayload.from(decision);
        JSONObject signal = new JSONObject();
        signal.put("symbol",decision.symbol);signal.put("asset",decision.asset);
        signal.put("profileVersion",decision.profileVersion);
        signal.put("side", decision.side); signal.put("family", decision.family);
        signal.put("score", decision.score); signal.put("qty", payload.quantityForScreen());
        signal.put("entry", decision.entry); signal.put("tp", decision.takeProfit);
        signal.put("sl", decision.stopLoss); signal.put("targetMove", decision.targetMove);
        signal.put("stopDistance", decision.stopDistance); signal.put("reason", decision.reasonText);
        return signal;
    }

    private static Object finiteOrNull(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }

    private static double avgRange(List<Candle> candles, int count) {
        int start = Math.max(0, candles.size() - count); double total = 0; int samples = 0;
        for (int i=start; i<candles.size(); i++) { total += Math.abs(candles.get(i).high-candles.get(i).low); samples++; }
        return samples == 0 ? 0 : total / samples;
    }

    private static double avgVolume(List<Candle> candles, int count) {
        int start = Math.max(0, candles.size() - count); double total = 0; int samples = 0;
        for (int i=start; i<candles.size(); i++) { total += candles.get(i).volume; samples++; }
        return samples == 0 ? 0 : total / samples;
    }

    private static double high(List<Candle> candles, int count) {
        int start = Math.max(0, candles.size() - count); double value = 0;
        for (int i=start; i<candles.size(); i++) value = Math.max(value, candles.get(i).high);
        return value;
    }

    private static double low(List<Candle> candles, int count) {
        int start = Math.max(0, candles.size() - count); double value = Double.MAX_VALUE;
        for (int i=start; i<candles.size(); i++) value = Math.min(value, candles.get(i).low);
        return value == Double.MAX_VALUE ? 0 : value;
    }

    private static double highBeforeLast(List<Candle> candles, int count) {
        int end = Math.max(0, candles.size() - 1);
        int start = Math.max(0, end - count);
        double value = 0;
        for (int i=start; i<end; i++) value = Math.max(value, candles.get(i).high);
        return value;
    }

    private static double lowBeforeLast(List<Candle> candles, int count) {
        int end = Math.max(0, candles.size() - 1);
        int start = Math.max(0, end - count);
        double value = Double.MAX_VALUE;
        for (int i=start; i<end; i++) value = Math.min(value, candles.get(i).low);
        return value == Double.MAX_VALUE ? 0 : value;
    }

    private static double percentMove(List<Candle> candles, int periods) {
        if (candles.size() < periods + 1) return 0;
        double current = candles.get(candles.size() - 1).close;
        double previous = candles.get(candles.size() - 1 - periods).close;
        if (previous <= 0) return 0;
        return (current - previous) / previous;
    }

    static final class Candle {
        final long openTime; final double open, high, low, close, volume;
        Candle(long openTime, double open, double high, double low, double close, double volume) {
            this.openTime=openTime; this.open=open; this.high=high; this.low=low; this.close=close; this.volume=volume;
        }
    }

    static final class MarketFrame {
        final long at;
        final double ethLast, ethBid, ethAsk, spread;
        final double btcLast;
        final double avgRange20, avgVolume20, lastVolume, volumeRatio;
        final double flowNorm, btcMove5;
        final double move1, move3, move8, move15;
        final double recentHigh, recentLow, recentRange;

        final double move1Norm, move3Norm, move8Norm;
        final double moveAccel13, moveAccel38;
        final double rangePosition, distanceToHigh, distanceToLow;
        final double roomLong, roomShort, pullbackFromHigh, pullbackFromLow;
        final double flow15, flow30, flow60, flow120;
        final double deltaFlow15_60, deltaFlow30_120, flowAccel;
        final double btcMove1, btcMove3, btcMove8;
        final double btcAccel1_5, btcAccel3_8;
        final double breakoutHighDistance, breakoutLowDistance, antiBurstScore;
        final String setupCandidate;
        final String decision, decisionCode, decisionText;
        final boolean isSignal;
        final String side, family;
        final int score, qty;
        final double entry, tp, sl, targetMove, stopDistance;

        final String learnedCandidateSide;
        final String learnedCandidateType;
        final int learnedCandidateScore;
        final double learnedOppositeMove8;
        final double learnedDirectionalMove3;
        final double learnedBtcDir;
        final double learnedRecentRangeRatio;

        final String hypothesisPrimarySide;
        final String hypothesisPrimaryType;
        final int hypothesisPrimaryScore;

        final String hypEngineInverseSide;
        final int hypEngineInverseScore;
        final String hypC1InverseSide;
        final int hypC1InverseScore;
        final String hypC2InverseSide;
        final int hypC2InverseScore;
        final String hypRangeFadeSide;
        final int hypRangeFadeScore;
        final String hypMove1ReversalSide;
        final int hypMove1ReversalScore;
        final String hypContinuationSide;
        final int hypContinuationScore;

        double futureMax5, futureMin5;
        double futureMax10, futureMin10;
        double futureMax15, futureMin15;
        boolean futureClosed15;

        long longHit2Sec = -1;
        long longHit22Sec = -1;
        long longHit28Sec = -1;
        long longHit35Sec = -1;
        long shortHit2Sec = -1;
        long shortHit22Sec = -1;
        long shortHit28Sec = -1;
        long shortHit35Sec = -1;

        double longAdverseBefore2;
        double longAdverseBefore22;
        double longAdverseBefore28;
        double longAdverseBefore35;
        double shortAdverseBefore2;
        double shortAdverseBefore22;
        double shortAdverseBefore28;
        double shortAdverseBefore35;

        MarketFrame(long at, MarketSnapshot s, SignalDecision d, String setupCandidate) {
            this.at = at;
            this.ethLast = s.ethLast;
            this.ethBid = s.ethBid;
            this.ethAsk = s.ethAsk;
            this.spread = Double.isFinite(s.ethAsk) && Double.isFinite(s.ethBid)
                    && s.ethAsk > 0 && s.ethBid > 0 ? s.ethAsk - s.ethBid : Double.NaN;
            this.btcLast = s.btcLast;
            this.avgRange20 = s.avgRange20;
            this.avgVolume20 = s.avgVolume20;
            this.lastVolume = s.lastVolume;
            this.volumeRatio = s.avgVolume20 > 0 ? s.lastVolume / s.avgVolume20 : Double.NaN;
            this.flowNorm = s.flowNorm;
            this.btcMove5 = s.btcMove5;
            this.move1 = s.move1;
            this.move3 = s.move3;
            this.move8 = s.move8;
            this.move15 = s.move15;
            this.recentHigh = s.recentHigh;
            this.recentLow = s.recentLow;
            this.recentRange = Math.max(0, s.recentHigh - s.recentLow);

            this.move1Norm = s.move1Norm;
            this.move3Norm = s.move3Norm;
            this.move8Norm = s.move8Norm;
            this.moveAccel13 = s.moveAccel13;
            this.moveAccel38 = s.moveAccel38;
            this.rangePosition = s.rangePosition;
            this.distanceToHigh = s.distanceToHigh;
            this.distanceToLow = s.distanceToLow;
            this.roomLong = s.roomLong;
            this.roomShort = s.roomShort;
            this.pullbackFromHigh = s.pullbackFromHigh;
            this.pullbackFromLow = s.pullbackFromLow;
            this.flow15 = s.flow15;
            this.flow30 = s.flow30;
            this.flow60 = s.flow60;
            this.flow120 = s.flow120;
            this.deltaFlow15_60 = s.deltaFlow15_60;
            this.deltaFlow30_120 = s.deltaFlow30_120;
            this.flowAccel = s.flowAccel;
            this.btcMove1 = s.btcMove1;
            this.btcMove3 = s.btcMove3;
            this.btcMove8 = s.btcMove8;
            this.btcAccel1_5 = s.btcAccel1_5;
            this.btcAccel3_8 = s.btcAccel3_8;
            this.breakoutHighDistance = s.breakoutHighDistance;
            this.breakoutLowDistance = s.breakoutLowDistance;
            this.antiBurstScore = s.antiBurstScore;

            this.setupCandidate = setupCandidate;

            PlaybackCandidate candidate = PlaybackCandidate.from(s);
            this.learnedCandidateSide = candidate.side;
            this.learnedCandidateType = candidate.type;
            this.learnedCandidateScore = candidate.score;
            this.learnedOppositeMove8 = candidate.oppositeMove8;
            this.learnedDirectionalMove3 = candidate.directionalMove3;
            this.learnedBtcDir = candidate.btcDir;
            this.learnedRecentRangeRatio = candidate.recentRangeRatio;

            HypothesisPack hypotheses = HypothesisPack.from(s, d, setupCandidate);
            this.hypothesisPrimarySide = hypotheses.primary.side;
            this.hypothesisPrimaryType = hypotheses.primary.type;
            this.hypothesisPrimaryScore = hypotheses.primary.score;

            this.hypEngineInverseSide = hypotheses.engineInverse.side;
            this.hypEngineInverseScore = hypotheses.engineInverse.score;
            this.hypC1InverseSide = hypotheses.c1Inverse.side;
            this.hypC1InverseScore = hypotheses.c1Inverse.score;
            this.hypC2InverseSide = hypotheses.c2Inverse.side;
            this.hypC2InverseScore = hypotheses.c2Inverse.score;
            this.hypRangeFadeSide = hypotheses.rangeFade.side;
            this.hypRangeFadeScore = hypotheses.rangeFade.score;
            this.hypMove1ReversalSide = hypotheses.move1Reversal.side;
            this.hypMove1ReversalScore = hypotheses.move1Reversal.score;
            this.hypContinuationSide = hypotheses.continuation.side;
            this.hypContinuationScore = hypotheses.continuation.score;

            this.futureMax5 = s.ethLast;
            this.futureMin5 = s.ethLast;
            this.futureMax10 = s.ethLast;
            this.futureMin10 = s.ethLast;
            this.futureMax15 = s.ethLast;
            this.futureMin15 = s.ethLast;

            this.decision = d == null ? "ATTENDRE" : d.decision;
            this.decisionCode = d == null ? "NO_DECISION" : d.reasonCode;
            this.decisionText = d == null ? "" : d.reasonText;
            this.isSignal = d != null && d.isSignal();
            this.side = isSignal ? d.side : "";
            this.family = isSignal ? d.family : "";
            this.score = d == null ? 0 : d.score;
            this.qty = isSignal ? d.quantity : 0;
            this.entry = isSignal ? d.entry : Double.NaN;
            this.tp = isSignal ? d.takeProfit : Double.NaN;
            this.sl = isSignal ? d.stopLoss : Double.NaN;
            this.targetMove = isSignal ? d.targetMove : Double.NaN;
            this.stopDistance = isSignal ? d.stopDistance : Double.NaN;
        }
    }

    static final class PlaybackCandidate {
        final String side;
        final String type;
        final int score;
        final double oppositeMove8;
        final double directionalMove3;
        final double btcDir;
        final double recentRangeRatio;

        PlaybackCandidate(String side, String type, int score,
                          double oppositeMove8, double directionalMove3,
                          double btcDir, double recentRangeRatio) {
            this.side = side;
            this.type = type;
            this.score = score;
            this.oppositeMove8 = oppositeMove8;
            this.directionalMove3 = directionalMove3;
            this.btcDir = btcDir;
            this.recentRangeRatio = recentRangeRatio;
        }

        static PlaybackCandidate none() {
            return new PlaybackCandidate("NONE", "NONE", 0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        static PlaybackCandidate from(MarketSnapshot s) {
            PlaybackCandidate longC = forSide("LONG", 1, s);
            PlaybackCandidate shortC = forSide("SHORT", -1, s);

            if (longC.score <= 0 && shortC.score <= 0) return none();
            if (longC.score > 0 && shortC.score > 0 && Math.abs(longC.score - shortC.score) < 4) {
                return new PlaybackCandidate("NONE", "CONFLICT", 0,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            return longC.score >= shortC.score ? longC : shortC;
        }

        static PlaybackCandidate forSide(String sideName, int side, MarketSnapshot s) {
            double avgRange = Math.max(0.35, s.avgRange20);
            double recentRange = Math.max(0, s.recentHigh - s.recentLow);
            double recentRangeRatio = avgRange > 0 ? recentRange / avgRange : Double.NaN;

            double oppositeMove8 = -side * s.move8;
            double directionalMove3 = side * s.move3;
            double btcDir = side * s.btcMove5;

            boolean btcExhaustionOk = Double.isFinite(btcDir) && btcDir <= 0.00010;

            boolean reversalLaunch =
                    oppositeMove8 > 0.95
                            && recentRangeRatio <= 5.96
                            && directionalMove3 > 0.98
                            && avgRange <= 1.06
                            && btcExhaustionOk;

            boolean extremeExhaustion =
                    oppositeMove8 > 0.95
                            && recentRangeRatio > 5.96
                            && btcExhaustionOk;

            if (!reversalLaunch && !extremeExhaustion) {
                return none();
            }

            int score = reversalLaunch ? 82 : 78;
            score += clamp((int)Math.round((oppositeMove8 - 0.95) * 4.0), 0, 8);
            score += clamp((int)Math.round((directionalMove3 - 0.98) * 3.0), 0, 6);
            score += btcDir <= 0 ? 4 : 0;
            score += recentRangeRatio > 5.96 ? 4 : 0;
            score = clamp(score, 0, 96);

            return new PlaybackCandidate(sideName,
                    reversalLaunch ? "REVERSAL_LAUNCH_PLAYBACK" : "EXTREME_EXHAUSTION_PLAYBACK",
                    score, oppositeMove8, directionalMove3, btcDir, recentRangeRatio);
        }

        static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    static final class HypothesisPack {
        final HypothesisCandidate primary;
        final HypothesisCandidate engineInverse;
        final HypothesisCandidate c1Inverse;
        final HypothesisCandidate c2Inverse;
        final HypothesisCandidate rangeFade;
        final HypothesisCandidate move1Reversal;
        final HypothesisCandidate continuation;

        HypothesisPack(HypothesisCandidate primary,
                       HypothesisCandidate engineInverse,
                       HypothesisCandidate c1Inverse,
                       HypothesisCandidate c2Inverse,
                       HypothesisCandidate rangeFade,
                       HypothesisCandidate move1Reversal,
                       HypothesisCandidate continuation) {
            this.primary = primary;
            this.engineInverse = engineInverse;
            this.c1Inverse = c1Inverse;
            this.c2Inverse = c2Inverse;
            this.rangeFade = rangeFade;
            this.move1Reversal = move1Reversal;
            this.continuation = continuation;
        }

        static HypothesisPack from(MarketSnapshot s, SignalDecision d, String setupCandidate) {
            HypothesisCandidate engineInverse = engineInverse(s, d);
            HypothesisCandidate c1Inverse = setupInverse(s, setupCandidate, "C1", "C1_EXHAUSTION_INVERSE_TEST");
            HypothesisCandidate c2Inverse = setupInverse(s, setupCandidate, "C2", "C2_EXHAUSTION_INVERSE_TEST");
            HypothesisCandidate rangeFade = rangeFade(s);
            HypothesisCandidate move1Reversal = move1Reversal(s);
            HypothesisCandidate continuation = continuation(s, setupCandidate);

            HypothesisCandidate primary = best(engineInverse, c1Inverse, c2Inverse, rangeFade, move1Reversal);
            if (primary.score <= 0) primary = continuation;

            return new HypothesisPack(primary, engineInverse, c1Inverse, c2Inverse,
                    rangeFade, move1Reversal, continuation);
        }

        static HypothesisCandidate engineInverse(MarketSnapshot s, SignalDecision d) {
            if (d == null || !d.isSignal()) return HypothesisCandidate.none();
            String side = opposite(d.side);
            if ("NONE".equals(side)) return HypothesisCandidate.none();

            int score = 68;
            score += clamp(d.score / 5, 0, 18);
            score += clamp((int)Math.round(Math.abs(s.move8) * 1.5), 0, 10);
            score += clamp((int)Math.round(Math.abs(s.flowNorm) * 4.0), 0, 8);
            return new HypothesisCandidate(side, "ENGINE_SIGNAL_INVERSE_TEST", clamp(score, 0, 96));
        }

        static HypothesisCandidate setupInverse(MarketSnapshot s, String setup, String prefix, String type) {
            if (setup == null || !setup.startsWith(prefix)) return HypothesisCandidate.none();
            String setupSide = setupSide(setup);
            String side = opposite(setupSide);
            if ("NONE".equals(side)) return HypothesisCandidate.none();

            double avgRange = Math.max(0.35, s.avgRange20);
            double recentRange = Math.max(0, s.recentHigh - s.recentLow);
            double recentRangeRatio = recentRange / avgRange;
            double extension = Math.abs(s.move8);
            if (extension < 1.60) return HypothesisCandidate.none();

            int score = "C1".equals(prefix) ? 58 : 52;
            score += clamp((int)Math.round(extension * 3.0), 0, 18);
            score += recentRangeRatio >= 2.6 ? 8 : 0;
            score += recentRangeRatio >= 3.4 ? 5 : 0;
            score += clamp((int)Math.round(Math.abs(s.move3) * 1.5), 0, 8);
            return new HypothesisCandidate(side, type, clamp(score, 0, 94));
        }

        static HypothesisCandidate rangeFade(MarketSnapshot s) {
            double avgRange = Math.max(0.35, s.avgRange20);
            double recentRange = Math.max(0, s.recentHigh - s.recentLow);
            double ratio = recentRange / avgRange;
            if (ratio < 3.0 || Math.abs(s.move8) < 1.50) return HypothesisCandidate.none();

            String side = s.move8 > 0 ? "SHORT" : s.move8 < 0 ? "LONG" : "NONE";
            if ("NONE".equals(side)) return HypothesisCandidate.none();

            int score = 54;
            score += clamp((int)Math.round((ratio - 3.0) * 6.0), 0, 18);
            score += clamp((int)Math.round(Math.abs(s.move8) * 1.2), 0, 10);
            return new HypothesisCandidate(side, "RANGE_FADE_TEST", clamp(score, 0, 92));
        }

        static HypothesisCandidate move1Reversal(MarketSnapshot s) {
            if (Math.abs(s.move8) < 2.50) return HypothesisCandidate.none();
            if (s.move1 * s.move8 >= 0) return HypothesisCandidate.none();

            String side = s.move8 > 0 ? "SHORT" : s.move8 < 0 ? "LONG" : "NONE";
            if ("NONE".equals(side)) return HypothesisCandidate.none();

            int score = 56;
            score += clamp((int)Math.round(Math.abs(s.move8) * 1.3), 0, 10);
            score += clamp((int)Math.round(Math.abs(s.move1) * 2.0), 0, 10);
            return new HypothesisCandidate(side, "MOVE1_REVERSAL_TEST", clamp(score, 0, 92));
        }

        static HypothesisCandidate continuation(MarketSnapshot s, String setup) {
            String side = setupSide(setup);
            if ("NONE".equals(side)) return HypothesisCandidate.none();

            int score = 45;
            score += setup != null && setup.startsWith("C1") ? 8 : 4;
            score += clamp((int)Math.round(Math.abs(s.move3) * 1.5), 0, 10);
            score += clamp((int)Math.round(Math.abs(s.flowNorm) * 3.0), 0, 8);
            return new HypothesisCandidate(side, "CONTINUATION_CONTROL", clamp(score, 0, 80));
        }

        static HypothesisCandidate best(HypothesisCandidate... candidates) {
            HypothesisCandidate best = HypothesisCandidate.none();
            for (HypothesisCandidate c : candidates) {
                if (c != null && c.score > best.score) best = c;
            }
            return best;
        }

        static String setupSide(String setup) {
            if (setup == null) return "NONE";
            if (setup.endsWith("_LONG")) return "LONG";
            if (setup.endsWith("_SHORT")) return "SHORT";
            return "NONE";
        }

        static String opposite(String side) {
            if ("LONG".equals(side)) return "SHORT";
            if ("SHORT".equals(side)) return "LONG";
            return "NONE";
        }

        static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    static final class HypothesisCandidate {
        final String side;
        final String type;
        final int score;

        HypothesisCandidate(String side, String type, int score) {
            this.side = side;
            this.type = type;
            this.score = score;
        }

        static HypothesisCandidate none() {
            return new HypothesisCandidate("NONE", "NONE", 0);
        }
    }

    static final class ObservedSignal {
        final long id;
        final long createdAt;
        SignalDecision signal;
        long lastUpdateAt;
        long closedAt;
        long exitAt;
        double exitPrice = Double.NaN;
        String exitReason = "";
        int updates;
        String status = "LIMIT_PENDING";
        double lastPrice;
        double maxPrice;
        double minPrice;
        double mfe;
        double mae;
        boolean entryTriggered;
        long entryTriggeredAt;
        double entryTriggerPrice = Double.NaN;
        boolean timeoutExtended;
        long timeoutDecisionAt;
        long timeoutExtensionUntil;
        final boolean marketableAtCreation;
        final double creationBid;
        final double creationAsk;
        final double creationLast;
        final double plannedEntry;
        long firstEntryTouchAt;
        double firstEntryTouchPrice = Double.NaN;
        double firstEntryTouchBid = Double.NaN;
        double firstEntryTouchAsk = Double.NaN;
        long simulatedFillAt;
        double simulatedFillPrice = Double.NaN;
        boolean manualEntryConfirmed;
        double manualEntryPrice = Double.NaN;
        long manualEntryAt;
        long departureAt;
        long finalConfirmedAt;
        long confirmationAt;
        boolean executableAtConfirmation;
        double finalConfirmationBid = Double.NaN;
        double finalConfirmationAsk = Double.NaN;
        double finalConfirmationA = Double.NaN;
        double finalConfirmationR = Double.NaN;
        double adverseExcursion60;
        double prefillFavorableExcursion;
        String sleeve = CandidateLifecycle.SLEEVE_P01;
        String p02Mode = "";
        NormalizedSignalMetrics.Result normalizedMetrics;
        P01SleeveFilter.Result p01SleeveFilter;
        P02SleeveFilter.Result p02SleeveFilter;
        TrendRegime60.Result trendRegime60;
        boolean premium15m;
        String notificationSignature = "";
        String candidateSignature = "";
        int notificationId;
        boolean alertSent;
        boolean blockedByActiveSignalRecorded;
        String activeSignalPublicationBlockDetail = "";
        String entryRevalidationCode = "";
        String lastConfirmationCode = "";
        String replayRiskReasonCode = "";
        String replayRiskDetail = "";
        boolean replayRiskBlocking;
        ConfirmedSizing.Result confirmedSizing;
        DynamicTradePlan.Result dynamicTradePlan;
        String restoredSizingDiagnostic = "";
        double p01Move1Aligned = Double.NaN;
        double p01Move3Aligned = Double.NaN;
        double p01Move8Aligned = Double.NaN;
        double p01Move15Aligned = Double.NaN;
        double p01Flow30Aligned = Double.NaN;
        boolean p01EarlyEligible;
        long p01EarlyQualitySince;
        String p01EarlyQualityMode = "";
        long p01EarlyStabilityMs;
        String p01EarlyLastReasonCode = "";
        long p01EarlyAgeAtConfirmationMs = -1L;
        double p01EarlyTriggerBid = Double.NaN;
        double p01EarlyTriggerAsk = Double.NaN;
        double p01EarlyQuoteDistance = Double.NaN;
        double p01EarlySecondsSaved = Double.NaN;

        final double signalEthLast;
        final double signalBid;
        final double signalAsk;
        final double signalSpread;
        final double avgRange20;
        final double avgVolume20;
        final double lastVolume;
        final double volumeRatio;
        final double flowNorm;
        final double btcMove5;
        final double move1;
        final double move3;
        final double move8;
        final double recentHigh;
        final double recentLow;
        final double recentRange;

        final double move1Norm;
        final double move3Norm;
        final double move8Norm;
        final double moveAccel13;
        final double moveAccel38;
        final double rangePosition;
        final double distanceToHigh;
        final double distanceToLow;
        final double roomLong;
        final double roomShort;
        final double flow15;
        final double flow30;
        final double flow60;
        final double flow120;
        final double deltaFlow15_60;
        final double deltaFlow30_120;
        final double flowAccel;
        final double btcMove1;
        final double btcMove3;
        final double btcMove8;
        final double btcAccel1_5;
        final double btcAccel3_8;
        final double antiBurstScore;

        ObservedSignal(long id, long createdAt, SignalDecision signal, double price, MarketSnapshot snapshot) {
            this.id = id;
            this.createdAt = createdAt;
            this.signal = signal;
            this.sleeve = signal != null && signal.family != null && signal.family.contains("P02")
                    ? CandidateLifecycle.SLEEVE_P02 : CandidateLifecycle.SLEEVE_P01;
            this.lastUpdateAt = createdAt;
            this.lastPrice = price;
            this.maxPrice = price;
            this.minPrice = price;
            this.creationBid = snapshot.ethBid;
            this.creationAsk = snapshot.ethAsk;
            this.creationLast = snapshot.ethLast;
            this.plannedEntry = signal.entry;
            this.marketableAtCreation = SignalSafetyPolicies.marketableAtCreation(
                    signal.side, snapshot.ethBid, snapshot.ethAsk, signal.entry);
            if (marketableAtCreation) {
                this.firstEntryTouchAt = createdAt;
                this.firstEntryTouchPrice = price;
                this.firstEntryTouchBid = snapshot.ethBid;
                this.firstEntryTouchAsk = snapshot.ethAsk;
            }
            observeAdverseExcursion60(this, snapshot, createdAt);

            this.signalEthLast = snapshot.ethLast;
            this.signalBid = snapshot.ethBid;
            this.signalAsk = snapshot.ethAsk;
            this.signalSpread = Double.isFinite(snapshot.ethAsk) && Double.isFinite(snapshot.ethBid)
                    && snapshot.ethAsk > 0 && snapshot.ethBid > 0 ? snapshot.ethAsk - snapshot.ethBid : Double.NaN;
            this.avgRange20 = snapshot.avgRange20;
            this.avgVolume20 = snapshot.avgVolume20;
            this.lastVolume = snapshot.lastVolume;
            this.volumeRatio = snapshot.avgVolume20 > 0 ? snapshot.lastVolume / snapshot.avgVolume20 : Double.NaN;
            this.flowNorm = snapshot.flowNorm;
            this.btcMove5 = snapshot.btcMove5;
            this.move1 = snapshot.move1;
            this.move3 = snapshot.move3;
            this.move8 = snapshot.move8;
            this.recentHigh = snapshot.recentHigh;
            this.recentLow = snapshot.recentLow;
            this.recentRange = Math.max(0, snapshot.recentHigh - snapshot.recentLow);

            this.move1Norm = snapshot.move1Norm;
            this.move3Norm = snapshot.move3Norm;
            this.move8Norm = snapshot.move8Norm;
            this.moveAccel13 = snapshot.moveAccel13;
            this.moveAccel38 = snapshot.moveAccel38;
            this.rangePosition = snapshot.rangePosition;
            this.distanceToHigh = snapshot.distanceToHigh;
            this.distanceToLow = snapshot.distanceToLow;
            this.roomLong = snapshot.roomLong;
            this.roomShort = snapshot.roomShort;
            this.flow15 = snapshot.flow15;
            this.flow30 = snapshot.flow30;
            this.flow60 = snapshot.flow60;
            this.flow120 = snapshot.flow120;
            this.deltaFlow15_60 = snapshot.deltaFlow15_60;
            this.deltaFlow30_120 = snapshot.deltaFlow30_120;
            this.flowAccel = snapshot.flowAccel;
            this.btcMove1 = snapshot.btcMove1;
            this.btcMove3 = snapshot.btcMove3;
            this.btcMove8 = snapshot.btcMove8;
            this.btcAccel1_5 = snapshot.btcAccel1_5;
            this.btcAccel3_8 = snapshot.btcAccel3_8;
            this.antiBurstScore = snapshot.antiBurstScore;
        }
    }

    static final class TradeFlow {
        final long time; final double signedQuantity;
        TradeFlow(long time, double signedQuantity) { this.time=time; this.signedQuantity=signedQuantity; }
    }
}
