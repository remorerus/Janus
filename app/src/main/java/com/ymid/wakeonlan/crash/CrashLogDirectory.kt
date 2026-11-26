package com.ymid.wakeonlan.crash

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Creates durable locations for future RE:Vision crash reports.
 *
 * The external app-specific directory is visible to compatible file managers;
 * the internal directory remains available even when external storage is absent.
 */
object CrashLogDirectory {
    private const val TAG = "REVisionCrash"
    private const val DIR_NAME = "RE-Vision/CrashLogs"

    @JvmStatic
    fun ensure(context: Context) {
        runCatching {
            val externalRoot = context.getExternalFilesDir(null)
            if (externalRoot != null) {
                createDirectory(File(externalRoot, DIR_NAME))
            }
        }.onFailure {
            Log.w(TAG, "Could not create external crash-log directory", it)
        }

        runCatching {
            createDirectory(File(context.filesDir, "CrashLogs"))
        }.onFailure {
            Log.w(TAG, "Could not create internal crash-log directory", it)
        }
    }

    private fun createDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Could not create directory: ${directory.absolutePath}")
        }
        if (!directory.isDirectory) {
            throw IllegalStateException("Crash-log path is not a directory: ${directory.absolutePath}")
        }

        // A small marker makes the otherwise-empty folder visible in file managers.
        val marker = File(directory, "FOLDER_READY.txt")
        if (!marker.exists()) {
            marker.writeText(
                "RE:Vision crash-log folder is ready.\n" +
                    "Crash reports will be saved here by a future diagnostic update.\n"
            )
        }
        Log.i(TAG, "Crash-log directory ready: ${directory.absolutePath}")
    }
}
