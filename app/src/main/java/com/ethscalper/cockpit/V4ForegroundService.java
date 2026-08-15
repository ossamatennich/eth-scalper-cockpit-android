package com.ethscalper.cockpit;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Foreground host for the single production V4 market runtime. */
public final class V4ForegroundService extends Service {
    public static final String ACTION_START = "com.ethscalper.cockpit.V4_FOREGROUND_START";
    public static final int FOREGROUND_NOTIFICATION_ID = 23_466;
    public static final int LEGACY_NOTIFICATION_ID = 22_801;
    public static final String LEGACY_CHANNEL_ID = "eth_scalper_watch_v22801";
    public static final String TITLE = "NMC · Surveillance V4";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private V4RuntimeCoordinator runtime;
    private boolean receiverRegistered;
    private final BroadcastReceiver runtimeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateForeground(); }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateForeground();handler.postDelayed(this,15_000L); }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        retireLegacyNotification(manager);
        V4NotificationChannels.ensure(this);
        startForeground(FOREGROUND_NOTIFICATION_ID,buildNotification(new JSONObject()));
        runtime = V4RuntimeCoordinator.start(this);
        ContextCompat.registerReceiver(this,runtimeChanged,new IntentFilter(V4RuntimeCoordinator.ACTION_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        updateForeground();
        handler.post(refresh);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(runtime==null)runtime=V4RuntimeCoordinator.start(this);
        updateForeground();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if(receiverRegistered){unregisterReceiver(runtimeChanged);receiverRegistered=false;}
        if(runtime!=null)runtime.stop();
        runtime=null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void updateForeground() {
        V4RuntimeCoordinator value=runtime;
        if(value==null)return;
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null)manager.notify(FOREGROUND_NOTIFICATION_ID,buildNotification(value.status()));
    }

    Notification buildNotification(JSONObject status) {
        Intent open = new Intent(this,V4MainActivity.class)
                .putExtra("v4_open_plans",false)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent=PendingIntent.getActivity(this,23_466,open,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this,V4NotificationChannels.MONITOR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_nmc)
                .setContentTitle(TITLE)
                .setContentText(monitorContent(status))
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    static String monitorContent(JSONObject status) {
        String state=status==null?"SYNCHRO":status.optString("scannerState","SYNCHRO");
        if("ACTIF".equals(state)){
            int markets=status.optInt("marketsConfigured",53);
            long analysed=status.optLong("lastAnalysisAt",0);
            return "ACTIF · "+markets+" marchés · analyse "+utcTime(analysed);
        }
        if("HORS LIGNE".equals(state))return "HORS LIGNE · reconnexion en attente";
        return "SYNCHRO · données V4 en cours";
    }

    static void retireLegacyNotification(NotificationManager manager) {
        if(manager==null)return;
        manager.cancel(LEGACY_NOTIFICATION_ID);
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
    }

    private static String utcTime(long at) {
        if(at<=0)return "en attente";
        SimpleDateFormat value=new SimpleDateFormat("HH:mm 'UTC'",Locale.FRANCE);
        value.setTimeZone(TimeZone.getTimeZone("UTC"));
        return value.format(new Date(at));
    }
}
