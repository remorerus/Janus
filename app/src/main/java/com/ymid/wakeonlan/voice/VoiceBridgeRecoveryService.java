package com.ymid.wakeonlan.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.ymid.wakeonlan.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal recovery endpoint on 127.0.0.1:38762.
 *
 * Unlike the full VoiceBridgeAccessibilityService listener, this service does
 * not depend on Android Accessibility being enabled. It exists only after the
 * user has enabled RE:Vision at least once and provides a safe way for the AIUI
 * agent to reopen Android's Accessibility settings after a firmware reset.
 */
public final class VoiceBridgeRecoveryService extends Service {
    private static final String TAG = "VoiceBridgeRecovery";
    private static final String CHANNEL_ID = "revision_voice_bridge";
    private static final int NOTIFICATION_ID = 38762;
    private static final int PORT = 38762;

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable supervisor = new Runnable() {
        @Override
        public void run() {
            if (!running || serverThread == null || !serverThread.isAlive()) {
                startServer();
            }
            mainHandler.postDelayed(this, 20_000L);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, VoiceBridgeRecoveryService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start recovery endpoint", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            // This is a recovery-only endpoint. A failure to promote it to a
            // foreground service must never crash the user's main RE:Vision UI.
            startForeground(NOTIFICATION_ID, buildNotification());
            startServer();
            mainHandler.postDelayed(supervisor, 20_000L);
        } catch (RuntimeException e) {
            Log.e(TAG, "Recovery endpoint could not start; Accessibility bridge remains independent", e);
            running = false;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        mainHandler.removeCallbacks(supervisor);
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) { }
        }
        Thread thread = serverThread;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startServer() {
        if (serverThread != null && serverThread.isAlive()) return;
        running = true;
        serverThread = new Thread(this::serve, "revision-voice-recovery");
        serverThread.start();
    }

    private void serve() {
        long retryDelayMs = 1_000L;
        while (running) {
            try (ServerSocket listener = new ServerSocket()) {
                listener.setReuseAddress(true);
                listener.bind(new java.net.InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), PORT), 4);
                serverSocket = listener;
                retryDelayMs = 1_000L;
                Log.i(TAG, "Recovery endpoint listening on 127.0.0.1:" + PORT);

                while (running) {
                    try (Socket client = listener.accept()) {
                        client.setSoTimeout(5000);
                        handle(client);
                    } catch (java.net.SocketException e) {
                        if (running) Log.w(TAG, "Recovery listener interrupted; will retry", e);
                        break;
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "Recovery request failed", e);
                    }
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "Could not bind recovery port " + PORT + "; retrying", e);
            } finally {
                serverSocket = null;
            }

            if (running) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (!running) break;
                }
                retryDelayMs = Math.min(retryDelayMs * 2L, 30_000L);
            }
        }

        synchronized (this) {
            if (serverThread == Thread.currentThread()) serverThread = null;
        }
        Log.i(TAG, "Recovery endpoint server loop stopped");
    }

    private void handle(Socket socket) throws Exception {
        if (socket.getInetAddress() == null || !socket.getInetAddress().isLoopbackAddress()) {
            writeResponse(socket.getOutputStream(), 403, "{\"ok\":false,\"error\":\"loopback_only\"}");
            return;
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String requestLine = reader.readLine();
        if (requestLine == null) return;
        String[] request = requestLine.trim().split(" ");
        if (request.length < 2) {
            writeResponse(socket.getOutputStream(), 400, "{\"ok\":false,\"error\":\"invalid_request\"}");
            return;
        }

        String token = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0 && "x-revision-token".equalsIgnoreCase(line.substring(0, separator).trim())) {
                token = line.substring(separator + 1).trim();
            }
        }

        String method = request[0];
        String path = request[1].split("\\?", 2)[0];
        if ("GET".equals(method) && "/health".equals(path)) {
            boolean enabled = isAccessibilityEnabled(this);
            boolean connected = VoiceBridgeAccessibilityService.isServiceConnected();
            boolean mainRunning = VoiceBridgeAccessibilityService.isMainServerRunning();
            String body = "{\"ok\":true,\"service\":\"RE:Vision recovery\",\"port\":" + PORT
                    + ",\"accessibilityEnabled\":" + enabled
                    + ",\"accessibilityConnected\":" + connected
                    + ",\"mainServerRunning\":" + mainRunning
                    + ",\"recoveryServerRunning\":true}";
            writeResponse(socket.getOutputStream(), 200, body);
            return;
        }

        if ("POST".equals(method) && "/accessibility/settings".equals(path)) {
            // This endpoint only opens Android's user-controlled settings screen.
            // It cannot enable Accessibility or execute device commands, so pairing
            // PIN is not required for recovery navigation.
            boolean opened = openAccessibilitySettings();
            if (opened) {
                writeResponse(socket.getOutputStream(), 200,
                        "{\"ok\":true,\"opened\":true,\"accessibilityEnabled\":"
                                + isAccessibilityEnabled(this) + "}");
            } else {
                writeResponse(socket.getOutputStream(), 500,
                        "{\"ok\":false,\"error\":\"accessibility_settings_unavailable\"}");
            }
            return;
        }

        writeResponse(socket.getOutputStream(), 404, "{\"ok\":false,\"error\":\"not_found\"}");
    }

    private static boolean isAccessibilityEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String expected = new ComponentName(
                    context.getApplicationContext(), VoiceBridgeAccessibilityService.class).flattenToString();
            for (String item : enabled.split(":")) {
                if (expected.equalsIgnoreCase(item.trim())) return true;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to read Android Accessibility state", e);
        }
        return false;
    }

    private boolean isAuthorized(String supplied) {
        if (supplied == null) return false;
        byte[] expected = VoiceBridgeAccessibilityService.getOrCreateAccessToken(this)
                .getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private boolean openAccessibilitySettings() {
        final boolean[] opened = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                opened[0] = true;
            } catch (Exception e) {
                Log.e(TAG, "Unable to open Accessibility settings", e);
            } finally {
                latch.countDown();
            }
        });
        try {
            return latch.await(4, TimeUnit.SECONDS) && opened[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
                .setContentText("Канал восстановления Voice Bridge активен")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private static void writeResponse(OutputStream output, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Internal Server Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }
}
