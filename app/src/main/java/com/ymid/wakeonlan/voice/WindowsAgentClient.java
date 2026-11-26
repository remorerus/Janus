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

    /** True when either the multi-device registry or the legacy STUDIO profile has a pairing. */
    public static boolean isAnyConfigured(Context context) {
        try {
            com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry registry =
                    new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context);
            if (registry.getDefault() != null) return true;
        } catch (Exception ignored) { }
        return isConfigured(context);
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
            device.endpointUrlsJson = previous.endpointUrlsJson;
            device.isDefault = previous.isDefault;
            device.enabled = previous.enabled;
        } else {
            device.endpointUrlsJson = new org.json.JSONArray().put(base).toString();
            device.isDefault = registry.getDefault() == null;
        }
        registry.addPairedDevice(device, device.isDefault);
    }

    /**
     * Sends a public SSH key to a discovered Windows Agent for owner approval in its local web manager.
     * No pairing secret or private key is transmitted.
     */
    public static JSONObject requestSshKeyApproval(Context context,
            com.ymid.wakeonlan.persistence.models.Device device, String username, String keyAlias) throws Exception {
        if (device == null) throw new IllegalArgumentException("Карточка устройства не загружена.");
        if (username == null || !username.trim().matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Укажи корректное имя пользователя Windows в карточке устройства.");
        if (keyAlias == null || keyAlias.trim().isEmpty())
            throw new IllegalStateException("SSH-ключ ещё не создан.");
        String publicKey = com.ymid.wakeonlan.security.SshKeyManager.getOpenSshPublicKey(keyAlias);
        if (publicKey == null || publicKey.trim().isEmpty())
            throw new IllegalStateException("Не удалось прочитать открытый ключ из Android Keystore.");
        List<JSONObject> found = discover(context);
        String wanted = normalizeDeviceName(device.name);
        String savedHost = endpointHost(device.sshAddress);
        if (savedHost.isEmpty()) savedHost = endpointHost(device.statusIp);
        List<JSONObject> matches = new ArrayList<>();
        for (JSONObject candidate : found) {
            String address = candidate.optString("address", "").trim();
            String agentName = normalizeDeviceName(candidate.optString("name", ""));
            boolean sameName = !wanted.isEmpty() && (wanted.equals(agentName) ||
                    (wanted.contains("studio") && agentName.contains("studio")));
            boolean sameAddress = !savedHost.isEmpty() && savedHost.equals(address);
            if (sameName || sameAddress) matches.add(candidate);
        }
        if (matches.isEmpty() && found.size() == 1) matches.add(found.get(0));
        if (matches.isEmpty())
            throw new IllegalStateException("Windows Agent не найден. Проверь, что компьютер включён и очки находятся в той же локальной сети.");
        if (matches.size() > 1)
            throw new IllegalStateException("Найдено несколько Windows Agent. Уточни имя или IP компьютера в карточке устройства.");
        JSONObject target = matches.get(0);
        String base = normalizeBase(target.optString("baseUrl", ""));
        JSONObject body = new JSONObject();
        body.put("deviceName", device.name == null || device.name.trim().isEmpty() ? "RE:Vision" : device.name.trim());
        body.put("deviceId", "revision-device-" + Integer.toHexString((device.name + "|" + savedHost).hashCode()));
        body.put("username", username.trim());
        body.put("publicKey", publicKey.trim());
        JSONObject response = request(base, "POST", "/v1/ssh-key-requests", body.toString(), null);
        response.put("agentName", target.optString("name", "Windows PC"));
        response.put("agentAddress", target.optString("address", ""));
        return response;
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
        return authenticatedRequestForDevice(context, device,
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
        return authenticatedRequestForDevice(context, device,
                "/v1/command/execute", body.toString());
    }

    public static JSONObject executePhrase(Context context, String deviceId, String phrase,
            java.util.List<String> alternatives) throws Exception {
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
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        unique.add(phrase.trim());
        if (alternatives != null) for (String item : alternatives) {
            if (item != null && !item.trim().isEmpty() && item.trim().length() <= 160 && unique.size() < 5)
                unique.add(item.trim());
        }
        for (String item : unique) arr.put(item);
        body.put("alternatives", arr);
        return authenticatedRequestForDevice(context, device, "/v1/command/execute", body.toString());
    }

    /**
     * Resolves a Windows Agent from the device name already known to RE:Vision.
     * First use the paired-agent display name/aliases; if the spoken name is a
     * saved scanner entry, associate it with the paired agent by its current
     * discovered identity or matching saved endpoint. Pairing secrets remain
     * keyed only by the Windows Agent's stable deviceId.
     */
    private static com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity resolveRegisteredDevice(
            Context context, String requestedName) throws Exception {
        com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry registry =
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context);
        String wanted = normalizeDeviceName(requestedName);
        if (wanted.isEmpty()) {
            com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity defaultDevice = registry.getDefault();
            if (defaultDevice != null) return defaultDevice;
            throw new IllegalStateException("Не указано имя компьютера и не выбрано устройство по умолчанию.");
        }

        java.util.List<com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity> paired = registry.getAll();
        java.util.List<com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity> exact =
                new java.util.ArrayList<>();
        for (com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device : paired) {
            if (!device.enabled) continue;
            boolean match = wanted.equals(normalizeDeviceName(device.displayName));
            try {
                org.json.JSONArray aliases = new org.json.JSONArray(device.voiceAliasesJson);
                for (int i = 0; !match && i < aliases.length(); i++)
                    match = wanted.equals(normalizeDeviceName(aliases.optString(i, "")));
            } catch (Exception ignored) { }
            if (match) exact.add(device);
        }
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1)
            throw new IllegalStateException("Имя компьютера неоднозначно. Уточни имя устройства.");

        // The spoken label may be the friendly name stored by RE:Vision's own
        // network scanner, while the Windows Agent advertises the Windows host name.
        java.util.List<com.ymid.wakeonlan.persistence.models.Device> scanned =
                com.ymid.wakeonlan.persistence.repository.DeviceRepository.getInstance(context).getAll();
        java.util.List<com.ymid.wakeonlan.persistence.models.Device> scannedMatches =
                new java.util.ArrayList<>();
        for (com.ymid.wakeonlan.persistence.models.Device item : scanned) {
            if (item != null && wanted.equals(normalizeDeviceName(item.name))) scannedMatches.add(item);
        }
        if (scannedMatches.size() > 1)
            throw new IllegalStateException("В списке RE:Vision несколько устройств с таким именем.");

        // Also consult the live scan screen. This lets a spoken hostname resolve
        // immediately after RE:Vision discovers it, without manually entering an IP
        // or adding the scan result to the saved-device list first.
        java.util.List<String> liveScanIps = new java.util.ArrayList<>();
        for (com.ymid.wakeonlan.ui.scan.model.NetworkScanDevice scanItem
                : com.ymid.wakeonlan.ui.scan.NetworkScanCache.snapshot()) {
            if (scanItem != null && scanItem.getName() != null && scanItem.getName().isPresent()
                    && wanted.equals(normalizeDeviceName(scanItem.getName().get()))) {
                String ip = scanItem.getIpAddress();
                if (ip != null && !ip.trim().isEmpty() && !liveScanIps.contains(ip.trim()))
                    liveScanIps.add(ip.trim());
            }
        }
        if (liveScanIps.size() > 1)
            throw new IllegalStateException("Сканирование RE:Vision нашло несколько компьютеров с таким именем.");

        java.util.LinkedHashSet<String> scannedEndpointHosts = new java.util.LinkedHashSet<>(liveScanIps);
        for (com.ymid.wakeonlan.persistence.models.Device saved : scannedMatches) {
            String statusHost = endpointHost(saved.statusIp);
            String sshHost = endpointHost(saved.sshAddress);
            if (!statusHost.isEmpty()) scannedEndpointHosts.add(statusHost);
            if (!sshHost.isEmpty()) scannedEndpointHosts.add(sshHost);
        }

        java.util.List<JSONObject> discovered = new java.util.ArrayList<>();
        try { discovered = discover(context); } catch (Exception ignored) { }

        java.util.List<com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity> resolved =
                new java.util.ArrayList<>();
        for (com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity pairedDevice : paired) {
            if (!pairedDevice.enabled) continue;
            JSONObject live = null;
            for (JSONObject candidate : discovered) {
                if (pairedDevice.deviceId.equals(candidate.optString("deviceId", ""))) {
                    live = candidate;
                    break;
                }
            }

            boolean match = live != null
                    && wanted.equals(normalizeDeviceName(live.optString("name", "")));

            if (!match && !liveScanIps.isEmpty()) {
                String currentAgentHost = live != null
                        ? endpointHost(live.optString("address", ""))
                        : endpointHost(pairedDevice.baseUrl);
                match = liveScanIps.contains(currentAgentHost);
            }

            if (!match && !scannedMatches.isEmpty()) {
                com.ymid.wakeonlan.persistence.models.Device saved = scannedMatches.get(0);
                String savedName = normalizeDeviceName(saved.name);
                String agentName = normalizeDeviceName(pairedDevice.displayName);
                String discoveredName = live == null ? "" : normalizeDeviceName(live.optString("name", ""));
                match = savedName.equals(agentName) || savedName.equals(discoveredName);

                if (!match) {
                    String savedStatusHost = endpointHost(saved.statusIp);
                    String savedSshHost = endpointHost(saved.sshAddress);
                    String liveHost = live == null ? "" : endpointHost(live.optString("address", ""));
                    String pairedHost = endpointHost(pairedDevice.baseUrl);
                    match = (!savedStatusHost.isEmpty()
                                    && (savedStatusHost.equals(liveHost) || savedStatusHost.equals(pairedHost)))
                            || (!savedSshHost.isEmpty()
                                    && (savedSshHost.equals(liveHost) || savedSshHost.equals(pairedHost)));
                }
            }

            // UDP discovery is subnet-local. If the RE:Vision scanner knows a
            // candidate address, verify the stable Windows Agent ID directly over
            // HTTP on 47821. Only an exact ID match can refresh this paired endpoint.
            if (!match && !scannedEndpointHosts.isEmpty()) {
                match = refreshEndpointForDevice(context, pairedDevice, scannedEndpointHosts);
            } else if (match && !scannedEndpointHosts.isEmpty()) {
                refreshEndpointForDevice(context, pairedDevice, scannedEndpointHosts);
            }

            if (match) resolved.add(pairedDevice);
        }

        if (resolved.size() == 1) return resolved.get(0);
        if (resolved.size() > 1)
            throw new IllegalStateException("Имя устройства соответствует нескольким сопряжённым компьютерам.");
        if (!scannedMatches.isEmpty() || !liveScanIps.isEmpty())
            throw new IllegalStateException("Устройство «" + requestedName
                    + "» найдено сканированием RE:Vision, но для него не найден соответствующий сопряжённый Windows Agent.");
        throw new IllegalStateException("Устройство «" + requestedName
                + "» не найдено среди устройств RE:Vision и обнаруженных Windows Agent.");
    }

    private static boolean refreshEndpointForDevice(Context context,
            com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device,
            java.util.Set<String> candidateHosts) {
        for (String host : candidateHosts) {
            try {
                String base = normalizeBase("http://" + host + ":47821");
                JSONObject identity = request(base, "GET", "/v1/discovery", "", null);
                if (!device.deviceId.equals(identity.optString("deviceId", ""))) continue;

                org.json.JSONArray endpoints = new org.json.JSONArray();
                endpoints.put(base);
                try {
                    org.json.JSONArray old = new org.json.JSONArray(
                            device.endpointUrlsJson == null ? "[]" : device.endpointUrlsJson);
                    for (int i = 0; i < old.length(); i++) {
                        String previous = old.optString(i, "");
                        if (!previous.isEmpty() && !previous.equals(base)) endpoints.put(previous);
                    }
                } catch (Exception ignored) { }
                device.baseUrl = base;
                device.endpointUrlsJson = endpoints.toString();
                device.lastSeenAt = System.currentTimeMillis();
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).update(device);
                return true;
            } catch (Exception ignored) {
                // A stale scanner result or a host without Windows Agent is not a match.
            }
        }
        return false;
    }

    private static String endpointHost(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return "";
        try {
            String value = endpoint.trim();
            if (!value.contains("://")) value = "http://" + value;
            return new URL(value).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isLegacyStudioAlias(String value) {
        String normalized = normalizeDeviceName(value);
        return normalized.equals("studio") || normalized.equals("studiopc")
                || normalized.equals("студия") || normalized.equals("студиописи");
    }

    private static String normalizeDeviceName(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е').replaceAll("[^a-zа-я0-9]", "");
        // Treat the spoken Cyrillic alias "студио локал" as the same RE:Vision
        // device name as its Latin display form "Studio Local".
        return normalized.replace("студиолокал", "studiolocal");
    }

    public static JSONObject executePhraseForDeviceName(Context context, String deviceName, String phrase) throws Exception {
        if (deviceName == null || deviceName.trim().isEmpty()) return executePhraseOnDefault(context, phrase);
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device;
        try {
            device = resolveRegisteredDevice(context, deviceName);
        } catch (IllegalStateException notFound) {
            if (isLegacyStudioAlias(deviceName)
                    && isConfigured(context)) return executePhrase(context, phrase);
            throw notFound;
        }
        if (device == null) throw new IllegalStateException("Компьютер по умолчанию не настроен.");
        return executePhrase(context, device.deviceId, phrase);
    }

    public static JSONObject executePhraseForDeviceName(Context context, String deviceName, String phrase,
            java.util.List<String> alternatives) throws Exception {
        if (deviceName == null || deviceName.trim().isEmpty())
            return executePhraseOnDefault(context, phrase, alternatives);
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device;
        try {
            device = resolveRegisteredDevice(context, deviceName);
        } catch (IllegalStateException notFound) {
            if (isLegacyStudioAlias(deviceName) && isConfigured(context))
                return executeLegacyPhrase(context, phrase, alternatives);
            throw notFound;
        }
        return executePhrase(context, device.deviceId, phrase, alternatives);
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
            if (isLegacyStudioAlias(deviceName) && isConfigured(context))
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
        return executePhraseOnDefault(context, phrase, java.util.Collections.emptyList());
    }

    public static JSONObject executePhraseOnDefault(Context context, String phrase,
            java.util.List<String> alternatives) throws Exception {
        com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device =
                new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).getDefault();
        if (device != null) return executePhrase(context, device.deviceId, phrase, alternatives);
        return executeLegacyPhrase(context, phrase, alternatives);
    }

    private static JSONObject executeLegacyPhrase(Context context, String phrase,
            java.util.List<String> alternatives) throws Exception {
        if (phrase == null || phrase.trim().isEmpty() || phrase.trim().length() > 160)
            throw new IllegalArgumentException("Не распознана голосовая фраза для запуска команды.");
        SharedPreferences p = prefs(context);
        String base = p.getString(KEY_BASE, null);
        String secret = p.getString(KEY_SECRET, null);
        if (base == null || secret == null) throw new IllegalStateException("Windows Agent ещё не сопряжён.");
        JSONObject body = new JSONObject();
        body.put("phrase", phrase.trim());
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        unique.add(phrase.trim());
        if (alternatives != null) for (String item : alternatives) {
            if (item != null && !item.trim().isEmpty() && item.trim().length() <= 160 && unique.size() < 5)
                unique.add(item.trim());
        }
        for (String item : unique) arr.put(item);
        body.put("alternatives", arr);
        return authenticatedRequest(base, secret, "/v1/command/execute", body.toString());
    }

    /**
     * Refreshes LAN routes from the Agent's stable deviceId, then tries known routes
     * in order. Discovery never changes the saved pairing secret or trusts a new device.
     */
    private static JSONObject authenticatedRequestForDevice(Context context,
            com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity device,
            String path, String body) throws Exception {
        java.util.LinkedHashSet<String> routes = new java.util.LinkedHashSet<>();
        try {
            for (JSONObject found : discover(context)) {
                if (device.deviceId.equals(found.optString("deviceId", ""))) {
                    String discovered = found.optString("baseUrl", "");
                    if (!discovered.isEmpty()) routes.add(discovered);
                }
            }
        } catch (Exception ignored) { }
        try {
            org.json.JSONArray saved = new org.json.JSONArray(device.endpointUrlsJson == null ? "[]" : device.endpointUrlsJson);
            for (int i = 0; i < saved.length(); i++) {
                String value = saved.optString(i, "");
                if (!value.isEmpty()) routes.add(value);
            }
        } catch (Exception ignored) { }
        if (device.baseUrl != null && !device.baseUrl.isEmpty()) routes.add(device.baseUrl);
        if (routes.isEmpty()) throw new IllegalStateException("Windows Agent не найден в локальной сети.");

        org.json.JSONArray updated = new org.json.JSONArray();
        for (String route : routes) updated.put(route);
        device.endpointUrlsJson = updated.toString();
        device.baseUrl = routes.iterator().next();
        device.lastSeenAt = System.currentTimeMillis();
        new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).update(device);

        Exception last = null;
        for (String route : routes) {
            try {
                JSONObject result = authenticatedRequest(route, device.clientSecret, path, body);
                if (!route.equals(device.baseUrl)) {
                    device.baseUrl = route;
                    new com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry(context).update(device);
                }
                return result;
            } catch (Exception error) { last = error; }
        }
        throw last == null ? new IllegalStateException("Нет доступных маршрутов Windows Agent.") : last;
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

    /** Validates and canonicalizes a saved private-LAN Windows Agent endpoint. */
    public static String normalizeEndpoint(String input) throws Exception {
        return normalizeBase(input);
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
