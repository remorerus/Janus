package com.ymid.wakeonlan.voice;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ComponentName;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ymid.wakeonlan.monitoring.HostReachability;
import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.persistence.repository.DeviceRepository;
import com.ymid.wakeonlan.security.AuthenticatedDeviceActionActivity;
import com.ymid.wakeonlan.security.DeviceActionExecutor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Loopback-only bridge used by the Rokid AIUI voice agent. */
public final class VoiceBridgeAccessibilityService extends AccessibilityService {
    private static final String TAG = "RevisionVoiceBridge";
    private static volatile VoiceBridgeAccessibilityService activeInstance;
    private volatile boolean serviceConnected;
    private static final int PORT = 38761;
    private static final String BRIDGE_PREFS = "revision_voice_bridge";
    private static final String TOKEN_KEY = "access_token";
    private static final int MAX_AUTH_FAILURES = 5;
    private static final long AUTH_LOCKOUT_MS = 30_000L;
    private int authFailureCount;
    private long authBlockedUntilMs;

    /** Returns a stable four-digit pairing PIN stored in app-private preferences. */
    public static synchronized String getOrCreateAccessToken(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(TOKEN_KEY, null);
        if (token != null && token.matches("\\d{4}")) return token;

        int pin = new SecureRandom().nextInt(10000);
        token = String.format(java.util.Locale.US, "%04d", pin);
        prefs.edit().putString(TOKEN_KEY, token).apply();
        return token;
    }

    /** Loopback bridge only; throttle failed PIN guesses to reduce the risk of a short PIN. */
    private synchronized boolean isAuthorized(String suppliedToken) {
        long now = System.currentTimeMillis();
        if (now < authBlockedUntilMs) return false;
        if (suppliedToken == null) {
            registerAuthFailure(now);
            return false;
        }
        byte[] expected = getOrCreateAccessToken(this).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedToken.getBytes(StandardCharsets.UTF_8);
        boolean valid = MessageDigest.isEqual(expected, supplied);
        if (valid) {
            authFailureCount = 0;
            authBlockedUntilMs = 0L;
        } else {
            registerAuthFailure(now);
        }
        return valid;
    }

    private void registerAuthFailure(long now) {
        authFailureCount++;
        if (authFailureCount >= MAX_AUTH_FAILURES) {
            authFailureCount = 0;
            authBlockedUntilMs = now + AUTH_LOCKOUT_MS;
        }
    }

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private final Handler serverHandler = new Handler(Looper.getMainLooper());
    private long serverRestartDelayMs = 1_000L;
    private final Runnable restartServerRunnable = () -> {
        synchronized (VoiceBridgeAccessibilityService.this) {
            if (!running) return;
            if (serverThread != null && serverThread.isAlive()) return;
            startServer();
        }
    };
    private volatile String pendingWifiTarget;
    private volatile long pendingWifiSelectionUntilMs;
    private volatile boolean pendingWifiSelectionClicked;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
        serviceConnected = true;

        // Keep each startup step isolated: a firmware/provider failure in an
        // auxiliary component must not tear down the Accessibility connection.
        try {
            getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("bridge_ever_enabled", true).apply();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to persist Voice Bridge enabled state", e);
        }

        try {
            startServer();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start the main Voice Bridge listener", e);
        }

