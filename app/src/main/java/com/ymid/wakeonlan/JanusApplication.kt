package com.ymid.wakeonlan

import android.app.Application
import com.ymid.wakeonlan.config.RemoteConfigManager
import com.ymid.wakeonlan.crash.CrashReporter
import com.ymid.wakeonlan.monitoring.LauncherIconWorker
import com.ymid.wakeonlan.monitoring.MonitoringScheduler
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
        // If this installation has used Voice Bridge before, restore its independent
        // recovery endpoint whenever the app process is brought up manually.
        if (getSharedPreferences("revision_voice_bridge", MODE_PRIVATE)
                .getBoolean("bridge_ever_enabled", false)) {
            VoiceBridgeRecoveryService.start(this)
        }
        LauncherIconWorker.schedule(this)
        Executors.newSingleThreadExecutor().execute {
            runCatching { DatabaseInstanceManager.getInstance(this) }
        }
    }
}
