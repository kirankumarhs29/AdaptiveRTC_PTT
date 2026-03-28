package com.netsense.mesh

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AppLogger — lightweight, file-backed logger for the KMP UI/transport layer.
 *
 * Design decisions:
 *  - Single background thread for all file writes (eliminates lock contention,
 *    never blocks the caller — audio/UI threads submit and return immediately).
 *  - Writes to [LOG_FILE_NAME] in Android internal storage (app-private, no
 *    WRITE_EXTERNAL_STORAGE permission needed).
 *  - File rotation: when [MAX_FILE_BYTES] is exceeded the current file is
 *    renamed to ui.log.1 and a fresh ui.log is opened (single-generation rollover).
 *  - DEBUG entries are suppressed unless [debugEnabled] is true.
 *  - android.util.Log is always written (Logcat) regardless of file setting, so
 *    the file is purely for persistent post-mortem analysis.
 *
 * Usage:
 *   // Once at app start (e.g. in Application.onCreate or MainActivity.onCreate):
 *   AppLogger.init(context)
 *
 *   // Anywhere on any thread:
 *   AppLogger.info(Module.RTP, "streaming started seq=0")
 *   AppLogger.debug(Module.ECS, "rtt=28ms confidence=43%")
 *   AppLogger.warn(Module.NETWORK, "PONG timeout, no RTT sample")
 *   AppLogger.error(Module.CALL, "prepareInfrastructure failed")
 *
 *   // At app shutdown:
 *   AppLogger.shutdown()
 */
object AppLogger {

    // ── Configuration ─────────────────────────────────────────────────────

    const val LOG_FILE_NAME = "ui.log"
    private const val LOG_FILE_BACKUP = "ui.log.1"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024   // 2 MB per file
    private const val BUFFER_FLUSH_LINES = 20              // flush every N lines

    /** Set false in production to suppress DEBUG entries. */
    @Volatile
    var debugEnabled: Boolean = true

    /**
     * RTT values are logged at most once every [RTT_LOG_INTERVAL_SAMPLES] calls
     * to [rttSample]. This prevents flooding core.log with 50 entries/second.
     */
    private const val RTT_LOG_INTERVAL_SAMPLES = 25  // ~every 50 s at 2 s PING

    // ── Log Modules ───────────────────────────────────────────────────────

    enum class Module {
        UI,        // button taps, screen transitions
        CALL,      // call setup / teardown
        RTP,       // RTP send / receive
        ECS,       // congestion signal and rate changes from Kotlin side
        NETWORK,   // Wi-Fi Direct, signaling, PING/PONG
        BLE,       // Bluetooth Low Energy mesh
        AUDIO,     // AudioRecord, AudioTrack
        SYSTEM     // lifecycle, init, shutdown
    }

    // ── Internal state ────────────────────────────────────────────────────

    private val initialized = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger-io").also { it.isDaemon = true }
    }

    @Volatile private var logFile: File? = null
    @Volatile private var writer: BufferedWriter? = null
    private var linesSinceFlush = 0
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var rttSampleCount = 0

    // ── Public API ────────────────────────────────────────────────────────

    /** Must be called once before any log calls. Safe to call multiple times. */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        executor.submit {
            try {
                val dir = context.filesDir          // /data/data/<pkg>/files/
                logFile = File(dir, LOG_FILE_NAME)
                openWriter()
                writeEntry("INFO", Module.SYSTEM, "AppLogger initialised file=${logFile?.absolutePath}")
            } catch (t: Throwable) {
                Log.e("AppLogger", "init failed", t)
            }
        }
    }

    fun debug(module: Module, message: String) {
        if (!debugEnabled) return
        submit("DEBUG", module, message)
        Log.d(module.name, message)
    }

    fun info(module: Module, message: String) {
        submit("INFO", module, message)
        Log.i(module.name, message)
    }

    fun warn(module: Module, message: String) {
        submit("WARN", module, message)
        Log.w(module.name, message)
    }

    fun error(module: Module, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message — ${throwable.message}" else message
        submit("ERROR", module, full)
        Log.e(module.name, full, throwable)
    }

    /**
     * Specialised helper: logs RTT only every [RTT_LOG_INTERVAL_SAMPLES] invocations.
     * Call on every PONG received; the logger itself decides whether to write.
     *
     * @param rttMs   round-trip time in milliseconds
     * @param status  ECS status string ("NO_CONGESTION" | "BUILDING" | "IMMINENT")
     * @param rateBps current allowed rate in bps
     */
    fun rttSample(rttMs: Long, status: String, rateBps: Int) {
        // Always counted, only written periodically.
        val shouldLog = synchronized(this) {
            rttSampleCount++
            rttSampleCount % RTT_LOG_INTERVAL_SAMPLES == 0
        }
        if (shouldLog) {
            debug(Module.ECS, "rtt=${rttMs}ms ecs=$status rate=${rateBps / 1000}kbps sample=$rttSampleCount")
        }
    }

    /** Flush pending writes and close the file. Call at app shutdown. */
    fun shutdown() {
        executor.submit {
            runCatching {
                writeEntry("INFO", Module.SYSTEM, "AppLogger shutting down")
                writer?.flush()
                writer?.close()
                writer = null
            }
        }
        executor.shutdown()
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun submit(level: String, module: Module, message: String) {
        if (!initialized.get()) return
        executor.submit {
            runCatching { writeEntry(level, module, message) }
        }
    }

    private fun writeEntry(level: String, module: Module, message: String) {
        val w = writer ?: return
        val ts = dateFormat.format(Date())
        // Format: [2026-03-21 12:45:23.456][INFO][ECS] RTT=28ms, trend=upward
        w.write("[$ts][$level][${module.name}] $message")
        w.newLine()
        linesSinceFlush++
        if (linesSinceFlush >= BUFFER_FLUSH_LINES) {
            w.flush()
            linesSinceFlush = 0
        }
        rotatIfNeeded()
    }

    private fun openWriter() {
        writer?.runCatching { close() }
        writer = BufferedWriter(FileWriter(logFile!!, /* append = */ true))
        linesSinceFlush = 0
    }

    private fun rotatIfNeeded() {
        val file = logFile ?: return
        if (file.length() < MAX_FILE_BYTES) return
        // Rotate: flush, close, rename, reopen fresh file.
        writer?.flush()
        writer?.close()
        val backup = File(file.parent, LOG_FILE_BACKUP)
        backup.delete()
        file.renameTo(backup)
        openWriter()
        writeEntry("INFO", Module.SYSTEM, "log rotated previous saved as $LOG_FILE_BACKUP")
    }
}
