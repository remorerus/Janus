package com.ymid.wakeonlan

import android.app.Application
import com.ymid.wakeonlan.config.RemoteConfigManager
import com.ymid.wakeonlan.crash.CrashLogDirectory
import com.ymid.wakeonlan.crash.CrashReporter
import com.ymid.wakeonlan.monitoring.LauncherIconWorker
import com.ymid.wakeonlan.monitoring.MonitoringScheduler
import com.ymid.wakeonlan.persistence.DatabaseInstanceManager
import com.ymid.wakeonlan.ui.LauncherIconManager
import com.ymid.wakeonlan.ui.notifications.NotificationHelper
import com.ymid.wakeonlan.voice.VoiceBridgeRecoveryService
import java.util.concurrent.Executors

class JanusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Prepare local report locations and install the crash handler before
        // starting services or touching the encrypted database.
        CrashLogDirectory.ensure(this)
        CrashReporter.initialize(this)
        CrashReporter.setOperation("application.initialize_services")
        LauncherIconManager.register(this)
        RemoteConfigManager.initialize()
        NotificationHelper.createChannels(this)
        MonitoringScheduler.scheduleIfEnabled(this)
        // Keep a local recovery endpoint available so the AIUI agent can reopen
        // Android Accessibility settings if the main bridge service is disabled.
        VoiceBridgeRecoveryService.start(this)
        LauncherIconWorker.schedule(this)
        CrashReporter.clearOperation()

        Executors.newSingleThreadExecutor().execute {
            CrashReporter.setOperation("database.background_init")
            try {
                DatabaseInstanceManager.getInstance(this)
                CrashReporter.clearOperation()
            } catch (failure: Throwable) {
                // The old startup path swallowed this exception. Preserve the app's
                // current behavior but persist the exact database startup failure.
                CrashReporter.recordException("database.background_init", failure)
            }
        }
    }
}
