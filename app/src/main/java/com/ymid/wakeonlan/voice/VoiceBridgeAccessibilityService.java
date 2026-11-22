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
import java.net.InetSocketAddress;
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
    private static final int PORT = 38761;
    private static final String BRIDGE_PREFS = "revision_voice_bridge";
    private static final String TOKEN_KEY = "access_token";
    private static final int MAX_AUTH_FAILURES = 5;
    private static final long AUTH_LOCKOUT_MS = 30_000L;
    private int authFailureCount;
    private long authBlockedUntilMs;

    /** Returns a stable four-digit pairing PIN stored in app-private preferences. */
    public static boolean isBridgeEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(BRIDGE_ENABLED_KEY, true);
    }

    /** Enables or disables only the local HTTP bridge; Accessibility remains under Android control. */
    public static void setBridgeEnabled(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(BRIDGE_ENABLED_KEY, enabled).apply();
        VoiceBridgeAccessibilityService service = instance;
        if (service != null) {
            if (enabled) service.startServer();
            else service.stopServer();
        }
    }

    public static boolean isBridgeServerActive() {
        VoiceBridgeAccessibilityService service = instance;
        ServerSocket socket = service == null ? null : service.serverSocket;
        return service != null && service.running && socket != null && socket.isBound() && !socket.isClosed();
    }

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

    private static volatile VoiceBridgeAccessibilityService instance;
    private static final String BRIDGE_ENABLED_KEY = "bridge_enabled";
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Thread serverThread;
    private volatile String pendingWifiTarget;
    private volatile long pendingWifiSelectionUntilMs;
    private volatile boolean pendingWifiSelectionClicked;
    private volatile int pendingWifiScrollSteps;
    private volatile long lastDiagLogMs;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (isBridgeEnabled(this)) startServer();
        else Log.i(TAG, "Voice bridge disabled in RE:Vision settings");
    }

    private static String norm(CharSequence s) {
        return s == null ? "" : s.toString().trim().replace('\u00a0', ' ')
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean matchesSsidLabel(CharSequence value, String targetNorm) {
        String label = norm(value);
        if (label.equals(targetNorm)) return true;
        if (!label.startsWith(targetNorm) || label.length() <= targetNorm.length()) return false;

        char separator = label.charAt(targetNorm.length());
        // Settings variants append status details to the SSID. Do not match a
        // different SSID that merely begins with the requested name.
        if (separator == ',' || separator == ':' || separator == '(' || separator == '['
                || separator == '-' || separator == '–' || separator == '—'
                || separator == '·' || separator == '•' || separator == '\n') {
            return true;
        }
        if (Character.isWhitespace(separator)) {
            String suffix = label.substring(targetNorm.length()).trim();
            String[] knownStatus = {
                    "connected", "saved", "secured", "wifi", "wi-fi", "signal",
                    "подключено", "сохранено", "защищено", "сигнал", "уровень"
            };
            for (String status : knownStatus) {
                if (suffix.startsWith(status)) return true;
            }
        }
        return false;
    }

    private static boolean labelMatches(AccessibilityNodeInfo n, String targetNorm) {
        return n != null && (matchesSsidLabel(n.getText(), targetNorm)
                || matchesSsidLabel(n.getContentDescription(), targetNorm));
    }

    private static boolean clickMatchingWifiNode(AccessibilityNodeInfo node, String targetNorm) {
        if (node == null) return false;
        if (labelMatches(node, targetNorm)) {
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickMatchingWifiNode(node.getChild(i), targetNorm)) return true;
        }
        return false;
    }

    private static boolean scrollForward(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (scrollForward(node.getChild(i))) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        final String target = pendingWifiTarget;
        if (target == null) return;
        long now = System.currentTimeMillis();
        if (now > pendingWifiSelectionUntilMs) {
            pendingWifiTarget = null;
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence packageName = root.getPackageName();
        String pkg = packageName == null ? "" : packageName.toString();
        if (!"com.android.settings".equals(pkg) && !"com.android.systemui".equals(pkg)) {
            if (now - lastDiagLogMs > 2000) {
                lastDiagLogMs = now;
                Log.w(TAG, "Wi-Fi select: unexpected foreground package " + pkg);
            }
            return;
        }
        String targetNorm = norm(target);
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(target);
        int candidates = matches == null ? 0 : matches.size();
        if (matches != null) {
            for (AccessibilityNodeInfo match : matches) {
                if (!labelMatches(match, targetNorm)) continue;
                AccessibilityNodeInfo clickable = match;
                while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    pendingWifiTarget = null;
                    pendingWifiSelectionClicked = true;
                    Log.i(TAG, "Wi-Fi select: clicked matching row for " + target + " in " + pkg);
                    return;
                }
            }
        }
        // Some firmware exposes the SSID only in a nested content description,
        // or combines it with a status label. Walk the full accessibility tree
        // as a fallback instead of relying only on findAccessibilityNodeInfosByText.
        if (clickMatchingWifiNode(root, targetNorm)) {
            pendingWifiTarget = null;
            pendingWifiSelectionClicked = true;
            Log.i(TAG, "Wi-Fi select: clicked row via accessibility tree for " + target + " in " + pkg);
            return;
        }
        // The SSID may be below the fold on the small glasses display.
        if (pendingWifiScrollSteps < 12 && scrollForward(root)) {
            pendingWifiScrollSteps++;
            Log.i(TAG, "Wi-Fi select: scrolled, step " + pendingWifiScrollSteps
                    + ", candidates=" + candidates);
        } else if (now - lastDiagLogMs > 2000) {
            lastDiagLogMs = now;
            Log.w(TAG, "Wi-Fi select: target not found in " + pkg + " (candidates=" + candidates + ")");
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
        stopServer();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopServer();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private synchronized void startServer() {
        if (serverThread != null && serverThread.isAlive()) return;
        running = true;
        serverThread = new Thread(this::serve, "revision-voice-http");
        serverThread.start();
    }

    private synchronized void stopServer() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) { }
            serverSocket = null;
        }
        serverThread = null;
    }

    /**
     * Keep the loopback endpoint alive for the full lifetime of the Accessibility service.
     *
     * R08's watchdog demonstrates why a single start attempt is not enough on Rokid firmware:
     * transient port conflicts and service restarts happen. Retry bind/accept failures with
     * bounded backoff instead of leaving a dead serverThread behind until Android reconnects us.
     */
    private void serve() {
        long retryDelayMs = 1_000L;
        while (running) {
            try (ServerSocket listener = new ServerSocket()) {
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
                serverSocket = listener;
                retryDelayMs = 1_000L;
                Log.i(TAG, "Voice bridge listening on 127.0.0.1:" + PORT);

                while (running) {
                    try (Socket client = listener.accept()) {
                        client.setSoTimeout(10_000);
                        handle(client);
                    } catch (java.net.SocketException e) {
                        if (running) Log.w(TAG, "Voice bridge listener interrupted; will retry", e);
                        break;
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "Voice bridge request failed", e);
                    }
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "Could not bind loopback port " + PORT + "; retrying", e);
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
        Log.i(TAG, "Voice bridge server loop stopped");
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
        Log.i(TAG, "HTTP " + method + " " + path);
        boolean protectedEndpoint =
                ("GET".equals(method) && ("/devices".equals(path) || "/status".equals(path))) ||
                ("POST".equals(method) && ("/command".equals(path) || "/wifi".equals(path) || "/wifi/toggle".equals(path))) ||
                ("GET".equals(method) && ("/wifi/status".equals(path) || "/wifi/networks".equals(path))) ||
                ("POST".equals(method) && ("/wifi".equals(path) || "/wifi/connect".equals(path)));
        if (protectedEndpoint && !isAuthorized(suppliedToken)) {
            Log.w(TAG, "HTTP 401 for " + path);
            writeResponse(socket.getOutputStream(), 401, error("unauthorized"));
            return;
        }
        if ("GET".equals(method) && "/health".equals(path)) {
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("service", "RE:Vision");
            response.addProperty("port", PORT);
            response.addProperty("wifiRoute", true);
            writeResponse(socket.getOutputStream(), 200, response);
        } else if ("GET".equals(method) && "/devices".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, listDevices());
        } else if ("GET".equals(method) && "/status".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, checkStatus(target));
        } else if ("POST".equals(method) && "/command".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, executeCommand(new String(body, StandardCharsets.UTF_8)));
        } else if ("POST".equals(method) && "/wifi".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, openWifiSettings());
        } else if ("POST".equals(method) && "/wifi/toggle".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, openWifiTogglePanel(new String(body, StandardCharsets.UTF_8)));
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



    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabledSafe() {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
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
                    || "0x".equalsIgnoreCase(ssid) || "null".equalsIgnoreCase(ssid)) ssid = null;
        }
        boolean permissionOk = hasLocationPermission();
        boolean locationOn = isLocationEnabledSafe();
        response.addProperty("locationPermission", permissionOk);
        response.addProperty("locationEnabled", locationOn);
        boolean ssidUnreadable = (ssid == null || ssid.isEmpty()) && (!permissionOk || !locationOn);
        response.addProperty("ssidUnreadable", ssidUnreadable);
        response.addProperty("ok", true);
        response.addProperty("enabled", enabled);
        response.addProperty("connected", ssid != null && !ssid.isEmpty());
        if (ssid != null && !ssid.isEmpty()) response.addProperty("ssid", ssid);
        response.addProperty("message",
                ssid != null && !ssid.isEmpty() ? "Connected to " + ssid
                : !permissionOk ? "Нужно разрешение на местоположение для RE:Vision."
                : !locationOn ? "Включи геолокацию на очках: без неё Android скрывает имя сети."
                : "Wi-Fi is enabled but the current SSID is unavailable.");
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

    /**
     * Android 10+ blocks ordinary apps targeting API 29+ from enumerating or
     * enabling saved Wi-Fi configurations. This Rokid build targets API 28
     * so a user-granted FINE_LOCATION permission can use the legacy API.
     * Returns null when the SSID is not saved, preserving the system-picker fallback.
     */
    private JsonObject connectSavedWifiInBackground(String target) {
        if (getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.Q || !hasLocationPermission()) return null;
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return null;
        try {
            List<android.net.wifi.WifiConfiguration> configured = wifi.getConfiguredNetworks();
            if (configured == null || configured.isEmpty()) return null;
            android.net.wifi.WifiConfiguration match = null;
            for (android.net.wifi.WifiConfiguration candidate : configured) {
                if (candidate == null || candidate.SSID == null) continue;
                String savedSsid = candidate.SSID.trim();
                if (savedSsid.length() >= 2 && savedSsid.startsWith("\"") && savedSsid.endsWith("\""))
                    savedSsid = savedSsid.substring(1, savedSsid.length() - 1);
                if (target.equalsIgnoreCase(savedSsid)) { match = candidate; break; }
            }
            if (match == null) return null;
            boolean enabled = wifi.enableNetwork(match.networkId, true);
            boolean reconnecting = enabled && wifi.reconnect();
            if (!enabled || !reconnecting) return null;
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline) {
                JsonObject status = wifiStatus();
                if (status.has("connected") && status.get("connected").getAsBoolean()
                        && status.has("ssid") && target.equalsIgnoreCase(status.get("ssid").getAsString())) {
                    status.addProperty("targetSsid", target);
                    status.addProperty("opened", false);
                    status.addProperty("alreadyConnected", false);
                    status.addProperty("backgroundConnect", true);
                    status.addProperty("verified", true);
                    status.addProperty("message", "Подключено к " + target + " в фоне. Системные настройки не открывались.");
                    return status;
                }
                try { Thread.sleep(500L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            JsonObject pending = new JsonObject();
            pending.addProperty("ok", true);
            pending.addProperty("opened", false);
            pending.addProperty("targetSsid", target);
            pending.addProperty("connected", false);
            pending.addProperty("verified", false);
            pending.addProperty("backgroundConnect", true);
            pending.addProperty("selectionPending", false);
            pending.addProperty("message", "Android принял команду фонового подключения к " + target + ", но соединение пока не подтверждено.");
            return pending;
        } catch (SecurityException e) {
            Log.w(TAG, "Background Wi-Fi connection requires permission", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Background Wi-Fi connection failed; using system picker", e);
            return null;
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
            current.addProperty("opened", true);
            current.addProperty("alreadyConnected", true);
            current.addProperty("message", "Уже подключено к " + target + ".");
            return current;
        }

        JsonObject background = connectSavedWifiInBackground(target);
        if (background != null) return background;

        pendingWifiSelectionClicked = false;
        pendingWifiScrollSteps = 0;
        pendingWifiTarget = target;
        pendingWifiSelectionUntilMs = System.currentTimeMillis() + 90_000L;
        JsonObject opened = openWifiSettings();
        if (!opened.has("ok") || !opened.get("ok").getAsBoolean()) {
            pendingWifiTarget = null;
            return opened;
        }

        // Accessibility events may arrive only after the AIUI voice card is dismissed.
        // Keep the target pending and return immediately; do not tell the user to select it manually
        // just because the AIUI page is still foreground or the connection takes longer than a few seconds.
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("opened", true);
        result.addProperty("targetSsid", target);
        result.addProperty("connected", false);
        result.addProperty("verified", false);
        result.addProperty("selectionClicked", pendingWifiSelectionClicked);
        result.addProperty("selectionPending", pendingWifiTarget != null);
        result.addProperty("selectionRequired", false);
        result.addProperty("message", pendingWifiSelectionClicked
                ? "Сеть " + target + " выбрана Android. Проверка подключения доступна отдельной командой."
                : "Список Wi-Fi открыт. Автоматический выбор сети " + target
                    + " ожидает, когда экран настроек станет активным. Закрой карточку голосового сообщения; сеть будет выбрана автоматически.");
        return result;
    }

    /**
     * Opens Android's Internet panel for an explicit user-confirmed Wi-Fi toggle.
     * Android 10+ blocks direct Wi-Fi toggling for ordinary apps targeting Q or newer.
     */
    private JsonObject openWifiTogglePanel(String body) throws Exception {
        boolean enabled;
        try {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            if (!request.has("enabled") || !request.get("enabled").isJsonPrimitive()) {
                return error("enabled_required");
            }
            enabled = request.get("enabled").getAsBoolean();
        } catch (Exception e) {
            return error("invalid_toggle_request");
        }

        final boolean[] opened = new boolean[1];
        final String[] failure = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent panel = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(panel);
                    opened[0] = true;
                } catch (RuntimeException e) {
                    failure[0] = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    try {
                        Intent settings = new Intent(Settings.ACTION_WIFI_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(settings);
                        opened[0] = true;
                    } catch (Exception fallback) {
                        failure[0] += "; fallback: " + fallback.getClass().getSimpleName()
                                + ": " + String.valueOf(fallback.getMessage());
                    }
                }
            } catch (Exception e) {
                failure[0] = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(4, TimeUnit.SECONDS)) return error("wifi_panel_timeout");
        if (!opened[0]) {
            JsonObject response = error("wifi_panel_unavailable");
            if (failure[0] != null) response.addProperty("detail", failure[0]);
            return response;
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("opened", true);
        response.addProperty("requestedEnabled", enabled);
        response.addProperty("changed", false);
        response.addProperty("requiresUserAction", true);
        response.addProperty("message", "Android requires the user to confirm the Wi-Fi toggle in the system panel.");
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
