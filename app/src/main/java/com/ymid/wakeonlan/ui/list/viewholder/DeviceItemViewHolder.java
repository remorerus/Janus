package com.ymid.wakeonlan.ui.list.viewholder;

import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.view.animation.TranslateAnimation;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.ymid.wakeonlan.security.AndroidKeyStoreKeyProvider;
import com.ymid.wakeonlan.security.KeystoreEcdsaSignature;
import com.ymid.wakeonlan.security.TofuHostKeyVerifier;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.base.Strings;

import com.ymid.wakeonlan.R;
import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.network.DeviceNetworkProfiles;
import com.ymid.wakeonlan.persistence.models.DeviceStatus;
import com.ymid.wakeonlan.shutdown.ShutdownModelFactory;
import com.ymid.wakeonlan.security.AuthenticatedDeviceActionActivity;
import com.ymid.wakeonlan.ui.LauncherIconManager;
import com.ymid.wakeonlan.ui.ObfuscatedTextView;
import com.ymid.wakeonlan.ui.list.DeviceClickedCallback;
import com.ymid.wakeonlan.ui.list.SparklineView;
import com.ymid.wakeonlan.ui.list.status.DeviceStatusBoard;
import com.ymid.wakeonlan.ui.list.status.DeviceStatusListener;
import com.ymid.wakeonlan.ui.list.status.DeviceStatusSnapshot;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTestType;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTesterPool;
import com.ymid.wakeonlan.voice.WindowsAgentClient;

public class DeviceItemViewHolder extends RecyclerView.ViewHolder {

    private enum RowState { UNKNOWN, ONLINE, OFFLINE, WAKING }

    private final View deviceStatus;
    private final TextView deviceName;
    private final ObfuscatedTextView deviceMacAddress;
    private final TextView deviceIp;
    private final TextView deviceIpPrevious;
    private final TextView deviceIpHint;
    private final TextView deviceStatusDetails;
    private final TextView deviceLatencyUnit;
    private final SparklineView latencySparkline;
    private final TextView wakeLog;
    private final TextView deviceIpArrow;
    private final View sshLed;
    private final TextView sshState;
    private final View agentLed;
    private final TextView agentState;
    private static final ScheduledExecutorService SERVICE_PROBES = Executors.newScheduledThreadPool(4);
    private ScheduledFuture<?> serviceProbeFuture;
    private volatile String lastActiveAddress;

    private final TextView editButton;
    private final TextView sendWolButton;
    private final TextView shutdownButton;
    private final DeviceClickedCallback deviceClickedCallback;
    private final StatusTesterPool statusTesterPool;
    private final DeviceStatusBoard statusBoard = DeviceStatusBoard.getInstance();

    private Device device;
    private boolean drivesLauncherIcon;
    private boolean shutdownAvailable;
    private RowState shownState;

    public DeviceItemViewHolder(View view, DeviceClickedCallback deviceClickedCallback, StatusTesterPool statusTesterPool) {
        super(view);
        deviceStatus = view.findViewById(R.id.device_status);
        deviceName = view.findViewById(R.id.device_name);
        deviceMacAddress = view.findViewById(R.id.device_mac);
        deviceIp = view.findViewById(R.id.device_ip);
        deviceIpPrevious = view.findViewById(R.id.device_ip_previous);
        deviceIpHint = view.findViewById(R.id.device_ip_hint);
        deviceStatusDetails = view.findViewById(R.id.device_status_details);
        deviceLatencyUnit = view.findViewById(R.id.device_latency_unit);
        latencySparkline = view.findViewById(R.id.device_latency_sparkline);
        wakeLog = view.findViewById(R.id.device_wake_log);
        deviceIpArrow = view.findViewById(R.id.device_ip_arrow);
        sshLed = view.findViewById(R.id.device_ssh_led);
        sshState = view.findViewById(R.id.device_ssh_state);
        agentLed = view.findViewById(R.id.device_agent_led);
        agentState = view.findViewById(R.id.device_agent_state);

        editButton = view.findViewById(R.id.edit);
        sendWolButton = view.findViewById(R.id.send_wol);
        shutdownButton = view.findViewById(R.id.shutdown);
        this.deviceClickedCallback = deviceClickedCallback;
        this.statusTesterPool = statusTesterPool;
    }

