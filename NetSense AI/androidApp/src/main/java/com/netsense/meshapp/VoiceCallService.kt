package com.netsense.meshapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.netsense.mesh.AppLogger
import com.netsense.mesh.VoiceTransportListener
import com.netsense.mesh.WifiDirectVoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.netsense.mesh.UiVoiceState
import net.sense.mesh.VoicePhase

/**
 * Foreground service that hosts Wi-Fi Direct voice transport.
 *
 * Keeps Wi-Fi Direct group, audio hardware, and RTP sockets alive when the
 * app is backgrounded or the screen turns off — mandatory for any real-world
 * PTT use-case (logistics, trekking, events).
 *
 * Lifecycle contract:
 *  1. Activity calls [Context.startForegroundService] before binding.
 *  2. Activity binds; receives [LocalBinder] to call [acquireVoiceManager].
 *  3. On foreground → [acquireVoiceManager] is idempotent (returns existing instance).
 *  4. Activity unbinds on pause; service stays alive via startForeground.
 *  5. User taps "End Call" notification action → [ACTION_END_CALL] → [disconnect].
 *  6. Activity calls [stopSelf] only when fully leaving the session.
 */
class VoiceCallService : Service() {

    companion object {
        private const val CHANNEL_ID = "netsense_call_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_END_CALL = "com.netsense.mesh.END_CALL"
        const val EXTRA_LOCAL_NODE_ID = "local_node_id"

        fun startIntent(context: Context, localNodeId: String): Intent =
            Intent(context, VoiceCallService::class.java).apply {
                putExtra(EXTRA_LOCAL_NODE_ID, localNodeId)
            }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()

    /** IO-scoped coroutines that outlive any single Activity binding. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var voiceManager: WifiDirectVoiceManager? = null
    private var listenerBridge: VoiceTransportListener? = null

    private val _uiState = MutableStateFlow<UiVoiceState>(UiVoiceState.Searching)
    val uiState: StateFlow<UiVoiceState> = _uiState.asStateFlow()

    // ── Android Service lifecycle ─────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(UiVoiceState.Searching))
        AppLogger.info(AppLogger.Module.SYSTEM, "VoiceCallService started")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_END_CALL -> disconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLogger.info(AppLogger.Module.SYSTEM, "VoiceCallService stopping")
        voiceManager?.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns (or creates) the [WifiDirectVoiceManager] owned by this service.
     *
     * Safe to call on every Activity resume — the manager is created only once.
     * [externalListener] is wired so the Activity's ViewModel receives phase events
     * while the internal bridge also updates the service notification.
     *
     * @param activity  required only for Wi-Fi Direct permission checks and window manager;
     *                  the manager uses [applicationContext] for all long-lived resources.
     * @param localNodeId  stable BLE node identifier for this device.
     * @param externalListener  forwarded to the voice manager for UI updates.
     */
    fun acquireVoiceManager(
        activity: android.app.Activity,
        localNodeId: String,
        externalListener: VoiceTransportListener
    ): WifiDirectVoiceManager {
        val existing = voiceManager
        if (existing != null) {
            // Update the external listener in case the Activity was recreated,
            // but keep the same internal bridge so the notification stays wired.
            existing.listener = buildBridgeListener(externalListener, existing)
            return existing
        }

        val mgr = WifiDirectVoiceManager(activity, localNodeId)
        voiceManager = mgr
        mgr.listener = buildBridgeListener(externalListener, mgr)
        return mgr
    }

    /** Gracefully ends any active call; manager remains alive for reconnection. */
    fun disconnect() {
        voiceManager?.disconnect()
    }

    /** Full shutdown — tears down manager and stops the service. */
    fun stopSession() {
        voiceManager?.stop()
        voiceManager = null
        stopSelf()
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Builds a [VoiceTransportListener] that:
     *  - updates the service-level [_uiState] and notification, AND
     *  - forwards every event to [external] for the Activity's ViewModel.
     */
    private fun buildBridgeListener(
        external: VoiceTransportListener,
        mgr: WifiDirectVoiceManager
    ): VoiceTransportListener {
        val bridge = object : VoiceTransportListener {
            override fun onVoicePeerDiscovered(peerId: String, peerName: String) {
                external.onVoicePeerDiscovered(peerId, peerName)
            }

            override fun onVoicePhaseChanged(phase: VoicePhase, detail: String) {
                val uiState = UiVoiceState.from(phase, detail)
                _uiState.value = uiState
                updateNotification(uiState)
                external.onVoicePhaseChanged(phase, detail)
            }

            override fun onVoicePathReady(remoteIp: String, rtpPort: Int) {
                external.onVoicePathReady(remoteIp, rtpPort)
            }

            override fun onVoicePushToTalk(active: Boolean) {
                if (active) {
                    _uiState.value = UiVoiceState.Speaking
                    updateNotification(UiVoiceState.Speaking)
                } else if (_uiState.value == UiVoiceState.Speaking) {
                    _uiState.value = UiVoiceState.Ready
                    updateNotification(UiVoiceState.Ready)
                }
                external.onVoicePushToTalk(active)
            }

            override fun onVoiceRemoteTransmitting(active: Boolean) {
                if (active) {
                    _uiState.value = UiVoiceState.Listening
                    updateNotification(UiVoiceState.Listening)
                } else if (_uiState.value == UiVoiceState.Listening) {
                    _uiState.value = UiVoiceState.Ready
                    updateNotification(UiVoiceState.Ready)
                }
                external.onVoiceRemoteTransmitting(active)
            }

            override fun onVoicePacketStats(txPackets: Long, rxPackets: Long) {
                external.onVoicePacketStats(txPackets, rxPackets)
            }

            override fun onVoiceError(reason: String) {
                val state = UiVoiceState.Reconnecting(reason)
                _uiState.value = state
                updateNotification(state)
                external.onVoiceError(reason)
            }
        }
        listenerBridge = bridge
        return bridge
    }

    private fun updateNotification(state: UiVoiceState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: UiVoiceState): Notification {
        val endIntent = Intent(this, VoiceCallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPending = PendingIntent.getService(
            this, 0, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NetSense Voice")
            .setContentText(state.label)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_delete, "End Call", endPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "NetSense active PTT voice call"
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
