package com.ymid.wakeonlan.ui.list.viewholder;

import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.view.animation.TranslateAnimation;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
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
        deviceMacAddress.setObfuscatedText(device.macAddress);
        // Never show a configured/first route as if it were active. The IP is populated only
        // after a successful status check identifies the exact endpoint that answered.
        deviceIp.setText("");
        deviceIp.setVisibility(View.GONE);

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
            statusBoard.record(deviceId, statusSnapshot);
            // The row may have been rebound to another device while the check was running
            if (device == null || device.id != deviceId) {
                return;
            }

            DeviceStatus status = statusSnapshot.getStatus();
            if (status == DeviceStatus.ONLINE) {
                String activeAddress = statusSnapshot.getActiveAddress();
                deviceIp.setText(activeAddress == null ? "" : activeAddress);
                deviceIp.setVisibility(Strings.isNullOrEmpty(activeAddress) ? View.GONE : View.VISIBLE);
                updateLauncherIconIfNeeded(true);
                render(RowState.ONLINE, statusSnapshot.getLatencyMs());
            } else if (status == DeviceStatus.OFFLINE) {
                deviceIp.setText("");
                deviceIp.setVisibility(View.GONE);
                updateLauncherIconIfNeeded(false);
                render(statusBoard.isWaking(deviceId) ? RowState.WAKING : RowState.OFFLINE, null);
            } else {
                render(RowState.UNKNOWN, null);
            }
        });
    }

    private void updateActiveIp(String activeAddress) {
        String next = Strings.isNullOrEmpty(activeAddress) ? null : activeAddress;
        String previous = lastActiveAddress;
        lastActiveAddress = next;
        deviceIp.setText(next == null ? "" : next);
        deviceIp.setVisibility(next == null ? View.GONE : View.VISIBLE);
        if (next != null && previous != null && !previous.equals(next)) {
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
            boolean sshOnline = probeSsh(target);
            int agentResult = probeWindowsAgent(target);
            itemView.post(() -> {
                if (device == null || device.id != targetId) return;
                setServiceState(sshLed, sshState, sshOnline ? "ONLINE" : "OFFLINE",
                        sshOnline ? 1 : 2);
                if (agentResult == 1) {
                    setServiceState(agentLed, agentState, "ONLINE", 1);
                } else if (agentResult == 0) {
                    setServiceState(agentLed, agentState, "OFFLINE", 2);
                } else {
                    setServiceState(agentLed, agentState, "НЕ ПРИВЯЗАН", 0);
                }
            });
        }, 0, 4, TimeUnit.SECONDS);
    }

    private boolean probeSsh(Device target) {
        String host = !Strings.isNullOrEmpty(target.sshAddress) ? target.sshAddress.trim() : lastActiveAddress;
        if (Strings.isNullOrEmpty(host)) return false;
        int port = target.sshPort == null || target.sshPort < 1 ? 22 : target.sshPort;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1200);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 1=online, 0=offline, -1=no paired agent associated with this device name. */
    private int probeWindowsAgent(Device target) {
        try {
            List<WindowsAgentDeviceEntity> paired =
                    new WindowsAgentDeviceRegistry(itemView.getContext()).getAll();
            WindowsAgentDeviceEntity match = null;
            for (WindowsAgentDeviceEntity candidate : paired) {
                if (candidate != null && candidate.enabled && candidate.displayName != null
                        && target.name != null
                        && candidate.displayName.trim().equalsIgnoreCase(target.name.trim())) {
                    match = candidate;
                    break;
                }
            }
            if (match == null || Strings.isNullOrEmpty(match.baseUrl)) return -1;
            URL base = new URL(match.baseUrl);
            URL statusUrl = new URL(match.baseUrl.replaceAll("/+$", "") + "/v1/status");
            HttpURLConnection connection = (HttpURLConnection) statusUrl.openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1200);
                connection.setReadTimeout(1200);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) return 0;
                try (java.io.InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    while (in.read(buffer) != -1) { }
                }
                return 1;
            } finally {
                connection.disconnect();
            }
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
        if (state == 1) {
            AlphaAnimation pulse = new AlphaAnimation(0.55f, 1f);
            pulse.setDuration(850);
            pulse.setRepeatMode(Animation.REVERSE);
            pulse.setRepeatCount(Animation.INFINITE);
            led.startAnimation(pulse);
        } else {
            led.clearAnimation();
        }
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
        if (statusTesterPool != null && device != null) {
            statusTesterPool.stopSingle(device, StatusTestType.LIST);
        }
    }
}