    public synchronized void fromDevice(Device device, boolean drivesLauncherIcon) {
        this.device = device;
        this.drivesLauncherIcon = drivesLauncherIcon;

        deviceName.setText(device.name);
        lastActiveAddress = statusBoard.getLastActiveAddress(device.id);
        String rememberedMac = statusBoard.getLastActiveMacAddress(device.id);
        updateActiveIp(lastActiveAddress, rememberedMac, false, lastActiveAddress != null,
                statusBoard.getPreviousActiveAddress(device.id));
        setServiceState(sshLed, sshState, "ПРОВЕРКА", 0);
        setServiceState(agentLed, agentState, "ПРОВЕРКА", 0);
        startServiceStatusProbes(device);

        setOnClickHandler(device);
        setOnEditClickHandler(device);
        setShutdownClickHandler(device);
        startDeviceStatusQuery(device);
    }

    public void setOnClickHandler(Device device) {
        sendWolButton.setOnClickListener(view -> {
            AuthenticatedDeviceActionActivity.startWake(view.getContext(), device.id);
            deviceClickedCallback.onDeviceClicked(deviceName.getText().toString());
        });
    }

    public void setOnEditClickHandler(Device device) {
        editButton.setOnClickListener(view -> {
            AuthenticatedDeviceActionActivity.startEdit(view.getContext(), device.id);
        });
    }

    public void setShutdownClickHandler(Device device) {
        shutdownAvailable = ShutdownModelFactory.fromDevice(device).isPresent();
        if (shutdownAvailable) {
            shutdownButton.setOnClickListener(v -> {
                AuthenticatedDeviceActionActivity.startShutdown(v.getContext(), device.id);
            });
        } else {
            shutdownButton.setOnClickListener(null);
        }
    }

    public void startDeviceStatusQuery(Device device) {
        shownState = null;
        render(statusBoard.isWaking(device.id) ? RowState.WAKING : RowState.UNKNOWN, null);

        final int deviceId = device.id;
        statusTesterPool.schedule(device, new DeviceStatusListener() {
            @Override
            public void onStatusAvailable(DeviceStatus deviceStatus) {
                updateStatusDetails(deviceId, new DeviceStatusSnapshot(deviceStatus, null));
            }

            @Override
            public void onStatusSnapshotAvailable(DeviceStatusSnapshot statusSnapshot) {
                updateStatusDetails(deviceId, statusSnapshot);
            }
        }, StatusTestType.LIST);
    }

    private void updateStatusDetails(int deviceId, DeviceStatusSnapshot statusSnapshot) {
        itemView.post(() -> {
            boolean addressChanged = statusBoard.record(deviceId, statusSnapshot);
            // The row may have been rebound to another device while the check was running
            if (device == null || device.id != deviceId) {
                return;
            }

            DeviceStatus status = statusSnapshot.getStatus();
            if (status == DeviceStatus.ONLINE) {
                String activeAddress = statusSnapshot.getActiveAddress();
                String activeMac = statusSnapshot.getActiveMacAddress();
                updateActiveIp(activeAddress, activeMac, addressChanged, false,
                        statusBoard.getPreviousActiveAddress(deviceId));
                updateLauncherIconIfNeeded(true);
                render(RowState.ONLINE, statusSnapshot.getLatencyMs());
            } else if (status == DeviceStatus.OFFLINE) {
                updateActiveIp(statusBoard.getLastActiveAddress(deviceId),
                        statusBoard.getLastActiveMacAddress(deviceId), false, true,
                        statusBoard.getPreviousActiveAddress(deviceId));
                updateLauncherIconIfNeeded(false);
                render(statusBoard.isWaking(deviceId) ? RowState.WAKING : RowState.OFFLINE, null);
            } else {
                render(RowState.UNKNOWN, null);
            }
        });
    }

