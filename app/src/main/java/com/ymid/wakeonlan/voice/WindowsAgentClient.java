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
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
        if (code == null || !code.trim().matches("[A-Fa-f0-9]{8}")) {
            throw new IllegalArgumentException("Код сопряжения должен содержать 8 шестнадцатеричных символов.");
        }
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        String secret = Base64.encodeToString(secretBytes, Base64.NO_WRAP);
        JSONObject body = new JSONObject();
        body.put("code", code.trim().toUpperCase(Locale.ROOT));
        body.put("clientSecret", secret);
        JSONObject response = request(base, "POST", "/v1/pair", body.toString(), null);
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(KEY_BASE, base);
        editor.putString(KEY_SECRET, secret);
        if (!editor.commit()) throw new IllegalStateException("Не удалось сохранить защищённые данные сопряжения.");
        return response;
    }

    public static boolean isConfigured(Context context) {
        try {
            SharedPreferences p = prefs(context);
            return p.getString(KEY_BASE, null) != null && p.getString(KEY_SECRET, null) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static JSONObject status(Context context) throws Exception {
        String base = prefs(context).getString(KEY_BASE, null);
        if (base == null) throw new IllegalStateException("Windows Agent ещё не сопряжён.");
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
        prefs(context).edit().remove(KEY_BASE).remove(KEY_SECRET).commit();
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
