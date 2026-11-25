package com.ymid.wakeonlan.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client for the paired RE:Vision Windows Agent.
 * Prototype transport is HTTP on a trusted private LAN; do not expose it to public networks.
 */
public final class WindowsAgentClient {
    private static final String PREFS = "revision_windows_agent";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_SECRET = "client_secret";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final int TIMEOUT_MS = 5000;

    private WindowsAgentClient() {}

    private static SharedPreferences prefs(Context context) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(
                context.getApplicationContext(), PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public static JSONObject pair(Context context, String host, String code) throws Exception {
        String base = normalizeBase(host);
        SharedPreferences existing = prefs(context);
        String existingBase = existing.getString(KEY_BASE, null);
        String existingSecret = existing.getString(KEY_SECRET, null);
        JSONObject targetIdentity = null;
        try { targetIdentity = request(base, "GET", "/v1/discovery", "", null); }
        catch (Exception ignored) { }

        // Preserve the current pairing. If the same physical PC received a new IP,
        // its stable discovery ID lets us update only the endpoint without pairing again.
        if (existingBase != null && existingSecret != null) {
            String existingId = existing.getString(KEY_DEVICE_ID, null);
            JSONObject oldIdentity = null;
            try {
                oldIdentity = request(existingBase, "GET", "/v1/discovery", "", null);
                if (existingId == null) existingId = oldIdentity.optString("deviceId", null);
                // Migrate the existing STUDIO pairing into the registry without asking for its code again.
                if (!oldIdentity.optString("deviceId", "").isEmpty()) {
                    com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry registry =
                            new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context);
                    if (registry.get(oldIdentity.optString("deviceId")) == null)
                        saveRegistryPair(context, oldIdentity, existingBase, existingSecret);
                }
            } catch (Exception ignored) { }
            String targetId = targetIdentity == null ? null : targetIdentity.optString("deviceId", null);
            boolean sameDevice = existingId != null && !existingId.isEmpty()
                    ? existingId.equals(targetId)
                    : existingBase.equals(base);
            if (sameDevice) {
                boolean agentPaired = false;
                try {
                    JSONObject status = request(base, "GET", "/v1/status", "", null);
                    agentPaired = status.optBoolean("paired", false);
                } catch (Exception ignored) { }
                if (agentPaired) {
                    saveRegistryPair(context, targetIdentity, base, existingSecret);
                    SharedPreferences.Editor keep = existing.edit().putString(KEY_BASE, base);
                    if (targetId != null && !targetId.isEmpty()) keep.putString(KEY_DEVICE_ID, targetId);
                    if (!keep.commit()) throw new IllegalStateException("Не удалось обновить адрес существующего устройства.");
                    JSONObject alreadyPaired = targetIdentity == null ? new JSONObject() : targetIdentity;
                    alreadyPaired.put("paired", true);
                    alreadyPaired.put("alreadyPaired", true);
                    return alreadyPaired;
                }
                // The same physical PC is reachable but has been locally unpaired.
                // Allow an explicit new one-time code to renew its secret.
            } else {
                // Keep the legacy pairing intact; the newly paired PC is stored separately in the registry.
            }
        }

        if (targetIdentity == null || targetIdentity.optString("deviceId", "").trim().isEmpty()) {
            throw new IllegalStateException("Windows Agent не сообщил постоянный ID. Обнови Windows Agent до версии с поддержкой обнаружения устройств.");
        }
        if (code == null || !code.trim().matches("[A-Fa-f0-9]{8}")) {
            throw new IllegalArgumentException("Код сопряжения должен содержать 8 шестнадцатеричных символов.");
        }
        // The Windows Agent is the authority for the pairing secret. It creates the
        // 32-byte key and returns it in PairResponse; never save a locally generated key.
        JSONObject body = new JSONObject();
        body.put("code", code.trim().toUpperCase(Locale.ROOT));
        JSONObject response = request(base, "POST", "/v1/pair", body.toString(), null);
        String secret = response.optString("clientSecret", null);
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("Windows Agent не вернул ключ сопряжения.");
        }
        byte[] secretBytes;
        try {
            secretBytes = Base64.decode(secret, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Windows Agent вернул некорректный ключ сопряжения.", e);
        }
        if (secretBytes.length != 32) {
            throw new IllegalStateException("Windows Agent вернул ключ неверной длины.");
        }
        saveRegistryPair(context, targetIdentity, base, secret);
        // Keep backward compatibility with the original STUDIO PC profile. Never replace it
        // when pairing an additional computer.
        if (existingBase == null || existingBase.isEmpty()) {
            SharedPreferences.Editor editor = prefs(context).edit();
            editor.putString(KEY_BASE, base);
            editor.putString(KEY_SECRET, secret);
            if (targetIdentity != null) {
                String deviceId = targetIdentity.optString("deviceId", "");
                if (!deviceId.isEmpty()) editor.putString(KEY_DEVICE_ID, deviceId);
            }
            if (!editor.commit()) throw new IllegalStateException("Не удалось сохранить защищённые данные сопряжения.");
        }
        return response;
    }

