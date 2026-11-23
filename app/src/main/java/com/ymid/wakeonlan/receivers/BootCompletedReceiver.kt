package com.ymid.wakeonlan.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ymid.wakeonlan.monitoring.MonitoringScheduler
import com.ymid.wakeonlan.voice.VoiceBridgeKeepAliveService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MonitoringScheduler.scheduleIfEnabled(context)

        // Accessibility remains user-controlled. If Android retained the user's
        // enabled state, start the foreground supervisor during boot so the
        // loopback bridge is supervised even before the app is opened manually.
        if (VoiceBridgeKeepAliveService.isAccessibilityEnabled(context)) {
            VoiceBridgeKeepAliveService.start(context)
        }
    }
}