    private void updateActiveIp(String activeAddress, String activeMac, boolean addressChanged,
                                boolean lastKnown, String previousAddress) {
        String next = Strings.isNullOrEmpty(activeAddress) ? null : activeAddress.trim();
        if (next != null) lastActiveAddress = next;
        String previous = Strings.isNullOrEmpty(previousAddress) ? null : previousAddress.trim();
        boolean hasTransition = previous != null && next != null && !previous.equalsIgnoreCase(next);
        deviceIpPrevious.setText(hasTransition ? previous : "");
        deviceIpPrevious.setVisibility(hasTransition ? View.VISIBLE : View.GONE);
        deviceIp.setText(next == null ? "" : next);
        deviceIp.setVisibility(next == null ? View.GONE : View.VISIBLE);
        deviceIp.setAlpha(lastKnown ? 0.65f : 1f);
        deviceIpHint.setText(lastKnown ? "последний" : "");
        deviceIpHint.setVisibility(lastKnown && next != null ? View.VISIBLE : View.GONE);

        if (!Strings.isNullOrEmpty(activeMac)) {
            deviceMacAddress.setObfuscatedText(activeMac);
            deviceMacAddress.setVisibility(View.VISIBLE);
        } else if (next == null && DeviceNetworkProfiles.statusAddresses(device).size() <= 1) {
            deviceMacAddress.setObfuscatedText(device.macAddress);
            deviceMacAddress.setVisibility(Strings.isNullOrEmpty(device.macAddress) ? View.GONE : View.VISIBLE);
        } else {
            // Do not display the first profile's MAC beside an IP from another network.
            deviceMacAddress.setVisibility(View.GONE);
        }

        if (hasTransition) {
            deviceIpArrow.setVisibility(View.VISIBLE);
        } else {
            deviceIpArrow.setVisibility(View.GONE);
            deviceIpArrow.clearAnimation();
        }
        if (addressChanged && hasTransition) {
            deviceIp.clearAnimation();
            TranslateAnimation ipMotion = new TranslateAnimation(-dp(22), 0, 0, 0);
            ipMotion.setDuration(850);
            deviceIp.startAnimation(ipMotion);

            deviceIpArrow.clearAnimation();
            TranslateAnimation arrow = new TranslateAnimation(-dp(8), dp(42), 0, 0);
            arrow.setDuration(1100);
            arrow.setRepeatCount(1);
            arrow.setRepeatMode(Animation.RESTART);
            deviceIpArrow.startAnimation(arrow);
        }
    }

    private int dp(float value) {
        return (int) (value * itemView.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startServiceStatusProbes(Device target) {
        if (serviceProbeFuture != null) serviceProbeFuture.cancel(true);
        final int targetId = target.id;
        serviceProbeFuture = SERVICE_PROBES.scheduleWithFixedDelay(() -> {
            String activeAddress = lastActiveAddress;
            int sshResult = probeSsh(target, activeAddress);
            int agentResult = probeWindowsAgent(target, activeAddress);
            itemView.post(() -> {
                if (device == null || device.id != targetId) return;
                if (sshResult == 1) {
                    setServiceState(sshLed, sshState, "SSH OK", 1);
                } else if (sshResult == 2) {
                    setServiceState(sshLed, sshState, "ПОРТ ЕСТЬ", 3);
                } else if (sshResult == -1) {
                    setServiceState(sshLed, sshState, "НЕ НАСТР.", 0);
                } else {
                    setServiceState(sshLed, sshState, "OFFLINE", 2);
                }

                if (agentResult == 1) {
                    setServiceState(agentLed, agentState, "ONLINE", 1);
                } else if (agentResult == 2) {
                    setServiceState(agentLed, agentState, "НУЖЕН КЛЮЧ", 3);
                } else if (agentResult == 3) {
                    setServiceState(agentLed, agentState, "КЛЮЧ ОШИБКА", 2);
                } else if (agentResult == 0) {
                    setServiceState(agentLed, agentState, "OFFLINE", 2);
                } else {
                    setServiceState(agentLed, agentState, "НЕ ПРИВЯЗАН", 0);
                }
            });
        }, 0, 6, TimeUnit.SECONDS);
    }

    /**
     * 1=SSH public-key authentication succeeded, 2=TCP port is reachable but SSH
     * authentication failed, 0=unreachable, -1=SSH credentials/key are not configured.
     */
    private int probeSsh(Device target, String activeAddress) {
        String username = target.sshUsername == null ? "" : target.sshUsername.trim();
        String alias = target.sshKeyAlias == null ? "" : target.sshKeyAlias.trim();
        if (username.isEmpty() || alias.isEmpty()
                || com.ymid.wakeonlan.security.SshKeyManager.getOpenSshPublicKey(alias) == null) {
            return -1;
        }

        int port = target.sshPort == null || target.sshPort < 1 ? 22 : target.sshPort;
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        if (!Strings.isNullOrEmpty(activeAddress)) hosts.add(activeAddress.trim());
        if (!Strings.isNullOrEmpty(target.sshAddress)) hosts.add(target.sshAddress.trim());
        if (hosts.isEmpty()) return -1;

        boolean tcpReachable = false;
        for (String host : hosts) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                tcpReachable = true;
            } catch (Exception ignored) {
                continue;
            }

            TofuHostKeyVerifier verifier = new TofuHostKeyVerifier(itemView.getContext());
            DefaultConfig config = new DefaultConfig();
            config.setKeyAlgorithms(KeystoreEcdsaSignature.replaceIn(config.getKeyAlgorithms()));
            try (SSHClient client = new SSHClient(config)) {
                client.addHostKeyVerifier(verifier);
                client.setConnectTimeout(1400);
                client.connect(host, port);
                client.authPublickey(username, new AndroidKeyStoreKeyProvider(alias));
                return 1;
            } catch (Exception ignored) {
                // A reachable TCP/22 port is not an authenticated SSH session.
                // Continue to any other explicitly configured route before reporting this.
            }
        }
        return tcpReachable ? 2 : 0;
    }

