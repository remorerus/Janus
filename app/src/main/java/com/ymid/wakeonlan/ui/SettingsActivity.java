package com.ymid.wakeonlan.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ymid.wakeonlan.R;
import com.ymid.wakeonlan.monitoring.MonitoringScheduler;
import com.ymid.wakeonlan.voice.VoiceBridgeAccessibilityService;
import com.ymid.wakeonlan.ui.notifications.NotificationHelper;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.settings_back).setOnClickListener(v -> finish());
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        View root = findViewById(R.id.settings_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), topInset, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements TrustedWifiDialogFragment.Callback {

        private static final String PREF_TRUSTED_SSIDS = "pref_trusted_ssids";
        private static final String PREF_SCAN_WIFI = "pref_scan_wifi";
        private static final String PREF_VOICE_BRIDGE = "pref_voice_bridge_accessibility";
        private static final String PREF_VOICE_TOKEN = "pref_voice_bridge_token";
        private static final String PREF_VOICE_BRIDGE_ENABLED = "pref_voice_bridge_enabled";
        private static final String PREF_VOICE_BRIDGE_STATUS = "pref_voice_bridge_status";
        private static final String PREF_WIFI_PERMISSION_STATUS = "pref_wifi_permission_status";
        private static final String PREF_WIFI_PERMISSION_REQUEST = "pref_wifi_permission_request";
        private static final String PREF_APP_PERMISSIONS = "pref_app_permissions";
        private static final String PREF_LOCATION_SETTINGS = "pref_location_settings";
        private static final String PREF_WIFI_SCAN_PERMISSION = "pref_wifi_scan_permission";
        private static final String PREF_NOTIFICATION_STATUS = "pref_notification_status";
        private static final String PREF_NOTIFICATION_SETTINGS = "pref_notification_settings";
        private static final String PREF_NOTIFICATION_REQUEST = "pref_notification_request";
        private static final String PREF_BATTERY_STATUS = "pref_battery_status";
        private static final String PREF_BATTERY_SETTINGS = "pref_battery_settings";

        private ActivityResultLauncher<String[]> wifiPermissionLauncher;
        private ActivityResultLauncher<String> locationPermissionLauncher;
        private ActivityResultLauncher<String> notificationPermissionLauncher;
        private WifiManager wifiManager;
        private Preference trustedSsidsPreference;
        private Preference scanWifiPreference;
        private SwitchPreferenceCompat monitoringPreference;
        private boolean openDialogAfterScan;
        private boolean receiverRegistered;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private String[] scannedEntries = new String[0];
        private String[] scannedValues = new String[0];

        private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateWifiScanResults();
            }
        };

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            notificationPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> updateSystemAccessStatuses());

            locationPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> updateWifiPermissionStatus());

            wifiPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = true;
                        for (Boolean value : result.values()) {
                            if (!Boolean.TRUE.equals(value)) {
                                granted = false;
                                break;
                            }
                        }

                        if (granted) {
                            startWifiScanWithPermission(true);
                        } else {
                            Toast.makeText(requireContext(), R.string.wifi_scan_permission_denied, Toast.LENGTH_SHORT).show();
                        }
                    });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            wifiManager = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            trustedSsidsPreference = findPreference(PREF_TRUSTED_SSIDS);
            scanWifiPreference = findPreference(PREF_SCAN_WIFI);
            SwitchPreferenceCompat voiceBridgeEnabledPreference = findPreference(PREF_VOICE_BRIDGE_ENABLED);
            if (voiceBridgeEnabledPreference != null) {
                voiceBridgeEnabledPreference.setChecked(VoiceBridgeAccessibilityService.isBridgeEnabled(requireContext()));
                voiceBridgeEnabledPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    VoiceBridgeAccessibilityService.setBridgeEnabled(requireContext(), Boolean.TRUE.equals(newValue));
                    handler.postDelayed(this::updateVoiceBridgeStatus, 300);
                    return true;
                });
            }
            updateVoiceBridgeStatus();
            updateSystemAccessStatuses();

            Preference wifiScanPermission = findPreference(PREF_WIFI_SCAN_PERMISSION);
            if (wifiScanPermission != null) {
                wifiScanPermission.setOnPreferenceClickListener(preference -> {
                    if (hasWifiScanPermission()) {
                        Toast.makeText(requireContext(), R.string.wifi_scan_permissions_already_granted, Toast.LENGTH_SHORT).show();
                    } else {
                        wifiPermissionLauncher.launch(getWifiScanPermissions());
                    }
                    return true;
                });
            }

            Preference notificationRequest = findPreference(PREF_NOTIFICATION_REQUEST);
            if (notificationRequest != null) {
                notificationRequest.setOnPreferenceClickListener(preference -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    } else {
                        openNotificationSettings();
                    }
                    return true;
                });
            }

            Preference notificationSettings = findPreference(PREF_NOTIFICATION_SETTINGS);
            if (notificationSettings != null) {
                notificationSettings.setOnPreferenceClickListener(preference -> {
                    openNotificationSettings();
                    return true;
                });
            }

            Preference batterySettings = findPreference(PREF_BATTERY_SETTINGS);
            if (batterySettings != null) {
                batterySettings.setOnPreferenceClickListener(preference -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception e) {
                        openAppDetailsSettings();
                    }
                    return true;
                });
            }

            Preference requestLocationPermission = findPreference(PREF_WIFI_PERMISSION_REQUEST);
            if (requestLocationPermission != null) {
                requestLocationPermission.setOnPreferenceClickListener(preference -> {
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(requireContext(), R.string.wifi_location_permission_already_granted, Toast.LENGTH_SHORT).show();
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
                    }
                    return true;
                });
            }

            Preference appPermissions = findPreference(PREF_APP_PERMISSIONS);
            if (appPermissions != null) {
                appPermissions.setOnPreferenceClickListener(preference -> {
                    openAppDetailsSettings();
                    return true;
                });
            }

            Preference locationSettings = findPreference(PREF_LOCATION_SETTINGS);
            if (locationSettings != null) {
                locationSettings.setOnPreferenceClickListener(preference -> {
                    openLocationSettings();
                    return true;
                });
            }

            Preference voiceBridgePreference = findPreference(PREF_VOICE_BRIDGE);
            if (voiceBridgePreference != null) {
                voiceBridgePreference.setOnPreferenceClickListener(preference -> {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return true;
                });
            }

            Preference voiceTokenPreference = findPreference(PREF_VOICE_TOKEN);
            if (voiceTokenPreference != null) {
                voiceTokenPreference.setOnPreferenceClickListener(preference -> {
                    String token = VoiceBridgeAccessibilityService.getOrCreateAccessToken(requireContext());
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.voice_bridge_token_dialog_title)
                            .setMessage(getString(R.string.voice_bridge_token_dialog_message, token))
                            .setPositiveButton(R.string.voice_bridge_token_copy, (dialog, which) -> {
                                ClipboardManager clipboard = (ClipboardManager) requireContext()
                                        .getSystemService(Context.CLIPBOARD_SERVICE);
                                if (clipboard != null) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("RE:Vision bridge token", token));
                                    Toast.makeText(requireContext(), R.string.voice_bridge_token_copied, Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
            }
            monitoringPreference = findPreference(MonitoringScheduler.PREF_MONITORING_ENABLED);

            if (trustedSsidsPreference != null) {
                updateTrustedSsidsSummary();

                trustedSsidsPreference.setOnPreferenceClickListener(preference -> {
                    showTrustedWifiDialog();
                    return true;
                });
            }

            if (scanWifiPreference != null) {
                scanWifiPreference.setOnPreferenceClickListener(preference -> {
                    startWifiScanWithPermission(true);
                    return true;
                });
            }

            if (monitoringPreference != null) {
                monitoringPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = Boolean.TRUE.equals(newValue);
                    Context context = requireContext().getApplicationContext();
                    if (enabled) {
                        MonitoringScheduler.schedule(context);
                    } else {
                        MonitoringScheduler.cancel(context);
                    }
                    return true;
                });
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            updateVoiceBridgeStatus();
            updateSystemAccessStatuses();
            handler.postDelayed(this::updateVoiceBridgeStatus, 700);
        }

        private void updateSystemAccessStatuses() {
            updateWifiPermissionStatus();
            updateNotificationStatus();
            updateBatteryStatus();
        }

        private void updateNotificationStatus() {
            if (!isAdded()) return;
            Preference status = findPreference(PREF_NOTIFICATION_STATUS);
            if (status == null) return;
            boolean enabled = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager manager = (NotificationManager) requireContext()
                        .getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    String[] channelIds = {
                            NotificationHelper.CHANNEL_DEVICE_STATUS,
                            NotificationHelper.CHANNEL_ACTIONS,
                            NotificationHelper.CHANNEL_ACTION_REQUESTS
                    };
                    for (String channelId : channelIds) {
                        android.app.NotificationChannel channel = manager.getNotificationChannel(channelId);
                        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                            enabled = false;
                            break;
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                enabled = enabled && ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            status.setSummary(enabled ? R.string.notification_permission_ready : R.string.notification_permission_missing);
        }

        private void updateBatteryStatus() {
            if (!isAdded()) return;
            Preference status = findPreference(PREF_BATTERY_STATUS);
            if (status == null) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                status.setSummary(R.string.battery_optimization_not_applicable);
                return;
            }
            PowerManager manager = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
            boolean exempt = manager != null && manager.isIgnoringBatteryOptimizations(requireContext().getPackageName());
            status.setSummary(exempt ? R.string.battery_optimization_exempt : R.string.battery_optimization_active);
        }

        private void openAppDetailsSettings() {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().getPackageName(), null));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.app_permissions_settings_unavailable, Toast.LENGTH_SHORT).show();
            }
        }

        private void openNotificationSettings() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                    startActivity(intent);
                } else {
                    openAppDetailsSettings();
                }
            } catch (Exception e) {
                openAppDetailsSettings();
            }
        }

        private void openLocationSettings() {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.app_permissions_settings_unavailable, Toast.LENGTH_SHORT).show();
            }
        }

        private void updateWifiPermissionStatus() {
            if (!isAdded()) return;
            Preference status = findPreference(PREF_WIFI_PERMISSION_STATUS);
            if (status == null) return;
            boolean permissionGranted = ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean nearbyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
            boolean locationEnabled = isLocationEnabled();
            if (permissionGranted && nearbyGranted && locationEnabled) {
                status.setSummary(R.string.wifi_location_permission_ready);
            } else if (permissionGranted && !nearbyGranted) {
                status.setSummary(R.string.wifi_nearby_permission_missing);
            } else if (permissionGranted) {
                status.setSummary(R.string.wifi_location_permission_location_off);
            } else {
                status.setSummary(R.string.wifi_location_permission_missing);
            }
        }

        private void updateVoiceBridgeStatus() {
            if (!isAdded()) return;
            Preference status = findPreference(PREF_VOICE_BRIDGE_STATUS);
            if (status == null) return;
            if (!VoiceBridgeAccessibilityService.isBridgeEnabled(requireContext())) {
                status.setSummary("Выключен — локальный HTTP-мост не принимает команды.");
            } else if (VoiceBridgeAccessibilityService.isBridgeServerActive()) {
                status.setSummary("Активен · 127.0.0.1:38761 · локальный сервер слушает запросы.");
            } else {
                status.setSummary("Ожидает запуска службы специальных возможностей RE:Vision. Проверь её включение в Android.");
            }
        }

        @Override
        public void onStart() {
            super.onStart();
            ensureReceiverRegistered();
        }

        @Override
        public void onStop() {
            super.onStop();
            unregisterReceiverIfNeeded();
        }

        private void startWifiScanWithPermission(boolean fromUser) {
            if (wifiManager == null || !wifiManager.isWifiEnabled()) {
                if (fromUser) Toast.makeText(requireContext(), R.string.wifi_scan_disabled, Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isLocationEnabled()) {
                if (fromUser) Toast.makeText(requireContext(), R.string.wifi_scan_location_disabled, Toast.LENGTH_SHORT).show();
                return;
            }

            if (hasWifiScanPermission()) {
                ensureReceiverRegistered();
                startWifiScan(fromUser);
                return;
            }

            if (fromUser) {
                wifiPermissionLauncher.launch(getWifiScanPermissions());
            }
        }

        private void startWifiScan(boolean fromUser) {
            if (wifiManager == null) {
                if (fromUser) Toast.makeText(requireContext(), R.string.wifi_scan_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            openDialogAfterScan = fromUser;
            boolean started = wifiManager.startScan();
            if (!started) {
                openDialogAfterScan = false;
                if (fromUser) {
                    Toast.makeText(requireContext(), R.string.wifi_scan_failed, Toast.LENGTH_SHORT).show();
                    updateWifiScanResults();
                }
                return;
            }

            if (fromUser) {
                handler.postDelayed(this::updateWifiScanResults, 2000);
            }
        }

        private void updateWifiScanResults() {
            if (!isAdded() || wifiManager == null || trustedSsidsPreference == null) {
                return;
            }

            List<ScanResult> results = wifiManager.getScanResults();
            Map<String, ScanResult> bestResults = new HashMap<>();
            for (ScanResult result : results) {
                if (TextUtils.isEmpty(result.SSID)) {
                    continue;
                }
                ScanResult currentBest = bestResults.get(result.SSID);
                if (currentBest == null || result.level > currentBest.level) {
                    bestResults.put(result.SSID, result);
                }
            }

            List<ScanResult> sortedResults = new ArrayList<>(bestResults.values());
            Collections.sort(sortedResults, (left, right) -> {
                int levelCompare = Integer.compare(right.level, left.level);
                if (levelCompare != 0) {
                    return levelCompare;
                }
                return left.SSID.compareToIgnoreCase(right.SSID);
            });

            String[] entries = new String[sortedResults.size()];
            String[] entryValues = new String[sortedResults.size()];
            for (int i = 0; i < sortedResults.size(); i++) {
                ScanResult result = sortedResults.get(i);
                entryValues[i] = result.SSID;
                entries[i] = formatWifiEntry(result);
            }

            scannedEntries = entries;
            scannedValues = entryValues;

            if (openDialogAfterScan) {
                openDialogAfterScan = false;
                if (sortedResults.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.wifi_scan_empty, Toast.LENGTH_SHORT).show();
                } else {
                    showTrustedWifiDialogScanMode();
                }
            }
        }

        private boolean hasWifiScanPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES)
                        == PackageManager.PERMISSION_GRANTED
                        && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private String[] getWifiScanPermissions() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION};
            }
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }

        private void ensureReceiverRegistered() {
            if (!receiverRegistered && isAdded()) {
                ContextCompat.registerReceiver(requireContext(), wifiScanReceiver,
                        new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                receiverRegistered = true;
            }
        }

        private void unregisterReceiverIfNeeded() {
            if (receiverRegistered) {
                requireContext().unregisterReceiver(wifiScanReceiver);
                receiverRegistered = false;
            }
            handler.removeCallbacksAndMessages(null);
        }

        private boolean isLocationEnabled() {
            LocationManager locationManager = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return locationManager.isLocationEnabled();
            }
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        }

        @Override
        public void onTrustedWifiSelection(Set<String> selected) {
            if (trustedSsidsPreference != null) {
                // Save to SharedPreferences
                getPreferenceManager().getSharedPreferences()
                        .edit()
                        .putStringSet(PREF_TRUSTED_SSIDS, selected)
                        .apply();
                updateTrustedSsidsSummary();
            }
        }

        private void updateTrustedSsidsSummary() {
            if (trustedSsidsPreference == null) {
                return;
            }
            Set<String> values = getPreferenceManager().getSharedPreferences()
                    .getStringSet(PREF_TRUSTED_SSIDS, null);
            if (values == null || values.isEmpty()) {
                trustedSsidsPreference.setSummary(getString(R.string.pref_trusted_ssids_summary_empty));
            } else {
                trustedSsidsPreference.setSummary(TextUtils.join(", ", values));
            }
        }

        private Set<String> getTrustedSsids() {
            return getPreferenceManager().getSharedPreferences()
                    .getStringSet(PREF_TRUSTED_SSIDS, new HashSet<>());
        }

        private void showTrustedWifiDialog() {
            if (trustedSsidsPreference == null) {
                return;
            }

            Set<String> selected = getTrustedSsids();

            TrustedWifiDialogFragment dialog = TrustedWifiDialogFragment.newInstance(
                    new String[0],
                    new String[0],
                    new ArrayList<>(selected),
                    TrustedWifiDialogFragment.MODE_VIEW
            );
            dialog.show(getChildFragmentManager(), "trustedWifiDialog");
        }

        private void showTrustedWifiDialogScanMode() {
            if (trustedSsidsPreference == null) {
                return;
            }

            Set<String> selected = getTrustedSsids();

            TrustedWifiDialogFragment dialog = TrustedWifiDialogFragment.newInstance(
                    scannedEntries,
                    scannedValues,
                    new ArrayList<>(selected),
                    TrustedWifiDialogFragment.MODE_SCAN
            );
            dialog.show(getChildFragmentManager(), "trustedWifiDialog");
        }


        private String formatWifiEntry(ScanResult result) {
            String band = formatWifiBand(result.frequency);
            StringBuilder details = new StringBuilder();
            if (!TextUtils.isEmpty(band)) {
                details.append(band);
            }
            details.append(details.length() > 0 ? ", " : "")
                    .append(result.level)
                    .append(" dBm");
            return result.SSID + " (" + details + ")";
        }

        private String formatWifiBand(int frequency) {
            if (frequency >= 2400 && frequency < 2500) {
                return "2.4 GHz";
            }
            if (frequency >= 4900 && frequency < 5900) {
                return "5 GHz";
            }
            if (frequency >= 5925 && frequency < 7125) {
                return "6 GHz";
            }
            return "";
        }
    }
}
