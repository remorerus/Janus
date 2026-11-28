package com.remorerus.revisionbridgelab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

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
    private static final String JANUS_PACKAGE = "com.ymid.wakeonlan";
    private WebSocketServer server;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long startedAt;

    @Override public void onCreate() {
        super.onCreate();
        startedAt = System.currentTimeMillis();
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("RE:Vision Bridge Lab")
                .setContentText("Локальный канал управления активен")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        startForeground(PORT, n);
        startServer();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null) startServer();
        return START_STICKY;
    }

    private synchronized void startServer() {
        if (server != null) return;
        try {
            server = new WebSocketServer(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT)) {
                @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    if (!validToken(handshake.getResourceDescriptor())) conn.close(1008, "invalid lab token");
                }
                @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {}
                @Override public void onMessage(WebSocket conn, String message) {
                    try {
                        JSONObject request = new JSONObject(message);
                        if ("command".equals(request.optString("type"))) {
                            conn.send(handleCommand(request).toString());
                        } else {
                            JSONObject response = new JSONObject();
                            response.put("ok", true);
                            response.put("service", "RevisionBridgeLab");
                            response.put("echo", message);
                            conn.send(response.toString());
                        }
                    } catch (Exception e) {
                        try {
                            JSONObject error = new JSONObject();
                            error.put("ok", false);
                            error.put("error", "invalid_request");
                            error.put("detail", e.getMessage() == null ? "Invalid JSON" : e.getMessage());
                            conn.send(error.toString());
                        } catch (Exception ignored) {}
                    }
                }
                @Override public void onError(WebSocket conn, Exception ex) {
                    Log.e("RevisionBridgeLab", "WebSocket server error", ex);
                }
                @Override public void onStart() {
                    Log.i("RevisionBridgeLab", "Listening on 127.0.0.1:" + PORT);
                }
            };
            server.setReuseAddr(true);
            server.start();
        } catch (Exception e) {
            Log.e("RevisionBridgeLab", "Cannot start local WebSocket", e);
            server = null;
        }
    }

    private JSONObject handleCommand(JSONObject request) throws Exception {
        String command = request.optString("command", "");
        JSONObject response = new JSONObject();
        response.put("service", "RevisionBridgeLab");
        response.put("command", command);
        switch (command) {
            case "status":
                response.put("ok", true);
                response.put("running", server != null);
                response.put("uptimeMs", Math.max(0, System.currentTimeMillis() - startedAt));
                response.put("wifi", wifiStatus());
                break;
            case "open_wifi_settings":
                openSystemScreen(Settings.ACTION_WIFI_SETTINGS, response, "wifi_settings");
                break;
            case "connect_wifi": {
                String network = request.optString("network", "").trim();
                String ssid;
                if ("Studio".equalsIgnoreCase(network) || "Студио".equalsIgnoreCase(network)) {
                    ssid = "Studio";
                } else if ("Local_Network_5G".equalsIgnoreCase(network)
                        || "Локал Нетворк".equalsIgnoreCase(network)) {
                    ssid = "Local_Network_5G";
                } else {
                    response.put("ok", false);
                    response.put("error", "unknown_network");
                    response.put("detail", "Разрешены только Studio и Local_Network_5G.");
                    break;
                }
                openSystemScreen(Settings.ACTION_WIFI_SETTINGS, response, "wifi_network_selection");
                response.put("targetSsid", ssid);
                response.put("connected", false);
                response.put("selectionRequired", true);
                response.put("message", "Открыт системный список Wi-Fi. Выбери сеть " + ssid + "; подключение пока не подтверждено.");
                break;
            }
            case "open_janus": {
                Intent launch = getPackageManager().getLaunchIntentForPackage(JANUS_PACKAGE);
                if (launch == null) {
                    response.put("ok", false);
                    response.put("error", "janus_not_found");
                    response.put("package", JANUS_PACKAGE);
                    response.put("detail", "На устройстве не найдено установленное приложение RE:Vision / Janus.");
                    break;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mainHandler.post(() -> {
                    try { startActivity(launch); }
                    catch (Exception e) { Log.e("RevisionBridgeLab", "Unable to launch Janus", e); }
                });
                response.put("ok", true);
                response.put("accepted", true);
                response.put("package", JANUS_PACKAGE);
                response.put("message", "Запрос на открытие Janus передан Android.");
                break;
            }
            case "wifi_enable":
            case "wifi_disable":
                response.put("ok", false);
                response.put("error", "system_restricted");
                response.put("detail", "Android 10 и новее ограничивает изменение Wi-Fi обычными приложениями. Открой настройки Wi-Fi и измени переключатель вручную.");
                response.put("wifi", wifiStatus());
                break;
            default:
                response.put("ok", false);
                response.put("error", "unknown_command");
                response.put("detail", "Неизвестная команда: " + command);
        }
        return response;
    }

    private JSONObject wifiStatus() throws Exception {
        JSONObject state = new JSONObject();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        boolean enabled = wifi != null && wifi.isWifiEnabled();
        state.put("enabled", enabled);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network active = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = active == null || cm == null ? null : cm.getNetworkCapabilities(active);
        boolean connected = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        state.put("connected", connected);
        String ssid = "";
        if (connected && wifi != null) {
            try {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    ssid = info.getSSID().replace("\"", "");
                    if ("<unknown ssid>".equalsIgnoreCase(ssid)) ssid = "";
                }
            } catch (SecurityException ignored) {}
        }
        state.put("ssid", ssid);
        state.put("ssidAvailable", !ssid.isEmpty());
        return state;
    }

    private void openSystemScreen(String action, JSONObject response, String mode) throws Exception {
        Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mainHandler.post(() -> {
            try { startActivity(intent); }
            catch (Exception e) { Log.e("RevisionBridgeLab", "Unable to open system screen", e); }
        });
        response.put("ok", true);
        response.put("accepted", true);
        response.put("opened", true);
        response.put("mode", mode);
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
