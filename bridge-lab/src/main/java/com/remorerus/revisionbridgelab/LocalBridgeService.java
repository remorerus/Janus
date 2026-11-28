package com.remorerus.revisionbridgelab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class LocalBridgeService extends Service {
    public static final String ACTION_START = "com.remorerus.revisionbridgelab.START";
    public static final String LAB_TOKEN = "revision-lab-only";
    private static final int PORT = 38763;
    private static final String CHANNEL = "revision_bridge_lab";
    private WebSocketServer server;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("RE:Vision Bridge Lab")
                .setContentText("Локальный WebSocket работает на 127.0.0.1:38763")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        startForeground(38763, n);
        startServer();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null || !server.isRunning()) startServer();
        return START_STICKY;
    }

    private synchronized void startServer() {
        if (server != null && server.isRunning()) return;
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            server = new WebSocketServer(new InetSocketAddress(loopback, PORT)) {
                @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    if (!validToken(handshake.getResourceDescriptor())) {
                        conn.close(1008, "invalid lab token");
                    }
                }
                @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {}
                @Override public void onMessage(WebSocket conn, String message) {
                    String safe = message.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n").replace("\r", "\\r");
                    conn.send("{\"ok\":true,\"service\":\"RevisionBridgeLab\",\"echo\":\"" + safe + "\"}");
                }
                @Override public void onError(WebSocket conn, Exception ex) {
                    android.util.Log.e("RevisionBridgeLab", "WebSocket server error", ex);
                }
                @Override public void onStart() {
                    android.util.Log.i("RevisionBridgeLab", "Listening on 127.0.0.1:" + PORT);
                }
            };
            server.setReuseAddr(true);
            server.start();
        } catch (Exception e) {
            android.util.Log.e("RevisionBridgeLab", "Cannot start local WebSocket", e);
        }
    }

    private boolean validToken(String resource) {
        try {
            URI uri = URI.create(resource);
            String query = uri.getRawQuery();
            if (query == null) return false;
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "token".equals(parts[0])) {
                    return LAB_TOKEN.equals(URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()));
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "RE:Vision Bridge Lab",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        if (server != null) {
            try { server.stop(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            server = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