    /**
     * Finds RE:Vision Windows Agents on the current private LAN. Discovery is
     * unauthenticated and returns only public identity/endpoint metadata; it
     * never reads, changes or replaces an existing pairing secret.
     */
    public static List<JSONObject> discover(Context context) throws Exception {
        Map<String, JSONObject> found = new LinkedHashMap<>();
        List<InetAddress> targets = new ArrayList<>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                byte[] address = new byte[] {
                        (byte) (broadcast & 0xff),
                        (byte) ((broadcast >> 8) & 0xff),
                        (byte) ((broadcast >> 16) & 0xff),
                        (byte) ((broadcast >> 24) & 0xff)
                };
                InetAddress subnetBroadcast = InetAddress.getByAddress(address);
                if (!targets.contains(subnetBroadcast)) targets.add(subnetBroadcast);
            }
        } catch (Exception ignored) { }

        byte[] request = "REVISION_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + 2800L;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            for (InetAddress target : targets) {
                DatagramPacket packet = new DatagramPacket(request, request.length, target, 47822);
                socket.send(packet);
            }
            while (System.currentTimeMillis() < deadline) {
                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                try {
                    String raw = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), StandardCharsets.UTF_8);
                    JSONObject item = new JSONObject(raw);
                    if (!"revision-discovery-v1".equals(item.optString("protocol"))) continue;
                    String deviceId = item.optString("deviceId", "").trim();
                    String name = item.optString("name", "").trim();
                    int port = item.optInt("port", 47821);
                    if (deviceId.isEmpty() || name.isEmpty() || port < 1 || port > 65535) continue;
                    String address = packet.getAddress().getHostAddress();
                    item.put("address", address);
                    item.put("baseUrl", "http://" + address + ":" + port);
                    found.put(deviceId, item);
                } catch (Exception ignored) { }
            }
        }
        return new ArrayList<>(found.values());
    }

    public static boolean isConfigured(Context context) {
        try {
            SharedPreferences p = prefs(context);
            return p.getString(KEY_BASE, null) != null && p.getString(KEY_SECRET, null) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the saved STUDIO PC address without exposing the pairing secret. */
    public static String getConfiguredBaseUrl(Context context) {
        try {
            return prefs(context).getString(KEY_BASE, null);
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONObject status(Context context) throws Exception {
        String base = prefs(context).getString(KEY_BASE, null);
        if (base == null) throw new IllegalStateException("Windows Agent ещё не сопряжён.");
        return status(context, base);
    }

    public static JSONObject status(Context context, String host) throws Exception {
        String base = normalizeBase(host);
        return request(base, "GET", "/v1/status", "", null);
    }

    public static JSONObject command(Context context, String action) throws Exception {
        if (!action.matches("shutdown|restart|cancel-shutdown|lock")) {
            throw new IllegalArgumentException("Недопустимая команда Windows Agent.");
        }
        SharedPreferences p = prefs(context);
        String base = p.getString(KEY_BASE, null);
        String secret = p.getString(KEY_SECRET, null);
        if (base == null || secret == null) throw new IllegalStateException("Windows Agent ещё не сопряжён.");
        String path = "/v1/command/" + action;
        long timestamp = System.currentTimeMillis() / 1000L;
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP | Base64.URL_SAFE);
        String payload = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n";
        byte[] key = Base64.decode(secret, Base64.DEFAULT);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String signature = Base64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        JSONObject result = request(base, "POST", path, "", new String[]{Long.toString(timestamp), nonce, signature});
        if (result.has("accepted") && !result.optBoolean("accepted")) {
            throw new IllegalStateException("Windows Agent не принял команду.");
        }
        return result;
    }

    private static void saveRegistryPair(Context context, JSONObject identity, String base, String secret)
            throws Exception {
        if (identity == null) throw new IllegalStateException("Windows Agent не сообщил постоянный ID устройства.");
        String deviceId = identity.optString("deviceId", "").trim();
        if (deviceId.isEmpty()) throw new IllegalStateException("Windows Agent не сообщил постоянный ID устройства.");
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device =
                new com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity();
        device.deviceId = deviceId;
        device.displayName = identity.optString("name", "Windows PC").trim();
        device.baseUrl = base;
        device.clientSecret = secret;
        device.createdAt = System.currentTimeMillis();
        device.lastSeenAt = device.createdAt;
        device.voiceAliasesJson = "[]";
        com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry registry =
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context);
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity previous = registry.get(deviceId);
        if (previous != null) {
            device.createdAt = previous.createdAt;
            device.voiceAliasesJson = previous.voiceAliasesJson;
            device.isDefault = previous.isDefault;
            device.enabled = previous.enabled;
        } else {
            device.isDefault = registry.getDefault() == null;
        }
        registry.addPairedDevice(device, device.isDefault);
    }

    /** Executes an allow-listed command on a specifically selected paired computer. */
    public static JSONObject command(Context context, String deviceId, String action) throws Exception {
        if (deviceId == null || deviceId.trim().isEmpty())
            throw new IllegalArgumentException("Не указан ID компьютера.");
        if (!action.matches("shutdown|restart|cancel-shutdown|lock"))
            throw new IllegalArgumentException("Недопустимая команда Windows Agent.");
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device =
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).get(deviceId);
        if (device == null || !device.enabled)
            throw new IllegalStateException("Компьютер не найден или отключён в списке устройств.");
        return authenticatedRequest(device.baseUrl, device.clientSecret,
                "/v1/command/" + action, "");
    }

    /** Executes a voice phrase on the selected paired computer, never on an arbitrary LAN host. */
    public static JSONObject executePhrase(Context context, String deviceId, String phrase) throws Exception {
        if (deviceId == null || deviceId.trim().isEmpty())
            throw new IllegalArgumentException("Не указан ID компьютера.");
        if (phrase == null || phrase.trim().isEmpty() || phrase.trim().length() > 160)
            throw new IllegalArgumentException("Не распознана голосовая фраза для запуска команды.");
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device =
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).get(deviceId);
        if (device == null || !device.enabled)
            throw new IllegalStateException("Компьютер не найден или отключён в списке устройств.");
        JSONObject body = new JSONObject();
        body.put("phrase", phrase.trim());
        return authenticatedRequest(device.baseUrl, device.clientSecret,
                "/v1/command/execute", body.toString());
    }

    /** Resolves a saved Windows Agent by exact display name or exact voice alias. */
    private static com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity resolveRegisteredDevice(
            Context context, String requestedName) throws Exception {
        com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry registry =
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context);
        String wanted = normalizeDeviceName(requestedName);
        if (wanted.isEmpty()) return registry.getDefault();
        java.util.List<com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity> devices = registry.getAll();
        java.util.List<com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity> matches = new java.util.ArrayList<>();
        for (com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device : devices) {
            if (!device.enabled) continue;
            boolean match = wanted.equals(normalizeDeviceName(device.displayName));
            try {
                org.json.JSONArray aliases = new org.json.JSONArray(device.voiceAliasesJson);
                for (int i = 0; !match && i < aliases.length(); i++)
                    match = wanted.equals(normalizeDeviceName(aliases.optString(i, "")));
            } catch (Exception ignored) { }
            if (match) matches.add(device);
        }
        if (matches.size() > 1)
            throw new IllegalStateException("Имя компьютера неоднозначно. Уточни голосовое имя устройства.");
        if (matches.isEmpty())
            throw new IllegalStateException("Компьютер «" + requestedName + "» не найден среди сопряжённых Windows Agent.");
        return matches.get(0);
    }

    private static String normalizeDeviceName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е').replaceAll("[^a-zа-я0-9]", "");
    }

    public static JSONObject executePhraseForDeviceName(Context context, String deviceName, String phrase) throws Exception {
        if (deviceName == null || deviceName.trim().isEmpty()) return executePhraseOnDefault(context, phrase);
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device;
        try {
            device = resolveRegisteredDevice(context, deviceName);
        } catch (IllegalStateException notFound) {
            if ("studio".equals(normalizeDeviceName(deviceName))
                    && isConfigured(context)) return executePhrase(context, phrase);
            throw notFound;
        }
        if (device == null) throw new IllegalStateException("Компьютер по умолчанию не настроен.");
        return executePhrase(context, device.deviceId, phrase);
    }

    public static JSONObject commandForDeviceName(Context context, String deviceName, String action) throws Exception {
        if (deviceName == null || deviceName.trim().isEmpty()) {
            com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity defaultDevice =
                    new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).getDefault();
            if (defaultDevice != null) return command(context, defaultDevice.deviceId, action);
            return command(context, action);
        }
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device;
        try {
            device = resolveRegisteredDevice(context, deviceName);
        } catch (IllegalStateException notFound) {
            if ("studio".equals(normalizeDeviceName(deviceName)) && isConfigured(context))
                return command(context, action);
            throw notFound;
        }
        return command(context, device.deviceId, action);
    }

    public static JSONObject statusForDeviceName(Context context, String deviceName) throws Exception {
        if (deviceName == null || deviceName.trim().isEmpty()) {
            com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity defaultDevice =
                    new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).getDefault();
            return defaultDevice == null ? status(context) : status(context, defaultDevice.baseUrl);
        }
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device =
                resolveRegisteredDevice(context, deviceName);
        return status(context, device.baseUrl);
    }

    /** Executes on the configured default paired device; legacy STUDIO pairing remains supported. */
    public static JSONObject executePhraseOnDefault(Context context, String phrase) throws Exception {
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device =
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).getDefault();
        if (device != null) return executePhrase(context, device.deviceId, phrase);
        return executePhrase(context, phrase);
    }

    private static JSONObject authenticatedRequest(String base, String secret, String path, String body)
            throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP | Base64.URL_SAFE);
        String payload = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.decode(secret, Base64.DEFAULT), "HmacSHA256"));
        String signature = Base64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return request(base, "POST", path, body,
                new String[]{Long.toString(timestamp), nonce, signature});
    }

    public static JSONObject executePhrase(Context context, String phrase) throws Exception {
        if (phrase == null || phrase.trim().isEmpty() || phrase.trim().length() > 160) {
            throw new IllegalArgumentException("Не распознана голосовая фраза для запуска команды.");
        }
        SharedPreferences p = prefs(context);
        String base = p.getString(KEY_BASE, null);
        String secret = p.getString(KEY_SECRET, null);
        if (base == null || secret == null) throw new IllegalStateException("Windows Agent ещё не сопряжён.");
        JSONObject body = new JSONObject();
        body.put("phrase", phrase.trim());
        String bodyText = body.toString();
        String path = "/v1/command/execute";
        long timestamp = System.currentTimeMillis() / 1000L;
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP | Base64.URL_SAFE);
        String payload = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyText;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.decode(secret, Base64.DEFAULT), "HmacSHA256"));
        String signature = Base64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return request(base, "POST", path, bodyText,
                new String[]{Long.toString(timestamp), nonce, signature});
    }

    public static void unpair(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String base = p.getString(KEY_BASE, null);
        String secret = p.getString(KEY_SECRET, null);
        if (base != null && secret != null) {
            String path = "/v1/unpair";
            long timestamp = System.currentTimeMillis() / 1000L;
            byte[] nonceBytes = new byte[16];
            new SecureRandom().nextBytes(nonceBytes);
            String nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP | Base64.URL_SAFE);
            String payload = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.decode(secret, Base64.DEFAULT), "HmacSHA256"));
            String signature = Base64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            request(base, "POST", path, "", new String[]{Long.toString(timestamp), nonce, signature});
        }
        prefs(context).edit().remove(KEY_BASE).remove(KEY_SECRET).remove(KEY_DEVICE_ID).commit();
    }

    private static String normalizeBase(String input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Укажи IP-адрес STUDIO PC.");
        String value = input.trim();
        if (!value.matches("(?i)^https?://[^/\\s:]+(?::[0-9]{1,5})?$")) {
            throw new IllegalArgumentException("Укажи адрес вида http://192.168.1.10:47821");
        }
        URL url = new URL(value);
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("Недопустимый протокол.");
        }
        InetAddress address = InetAddress.getByName(url.getHost());
        byte[] b = address.getAddress();
        boolean privateV4 = b.length == 4 && ((b[0] & 255) == 10 ||
                ((b[0] & 255) == 172 && (b[1] & 0xf0) == 16) ||
                ((b[0] & 255) == 192 && (b[1] & 255) == 168));
        boolean loopback = address.isLoopbackAddress();
        if (!privateV4 && !loopback && !address.isSiteLocalAddress()) {
            throw new IllegalArgumentException("Разрешены только адреса частной локальной сети.");
        }
        return value.replaceAll("/+$", "");
    }

    private static JSONObject request(String base, String method, String path, String body, String[] auth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (auth != null) {
                connection.setRequestProperty("X-Revision-Time", auth[0]);
                connection.setRequestProperty("X-Revision-Nonce", auth[1]);
                connection.setRequestProperty("X-Revision-Signature", auth[2]);
            }
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] buffer = new byte[2048];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (bytes.size() + count > 16384) throw new IllegalStateException("Ответ Windows Agent слишком велик.");
                        bytes.write(buffer, 0, count);
                    }
                }
            }
            String text = bytes.toString(StandardCharsets.UTF_8.name());
            if (status < 200 || status >= 300) throw new IllegalStateException("Windows Agent: HTTP " + status + (text.isEmpty() ? "" : " — " + text));
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }
}