        try {
            VoiceBridgeRecoveryService.start(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start Voice Bridge recovery service", e);
        }

        try {
            VoiceBridgeKeepAliveService.start(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start Voice Bridge keeper", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Select only the exact SSID requested by the user's voice command, and only
        // inside Android Settings. Never click unrelated controls or confirm dialogs.
        String target = pendingWifiTarget;
        if (target == null) return;
        if (System.currentTimeMillis() > pendingWifiSelectionUntilMs) {
            pendingWifiTarget = null;
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence packageName = root.getPackageName();
        String activePackage = packageName == null ? "" : packageName.toString();
        // Android 10+ may render the compact Internet/Wi-Fi panel in System UI,
        // while the full Wi-Fi settings screen is owned by com.android.settings.
        // Restrict interaction to these two trusted system surfaces only.
        if (!"com.android.settings".equals(activePackage)
                && !"com.android.systemui".equals(activePackage)) return;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(target);
        if (matches == null) return;
        for (AccessibilityNodeInfo match : matches) {
            CharSequence label = match.getText();
            CharSequence description = match.getContentDescription();
            boolean exact = (label != null && target.equalsIgnoreCase(label.toString().trim()))
                    || (description != null && target.equalsIgnoreCase(description.toString().trim()));
            if (!exact) continue;
            AccessibilityNodeInfo clickable = match;
            while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                pendingWifiTarget = null;
                pendingWifiSelectionClicked = true;
                Log.i(TAG, "Selected requested Wi-Fi network: " + target);
                return;
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Android may interrupt accessibility event delivery without unbinding the
        // service. Keep the loopback HTTP bridge alive; stop it only on unbind/destroy.
        Log.w(TAG, "Accessibility events interrupted; keeping voice HTTP bridge running");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        serviceConnected = false;
        stopServer();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        serviceConnected = false;
        if (activeInstance == this) activeInstance = null;
        stopServer();
        super.onDestroy();
    }

    /** True only while Android has actually bound this Accessibility service instance. */
    public static boolean isServiceConnected() {
        VoiceBridgeAccessibilityService service = activeInstance;
        return service != null && service.serviceConnected;
    }

    /** True only while the main loopback listener is bound and accepting connections. */
    public static boolean isMainServerRunning() {
        VoiceBridgeAccessibilityService service = activeInstance;
        if (service == null || !service.serviceConnected) return false;
        ServerSocket socket = service.serverSocket;
        Thread thread = service.serverThread;
        return socket != null && socket.isBound() && !socket.isClosed()
                && thread != null && thread.isAlive();
    }

    /** Called by the independent foreground keeper while Android keeps Accessibility enabled. */
    public static void ensureServerRunning() {
        VoiceBridgeAccessibilityService service = activeInstance;
        if (service == null || !service.serviceConnected) return;
        service.startServer();
    }

    private synchronized void startServer() {
        if (!serviceConnected || isDestroyed()) return;
        if (serverThread != null && serverThread.isAlive()) return;
        serverHandler.removeCallbacks(restartServerRunnable);
        running = true;
        Thread listenerThread = new Thread(this::serve, "revision-voice-http");
        serverThread = listenerThread;
        try {
            listenerThread.start();
        } catch (RuntimeException e) {
            running = false;
            if (serverThread == listenerThread) serverThread = null;
            Log.e(TAG, "Unable to create Voice Bridge listener thread", e);
            serverHandler.postDelayed(restartServerRunnable, serverRestartDelayMs);
        }
    }

    private synchronized void stopServer() {
        running = false;
        serverHandler.removeCallbacks(restartServerRunnable);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) { }
            serverSocket = null;
        }
        Thread oldThread = serverThread;
        serverThread = null;
        if (oldThread != null) oldThread.interrupt();
    }

    private void serve() {
        try (ServerSocket listener = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            serverSocket = listener;
            synchronized (this) { serverRestartDelayMs = 1_000L; }
            while (running) {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(10000);
                    handle(client);
                } catch (Exception e) {
                    if (running) Log.w(TAG, "Voice bridge request failed", e);
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Could not bind loopback port " + PORT, e);
        } finally {
            synchronized (this) {
                serverSocket = null;
                if (serverThread == Thread.currentThread()) serverThread = null;
                if (running) {
                    long delay = serverRestartDelayMs;
                    serverRestartDelayMs = Math.min(serverRestartDelayMs * 2L, 60_000L);
                    Log.w(TAG, "Voice bridge listener stopped; retrying in " + delay + " ms");
                    serverHandler.removeCallbacks(restartServerRunnable);
                    serverHandler.postDelayed(restartServerRunnable, delay);
                }
            }
        }
    }

    private void handle(Socket socket) throws Exception {
        if (socket.getInetAddress() == null || !socket.getInetAddress().isLoopbackAddress()) {
            writeResponse(socket.getOutputStream(), 403, error("loopback_only"));
            return;
        }
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        int value;
        while ((value = input.read()) != -1 && headerBytes.size() < 8192) {
            headerBytes.write(value);
            if (matched == 0 && value == 13) matched = 1;
            else if (matched == 1 && value == 10) matched = 2;
            else if (matched == 2 && value == 13) matched = 3;
            else if (matched == 3 && value == 10) { matched = 4; break; }
            else matched = value == 13 ? 1 : 0;
        }
        if (matched != 4) {
            writeResponse(socket.getOutputStream(), 400, error("invalid_http_headers"));
            return;
        }
        String headers = new String(headerBytes.toByteArray(), StandardCharsets.US_ASCII);
        String[] lines = headers.split("\\r\\n");
        String[] request = lines[0].split(" ");
        if (request.length < 2) {
            writeResponse(socket.getOutputStream(), 400, error("invalid_request"));
            return;
        }
        int contentLength = 0;
        String suppliedToken = null;
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String headerName = line.substring(0, separator).trim();
            String headerValue = line.substring(separator + 1).trim();
            if ("content-length".equalsIgnoreCase(headerName)) {
                contentLength = Integer.parseInt(headerValue);
            } else if ("x-revision-token".equalsIgnoreCase(headerName)) {
                suppliedToken = headerValue;
            }
        }
        if (contentLength < 0 || contentLength > 8192) {
            writeResponse(socket.getOutputStream(), 413, error("body_too_large"));
            return;
        }
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int count = input.read(body, offset, contentLength - offset);
            if (count < 0) break;
            offset += count;
        }
        if (offset != contentLength) {
            writeResponse(socket.getOutputStream(), 400, error("incomplete_body"));
            return;
        }

        String method = request[0];
        String target = request[1];
        String path = target.split("\\?", 2)[0];
        boolean protectedEndpoint =
                ("GET".equals(method) && ("/devices".equals(path) || "/status".equals(path))) ||
                ("POST".equals(method) && ("/command".equals(path) || "/wifi".equals(path))) ||
                ("GET".equals(method) && ("/wifi/status".equals(path) || "/wifi/networks".equals(path))) ||
                ("POST".equals(method) && ("/wifi".equals(path) || "/wifi/connect".equals(path) || "/wifi/toggle".equals(path)));
        if (protectedEndpoint && !isAuthorized(suppliedToken)) {
            writeResponse(socket.getOutputStream(), 401, error("unauthorized"));
            return;
        }
        if ("GET".equals(method) && "/health".equals(path)) {
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("service", "RE:Vision");
            response.addProperty("port", PORT);
            response.addProperty("wifiRoute", true);
            response.addProperty("accessibilityEnabled",
                    VoiceBridgeKeepAliveService.isAccessibilityEnabled(getApplicationContext()));
            response.addProperty("accessibilityConnected", isServiceConnected());
            response.addProperty("mainServerRunning", isMainServerRunning());
            response.addProperty("recoveryPort", 38762);
            writeResponse(socket.getOutputStream(), 200, response);
        } else if ("GET".equals(method) && "/devices".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, listDevices());
        } else if ("GET".equals(method) && "/status".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, checkStatus(target));
        } else if ("POST".equals(method) && "/command".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, executeCommand(new String(body, StandardCharsets.UTF_8)));
        } else if ("POST".equals(method) && "/accessibility/settings".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, openAccessibilitySettings());
        } else if ("POST".equals(method) && "/wifi".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, openWifiSettings());
        } else if ("POST".equals(method) && "/wifi/toggle".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, toggleWifi(new String(body, StandardCharsets.UTF_8)));
        } else if ("GET".equals(method) && "/wifi/status".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, wifiStatus());
        } else if ("GET".equals(method) && "/wifi/networks".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, wifiNetworks());
        } else if ("POST".equals(method) && "/wifi/connect".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, connectWifi(new String(body, StandardCharsets.UTF_8)));
        } else {
            writeResponse(socket.getOutputStream(), 404, error("not_found"));
        }
    }

    private JsonObject listDevices() {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        JsonArray result = new JsonArray();
        for (Device device : DeviceRepository.getInstance(this).getAll()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", device.id);
            item.addProperty("name", device.name);
            item.addProperty("canWake", device.macAddress != null && !device.macAddress.trim().isEmpty());
            item.addProperty("canShutdown", device.remoteShutdownEnabled);
            result.add(item);
        }
        response.add("devices", result);
        return response;
    }

    private JsonObject checkStatus(String target) throws Exception {
        String idValue = queryParameter(target, "deviceId");
        if (idValue == null) return error("deviceId_required");
        Device device = DeviceRepository.getInstance(this).getById(Integer.parseInt(idValue));
        if (device == null) return error("device_not_found");
        String host = HostReachability.INSTANCE.statusHost(device);
        if (host == null || host.trim().isEmpty()) return error("status_address_missing");
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("deviceId", device.id);
        response.addProperty("name", device.name);
        response.addProperty("online", HostReachability.INSTANCE.isReachable(host));
        response.addProperty("checkedHost", host);
        return response;
    }



    /**
     * Android 10+ blocks ordinary apps targeting API 29+ from toggling Wi-Fi
     * with WifiManager.setWifiEnabled(). Open the system Wi-Fi controls and
     * explicitly report that the user must complete the requested change.
     */
    private JsonObject toggleWifi(String body) throws Exception {
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        if (!request.has("enabled") || request.get("enabled").isJsonNull()) {
            return error("wifi_enabled_required");
        }

        boolean requestedEnabled;
        try {
            requestedEnabled = request.get("enabled").getAsBoolean();
        } catch (Exception e) {
            return error("wifi_enabled_invalid");
        }

        JsonObject current = wifiStatus();
        if (current.has("enabled") && current.get("enabled").getAsBoolean() == requestedEnabled) {
            current.addProperty("verified", true);
            current.addProperty("requestedEnabled", requestedEnabled);
            current.addProperty("requiresUserAction", false);
            return current;
        }

        JsonObject opened = openWifiSettings();
        if (!opened.has("ok") || !opened.get("ok").getAsBoolean()) {
            return opened;
        }
        opened.addProperty("requiresUserAction", true);
        opened.addProperty("verified", false);
        opened.addProperty("requestedEnabled", requestedEnabled);
        if (current.has("enabled")) opened.add("enabled", current.get("enabled"));
        opened.addProperty("message", requestedEnabled
                ? "Android требует подтверждения пользователя для включения Wi-Fi. Открыты системные настройки."
                : "Android требует подтверждения пользователя для выключения Wi-Fi. Открыты системные настройки.");
        return opened;
    }

    /** Returns the current Wi-Fi connection without attempting to change networks. */
    private JsonObject wifiStatus() {
        JsonObject response = new JsonObject();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean enabled = false;
        String ssid = null;
        try {
            enabled = wifi != null && wifi.isWifiEnabled();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ConnectivityManager connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                Network active = connectivity == null ? null : connectivity.getActiveNetwork();
                NetworkCapabilities caps = active == null || connectivity == null
                        ? null : connectivity.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.getTransportInfo() instanceof WifiInfo) {
                    ssid = ((WifiInfo) caps.getTransportInfo()).getSSID();
                }
            }
            if ((ssid == null || ssid.trim().isEmpty()) && wifi != null) {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null) ssid = info.getSSID();
            }
        } catch (SecurityException e) {
            response.addProperty("permissionRequired", true);
        } catch (Exception ignored) {
        }
        if (ssid != null) {
            ssid = ssid.trim();
            if (ssid.length() >= 2 && ssid.charAt(0) == '"' && ssid.charAt(ssid.length() - 1) == '"') {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if ("<unknown ssid>".equalsIgnoreCase(ssid) || "unknown ssid".equalsIgnoreCase(ssid)
                    || "0x".equalsIgnoreCase(ssid)) ssid = null;
        }
        response.addProperty("ok", true);
        response.addProperty("enabled", enabled);
        response.addProperty("connected", ssid != null && !ssid.isEmpty());
        if (ssid != null && !ssid.isEmpty()) response.addProperty("ssid", ssid);
        response.addProperty("message", ssid == null || ssid.isEmpty()
                ? "Wi-Fi is enabled but the current SSID is unavailable."
                : "Connected to " + ssid);
        return response;
    }

    /** Returns the latest Wi-Fi scan results when Android permissions allow it. */
    private JsonObject wifiNetworks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return error("wifi_unavailable");
        try {
            if (Build.VERSION.SDK_INT >= 23 &&
                    checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                JsonObject denied = error("wifi_scan_permission_required");
                denied.addProperty("permission", "ACCESS_FINE_LOCATION");
                denied.addProperty("message", "Разреши RE:Vision доступ к местоположению для сканирования Wi-Fi.");
                return denied;
            }
            boolean scanStarted = wifi.startScan();
            JsonArray networks = new JsonArray();
            for (android.net.wifi.ScanResult result : wifi.getScanResults()) {
                if (result == null || result.SSID == null || result.SSID.trim().isEmpty()) continue;
                JsonObject item = new JsonObject();
                item.addProperty("ssid", result.SSID);
                item.addProperty("signalLevel", result.level);
                item.addProperty("frequency", result.frequency);
                networks.add(item);
            }
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("scanStarted", scanStarted);
            response.add("networks", networks);
            response.addProperty("message", "Android may throttle scans; results can be from the latest completed scan.");
            return response;
        } catch (SecurityException e) {
            JsonObject denied = error("wifi_scan_permission_required");
            denied.addProperty("permission", "ACCESS_FINE_LOCATION");
            return denied;
        } catch (Exception e) {
            return error("wifi_scan_failed");
        }
    }

    /** Resolves voice aliases and opens Android's Wi-Fi picker; final selection remains user-approved. */
    private JsonObject connectWifi(String body) throws Exception {
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String requested = request.has("networkName") ? request.get("networkName").getAsString() : "";
        String normalized = requested.trim().toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        String target = null;
        if (normalized.contains("студи") || normalized.contains("studio")) target = "Studio";
        else if (normalized.contains("локал") || normalized.contains("local") ||
                normalized.contains("нетворк") || normalized.contains("network")) target = "Local_Network_5G";
        else if (request.has("ssid")) target = request.get("ssid").getAsString().trim();
        if (target == null || target.isEmpty()) {
            JsonObject invalid = error("wifi_network_required");
            invalid.addProperty("message", "Назови сеть: Студио или Локал нетворк.");
            return invalid;
        }
        // Do not reopen Settings when the requested saved network is already active.
        JsonObject current = wifiStatus();
        if (current.has("connected") && current.get("connected").getAsBoolean()
                && current.has("ssid") && target.equalsIgnoreCase(current.get("ssid").getAsString())) {
            current.addProperty("targetSsid", target);
            current.addProperty("alreadyConnected", true);
            current.addProperty("message", "Уже подключено к " + target + ".");
            return current;
        }

        pendingWifiSelectionClicked = false;
        pendingWifiTarget = target;
        pendingWifiSelectionUntilMs = System.currentTimeMillis() + 15_000L;
        JsonObject opened = openWifiSettings();
        if (!opened.has("ok") || !opened.get("ok").getAsBoolean()) {
            pendingWifiTarget = null;
            return opened;
        }

        // Accessibility selects the exact saved SSID in Android Settings/System UI.
        // Verify the actual active SSID before reporting success; selecting a row is
        // not proof that Android completed the connection.
        long deadline = System.currentTimeMillis() + 8_000L;
        JsonObject status = wifiStatus();
        while (System.currentTimeMillis() < deadline) {
            if (status.has("connected") && status.get("connected").getAsBoolean()
                    && status.has("ssid") && target.equalsIgnoreCase(status.get("ssid").getAsString())) {
                status.addProperty("targetSsid", target);
                status.addProperty("connected", true);
                status.addProperty("selectionClicked", pendingWifiSelectionClicked);
                status.addProperty("message", "Подключено к " + target + ".");
                return status;
            }
            try { Thread.sleep(400L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            status = wifiStatus();
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("targetSsid", target);
        result.addProperty("connected", false);
        result.addProperty("selectionClicked", pendingWifiSelectionClicked);
        result.addProperty("selectionRequired", !pendingWifiSelectionClicked);
        result.addProperty("message", pendingWifiSelectionClicked
                ? "Сеть " + target + " выбрана, но Android ещё не подтвердил подключение. Проверь запрос системы."
                : "Открыл список Wi-Fi, но не смог подтвердить выбор сети " + target
                    + ". Выбери её вручную на экране очков.");
        return result;
    }

    /** Opens Android's user-controlled Accessibility settings; enabling the service remains a manual user action. */
    private JsonObject openAccessibilitySettings() throws Exception {
        final boolean[] opened = new boolean[1];
        final String[] failure = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                opened[0] = true;
            } catch (ActivityNotFoundException | SecurityException e) {
                failure[0] = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            } catch (RuntimeException e) {
                failure[0] = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(4, TimeUnit.SECONDS)) return error("accessibility_settings_timeout");
        if (!opened[0]) {
            JsonObject response = error("accessibility_settings_unavailable");
            if (failure[0] != null) response.addProperty("detail", failure[0]);
            return response;
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("opened", true);
        response.addProperty("message", "Android Accessibility settings opened; user must enable RE:Vision manually.");
        return response;
    }

    /** Opens the Wi-Fi picker used by Rokid firmware; the user confirms the network. */
    private JsonObject openWifiSettings() throws Exception {
        final boolean[] opened = new boolean[1];
        final String[] mode = new String[1];
        final String[] failure = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // Prefer the full Settings activity: its accessibility tree exposes
                // saved networks more consistently than the compact System UI panel.
                // Keep the panel as a fallback for Rokid firmware variants.
                Intent[] candidates = new Intent[] {
                    new Intent(Settings.ACTION_WIFI_SETTINGS).setPackage("com.android.settings"),
                    new Intent().setComponent(new ComponentName(
                            "com.android.settings",
                            "com.android.settings.Settings$WifiSettingsActivity")),
                    new Intent(Settings.ACTION_WIFI_SETTINGS),
                    new Intent(Settings.Panel.ACTION_WIFI).setPackage("com.android.systemui"),
                    new Intent(Settings.Panel.ACTION_WIFI),
                    new Intent(Settings.ACTION_WIRELESS_SETTINGS)
                };
                String[] modes = new String[] {
                    "settings", "settings", "settings", "panel", "panel", "network_settings"
                };
                for (int i = 0; i < candidates.length; i++) {
                    Intent candidate = candidates[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(candidate);
                        opened[0] = true;
                        mode[0] = modes[i];
                        break;
                    } catch (ActivityNotFoundException | SecurityException e) {
                        failure[0] = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    } catch (RuntimeException e) {
                        failure[0] = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    }
                }
            } catch (Exception e) {
                failure[0] = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(4, TimeUnit.SECONDS)) return error("wifi_settings_timeout");
        if (!opened[0]) {
            JsonObject response = error("wifi_settings_unavailable");
            if (failure[0] != null) response.addProperty("detail", failure[0]);
            return response;
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("opened", true);
        response.addProperty("mode", mode[0]);
        response.addProperty("message", "Wi-Fi controls opened; user must select a network.");
        return response;
    }

    private JsonObject executeCommand(String body) throws Exception {
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String action = request.has("action") ? request.get("action").getAsString() : "";
        if (!"wake".equalsIgnoreCase(action) && !"shutdown".equalsIgnoreCase(action)) {
            return error("unsupported_action");
        }
        List<Device> devices = DeviceRepository.getInstance(this).getAll();
        Device selected = null;
        if (request.has("deviceId")) {
            int id = request.get("deviceId").getAsInt();
            for (Device device : devices) if (device.id == id) selected = device;
        } else if (request.has("device")) {
            String name = request.get("device").getAsString();
            for (Device device : devices) {
                if (device.name != null && device.name.equalsIgnoreCase(name.trim())) selected = device;
            }
        }
        if (selected == null) return error("device_not_found");

        final Device device = selected;
        final boolean[] accepted = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            accepted[0] = DeviceActionExecutor.execute(getApplicationContext(), device,
                    "wake".equalsIgnoreCase(action)
                            ? AuthenticatedDeviceActionActivity.ACTION_WAKE
                            : AuthenticatedDeviceActionActivity.ACTION_SHUTDOWN);
            latch.countDown();
        });
        if (!latch.await(4, TimeUnit.SECONDS)) return error("execution_timeout");
        if (!accepted[0]) return error("action_not_configured");
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("accepted", true);
        response.addProperty("action", action.toLowerCase());
        response.addProperty("deviceId", device.id);
        response.addProperty("device", device.name);
        response.addProperty("stateConfirmed", false);
        response.addProperty("message", "Command accepted; device power state is not yet confirmed.");
        return response;
    }

    private static String queryParameter(String target, String name) throws Exception {
        int start = target.indexOf('?');
        if (start < 0) return null;
        for (String part : target.substring(start + 1).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) return URLDecoder.decode(pair[1], "UTF-8");
        }
        return null;
    }

    private static JsonObject error(String code) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", code);
        return response;
    }

    private static void writeResponse(OutputStream output, int status, JsonObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" :
                status == 403 ? "Forbidden" : status == 404 ? "Not Found" :
                status == 413 ? "Payload Too Large" : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }
}
