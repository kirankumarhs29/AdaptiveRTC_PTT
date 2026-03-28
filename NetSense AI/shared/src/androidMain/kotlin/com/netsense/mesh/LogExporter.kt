package com.netsense.mesh

import android.content.Context
import com.netsense.mesh.AppLogger.Module
import java.io.File

/**
 * Copies the current log files from the app's private storage to the
 * app-specific external directory:
 *   /sdcard/Android/data/com.netsense.meshapp/files/logs/
 *
 * This path is:
 *  - Writable by the app without any manifest permission
 *  - Pullable via ADB without run-as:
 *      adb pull /sdcard/Android/data/com.netsense.meshapp/files/logs/
 *  - Browsable via USB MTP on most devices (Settings → USB → File Transfer)
 */
object LogExporter {

    private const val EXPORT_SUBDIR = "logs"
    private val LOG_FILES = listOf("ui.log", "ui.log.1", "core.log", "core.log.1")

    /**
     * Copies available log files and returns a human-readable result message.
     */
    fun export(context: Context): String {
        val srcDir = context.filesDir
        val dstDir = File(context.getExternalFilesDir(null), EXPORT_SUBDIR)
        if (!dstDir.mkdirs() && !dstDir.isDirectory) {
            AppLogger.error(Module.SYSTEM, "log export failed: cannot create $dstDir")
            return "Export failed — external storage unavailable"
        }

        var copied = 0
        val copiedNames = mutableListOf<String>()
        for (name in LOG_FILES) {
            val src = File(srcDir, name)
            if (src.exists() && src.length() > 0) {
                runCatching { src.copyTo(File(dstDir, name), overwrite = true) }
                    .onSuccess { copied++; copiedNames += name }
                    .onFailure { AppLogger.error(Module.SYSTEM, "log export copy failed $name: ${it.message}") }
            }
        }

        return if (copied == 0) {
            "No log files generated yet.\nUse the app first, then export."
        } else {
            AppLogger.info(Module.SYSTEM, "logs exported copied=$copied to=${dstDir.absolutePath}")
            "$copied file(s) copied to:\nAndroid/data/com.netsense.meshapp/files/logs/\n" +
                "(pull via ADB or USB file transfer)"
        }
    }
}
