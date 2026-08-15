package com.ethscalper.cockpit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

/** Notification channels owned by the production V4 runtime. */
public final class V4NotificationChannels {
    public static final String LOUD_CHANNEL_ID = "nmc_final_signal_loud_v2";
    public static final String MONITOR_CHANNEL_ID = "nmc_v4_monitor_v1";
    public static final long[] ALERT_VIBRATION = {0, 750, 180, 750, 180, 1200};

    private V4NotificationChannels() {}

    public static void ensure(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL_ID, "NMC · Surveillance V4", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Maintient la surveillance V4 des 53 marchés en arrière-plan.");
        monitor.setShowBadge(false);
        monitor.setSound(null, null);
        manager.createNotificationChannel(monitor);

        NotificationChannel loud = new NotificationChannel(
                LOUD_CHANNEL_ID, "NMC · Alertes de signaux confirmés", NotificationManager.IMPORTANCE_HIGH);
        loud.setDescription("Alerte sonore forte pour les transitions importantes des plans V4.");
        loud.enableVibration(true);
        loud.setVibrationPattern(ALERT_VIBRATION);
        loud.enableLights(true);
        loud.setLightColor(0xffff315f);
        loud.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        Uri sound = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.eth_alert_loud);
        AudioAttributes audio = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        loud.setSound(sound, audio);
        manager.createNotificationChannel(loud);
    }
}
