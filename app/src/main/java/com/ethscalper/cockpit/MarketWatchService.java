package com.ethscalper.cockpit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MarketWatchService extends Service {
    public static final String ACTION_START = "com.ethscalper.cockpit.START";
    public static final String ACTION_STOP = "com.ethscalper.cockpit.STOP";
    public static final String ACTION_SYNC_NOW = "com.ethscalper.cockpit.SYNC_NOW";
    public static final String BROADCAST_STATUS = "com.ethscalper.cockpit.STATUS";

    private static final String CH_WATCH = "eth_scalper_watch";
    private static final String CH_SIGNAL = "eth_scalper_signals_strong_v2202";
    private static final int NOTIF_WATCH_ID = 2192;
    private static final String BINANCE_STREAM = "wss://fstream.binance.com/stream?streams=" +
            "ethusdt@kline_1m/ethusdt@aggTrade/ethusdt@bookTicker/btcusdt@kline_1m/btcusdt@bookTicker";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OkHttpClient client;
    private WebSocket socket;
    private PowerManager.WakeLock wakeLock;
    private boolean running = false;
    private long lastMessageAt = 0;
    private long lastSignalAt = 0;
    private long lastStatusAt = 0;
    private int reconnectAttempt = 0;
    private int signalId = 3000;

    private double ethBid = 0, ethAsk = 0, ethLast = 0;
    private double btcLast = 0;
    private final Deque<Candle> eth = new ArrayDeque<>();
    private final Deque<Candle> btc = new ArrayDeque<>();
    private final Deque<TradeFlow> flows = new ArrayDeque<>();
    private SignalPlan lastPlan = null;
    private boolean healthScheduled = false;
    public static volatile String LAST_STATUS_JSON = "";

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
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            running = false;
            stopSocket();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        startForeground(NOTIF_WATCH_ID, buildWatchNotification("Surveillance native permanente — connexion Binance…"));
        connectIfNeeded();
        if (ACTION_SYNC_NOW.equals(action)) {
            broadcastStatus("sync", "Service natif actif — état resynchronisé");
            handler.postDelayed(() -> broadcastStatus("sync_late", "Service natif actif — surveillance indépendante de l’écran"), 800);
        }
        scheduleHealthCheck();
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (running) {
            try {
                Intent restart = new Intent(getApplicationContext(), MarketWatchService.class);
                restart.setAction(ACTION_START);
                PendingIntent pi = PendingIntent.getService(getApplicationContext(), 2191, restart, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5000, pi);
            } catch (Exception ignored) {}
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        stopSocket();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel watch = new NotificationChannel(CH_WATCH, "Surveillance permanente", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("Garde le moteur ETH Scalper actif en arrière-plan.");
        nm.createNotificationChannel(watch);
        NotificationChannel signals = new NotificationChannel(CH_SIGNAL, "Signaux ETH Scalper — son fort", NotificationManager.IMPORTANCE_HIGH);
        signals.setDescription("Alertes fortes ETH : son + vibration lorsqu'un setup exploitable est détecté.");
        signals.enableVibration(true);
        signals.setVibrationPattern(new long[]{0, 450, 180, 450, 180, 900});
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            signals.setSound(sound, attrs);
        } catch (Exception ignored) {}
        nm.createNotificationChannel(signals);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ETHScalper:MarketWatch");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    private void connectIfNeeded() {
        if (!running || socket != null) return;
        Request request = new Request.Builder().url(BINANCE_STREAM).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                reconnectAttempt = 0;
                lastMessageAt = System.currentTimeMillis();
                updateWatch("Connecté Binance — surveillance ETH/BTC active même écran verrouillé");
                broadcastStatus("connected", "Flux Binance connecté");
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                lastMessageAt = System.currentTimeMillis();
                handleMessage(text);
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                socket = null;
                updateWatch("Flux coupé — reconnexion automatique…");
                broadcastStatus("reconnect", "Flux coupé, reconnexion automatique");
                scheduleReconnect();
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                socket = null;
                if (running) scheduleReconnect();
            }
        });
    }

    private void stopSocket() {
        try { if (socket != null) socket.close(1000, "stop"); } catch (Exception ignored) {}
        socket = null;
    }

    private void scheduleReconnect() {
        if (!running) return;
        reconnectAttempt++;
        long delay = Math.min(30000, 1000L * reconnectAttempt * reconnectAttempt);
        handler.postDelayed(this::connectIfNeeded, delay);
    }

    private void scheduleHealthCheck() {
        if (healthScheduled) return;
        healthScheduled = true;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running) { healthScheduled = false; return; }
                long age = System.currentTimeMillis() - lastMessageAt;
                if (lastMessageAt == 0 || age > 65000) {
                    stopSocket();
                    updateWatch("Flux silencieux — reconnexion forcée…");
                    broadcastStatus("reconnect", "Flux silencieux — reconnexion forcée");
                    connectIfNeeded();
                } else {
                    String msg = String.format(Locale.US, "Connecté Binance — ETH %.2f — dernier flux %ds — écran verrouillé OK", ethLast, Math.max(0, age / 1000));
                    updateWatch(msg);
                    broadcastStatus("live", msg);
                }
                handler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void handleMessage(String text) {
        try {
            JSONObject root = new JSONObject(text);
            String stream = root.optString("stream", "");
            JSONObject data = root.optJSONObject("data");
            if (data == null) return;
            if (stream.contains("bookTicker")) handleBookTicker(stream, data);
            else if (stream.contains("kline_1m")) handleKline(stream, data);
            else if (stream.contains("aggTrade")) handleAggTrade(data);
            evaluateSignal();
            long now = System.currentTimeMillis();
            if (now - lastStatusAt > 1500) {
                lastStatusAt = now;
                broadcastStatus("live", String.format(Locale.US, "ETH %.2f bid %.2f ask %.2f", ethLast, ethBid, ethAsk));
            }
        } catch (Exception ignored) {}
    }

    private void handleBookTicker(String stream, JSONObject data) {
        if (stream.startsWith("ethusdt")) {
            ethBid = data.optDouble("b", ethBid);
            ethAsk = data.optDouble("a", ethAsk);
            if (ethBid > 0 && ethAsk > 0) ethLast = (ethBid + ethAsk) / 2.0;
        } else if (stream.startsWith("btcusdt")) {
            double bid = data.optDouble("b", 0);
            double ask = data.optDouble("a", 0);
            if (bid > 0 && ask > 0) btcLast = (bid + ask) / 2.0;
        }
    }

    private void handleKline(String stream, JSONObject data) {
        JSONObject k = data.optJSONObject("k");
        if (k == null) return;
        Candle c = new Candle(
                k.optLong("t"),
                k.optDouble("o"),
                k.optDouble("h"),
                k.optDouble("l"),
                k.optDouble("c"),
                k.optDouble("v")
        );
        if (stream.startsWith("ethusdt")) {
            ethLast = c.close;
            upsert(eth, c, 180);
        } else if (stream.startsWith("btcusdt")) {
            btcLast = c.close;
            upsert(btc, c, 180);
        }
    }

    private void handleAggTrade(JSONObject data) {
        long t = data.optLong("T", System.currentTimeMillis());
        double q = data.optDouble("q", 0);
        boolean buyerMaker = data.optBoolean("m", false);
        double signed = buyerMaker ? -q : q;
        flows.addLast(new TradeFlow(t, signed));
        pruneFlows(System.currentTimeMillis());
    }

    private void upsert(Deque<Candle> d, Candle c, int max) {
        if (!d.isEmpty() && d.peekLast().openTime == c.openTime) d.removeLast();
        d.addLast(c);
        while (d.size() > max) d.removeFirst();
    }

    private void pruneFlows(long now) {
        while (!flows.isEmpty() && now - flows.peekFirst().time > 120000) flows.removeFirst();
    }

    private void evaluateSignal() {
        long now = System.currentTimeMillis();
        if (now - lastSignalAt < 8 * 60 * 1000L) return;
        if (eth.size() < 30 || btc.size() < 10 || ethLast <= 0) return;

        List<Candle> e = new ArrayList<>(eth);
        List<Candle> b = new ArrayList<>(btc);
        Candle last = e.get(e.size() - 1);
        Candle prev1 = e.get(e.size() - 2);
        Candle prev3 = e.get(e.size() - 4);
        Candle prev8 = e.get(e.size() - 9);
        double avgRange20 = avgRange(e, 20);
        double avgVol20 = avgVol(e, 20);
        double move1 = last.close - prev1.close;
        double move3 = last.close - prev3.close;
        double move8 = last.close - prev8.close;
        double volumeRatio = avgVol20 > 0 ? last.volume / avgVol20 : 1.0;
        double flow60 = signedFlow(now, 60000);
        double flowNorm = avgVol20 > 0 ? flow60 / avgVol20 : 0;
        double btcMove5 = (b.get(b.size() - 1).close - b.get(Math.max(0, b.size() - 6)).close) / Math.max(1, b.get(Math.max(0, b.size() - 6)).close);
        double recentRange = high(e, 8) - low(e, 8);
        double vertical = Math.abs(move8) / Math.max(0.35, avgRange20);

        int side = 0;
        String family = "";
        double freshThreshold = Math.max(0.75, avgRange20 * 0.55);
        if (move1 > freshThreshold && move3 > freshThreshold * 1.15 && flowNorm > 0.08 && btcMove5 > -0.0012) {
            side = 1; family = "C1 cassure fraîche";
        } else if (move1 < -freshThreshold && move3 < -freshThreshold * 1.15 && flowNorm < -0.08 && btcMove5 < 0.0012) {
            side = -1; family = "C1 cassure fraîche";
        } else if (move3 > freshThreshold * 1.35 && move1 > -avgRange20 * 0.25 && flowNorm > -0.05 && btcMove5 > -0.0015) {
            side = 1; family = "C2 reprise contrôlée";
        } else if (move3 < -freshThreshold * 1.35 && move1 < avgRange20 * 0.25 && flowNorm < 0.05 && btcMove5 < 0.0015) {
            side = -1; family = "C2 reprise contrôlée";
        }
        if (side == 0) return;

        double chase = Math.abs(last.close - prev1.close);
        boolean tooVertical = vertical > 5.8 || recentRange > avgRange20 * 7.5;
        if (tooVertical && chase > avgRange20 * 1.1) return;

        int score = 52;
        score += clampInt((int)Math.round(Math.abs(move3) / Math.max(0.35, avgRange20) * 7), 0, 18);
        score += clampInt((int)Math.round((volumeRatio - 1.0) * 10), 0, 12);
        score += clampInt((int)Math.round(Math.abs(flowNorm) * 18), 0, 12);
        if ((side == 1 && btcMove5 > 0) || (side == -1 && btcMove5 < 0)) score += 8;
        if (family.startsWith("C2")) score -= 3;
        if (tooVertical) score -= 12;
        score = clampInt(score, 0, 94);
        if (score < 62) return;

        double target = computeTarget(avgRange20, recentRange, volumeRatio, Math.abs(flowNorm), score, tooVertical);
        double stop = computeStop(avgRange20, recentRange, score);
        int qty = computeQty(score, stop, target, tooVertical);
        if (qty < 3) return;
        double entry = side == 1 ? (ethBid > 0 ? ethBid : last.close) : (ethAsk > 0 ? ethAsk : last.close);
        double tp = entry + side * target;
        double sl = entry - side * stop;

        SignalPlan plan = new SignalPlan(side, family, score, qty, entry, tp, sl, target, stop);
        lastPlan = plan;
        lastSignalAt = now;
        notifySignal(plan);
        broadcastStatus("signal", plan.toJson());
    }

    private double computeTarget(double avgRange, double recentRange, double volumeRatio, double flowPower, int score, boolean tooVertical) {
        double base = Math.max(2.85, avgRange * 1.95);
        base += Math.min(2.4, recentRange * 0.18);
        base += Math.min(1.2, Math.max(0, volumeRatio - 1.0) * 0.45);
        base += Math.min(1.0, flowPower * 0.75);
        if (score >= 82) base += 0.8;
        if (score >= 88) base += 0.8;
        if (tooVertical) base *= 0.72;
        return round2(Math.max(2.80, Math.min(8.80, base)));
    }

    private double computeStop(double avgRange, double recentRange, int score) {
        double stop = Math.max(0.78, avgRange * 0.92);
        stop += Math.min(0.8, recentRange * 0.06);
        if (score >= 82) stop *= 0.92;
        return round2(Math.max(0.78, Math.min(2.35, stop)));
    }

    private int computeQty(int score, double stop, double target, boolean tooVertical) {
        double feeRoundTrip = 1.33;
        double riskPerEth = stop + feeRoundTrip;
        double netPerEth = target - feeRoundTrip;
        if (netPerEth <= 0 || netPerEth / riskPerEth < 0.72) return 0;
        int qty = 3;
        if (score >= 70) qty = 4;
        if (score >= 78) qty = 5;
        if (score >= 85 && netPerEth / riskPerEth >= 1.05) qty = 6;
        if (score >= 90 && netPerEth / riskPerEth >= 1.20) qty = 7;
        if (tooVertical) qty = Math.min(qty, 4);
        return qty;
    }

    private void notifySignal(SignalPlan p) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String side = p.side > 0 ? "LONG" : "SHORT";
        String title = "🚨 SIGNAL ETH " + side + " — score " + p.score + "/100";
        String body = String.format(Locale.US, "%s | qty %d ETH | entry %.2f | TP %.2f | SL %.2f", p.family, p.qty, p.entry, p.tp, p.sl);
        Notification n = new Notification.Builder(this, CH_SIGNAL)
                .setSmallIcon(R.drawable.ic_stat_eth)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setPriority(Notification.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build();
        nm.notify(signalId++, n);
        updateWatch("Dernier signal : " + title);
    }

    private Notification buildWatchNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat_eth)
                .setContentTitle("ETH Scalper actif — arrière-plan")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateWatch(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_WATCH_ID, buildWatchNotification(text));
    }

    private void broadcastStatus(String type, String message) {
        try {
            JSONObject j = new JSONObject();
            j.put("version", "2.20.3-android");
            j.put("nativeActive", running);
            j.put("connected", socket != null && lastMessageAt > 0 && System.currentTimeMillis() - lastMessageAt < 70000);
            j.put("lastAgeSec", lastMessageAt == 0 ? -1 : Math.max(0, (System.currentTimeMillis() - lastMessageAt) / 1000));
            j.put("type", type);
            j.put("message", message);
            j.put("eth", ethLast);
            j.put("bid", ethBid);
            j.put("ask", ethAsk);
            j.put("candles", eth.size());
            if (lastPlan != null) j.put("lastPlan", lastPlan.toJsonObject());
            String out = j.toString();
            LAST_STATUS_JSON = out;
            Intent i = new Intent(BROADCAST_STATUS);
            i.putExtra("payload", out);
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private static double avgRange(List<Candle> v, int n) {
        int start = Math.max(0, v.size() - n);
        double sum = 0; int c = 0;
        for (int i = start; i < v.size(); i++) { sum += Math.abs(v.get(i).high - v.get(i).low); c++; }
        return c == 0 ? 0 : sum / c;
    }

    private static double avgVol(List<Candle> v, int n) {
        int start = Math.max(0, v.size() - n);
        double sum = 0; int c = 0;
        for (int i = start; i < v.size(); i++) { sum += v.get(i).volume; c++; }
        return c == 0 ? 0 : sum / c;
    }

    private static double high(List<Candle> v, int n) {
        int start = Math.max(0, v.size() - n); double h = -1;
        for (int i = start; i < v.size(); i++) h = Math.max(h, v.get(i).high);
        return h;
    }
    private static double low(List<Candle> v, int n) {
        int start = Math.max(0, v.size() - n); double l = Double.MAX_VALUE;
        for (int i = start; i < v.size(); i++) l = Math.min(l, v.get(i).low);
        return l == Double.MAX_VALUE ? 0 : l;
    }

    private double signedFlow(long now, long window) {
        pruneFlows(now);
        double s = 0;
        for (TradeFlow f : flows) if (now - f.time <= window) s += f.signedQty;
        return s;
    }

    private static int clampInt(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    static class Candle {
        long openTime; double open, high, low, close, volume;
        Candle(long t, double o, double h, double l, double c, double v) { openTime=t; open=o; high=h; low=l; close=c; volume=v; }
    }
    static class TradeFlow {
        long time; double signedQty;
        TradeFlow(long t, double q) { time=t; signedQty=q; }
    }
    static class SignalPlan {
        int side, score, qty; String family; double entry, tp, sl, target, stop;
        SignalPlan(int side, String family, int score, int qty, double entry, double tp, double sl, double target, double stop) {
            this.side=side; this.family=family; this.score=score; this.qty=qty; this.entry=entry; this.tp=tp; this.sl=sl; this.target=target; this.stop=stop;
        }
        JSONObject toJsonObject() throws Exception {
            JSONObject j = new JSONObject();
            j.put("side", side > 0 ? "LONG" : "SHORT");
            j.put("family", family); j.put("score", score); j.put("qty", qty);
            j.put("entry", entry); j.put("tp", tp); j.put("sl", sl); j.put("targetMove", target); j.put("stopDistance", stop);
            return j;
        }
        String toJson() {
            try { return toJsonObject().toString(); } catch (Exception e) { return "{}"; }
        }
    }
}
