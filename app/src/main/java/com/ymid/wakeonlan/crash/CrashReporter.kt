package com.ymid.wakeonlan.crash

import android.content.Context
import android.os.Build
import android.os.Process
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ymid.wakeonlan.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Writes uncaught application failures to app-private storage before delegating
 * to Android's normal crash handler. Reports intentionally contain operation
 * names and stack traces, never device form values, passwords, PINs or keys.
 */
object CrashReporter {
    private const val TAG = "REVisionCrash"
    private const val MAX_BREADCRUMBS = 100
    private const val MAX_REPORTS = 15

    private val firebaseEnabled = BuildConfig.FIREBASE_CONFIGURED
    private val lock = Any()
    private val breadcrumbs = ArrayDeque<String>()

    @Volatile private var appContext: Context? = null
    @Volatile private var currentOperation: String = "application_startup"
    @Volatile private var handlerInstalled = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        installUncaughtExceptionHandler()

        if (firebaseEnabled) {
            runCatching {
                FirebaseCrashlytics.getInstance().apply {
                    setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
                    setCustomKey("version_name", BuildConfig.VERSION_NAME)
                    setCustomKey("version_code", BuildConfig.VERSION_CODE)
                    log("Crashlytics initialized")
                }
            }
        }
        log("local crash dump handler initialized")
    }

    fun setOperation(operation: String) {
        val safeOperation = operation
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            .take(96)
            .ifBlank { "unknown_operation" }
        currentOperation = safeOperation
        log("operation=$safeOperation")
    }

    fun clearOperation() {
        currentOperation = "idle"
        log("operation=idle")
    }

    fun log(message: String) {
        val safeMessage = message.take(240)
        val line = "${System.currentTimeMillis()} $safeMessage"
        synchronized(lock) {
            if (breadcrumbs.size >= MAX_BREADCRUMBS) breadcrumbs.removeFirst()
            breadcrumbs.addLast(line)
        }
        if (firebaseEnabled) {
            runCatching { FirebaseCrashlytics.getInstance().log(safeMessage) }
        }
    }

    fun recordException(operation: String, throwable: Throwable) {
        setOperation(operation)
        persistCrashReport(Thread.currentThread(), throwable, operation)
        if (firebaseEnabled) {
            runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
        }
    }

    private fun installUncaughtExceptionHandler() {
        synchronized(lock) {
            if (handlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                persistCrashReport(thread, throwable, currentOperation)
                if (firebaseEnabled) {
                    runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
            handlerInstalled = true
        }
    }

    private fun persistCrashReport(thread: Thread, throwable: Throwable, operation: String) {
        val context = appContext ?: return
        runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val stack = StringWriter().also { writer ->
                throwable.printStackTrace(PrintWriter(writer))
            }.toString()
            val recentEvents = synchronized(lock) { breadcrumbs.joinToString("\n") }

            val report = buildString {
                appendLine("RE:Vision crash report")
                appendLine("Timestamp: $timestamp")
                appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Manufacturer: ${Build.MANUFACTURER}")
                appendLine("Model: ${Build.MODEL}")
                appendLine("Process ID: ${Process.myPid()}")
                appendLine("Thread: ${thread.name}")
                appendLine("Operation: $operation")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine()
                appendLine("Recent app events:")
                appendLine(recentEvents)
                appendLine()
                appendLine("Stack trace:")
                append(stack)
            }

            val directories = listOfNotNull(
                context.getExternalFilesDir(null)?.let { File(it, "RE-Vision/CrashLogs") },
                File(context.filesDir, "CrashLogs")
            ).distinctBy { it.absolutePath }

            synchronized(lock) {
                directories.forEach { directory ->
                    if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                        throw IllegalStateException("Cannot create crash directory")
                    }
                    val reportFile = File(directory, "crash-$timestamp-${Process.myPid()}.txt")
                    FileOutputStream(reportFile).use { stream ->
                        stream.write(report.toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    directory.listFiles()
                        ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(MAX_REPORTS)
                        ?.forEach { it.delete() }
                }
            }
            android.util.Log.e(TAG, "Crash report saved for operation=$operation")
        }.onFailure {
            android.util.Log.e(TAG, "Could not persist crash report", it)
        }
    }
}
