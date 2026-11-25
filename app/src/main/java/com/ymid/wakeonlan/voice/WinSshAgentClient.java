package com.ymid.wakeonlan.voice;

import android.content.Context;
import android.util.Base64;

import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.security.AndroidKeyStoreKeyProvider;
import com.ymid.wakeonlan.security.TofuHostKeyVerifier;

import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Executes an exact phrase from the Win SSH Agent catalog over the device's paired SSH key. */
public final class WinSshAgentClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int COMMAND_TIMEOUT_MS = 15000;
    private static final String RUNNER = "C:\\Program Files\\RE:Vision SSH Agent\\run-ssh-command.ps1";

    private WinSshAgentClient() {}

    public static JSONObject executePhrase(Context context, Device device, String phrase) throws Exception {
        if (device == null) throw new IllegalArgumentException("STUDIO device is not configured.");
        if (phrase == null || phrase.trim().isEmpty()) throw new IllegalArgumentException("Voice phrase is empty.");
        String host = device.sshAddress == null || device.sshAddress.trim().isEmpty()
                ? device.statusIp : device.sshAddress;
        if (host == null || host.trim().isEmpty()) throw new IllegalStateException("SSH address is missing.");
        if (device.sshUsername == null || device.sshUsername.trim().isEmpty())
            throw new IllegalStateException("SSH Windows username is missing.");
        if (device.sshKeyAlias == null || device.sshKeyAlias.trim().isEmpty())
            throw new IllegalStateException("SSH key is not generated. Generate the key in RE:Vision device settings.");
        int port = device.sshPort == null || device.sshPort < 1 ? 22 : device.sshPort;

        String encodedPhrase = Base64.encodeToString(phrase.trim().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE);
        String remoteCommand = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \""
                + RUNNER + "\" -PhraseBase64 " + encodedPhrase;

        TofuHostKeyVerifier verifier = new TofuHostKeyVerifier(context);
        DefaultConfig config = new DefaultConfig();
        config.setKeyAlgorithms(com.ymid.wakeonlan.security.KeystoreEcdsaSignature.replaceIn(config.getKeyAlgorithms()));
        try (SSHClient client = new SSHClient(config)) {
            client.addHostKeyVerifier(verifier);
            client.setConnectTimeout(CONNECT_TIMEOUT_MS);
            client.connect(host.trim(), port);
            client.authPublickey(device.sshUsername.trim(), new AndroidKeyStoreKeyProvider(device.sshKeyAlias));
            try (Session session = client.startSession()) {
                Session.Command command = session.exec(remoteCommand);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                copyLimited(command.getInputStream(), output);
                command.join(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                Integer exit = command.getExitStatus();
                String result = output.toString(StandardCharsets.UTF_8.name()).trim();
                if (exit != null && exit != 0) {
                    throw new IllegalStateException(result.isEmpty()
                            ? "Win SSH Agent command failed with exit code " + exit : result);
                }
                JSONObject response = new JSONObject();
                response.put("ok", true);
                response.put("accepted", true);
                response.put("phrase", phrase.trim());
                response.put("output", result);
                return response;
            }
        } catch (Exception error) {
            TofuHostKeyVerifier.Mismatch mismatch = verifier.getMismatch();
            if (mismatch != null) {
                throw new IllegalStateException("SSH host key changed for " + mismatch.getHost()
                        + ". Stored fingerprint: " + mismatch.getStoredFingerprint()
                        + "; presented fingerprint: " + mismatch.getPresentedFingerprint(), error);
            }
            throw error;
        }
    }

    private static void copyLimited(InputStream input, ByteArrayOutputStream output) throws Exception {
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 16384) throw new IllegalStateException("Win SSH Agent output is too large.");
            output.write(buffer, 0, count);
        }
    }
}
