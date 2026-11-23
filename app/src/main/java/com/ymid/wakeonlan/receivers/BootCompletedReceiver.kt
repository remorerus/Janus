package com.ymid.wakeonlan.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ymid.wakeonlan.monitoring.MonitoringScheduler
import com.ymid.wakeonlan.voice.VoiceBridgeKeepAliveService
import com.ymid.wakeonlan.voice.VoiceBridgeRecoveryService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MonitoringScheduler.scheduleIfEnabled(context)

        val bridgePrefs = context.getSharedPreferences("revision_voice_bridge", Context.MODE_PRIVATE)
        val accessibilityEnabled = VoiceBridgeKeepAliveService.isAccessibilityEnabled(context)

        // Migrate installations that had Voice Bridge enabled before the recovery
        // flag existed, and keep the emergency endpoint available after a firmware
        // reset of the Android accessibility switch.
        if (accessibilityEnabled) {
            bridgePrefs.edit().putBoolean("bridge_ever_enabled", true).apply()
        }
        // Existing installations may predate bridge_ever_enabled. A saved bridge
        // PIN proves that the AIUI bridge was configured before this upgrade.
        val recoveryWasConfigured = bridgePrefs.getBoolean("bridge_ever_enabled", false)
                || bridgePrefs.contains("access_token")
        if (recoveryWasConfigured) {
            bridgePrefs.edit().putBoolean("bridge_ever_enabled", true).apply()
            VoiceBridgeRecoveryService.start(context)
        }
        if (accessibilityEnabled) {
            VoiceBridgeKeepAliveService.start(context)
        }
    }
}
