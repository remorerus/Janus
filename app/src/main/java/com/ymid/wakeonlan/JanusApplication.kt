package com.ymid.wakeonlan

import android.app.Application
import com.ymid.wakeonlan.config.RemoteConfigManager
import com.ymid.wakeonlan.crash.CrashReporter
import com.ymid.wakeonlan.monitoring.LauncherIconWorker
import com.ymid.wakeonlan.monitoring.MonitoringScheduler
import com.ymid.wakeonlan.voice.VoiceBridgeKeepAliveService
import com.ymid.wakeonlan.voice.VoiceBridgeRecoveryService
import com.ymid.wakeonlan.persistence.DatabaseInstanceManager
import com.ymid.wakeonlan.ui.LauncherIconManager
import com.ymid.wakeonlan.ui.notifications.NotificationHelper
import java.util.concurrent.Executors

class JanusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LauncherIconManager.register(this)
        CrashReporter.initialize()
        RemoteConfigManager.initialize()
        NotificationHelper.createChannels(this)
        MonitoringScheduler.scheduleIfEnabled(this)

        // Always restore the lightweight loopback recovery endpoint when the app
        // process starts. Do not rely only on a preference flag: older installs,
        // firmware resets and interrupted first-run setup can leave that flag absent
        // even while the user has enabled the Accessibility service.
        VoiceBridgeRecoveryService.start(this)

        // The main command bridge is supervised only while Android Accessibility
        // is actually enabled by the user.
        if (VoiceBridgeKeepAliveService.isAccessibilityEnabled(this)) {
            VoiceBridgeKeepAliveService.start(this)
        }

        LauncherIconWorker.schedule(this)
        Executors.newSingleThreadExecutor().execute {
            runCatching { DatabaseInstanceManager.getInstance(this) }
        }
    }
}
