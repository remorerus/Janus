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

        // Accessibility remains user-controlled. If Android retained the user's
        // enabled state, start the foreground supervisor during boot so the
        // loopback bridge is supervised even before the app is opened manually.
        val bridgePrefs = context.getSharedPreferences("revision_voice_bridge", Context.MODE_PRIVATE)
        if (bridgePrefs.getBoolean("bridge_ever_enabled", false)) {
            // This local recovery endpoint does not depend on AccessibilityService,
            // so the AIUI agent can still open Android settings if firmware reset it.
            VoiceBridgeRecoveryService.start(context)
        }
        if (VoiceBridgeKeepAliveService.isAccessibilityEnabled(context)) {
            VoiceBridgeKeepAliveService.start(context)
        }
    }
}
