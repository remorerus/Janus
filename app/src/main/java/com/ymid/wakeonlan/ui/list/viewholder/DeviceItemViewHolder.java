package com.ymid.wakeonlan.ui.list.viewholder;

import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.view.animation.TranslateAnimation;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.ymid.wakeonlan.persistence.WindowsAgentDeviceRegistry;
import com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity;
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
import com.ymid.wakeonlan.voice.WindowsAgentClient;
import com.ymid.wakeonlan.ui.list.status.DeviceStatusBoard;
import com.ymid.wakeonlan.ui.list.status.DeviceStatusListener;
import com.ymid.wakeonlan.ui.list.status.DeviceStatusSnapshot;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTestType;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTesterPool;

public class DeviceItemViewHolder extends RecyclerView.ViewHolder {

    private enum RowState { UNKNOWN, ONLINE, OFFLINE, WAKING }

    private final View deviceStatus;
    private final TextView deviceName;
    private final ObfuscatedTextView deviceMacAddress;
    private final TextView deviceIp;
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
        // Restore the last verified endpoint immediately. A RecyclerView rebind (Edit,
        // screen-off/on, or data refresh) must not erase the known active route.
        lastActiveAddress = statusBoard.getActiveAddress(device.id);
        updateActiveIp(lastActiveAddress, false);
        deviceIpArrow.setVisibility(View.GONE);
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
                String activeAddress = statusBoard.getActiveAddress(deviceId);
                updateActiveIp(activeAddress, addressChanged);
                updateLauncherIconIfNeeded(true);
                render(RowState.ONLINE, statusSnapshot.getLatencyMs());
            } else if (status == DeviceStatus.OFFLINE) {
                // Keep the last confirmed route visible while showing the device as offline.
                updateActiveIp(statusBoard.getActiveAddress(deviceId), false);
                updateLauncherIconIfNeeded(false);
                render(statusBoard.isWaking(deviceId) ? RowState.WAKING : RowState.OFFLINE, null);
            } else {
                updateActiveIp(statusBoard.getActiveAddress(deviceId), false);
                render(RowState.UNKNOWN, null);
            }
        });
    }

    private void updateActiveIp(String activeAddress, boolean addressChanged) {
        String next = Strings.isNullOrEmpty(activeAddress) ? null : activeAddress.trim();
        String previous = lastActiveAddress;
        lastActiveAddress = next;
        deviceIp.setText(next == null ? "IP не определён" : next);
        deviceIp.setVisibility(View.VISIBLE);
        if (device != null) {
            deviceMacAddress.setObfuscatedText(DeviceNetworkProfiles.macAddressFor(device, next));
        }
        if (addressChanged && next != null && previous != null && !previous.equalsIgnoreCase(next)) {
            deviceIpArrow.setVisibility(View.VISIBLE);
            deviceIp.clearAnimation();
            deviceIp.startAnimation(new TranslateAnimation(-dp(18), 0, 0, 0) {{
                setDuration(420);
            }});
            TranslateAnimation arrow = new TranslateAnimation(0, dp(38), 0, 0);
            arrow.setDuration(620);
            arrow.setRepeatCount(1);
            arrow.setRepeatMode(Animation.RESTART);
            arrow.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation animation) { }
                @Override public void onAnimationEnd(Animation animation) {
                    deviceIpArrow.setVisibility(View.GONE);
                    deviceIpArrow.clearAnimation();
                }
                @Override public void onAnimationRepeat(Animation animation) { }
            });
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
            int sshResult = probeSsh(target);
            int agentResult = probeWindowsAgent(target);
            itemView.post(() -> {
                if (device == null || device.id != targetId) return;
                setServiceState(sshLed, sshState,
                        sshResult == 1 ? "ПОРТ ОТКРЫТ" : sshResult == 0 ? "НЕТ СВЯЗИ" : "ЖДЁМ IP",
                        sshResult == 1 ? 1 : sshResult == 0 ? 2 : 0);
                if (agentResult == 1) {
                    setServiceState(agentLed, agentState, "СОПРЯЖЁН", 1);
                } else if (agentResult == 0) {
                    setServiceState(agentLed, agentState, "НЕ ПОДТВЕРЖДЁН", 2);
                } else {
                    setServiceState(agentLed, agentState, "НЕ ПРИВЯЗАН", 0);
                }
            });
        }, 0, 4, TimeUnit.SECONDS);
    }

    /** 1=TCP port responds, 0=checked and unavailable, -1=wait for the active profile. */
    private int probeSsh(Device target) {
        String host = lastActiveAddress;
        if (Strings.isNullOrEmpty(host)) {
            // Do not label the first saved SSH address ONLINE while several network
            // profiles exist and the current one has not yet been identified by ping.
            if (DeviceNetworkProfiles.statusAddresses(target).size() > 1) return -1;
            host = target.sshAddress;
        }
        if (Strings.isNullOrEmpty(host)) return -1;
        int port = target.sshPort == null || target.sshPort < 1 ? 22 : target.sshPort;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1200);
            return 1;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 1=cryptographically verified pairing, 0=known but unreachable/invalid, -1=no association. */
    private int probeWindowsAgent(Device target) {
        try {
            WindowsAgentDeviceEntity match =
                    WindowsAgentClient.findRegisteredDeviceForCard(itemView.getContext(), target);
            if (match == null) return -1;
            return WindowsAgentClient.verifyRegisteredPairing(itemView.getContext(), match, target) ? 1 : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void setServiceState(View led, TextView label, String value, int state) {
        label.setText(value);
        int color = state == 1 ? Color.rgb(67, 205, 120)
                : state == 2 ? Color.rgb(235, 83, 83) : Color.rgb(145, 145, 145);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        led.setBackground(dot);
        label.setTextColor(color);
        // Service indicators remain steady; pulse animation is reserved for actual
        // wake/action progress, not a successful TCP port probe.
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
