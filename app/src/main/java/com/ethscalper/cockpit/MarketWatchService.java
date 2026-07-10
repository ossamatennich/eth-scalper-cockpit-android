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
import java.util.List;
import java.util.Locale;
import java.io.IOException;
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
    private static final String CH_SIGNAL = "eth_scalper_signal_loud_v22801";
    private static final String STATE_PREFERENCES = "market_watch_state";
    private static final String STATE_JSON = "last_status_json";
    private static final int NOTIF_WATCH_ID = 22801;
    private static final long[] ALERT_VIBRATION = {0, 750, 180, 750, 180, 1200};
    private static final long OBSERVATION_MAX_AGE_MS = 15 * 60 * 1000L;
    private static final String BINANCE_STREAM = "wss://fstream.binance.com/stream?streams=" +
            "ethusdt@kline_1m/ethusdt@aggTrade/ethusdt@bookTicker/" +
            "btcusdt@kline_1m/btcusdt@bookTicker";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<Candle> ethCandles = new ArrayDeque<>();
    private final Deque<Candle> btcCandles = new ArrayDeque<>();
    private final Deque<TradeFlow> flows = new ArrayDeque<>();
    private final SignalEngine signalEngine = new SignalEngine();
    private final Deque<ObservedSignal> observedSignals = new ArrayDeque<>();
    private final Deque<MarketFrame> marketFrames = new ArrayDeque<>();
    private long observedSignalId;
    private long lastMarketFrameAt;
    private long lastMarketFrameJsonRefreshAt;

    private OkHttpClient client;
    private WebSocket socket;
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private boolean healthScheduled;
    private long lastMessageAt;
    private long lastSignalAt;
    private long lastStatusAt;
    private long lastEvaluationAt;
    private long lastWatchNotificationAt;
    private long bookTickerMessages;
    private long klineMessages;
    private long aggTradeMessages;
    private long lastBookTickerAt;
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
    public static volatile String LAST_STATUS_JSON = "";
    public static volatile String LAST_MARKET_FRAMES_JSON = "[]";
    public static volatile String LAST_MARKET_SUMMARY_JSON = "{}";

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

    @Override public void onCreate() {
        super.onCreate();
        ensureChannels(this);
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
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
            signalEngine.clearDiagnostics();
            lastDecision = null;
            broadcastStatus("diagnostics_reset", "Diagnostic moteur réinitialisé");
        } else if (ACTION_SYNC_NOW.equals(action)) {
            broadcastStatus("sync", "État du service natif resynchronisé");
        }
        connectIfNeeded();
        prefillHistoricalCandlesIfNeeded();
        scheduleHealthCheck();
        return START_STICKY;
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

        NotificationChannel signals = new NotificationChannel(CH_SIGNAL, "Signaux ETH — pro score engine v2.29.1",
                NotificationManager.IMPORTANCE_HIGH);
        signals.setDescription("Signal manuel ETH : son fort, vibration longue et écran verrouillé.");
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
        boolean needKlines = lastRestKlineRefreshAt == 0
                || now - lastRestKlineRefreshAt >= 15_000L
                || ethCandles.size() < 30
                || btcCandles.size() < 10
                || liveKlineAge < 0
                || liveKlineAge > 120;

        if (needKlines) {
            lastRestKlineRefreshAt = now;
            fetchRestKlines("ETHUSDT", true);
            fetchRestKlines("BTCUSDT", false);
        }

        long liveTradeAge = ageSeconds(now, lastAggTradeAt);
        boolean needTrades = lastRestTradeRefreshAt == 0
                || now - lastRestTradeRefreshAt >= 7_000L
                || flows.isEmpty()
                || liveTradeAge < 0
                || liveTradeAge > 120;

        if (needTrades) {
            lastRestTradeRefreshAt = now;
            fetchRestAggTrades("ETHUSDT");
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
        fetchHistoricalKlines("ETHUSDT", true);
        fetchHistoricalKlines("BTCUSDT", false);
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

    private void handleMessage(String text) {
        try {
            JSONObject root = new JSONObject(text);
            String stream = root.optString("stream", "");
            JSONObject data = root.optJSONObject("data");
            if (data == null) return;
            String normalizedStream = stream.toLowerCase(Locale.ROOT);
            if (normalizedStream.contains("bookticker")) handleBookTicker(stream, data);
            else if (normalizedStream.contains("kline_1m")) handleKline(stream, data);
            else if (normalizedStream.contains("aggtrade")) handleAggTrade(data);

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
        lastBookTickerAt = System.currentTimeMillis();
        if (stream.startsWith("ethusdt")) {
            ethBid = data.optDouble("b", ethBid);
            ethAsk = data.optDouble("a", ethAsk);
            if (ethBid > 0 && ethAsk > 0) ethLast = (ethBid + ethAsk) / 2.0;
        } else if (stream.startsWith("btcusdt")) {
            btcBid = data.optDouble("b", btcBid);
            btcAsk = data.optDouble("a", btcAsk);
            if (btcBid > 0 && btcAsk > 0) btcLast = (btcBid + btcAsk) / 2.0;
        }
    }

    private void handleKline(String stream, JSONObject data) {
        klineMessages++;
        lastKlineAt = System.currentTimeMillis();
        JSONObject kline = data.optJSONObject("k");
        if (kline == null) return;
        Candle candle = new Candle(kline.optLong("t"), kline.optDouble("o"), kline.optDouble("h"),
                kline.optDouble("l"), kline.optDouble("c"), kline.optDouble("v"));
        if (stream.startsWith("ethusdt")) {
            ethLast = candle.close;
            upsert(ethCandles, candle, 180);
        } else if (stream.startsWith("btcusdt")) {
            btcLast = candle.close;
            upsert(btcCandles, candle, 180);
        }
    }

    private void handleAggTrade(JSONObject data) {
        aggTradeMessages++;
        lastAggTradeAt = System.currentTimeMillis();
        long tradeId = data.optLong("a", -1);
        if (tradeId > lastRestAggTradeId) lastRestAggTradeId = tradeId;
        long time = data.optLong("T", System.currentTimeMillis());
        double quantity = data.optDouble("q", 0);
        flows.addLast(new TradeFlow(time, data.optBoolean("m", false) ? -quantity : quantity));
        pruneFlows(System.currentTimeMillis());
    }

    private synchronized void evaluateSignal(long now) {
        MarketSnapshot snapshot = buildSnapshot(now);
        updateObservedSignals(snapshot, now);

        SignalDecision decision = signalEngine.evaluate(snapshot);
        recordMarketFrame(snapshot, decision, now);

        if (!decision.isSignal() && isLastSignalStillActionable(snapshot, now)) {
            lastDecision = lastSignal;
            broadcastStatus("signal_active", "Signal actif conservé : marché encore valide");
            return;
        }

        lastDecision = decision;
        if (decision.isSignal()) {
            if (isDuplicateRecentObservation(decision, now)) {
                broadcastStatus("signal_duplicate_lock", "Signal doublon ignoré par verrou observation");
                return;
            }

            lastSignal = decision;
            lastSignalAt = now;
            recordObservedSignal(decision, snapshot, now);
            notifyObservationSignal(decision);
            broadcastStatus("signal_observation", decision.reasonCode);
        }
    }



    private void recordMarketFrame(MarketSnapshot snapshot, SignalDecision decision, long now) {
        if (lastMarketFrameAt > 0 && now - lastMarketFrameAt < 1000
                && (decision == null || !decision.isSignal())) return;
        lastMarketFrameAt = now;

        MarketFrame frame = new MarketFrame(now, snapshot, decision, setupCandidateFor(snapshot));
        marketFrames.addLast(frame);
        updateMarketFrameFutureLabels(snapshot.ethLast, now);

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
        double threshold = Math.max(0.75, s.avgRange20 * 0.55);
        boolean c1Long = s.move1 > threshold && s.move3 > threshold * 1.15;
        boolean c1Short = s.move1 < -threshold && s.move3 < -threshold * 1.15;
        boolean c2Long = s.move3 > threshold * 1.35 && s.move1 > -s.avgRange20 * 0.25;
        boolean c2Short = s.move3 < -threshold * 1.35 && s.move1 < s.avgRange20 * 0.25;
        if (c1Long) return "C1_LONG";
        if (c1Short) return "C1_SHORT";
        if (c2Long) return "C2_LONG";
        if (c2Short) return "C2_SHORT";
        return "NONE";
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

    private boolean isDuplicateRecentObservation(SignalDecision decision, long now) {
        if (decision == null || !decision.isSignal()) return false;

        for (ObservedSignal item : observedSignals) {
            if (item == null || item.signal == null) continue;
            if (now - item.createdAt <= 5000
                    && decision.side.equals(item.signal.side)
                    && Math.abs(decision.entry - item.signal.entry) <= 0.05) {
                return true;
            }
        }

        return false;
    }

    private void recordObservedSignal(SignalDecision decision, MarketSnapshot snapshot, long now) {
        double price = Double.isFinite(snapshot.ethLast) && snapshot.ethLast > 0 ? snapshot.ethLast : decision.entry;
        ObservedSignal item = new ObservedSignal(++observedSignalId, now, decision, price, snapshot);
        observedSignals.addLast(item);
        while (observedSignals.size() > 200) observedSignals.removeFirst();
    }

    private void updateObservedSignals(MarketSnapshot snapshot, long now) {
        for (ObservedSignal item : observedSignals) {
            if (!"ACTIVE".equals(item.status)) continue;

            double price = snapshot.ethLast;
            if (!Double.isFinite(price) || price <= 0) continue;

            item.lastPrice = price;
            item.lastUpdateAt = now;
            item.updates++;
            item.maxPrice = Math.max(item.maxPrice, price);
            item.minPrice = Math.min(item.minPrice, price);

            if ("LONG".equals(item.signal.side)) {
                item.mfe = Math.max(0, item.maxPrice - item.signal.entry);
                item.mae = Math.max(0, item.signal.entry - item.minPrice);
            } else if ("SHORT".equals(item.signal.side)) {
                item.mfe = Math.max(0, item.signal.entry - item.minPrice);
                item.mae = Math.max(0, item.maxPrice - item.signal.entry);
            }

            String status = now - item.createdAt >= OBSERVATION_MAX_AGE_MS
                    ? "TIMEOUT_15M"
                    : marketStatusForSignal(item.signal, snapshot);

            if (!"ACTIVE".equals(status) && !"NONE".equals(status) && !"NO_PRICE".equals(status)) {
                item.status = status;
                item.closedAt = now;
                notifyObservationStatus(item, status);
            }
        }
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
            o.put("updates", item.updates);

            o.put("side", item.signal.side);
            o.put("family", item.signal.family);
            o.put("score", item.signal.score);
            o.put("qty", item.signal.quantity);
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

            if ("LONG".equals(item.signal.side)) {
                putMetric(o, "unrealizedMove", item.lastPrice - item.signal.entry);
            } else if ("SHORT".equals(item.signal.side)) {
                putMetric(o, "unrealizedMove", item.signal.entry - item.lastPrice);
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

        putMetric(m, "targetNetPerEthAfterFees", item.signal.targetMove - 1.33);
        putMetric(m, "stopCostPerEthAfterFees", item.signal.stopDistance + 1.33);
        return m;
    }

    private JSONObject observationSummaryJson() throws Exception {
        JSONObject o = new JSONObject();
        int total = 0, active = 0, tp = 0, sl = 0, invalid = 0;

        for (ObservedSignal item : observedSignals) {
            total++;
            if ("ACTIVE".equals(item.status)) active++;
            else if ("TP_TOUCHED".equals(item.status)) tp++;
            else if ("SL_TOUCHED".equals(item.status)) sl++;
            else invalid++;
        }

        o.put("totalSignalsObserved", total);
        o.put("active", active);
        o.put("tpTouched", tp);
        o.put("slTouched", sl);
        o.put("invalidated", invalid);
        o.put("mode", "RESEARCH_JOURNAL");
        o.put("maxStoredSignals", 200);
        return o;
    }

    private boolean isLastSignalStillActionable(MarketSnapshot snapshot, long now) {
        return "ACTIVE".equals(activeSignalStatus(snapshot, now));
    }

    private String observedStatusForLastSignal() {
        if (lastSignalAt <= 0) return "NONE";
        for (ObservedSignal item : observedSignals) {
            if (item.createdAt == lastSignalAt) return item.status == null ? "NONE" : item.status;
        }
        return "NONE";
    }

    private String activeSignalStatus(MarketSnapshot snapshot, long now) {
        if (lastSignal == null || lastSignalAt <= 0) return "NONE";

        String journalStatus = observedStatusForLastSignal();
        if (!"NONE".equals(journalStatus) && !"ACTIVE".equals(journalStatus)) {
            return journalStatus;
        }

        long age = now - lastSignalAt;
        if (age < 0) return "NONE";
        if (age >= OBSERVATION_MAX_AGE_MS) return "TIMEOUT_15M";

        double price = snapshot.ethLast;
        if (!Double.isFinite(price) || price <= 0) return "NO_PRICE";

        if ("LONG".equals(lastSignal.side)) {
            if (price <= lastSignal.stopLoss) return "SL_TOUCHED";
            if (price >= lastSignal.takeProfit) return "TP_TOUCHED";
            return "ACTIVE";
        }

        if ("SHORT".equals(lastSignal.side)) {
            if (price >= lastSignal.stopLoss) return "SL_TOUCHED";
            if (price <= lastSignal.takeProfit) return "TP_TOUCHED";
            return "ACTIVE";
        }

        return "NONE";
    }

    private long activeSignalAgeSec(long now) {
        return lastSignalAt <= 0 ? -1 : Math.max(0, (now - lastSignalAt) / 1000);
    }

    private long activeSignalRemainingSec(long now) {
        if (lastSignalAt <= 0) return -1;
        long remaining = OBSERVATION_MAX_AGE_MS - Math.max(0, now - lastSignalAt);
        return Math.max(0, remaining / 1000);
    }

    private MarketSnapshot buildSnapshot(long now) {
        List<Candle> ethList = new ArrayList<>(ethCandles);
        List<Candle> btcList = new ArrayList<>(btcCandles);

        double averageRange = avgRange(ethList, 20);
        double averageVolume = avgVolume(ethList, 20);
        double avg = Math.max(0.35, averageRange);

        double move1 = 0, move3 = 0, move8 = 0, lastVolume = 0, recentHigh = 0, recentLow = 0;
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
                .lastSignalAt(lastSignalAt)
                .eth(ethLast, ethBid, ethAsk)
                .btc(btcLast, btcBid, btcAsk)
                .candleCounts(ethList.size(), btcList.size())
                .averages(averageRange, averageVolume)
                .movement(move1, move3, move8, recentHigh, recentLow)
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
        if (item == null || item.signal == null) return;

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        String emoji = "ℹ️";
        if ("TP_TOUCHED".equals(status)) emoji = "✅";
        else if ("SL_TOUCHED".equals(status)) emoji = "🛑";
        else if ("ENTRY_TOO_FAR".equals(status)) emoji = "⚠️";

        String title = emoji + " OBS ETH " + item.signal.side + " · " + status;
        String body = String.format(Locale.US,
                "%s · entrée %.2f · TP %.2f · SL %.2f · MFE %.2f · MAE %.2f · AUCUN TRADE RÉEL",
                item.signal.family, item.signal.entry, item.signal.takeProfit,
                item.signal.stopLoss, item.mfe, item.mae);

        manager.notify(signalNotificationId++, buildSignalNotification(title, body));
    }

    private void notifyObservationSignal(SignalDecision decision) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String title = "🔔 OBSERVATION ETH " + decision.side;
        String body = String.format(Locale.US,
                "%s · score %d/100 · LIMIT %.2f · TP %.2f · SL %.2f · %d ETH · AUCUN TRADE RÉEL",
                decision.family, decision.score, decision.entry, decision.takeProfit,
                decision.stopLoss, decision.quantity);

        if (manager != null) {
            manager.notify(signalNotificationId++, buildSignalNotification(title, body));
        }

        updateWatch("Observation : signal " + decision.side + " enregistré · score "
                + decision.score + "/100 · aucun trade réel", true);
    }

    private void notifySignal(SignalDecision decision) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        String title = "🚨 SIGNAL ETH " + decision.side;
        String body = String.format(Locale.US,
                "%s · score %d/100 · LIMIT %.2f · TP %.2f · SL %.2f · %d ETH",
                decision.family, decision.score, decision.entry, decision.takeProfit,
                decision.stopLoss, decision.quantity);
        manager.notify(signalNotificationId++, buildSignalNotification(title, body));
        updateWatch("Dernier signal : " + title, true);
    }

    private void notifyTestAlert() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(signalNotificationId++, buildSignalNotification(
                "🚨 TEST ALERTE ETH", "Test sonore v2.29.1 · aucun ordre n’est envoyé"));
    }

    private Notification buildSignalNotification(String title, String body) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Uri sound = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.eth_alert_loud);
        AudioAttributes audio = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        return new Notification.Builder(this, CH_SIGNAL)
                .setSmallIcon(R.drawable.ic_stat_eth)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setPriority(Notification.PRIORITY_MAX)
                .setSound(sound, audio)
                .setVibrate(ALERT_VIBRATION)
                .setLights(0xffff315f, 1000, 500)
                .setContentIntent(pending)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build();
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
            state.put("version", "2.29.1-android");
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
            state.put("activeSignalAgeSec", activeSignalAgeSec(now));
            state.put("activeSignalRemainingSec", activeSignalRemainingSec(now));
            state.put("activeSignalValidity", "RESEARCH_UNTIL_MARKET_INVALIDATION");
            state.put("executionMode", "RESEARCH_ONLY");
            state.put("realTradingAllowed", false);
            state.put("marketFramesInMemory", marketFrames.size());
            state.put("marketRecorderSummary", marketRecorderSummaryJson());
            state.put("observationSummary", observationSummaryJson());
            state.put("observedSignals", observedSignalsJson());
            state.put("engineMetrics", engineMetricsJson(snapshot, decision));
            state.put("lastSignalAt", lastSignalAt);
            state.put("decision", decision == null ? "ATTENDRE" : decision.decision);
            state.put("decisionReason", decision == null ? "Initialisation du moteur" : decision.reasonText);
            state.put("engineReason", decision == null ? "NO_DATA" : decision.reasonCode);
            state.put("score", decision == null ? 0 : decision.score);
            state.put("action", decision != null && decision.isSignal()
                    ? "ENTRER " + decision.side + " LIMIT" : "NE PAS ENTRER");
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
        m.put("rulesProfile", "ETH Scalper sessions v2.29.1-feature-replay-lab");

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
        JSONObject signal = new JSONObject();
        signal.put("side", decision.side); signal.put("family", decision.family);
        signal.put("score", decision.score); signal.put("qty", decision.quantity);
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
        final double move1, move3, move8;
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
        final SignalDecision signal;
        long lastUpdateAt;
        long closedAt;
        int updates;
        String status = "ACTIVE";
        double lastPrice;
        double maxPrice;
        double minPrice;
        double mfe;
        double mae;

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
            this.lastUpdateAt = createdAt;
            this.lastPrice = price;
            this.maxPrice = price;
            this.minPrice = price;

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
