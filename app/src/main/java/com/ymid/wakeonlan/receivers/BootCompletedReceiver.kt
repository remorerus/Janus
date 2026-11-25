package com.ymid.wakeonlan.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ymid.wakeonlan.monitoring.MonitoringScheduler
import com.ymid.wakeonlan.voice.VoiceBridgeRecoveryService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Recreate the independent loopback recovery endpoint after every glasses reboot.
        // The main port 38761 is owned by Android Accessibility and is rebound by Android
        // when the user-enabled AccessibilityService reconnects.
        Log.i("RevisionBoot", "Boot completed; restarting Voice Bridge recovery endpoint")
        VoiceBridgeRecoveryService.start(context.applicationContext)

        MonitoringScheduler.scheduleIfEnabled(context)
    }
}
