package com.ymid.wakeonlan.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.ymid.wakeonlan.R;

/**
 * Keeps the app process in a user-visible foreground state while the user-enabled
 * Accessibility bridge is active. It also supervises the loopback listener when
 * the AccessibilityService instance is connected.
 *
 * Android does not allow an ordinary app to silently enable/rebind an Accessibility
 * service that the user has disabled. In that case this keeper exits cleanly.
 */
public final class VoiceBridgeKeepAliveService extends Service {
    private static final String TAG = "VoiceBridgeKeeper";
    private static final String CHANNEL_ID = "revision_voice_bridge";
    private static final int NOTIFICATION_ID = 38761;
    private static final long CHECK_INTERVAL_MS = 20_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable supervisor = new Runnable() {
        @Override
        public void run() {
            if (!isAccessibilityEnabled(VoiceBridgeKeepAliveService.this)) {
                Log.i(TAG, "Accessibility bridge is disabled by the system/user; stopping keeper");
                stopForeground(true);
                stopSelf();
                return;
            }

            // If the listener thread died while the Accessibility service remains
            // connected, ask that service instance to restore it.
            VoiceBridgeAccessibilityService.ensureServerRunning();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, VoiceBridgeKeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            // The Accessibility service itself remains usable; log rather than
            // crashing its connection callback on firmware-specific restrictions.
            Log.e(TAG, "Unable to start foreground keeper", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.post(supervisor);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY asks Android to recreate this foreground process after a
        // reclaim where the firmware permits sticky foreground services.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(supervisor);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Checks the user-controlled Android accessibility switch without attempting to change it. */
    public static boolean isAccessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        String expected = new ComponentName(
                context, VoiceBridgeAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            if (expected.equalsIgnoreCase(item.trim())) return true;
        }
        return false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_bridge_notification_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.voice_bridge_notification_text));
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_off)
                .setContentTitle(getString(R.string.voice_bridge_notification_title))
                .setContentText(getString(R.string.voice_bridge_notification_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
