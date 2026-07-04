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

    private static final String CH_WATCH = "eth_scalper_watch_v22304";
    private static final String CH_SIGNAL = "eth_scalper_signal_loud_v22304";
    private static final String STATE_PREFERENCES = "market_watch_state";
    private static final String STATE_JSON = "last_status_json";
    private static final int NOTIF_WATCH_ID = 22304;
    private static final long[] ALERT_VIBRATION = {0, 750, 180, 750, 180, 1200};
    private static final String BINANCE_STREAM = "wss://fstream.binance.com/stream?streams=" +
            "ethusdt@kline_1m/ethusdt@aggTrade/ethusdt@bookTicker/" +
            "btcusdt@kline_1m/btcusdt@bookTicker";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<Candle> ethCandles = new ArrayDeque<>();
    private final Deque<Candle> btcCandles = new ArrayDeque<>();
    private final Deque<TradeFlow> flows = new ArrayDeque<>();
    private final SignalEngine signalEngine = new SignalEngine();

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

    public static String getLastStatusJson(Context context) {
        String memory = LAST_STATUS_JSON == null ? "" : LAST_STATUS_JSON;
        if (!memory.isEmpty()) return memory;
        return context.getSharedPreferences(STATE_PREFERENCES, MODE_PRIVATE).getString(STATE_JSON, "");
    }

    public static String getLastStatusJson() {
        return LAST_STATUS_JSON == null ? "" : LAST_STATUS_JSON;
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

        NotificationChannel signals = new NotificationChannel(CH_SIGNAL, "Signaux ETH — alerte forte v2.23.4",
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

    private void evaluateSignal(long now) {
        SignalDecision decision = signalEngine.evaluate(buildSnapshot(now));
        lastDecision = decision;
        if (decision.isSignal()) {
            lastSignal = decision;
            lastSignalAt = now;
            notifySignal(decision);
            broadcastStatus("signal", decision.reasonCode);
        }
    }

    private MarketSnapshot buildSnapshot(long now) {
        List<Candle> ethList = new ArrayList<>(ethCandles);
        List<Candle> btcList = new ArrayList<>(btcCandles);
        double averageRange = avgRange(ethList, 20);
        double averageVolume = avgVolume(ethList, 20);
        double move1 = 0, move3 = 0, move8 = 0, lastVolume = 0, recentHigh = 0, recentLow = 0;
        if (!ethList.isEmpty()) lastVolume = ethList.get(ethList.size() - 1).volume;
        if (ethList.size() >= 9) {
            Candle last = ethList.get(ethList.size() - 1);
            move1 = last.close - ethList.get(ethList.size() - 2).close;
            move3 = last.close - ethList.get(ethList.size() - 4).close;
            move8 = last.close - ethList.get(ethList.size() - 9).close;
            recentHigh = high(ethList, 8);
            recentLow = low(ethList, 8);
        }
        double btcMove5 = 0;
        if (btcList.size() >= 6) {
            double previous = btcList.get(btcList.size() - 6).close;
            if (previous > 0) btcMove5 = (btcList.get(btcList.size() - 1).close - previous) / previous;
        }
        double flowNorm = averageVolume > 0 ? signedFlow(now, 60_000) / averageVolume : 0;
        return MarketSnapshot.builder(now)
                .lastSignalAt(lastSignalAt)
                .eth(ethLast, ethBid, ethAsk)
                .btc(btcLast, btcBid, btcAsk)
                .candleCounts(ethList.size(), btcList.size())
                .averages(averageRange, averageVolume)
                .movement(move1, move3, move8, recentHigh, recentLow)
                .flow(flowNorm, lastVolume)
                .btcMove5(btcMove5)
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
                "🚨 TEST ALERTE ETH", "Test sonore v2.23.4 · aucun ordre n’est envoyé"));
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
            JSONObject state = new JSONObject();
            state.put("version", "2.23.4-android");
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
            MarketSnapshot snapshot = buildSnapshot(now);
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
        m.put("rulesProfile", "ETH Scalper sessions v2.23.4");

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

    static final class Candle {
        final long openTime; final double open, high, low, close, volume;
        Candle(long openTime, double open, double high, double low, double close, double volume) {
            this.openTime=openTime; this.open=open; this.high=high; this.low=low; this.close=close; this.volume=volume;
        }
    }

    static final class TradeFlow {
        final long time; final double signedQuantity;
        TradeFlow(long time, double signedQuantity) { this.time=time; this.signedQuantity=signedQuantity; }
    }
}