    /** 1=HMAC-verified paired agent, 2=PC holds a key but this app lacks it, 3=key rejected. */
    private int probeWindowsAgent(Device target, String activeAddress) {
        try {
            return WindowsAgentClient.probeForDevice(itemView.getContext(), target, activeAddress);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void setServiceState(View led, TextView label, String value, int state) {
        label.setText(value);
        int color = state == 1 ? Color.rgb(67, 205, 120)
                : state == 2 ? Color.rgb(235, 83, 83)
                : state == 3 ? Color.rgb(244, 174, 74) : Color.rgb(145, 145, 145);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        led.setBackground(dot);
        label.setTextColor(color);
        // Service LEDs show a stable state; pulsing was making an unverified TCP check
        // look like a live authenticated SSH session.
        led.clearAnimation();
    }

    private void render(RowState state, Long latencyMs) {
        if (state == RowState.ONLINE) {
            deviceStatusDetails.setText(latencyMs == null ? "--" : String.valueOf(latencyMs));
            latencySparkline.setValues(statusBoard.getLatencyHistory(device.id));
        }
        if (state == shownState) {
            return;
        }
        shownState = state;

        int figureColor;
        int ledDrawable;
        switch (state) {
            case ONLINE:
                ledDrawable = R.drawable.device_status_online;
                figureColor = R.color.ledOnlineColor;
                break;
            case OFFLINE:
                ledDrawable = R.drawable.device_status_offline;
                figureColor = R.color.ledOfflineColor;
                deviceStatusDetails.setText(R.string.device_status_short_offline);
                break;
            case WAKING:
                ledDrawable = R.drawable.device_status_waking;
                figureColor = R.color.ledWakingColor;
                deviceStatusDetails.setText(R.string.device_status_short_waking);
                break;
            default:
                ledDrawable = R.drawable.device_status_unknown;
                figureColor = R.color.primaryTextColorMuted;
                deviceStatusDetails.setText(R.string.device_status_short_unknown);
                break;
        }

        deviceStatus.setBackground(AppCompatResources.getDrawable(itemView.getContext(), ledDrawable));
        deviceStatusDetails.setTextColor(ContextCompat.getColor(itemView.getContext(), figureColor));
        // Figures read large; status words stay smaller so they don't shout
        deviceStatusDetails.setTextSize(state == RowState.ONLINE ? 22 : 15);
        deviceLatencyUnit.setVisibility(state == RowState.ONLINE ? View.VISIBLE : View.GONE);

        latencySparkline.setColor(ContextCompat.getColor(itemView.getContext(),
                state == RowState.ONLINE ? R.color.ledOnlineColor : R.color.primaryTextColorMuted));
        if (state != RowState.ONLINE) {
            latencySparkline.setValues(statusBoard.getLatencyHistory(device.id));
        }

        // Offer only the actions that make sense for the current state
        boolean waking = state == RowState.WAKING;
        sendWolButton.setVisibility(state == RowState.OFFLINE || state == RowState.UNKNOWN ? View.VISIBLE : View.GONE);
        shutdownButton.setVisibility(shutdownAvailable && (state == RowState.ONLINE || state == RowState.UNKNOWN) ? View.VISIBLE : View.GONE);
        editButton.setVisibility(waking ? View.GONE : View.VISIBLE);
        wakeLog.setVisibility(waking ? View.VISIBLE : View.GONE);

        if (waking) {
            AlphaAnimation blink = new AlphaAnimation(1f, 0.2f);
            blink.setDuration(450);
            blink.setRepeatMode(Animation.REVERSE);
            blink.setRepeatCount(Animation.INFINITE);
            deviceStatus.startAnimation(blink);
        } else {
            deviceStatus.clearAnimation();
        }
    }

    private void updateLauncherIconIfNeeded(boolean online) {
        if (!drivesLauncherIcon) {
            return;
        }
        LauncherIconManager.updateLauncherIcon(itemView.getContext(), online);
    }

    public void cancelStatusUpdates() {
        if (serviceProbeFuture != null) {
            serviceProbeFuture.cancel(true);
            serviceProbeFuture = null;
        }
        if (statusTesterPool != null && device != null) {
            statusTesterPool.stopSingle(device, StatusTestType.LIST);
        }
    }
}
