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

/** Loopback-only bridge used by the Rokid AIUI voice agent. No screen reading or simulated taps. */
public final class VoiceBridgeAccessibilityService extends AccessibilityService {
    private static final String TAG = "RevisionVoiceBridge";
    private static final int PORT = 38761;
    private static final String BRIDGE_PREFS = "revision_voice_bridge";
    private static final String TOKEN_KEY = "access_token";

    /** Returns a stable per-installation secret stored in the app-private preferences. */
    public static synchronized String getOrCreateAccessToken(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(TOKEN_KEY, null);
        if (token != null && token.length() == 64) return token;

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : bytes) hex.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        token = hex.toString();
        prefs.edit().putString(TOKEN_KEY, token).apply();
        return token;
    }

    private boolean isAuthorized(String suppliedToken) {
        if (suppliedToken == null) return false;
        byte[] expected = getOrCreateAccessToken(this).getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        startServer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally empty. The service only provides a local command endpoint.
    }

    @Override
    public void onInterrupt() {
        stopServer();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopServer();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopServer();
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

    private void serve() {
        try (ServerSocket listener = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            serverSocket = listener;
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
            serverSocket = null;
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
                ("POST".equals(method) && ("/wifi".equals(path) || "/wifi/connect".equals(path)));
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
            writeResponse(socket.getOutputStream(), 200, response);
        } else if ("GET".equals(method) && "/devices".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, listDevices());
        } else if ("GET".equals(method) && "/status".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, checkStatus(target));
        } else if ("POST".equals(method) && "/command".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, executeCommand(new String(body, StandardCharsets.UTF_8)));
        } else if ("POST".equals(method) && "/wifi".equals(path)) {
            writeResponse(socket.getOutputStream(), 200, openWifiSettings());
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
        JsonObject opened = openWifiSettings();
        if (!opened.has("ok") || !opened.get("ok").getAsBoolean()) return opened;
        opened.addProperty("targetSsid", target);
        opened.addProperty("selectionRequired", true);
        opened.addProperty("message", "Открыл системный выбор Wi-Fi. Выбери сеть " + target +
                " и подтверди подключение, если Android запросит подтверждение. Затем проверь подключение командой «Ревижн, проверь Wi-Fi».");
        return opened;
    }

    /** Opens the Wi-Fi picker used by Rokid firmware; the user confirms the network. */
    private JsonObject openWifiSettings() throws Exception {
        final boolean[] opened = new boolean[1];
        final String[] mode = new String[1];
        final String[] failure = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent[] candidates = new Intent[] {
                    new Intent(Settings.Panel.ACTION_WIFI).setPackage("com.android.settings"),
                    new Intent(Settings.Panel.ACTION_WIFI).setComponent(new ComponentName(
                            "com.android.settings",
                            "com.android.settings.panel.SettingsPanelActivity")),
                    new Intent(Settings.ACTION_WIFI_SETTINGS).setPackage("com.android.settings"),
                    new Intent().setComponent(new ComponentName(
                            "com.android.settings",
                            "com.android.settings.Settings$WifiSettingsActivity")),
                    new Intent(Settings.ACTION_WIFI_SETTINGS),
                    new Intent(Settings.ACTION_WIRELESS_SETTINGS)
                };
                String[] modes = new String[] {
                    "panel", "panel", "settings", "settings", "settings", "network_settings"
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
