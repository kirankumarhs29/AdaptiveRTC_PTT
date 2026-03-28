package com.netsense.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.netsense.mesh.AppLogger.Module
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sense.mesh.VoicePhase

interface VoiceTransportListener {
    fun onVoicePeerDiscovered(peerId: String, peerName: String)
    fun onVoicePhaseChanged(phase: VoicePhase, detail: String)
    fun onVoicePathReady(remoteIp: String, rtpPort: Int)
    fun onVoicePushToTalk(active: Boolean)
    fun onVoiceRemoteTransmitting(active: Boolean)
    fun onVoicePacketStats(txPackets: Long, rxPackets: Long)
    fun onVoiceError(reason: String)
}

class WifiDirectVoiceManager(
    private val activity: Activity,
    private val localNodeId: String
) {
    companion object {
        private const val TAG = "WifiDirectVoice"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        // If the P2P group hasn't formed within this window after a connect() request,
        // cancel the stuck attempt and immediately retry (no rediscovery needed).
        private const val GROUP_FORMATION_WATCHDOG_MS = 8_000L
        private const val DISCOVER_RETRY_DELAY_MS = 750L
        // When an established group drops unexpectedly (signal loss, device moved away),
        // automatically retry up to this many times before surfacing an error to the user.
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
        // Retry policy for connect() transient failures (BUSY/ERROR).
        // We periodically perform a hard P2P reset to break driver/framework stale state,
        // and we cap retries so the app fails fast instead of looping forever.
        private const val MAX_CONNECT_RETRIES = 18
        private const val HARD_RECOVERY_EVERY_N_RETRIES = 6
        private const val HARD_RECOVERY_DELAY_MS = 1_200L
        // Re-run discoverPeers() at this interval while in Discovering/Connecting phase and
        // no Wi-Fi Direct peer has been found yet. Both devices scan simultaneously and can
        // miss each other in the same window; periodic retry breaks the deadlock.
        private const val PERIODIC_DISCOVERY_INTERVAL_MS = 5_000L
    }

    private val wifiP2pManager = activity.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = wifiP2pManager?.initialize(activity, activity.mainLooper, null)
    private val peersByAddress = mutableMapOf<String, WifiP2pDevice>()
    // WifiP2pManager requires all API calls (connect, cancelConnect, removeGroup, …) to be
    // made from the thread whose Looper was passed to initialize() — i.e. the main thread.
    // Using Dispatchers.Main ensures every coroutine in this class satisfies that requirement.
    // All wifiP2pManager calls are non-blocking (they return immediately via callbacks), so
    // running on Main does not stall the UI. Heavy work (audio, RTP) lives in CallManager's
    // own Dispatchers.IO scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = VoiceAudioManager(activity)

    private var receiverRegistered = false
    private var activeInfo: WifiP2pInfo? = null
    private var pendingConnectPeerHint: String? = null
    private var pendingOutgoingCall = false
    private var callManager: CallManager? = null
    @Volatile private var connectRequestInFlight = false
    @Volatile private var connectRetryScheduled = false
    @Volatile private var transportInitializedForGroup = false
    /** Incremented on every explicit connect()/stop()/disconnect() to invalidate stale async callbacks. */
    @Volatile private var sessionVersion: Long = 0L
    /** Backoff counter for scheduleConnectRetry; reset on explicit connect() or successful group formation. */
    @Volatile private var connectRetryCount = 0
    private var connectionTimeoutJob: Job? = null
    /** Tracks the async endCall launched by stop(); connect() awaits it to prevent race. */
    private var shutdownJob: Job? = null
    @Volatile private var discoverRetryPending = false
    // Local Wi-Fi Direct device address — populated from WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.
    // Android 10+ returns 02:00:00:00:00:00 here (privacy masking); see connectToPeer for fallback.
    @Volatile private var localWifiDirectAddress: String = ""
    // BLE node ID of the active voice peer. Unlike pendingConnectPeerHint this is NOT cleared
    // on connect() success, so the group-formation watchdog can still read it during retries.
    private var activePeerNodeId: String? = null
    // The last peer we sent a connect() request to; retained so the group formation
    // watchdog can retry without rediscovery.
    private var lastConnectedPeerDevice: WifiP2pDevice? = null
    private var groupFormationWatchdogJob: Job? = null
    // How many consecutive auto-reconnect attempts have been made after an unexpected group drop.
    // Reset to 0 on an explicit connect() call or a successful group formation.
    private var reconnectAttemptCount = 0
    // Fires discoverPeers() periodically while no Wi-Fi Direct peer has been found so that a
    // simultaneous-scan deadlock (both sides scanning, neither advertising) resolves itself.
    private var periodicDiscoveryJob: Job? = null
    // Tracks whether the Wi-Fi Direct radio is currently available.
    // On chipsets without STA+P2P concurrency this goes false while Wi-Fi is connected to an AP.
    @Volatile private var p2pEnabled = true

    var listener: VoiceTransportListener? = null
        set(value) {
            field = value
            callManager = value?.let {
                // Initialise both loggers when the listener is first wired in.
                // This is the earliest point where we have both a Context (activity)
                // and certainty that the app will actually use voice.
                AppLogger.init(activity)
                val coreLogPath = activity.filesDir.absolutePath + "/core.log"
                EcsBridge.setLogPath(coreLogPath)
                AppLogger.info(AppLogger.Module.SYSTEM,
                    "loggers ready ui=${activity.filesDir}/ui.log core=$coreLogPath")
                CallManager(
                    localNodeId = localNodeId,
                    scope = scope,
                    listener = it,
                    audioManager = audioManager
                )
            }
        }

    private val peerListListener: WifiP2pManager.PeerListListener  = WifiP2pManager.PeerListListener { list: WifiP2pDeviceList ->
        peersByAddress.clear()
        list.deviceList.forEach { peer ->
            peersByAddress[peer.deviceAddress] = peer
            listener?.onVoicePeerDiscovered(peer.deviceAddress, peer.deviceName ?: peer.deviceAddress)
        }

        val pendingHint = pendingConnectPeerHint ?: return@PeerListListener
        // Post connect() outside the P2P callback to avoid re-entering WifiP2pManager from
        // within its own PeerListListener dispatch loop. Calling connect() synchronously here
        // causes Android to log "java.lang.Throwable (Fix with AI)" from WifiP2pManager.java
        // and can trigger intermittent ERROR/reason=0 rejections due to handler re-entrancy.
        scope.launch { tryConnectInternal(pendingHint, fromRetry = true) }
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info -> handleConnectionInfo(info) }

    @SuppressLint("MissingPermission")
    private fun handleConnectionInfo(info: WifiP2pInfo) {
        val hadGroup = activeInfo?.groupFormed == true
        activeInfo = info
        if (!info.groupFormed) {
            transportInitializedForGroup = false
            when {
                hadGroup -> {
                    connectionTimeoutJob?.cancel()
                    groupFormationWatchdogJob?.cancel()
                    connectRequestInFlight = false
                    val peerNodeId = activePeerNodeId
                    scope.launch {
                        withContext(Dispatchers.IO) { callManager?.shutdown("Wi-Fi Direct group removed") }
                        // Auto-reconnect if the user still wants a voice call and we haven't
                        // exhausted retries. pendingOutgoingCall stays true until disconnect()
                        // is explicitly called, so it acts as the "user still wants this" flag.
                        if (pendingOutgoingCall && peerNodeId != null &&
                            reconnectAttemptCount < MAX_RECONNECT_ATTEMPTS
                        ) {
                            reconnectAttemptCount++
                            Log.w(
                                TAG,
                                "group lost; auto-reconnect attempt $reconnectAttemptCount/$MAX_RECONNECT_ATTEMPTS peer=$peerNodeId"
                            )
                            AppLogger.warn(Module.NETWORK, "group-lost reconnect attempt=$reconnectAttemptCount/$MAX_RECONNECT_ATTEMPTS peer=$peerNodeId")
                            listener?.onVoicePhaseChanged(
                                VoicePhase.Connecting,
                                "signal lost — reconnecting (attempt $reconnectAttemptCount)"
                            )
                            delay(RECONNECT_DELAY_MS)
                            if (!pendingOutgoingCall) return@launch // user called disconnect() during delay
                            pendingConnectPeerHint = peerNodeId
                            val manager = wifiP2pManager
                            if (manager != null) {
                                manager.requestConnectionInfo(channel, connectionInfoListener)
                                manager.requestPeers(channel, peerListListener)
                            }
                            discoverPeers()
                            connectionTimeoutJob?.cancel()
                            connectionTimeoutJob = scope.launch {
                                delay(CONNECT_TIMEOUT_MS)
                                if (pendingOutgoingCall && !transportInitializedForGroup) {
                                    Log.e(TAG, "reconnect timeout after ${CONNECT_TIMEOUT_MS}ms")
                                    listener?.onVoiceError("voice reconnect timed out")
                                }
                            }
                        } else if (pendingOutgoingCall) {
                            Log.e(TAG, "group lost; max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) exhausted")
                            AppLogger.error(Module.NETWORK, "group-lost max-reconnect-exhausted peer=$peerNodeId")
                            listener?.onVoiceError("voice link lost — move closer and tap Call Again")
                        }
                    }
                }

                pendingOutgoingCall -> {
                    listener?.onVoicePhaseChanged(VoicePhase.Connecting, "waiting for Wi-Fi Direct group")
                }

                else -> {
                    Log.d(TAG, "ignoring non-group connection update during discovery")
                }
            }
            return
        }

        val remoteHint = if (info.isGroupOwner) null else info.groupOwnerAddress
        val role = if (info.isGroupOwner) "group_owner" else "client"
        connectRequestInFlight = false
        reconnectAttemptCount = 0 // successful formation resets the counter
        connectRetryCount = 0     // reset backoff now that group is formed

        connectionTimeoutJob?.cancel()
        groupFormationWatchdogJob?.cancel()
        val capturedVersion = sessionVersion  // capture before launch to detect stale callbacks
        scope.launch {
            if (capturedVersion != sessionVersion) {
                Log.d(TAG, "handleConnectionInfo: stale group-formed callback for session $capturedVersion; ignoring")
                return@launch
            }
            // Await any in-flight cleanup (from a prior stop()) before notifying CallManager.
            // Without this, onTransportConnected bails out immediately because cleanupInProgress
            // is still true — the group forms faster than the async endCall/cleanup finishes.
            shutdownJob?.join()
            // Re-check after the wait: a concurrent disconnect()/stop() may have started a new
            // shutdown and incremented sessionVersion, making this callback stale.
            if (capturedVersion != sessionVersion) {
                Log.d(TAG, "handleConnectionInfo: stale after shutdown join session $capturedVersion; ignoring")
                return@launch
            }
            if (!transportInitializedForGroup) {
                // Group formed — stop periodic rediscovery; it's no longer needed.
                periodicDiscoveryJob?.cancel()
                periodicDiscoveryJob = null
                // Publish Connecting only on the first group formation. Duplicate
                // connectionInfo callbacks with groupFormed=true must NOT push the
                // phase backwards; the CallManager owns subsequent state transitions.
                listener?.onVoicePhaseChanged(VoicePhase.Connecting, "Wi-Fi Direct connected role=$role")
                AppLogger.info(Module.NETWORK, "p2p-group-formed role=$role remoteHint=$remoteHint")
                withContext(Dispatchers.IO) { callManager?.onTransportConnected(remoteHint, isGroupOwner = info.isGroupOwner) }
                transportInitializedForGroup = true
            } else {
                Log.d(TAG, "connectionInfo duplicate group callback ignored for transport init")
            }
            // Do NOT auto-trigger requestOutgoingCall() here.  Call initiation is
            // explicit: the user taps "Call" which goes through startCall() →
            // CallManager.startCall() → requestOutgoingCall().  Conflating transport
            // establishment with call setup caused "stuck in Connecting" re-entry bugs.
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (!hasWifiDirectPermission()) {
                Log.w(TAG, "p2pReceiver action=$action ignored missing permission")
                return
            }

            Log.d(
                TAG,
                "p2pReceiver action=$action pendingOutgoingCall=$pendingOutgoingCall activeGroup=${activeInfo?.groupFormed == true}"
            )

            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    p2pEnabled = enabled
                    Log.d(TAG, "Wi-Fi Direct state changed enabled=$enabled")
                    if (!enabled && pendingOutgoingCall) {
                        // P2P radio went offline mid-call (chipset STA+P2P conflict).
                        // Cancel the connection attempt and inform the user.
                        connectionTimeoutJob?.cancel()
                        listener?.onVoiceError(staP2pConflictMessage())
                    } else {
                        wifiP2pManager?.requestConnectionInfo(channel, connectionInfoListener)
                        wifiP2pManager?.requestPeers(channel, peerListListener)
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Capture our own Wi-Fi Direct MAC for deterministic group owner selection.
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    if (device != null) {
                        localWifiDirectAddress = device.deviceAddress ?: ""
                        Log.d(TAG, "local Wi-Fi Direct address=$localWifiDirectAddress")
                    }
                    wifiP2pManager?.requestConnectionInfo(channel, connectionInfoListener)
                    wifiP2pManager?.requestPeers(channel, peerListListener)
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    wifiP2pManager?.requestConnectionInfo(channel, connectionInfoListener)
                    wifiP2pManager?.requestPeers(channel, peerListListener)
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager?.requestPeers(channel, peerListListener)
                }
            }
        }
    }

    fun start() {
        Log.d(TAG, "start voice manager")
        AppLogger.info(Module.NETWORK, "wifi-direct-voice start")
        registerReceiverIfNeeded()
    }

    fun stop() {
        Log.d(TAG, "stop voice manager pendingOutgoingCall=$pendingOutgoingCall")
        AppLogger.info(Module.NETWORK, "wifi-direct-voice stop pendingOutgoingCall=$pendingOutgoingCall")
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        groupFormationWatchdogJob?.cancel()
        groupFormationWatchdogJob = null
        periodicDiscoveryJob?.cancel()
        periodicDiscoveryJob = null
        lastConnectedPeerDevice = null
        activePeerNodeId = null
        reconnectAttemptCount = 0
        discoverRetryPending = false
        sessionVersion++       // invalidate any stale group-formed callbacks still in-flight
        connectRetryCount = 0
        shutdownJob = scope.launch {
            // Full cleanup (fullCleanup=true): stop() is called on an explicit disconnect,
            // so the transport is going away.  A call-level-only cleanup would try to resolve
            // to Connected, but the Wi-Fi Direct group is being removed so we must go to Idle.
            withContext(Dispatchers.IO) {
                callManager?.endCall(localInitiated = true, detail = "stopped", fullCleanup = true)
            }
        }
        unregisterReceiverIfNeeded()
        activeInfo = null
        pendingConnectPeerHint = null
        pendingOutgoingCall = false
        connectRequestInFlight = false
        transportInitializedForGroup = false
        p2pEnabled = true
        connectRetryScheduled = false
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        Log.d(TAG, "discoverPeers requested")
        if (!hasWifiDirectPermission()) {
            listener?.onVoiceError("Missing Wi-Fi Direct permission")
            return
        }

        start()
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "discoverPeers success")
                AppLogger.info(Module.NETWORK, "discoverPeers success")
                discoverRetryPending = false
                if (pendingOutgoingCall) {
                    scope.launch { callManager?.publishDiscoveryState("discovering voice peers") }
                }
                wifiP2pManager.requestPeers(channel, peerListListener)
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "discoverPeers failed reason=$reason")
                AppLogger.warn(Module.NETWORK, "discoverPeers failed reason=$reason")
                if (reason == WifiP2pManager.BUSY) {
                    // p2pEnabled may be transiently false because the WIFI_P2P_STATE_CHANGED_ACTION
                    // broadcast (DISABLED) arrived just before discoverPeers was called during
                    // framework initialisation or a stop/start cycle. Grant one 1-second retry to
                    // allow the state broadcast to settle. Only surface a fatal error on the retry
                    // if P2P is still disabled — that indicates a true STA+P2P concurrency limit.
                    if (!p2pEnabled) {
                        if (!discoverRetryPending && pendingConnectPeerHint != null) {
                            Log.w(TAG, "discoverPeers BUSY with p2pEnabled=false - retrying in 1000ms to allow state to settle")
                            discoverRetryPending = true
                            scope.launch {
                                delay(1000L)
                                discoverRetryPending = false
                                if (!p2pEnabled) {
                                    Log.e(TAG, "discoverPeers BUSY with P2P still disabled after retry - STA+P2P concurrency not supported")
                                    connectionTimeoutJob?.cancel()
                                    listener?.onVoiceError(staP2pConflictMessage())
                                } else if (pendingConnectPeerHint != null) {
                                    discoverPeers()
                                }
                            }
                        }
                        return
                    }
                    // P2P is enabled but subsystem is transiently busy — cancel stale state and retry.
                    if (!discoverRetryPending && pendingConnectPeerHint != null) {
                        Log.w(TAG, "discoverPeers BUSY - cancelling stuck P2P connect, retrying in ${DISCOVER_RETRY_DELAY_MS}ms")
                        discoverRetryPending = true
                        connectRequestInFlight = false
                        wifiP2pManager?.cancelConnect(channel, null)
                        scope.launch {
                            delay(DISCOVER_RETRY_DELAY_MS)
                            discoverRetryPending = false
                            if (pendingConnectPeerHint != null) discoverPeers()
                        }
                    }
                    return
                }
                listener?.onVoiceError("discoverPeers failed reason=$reason")
            }
        })
    }

    fun connect(peerId: String, originateCall: Boolean = true) {
        Log.d(TAG, "connect requested peerId=$peerId")
        AppLogger.info(Module.NETWORK, "voice-connect requested peerId=$peerId originateCall=$originateCall")
        if (!hasWifiDirectPermission()) {
            listener?.onVoiceError("Missing Wi-Fi Direct permission")
            return
        }

        pendingConnectPeerHint = peerId
        activePeerNodeId = peerId
        reconnectAttemptCount = 0 // explicit call resets counter
        sessionVersion++           // invalidate any stale connection-info callbacks from prior session
        connectRetryCount = 0      // reset backoff for the new attempt
        pendingOutgoingCall = originateCall
        listener?.onVoicePhaseChanged(VoicePhase.Connecting, "resolving Wi-Fi Direct peer")
        start()
        // Await any in-flight shutdown from a prior stop() so that prewarm() does not race
        // with cleanup().  The shutdownJob runs endCall→cleanup→signalingManager.stop();
        // if we call prewarm() before that finishes, it creates sockets that cleanup then
        // destroys.  Awaiting serialises the two lifecycles.
        scope.launch {
            shutdownJob?.join()
            shutdownJob = null
            withContext(Dispatchers.IO) { callManager?.prewarm() }
        }
        // Fast path: pick up an already-formed group immediately (e.g. Call Again after End Call).
        wifiP2pManager?.requestConnectionInfo(channel, connectionInfoListener)
        // Check the cached peer list right away — if the peer is already known, connect skips discovery.
        wifiP2pManager?.requestPeers(channel, peerListListener)
        // Kick off fresh discovery in parallel; covers the common first-connect case.
        discoverPeers()

        // Safety valve: if Wi-Fi Direct group never forms, report error instead of hanging forever.
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (originateCall && !transportInitializedForGroup) {
                Log.e(TAG, "connect timeout after ${CONNECT_TIMEOUT_MS}ms - Wi-Fi Direct group never formed")
                listener?.onVoiceError("voice connect timeout")
            }
        }

        // Periodically re-run discoverPeers() while no peer has been found. Both devices can
        // end up scanning simultaneously with neither beaconing, causing a miss. Retrying
        // every 5 s alternates their active/listen windows and breaks the deadlock.
        periodicDiscoveryJob?.cancel()
        periodicDiscoveryJob = scope.launch {
            while (true) {
                delay(PERIODIC_DISCOVERY_INTERVAL_MS)
                if (!pendingOutgoingCall || transportInitializedForGroup) break
                // Don't interfere while a connect() or retry is already in-flight.
                if (!connectRequestInFlight && !connectRetryScheduled && !discoverRetryPending) {
                    Log.d(TAG, "periodic rediscovery: peer not yet visible, retrying discoverPeers")
                    AppLogger.info(Module.NETWORK, "p2p-periodic-rediscovery peer=$pendingConnectPeerHint")
                    discoverPeers()
                }
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect requested")
        AppLogger.info(Module.NETWORK, "voice-disconnect requested")
        pendingOutgoingCall = false
        // endCall + cleanup happen inside stop() via shutdown(); no separate launch
        // needed — avoids the double-cleanup race where a stale shutdown() could trash
        // infrastructure that a subsequent connect()/prewarm() just created.

        if (!hasWifiDirectPermission()) {
            stop()
            return
        }

        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                listener?.onVoicePhaseChanged(VoicePhase.Idle, "group removed")
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) {
                    Log.w(TAG, "removeGroup transient failure reason=$reason; forcing local idle cleanup")
                    listener?.onVoicePhaseChanged(VoicePhase.Idle, "group cleanup local")
                    return
                }
                listener?.onVoiceError("removeGroup failed reason=$reason")
            }
        })
        stop()
    }

    /**
     * Explicitly starts an outgoing call from the [VoicePhase.Connected] state.
     *
     * The Wi-Fi Direct transport must already be established (group formed + TRANSPORT_READY
     * handshake complete) before calling this.  If the transport is not yet ready, call
     * [connect] first; [AndroidMeshService] will auto-call once [VoicePhase.Connected] is reached.
     */
    fun startCall() {
        Log.d(TAG, "startCall requested")
        AppLogger.info(Module.NETWORK, "voice start-call requested")
        scope.launch { withContext(Dispatchers.IO) { callManager?.startCall() } }
    }

    fun startPushToTalk() {
        Log.d(TAG, "startPushToTalk requested")
        scope.launch { withContext(Dispatchers.IO) { callManager?.startPushToTalk() } }
    }

    fun stopPushToTalk() {
        Log.d(TAG, "stopPushToTalk requested")
        scope.launch { withContext(Dispatchers.IO) { callManager?.stopPushToTalk() } }
    }

    private fun tryConnectInternal(peerHint: String, fromRetry: Boolean) {
        if (connectRequestInFlight) {
            Log.d(TAG, "tryConnectInternal skipped, request already in-flight")
            return
        }
        if (connectRetryScheduled) {
            Log.d(TAG, "tryConnectInternal skipped, retry already scheduled")
            return
        }

        val peer = resolvePeer(peerHint)
        if (peer == null) {
            if (!fromRetry) {
                Log.d(TAG, "tryConnectInternal peer not in cache; discovery already started")
                listener?.onVoicePhaseChanged(VoicePhase.Connecting, "waiting for Wi-Fi Direct peer list")
            }
            return
        }

        connectToPeer(peer)
    }

    /**
     * Routes to [createAutonomousGroup] or [connectAsClient] based on the deterministic
     * MAC-address comparison that was already used for goIntent selection.
     *
     * Using createGroup() on the owner side eliminates the "Accept Wi-Fi Direct
     * connection?" dialog that Android pops up when connect() receives an inbound
     * P2P_GO_NEG_REQ before the remote side has also called connect().
     * With an already-formed autonomous group the client's connect() is treated as
     * a Provision Discovery / group-join, which the framework accepts automatically.
     */
    private fun connectToPeer(peer: WifiP2pDevice) {
        if (connectRequestInFlight) {
            Log.d(TAG, "connectToPeer skipped, request already in-flight")
            return
        }

        val localAddr = localWifiDirectAddress
        // Android 10+ masks the local MAC to 02:00:00:00:00:00 (privacy feature).
        // When masked, both devices would compute weAreOwner=false and both call connectAsClient(),
        // so nobody creates the group. Fall back to BLE node ID comparison instead — both sides
        // independently arrive at complementary GO/client decisions from the same two IDs.
        val macMasked = localAddr == "02:00:00:00:00:00" || localAddr.isEmpty()
        val weAreOwner = if (!macMasked) {
            localAddr >= peer.deviceAddress
        } else {
            val peerNodeId = activePeerNodeId ?: peer.deviceAddress
            Log.d(TAG, "local MAC masked; GO role via nodeId local=$localNodeId peer=$peerNodeId owner=${localNodeId >= peerNodeId}")
            localNodeId >= peerNodeId
        }

        if (weAreOwner) {
            connectAsOwner(peer)
        } else {
            connectAsClient(peer)
        }
    }

    /**
     * Higher-ID device: calls connect() with goIntent=15 so the framework's GO negotiation
     * reliably assigns the owner role to this side.
     *
     * Using symmetric connect() on both sides (goIntent=15 here, goIntent=0 on the client)
     * is the correct Wi-Fi Direct approach. Both devices initiate simultaneously so neither
     * side triggers the "Accept Wi-Fi Direct connection?" dialog, and the framework never
     * sees an autonomous-group owner conflict (which causes ERROR/reason=0 on the client).
     */
    @SuppressLint("MissingPermission")
    private fun connectAsOwner(peer: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
            groupOwnerIntent = 15
        }
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                connectRequestInFlight = true
                connectRetryScheduled = false
                lastConnectedPeerDevice = peer
                pendingConnectPeerHint = null
                Log.d(TAG, "Wi-Fi Direct owner connect sent peer=${peer.deviceAddress}")
                AppLogger.info(Module.NETWORK, "p2p-owner-connect-sent peer=${peer.deviceAddress}")
                listener?.onVoicePhaseChanged(VoicePhase.Connecting, "owner: negotiating with ${peer.deviceAddress}")
                startGroupFormationWatchdog(peer)
            }

            override fun onFailure(reason: Int) {
                connectRequestInFlight = false
                Log.e(TAG, "Wi-Fi Direct owner connect failed reason=$reason peer=${peer.deviceAddress}")
                AppLogger.warn(Module.NETWORK, "p2p-owner-connect-failed reason=$reason peer=${peer.deviceAddress}")
                if (reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) {
                    listener?.onVoicePhaseChanged(VoicePhase.Connecting, "Wi-Fi Direct transient failure reason=$reason; retrying")
                    scheduleConnectRetry(peer)
                    return
                }
                listener?.onVoiceError("connect failed reason=$reason")
            }
        })
    }

    /** Lower-MAC device: join the owner's group. goIntent=0 guarantees client role. */
    @SuppressLint("MissingPermission")
    private fun connectAsClient(peer: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
            groupOwnerIntent = 0
        }
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                connectRequestInFlight = true
                connectRetryScheduled = false
                lastConnectedPeerDevice = peer
                pendingConnectPeerHint = null
                Log.d(TAG, "Wi-Fi Direct client connect sent peer=${peer.deviceAddress}")
                AppLogger.info(Module.NETWORK, "p2p-client-connect-sent peer=${peer.deviceAddress}")
                listener?.onVoicePhaseChanged(VoicePhase.Connecting, "client: connecting to ${peer.deviceAddress}")
                startGroupFormationWatchdog(peer)
            }

            override fun onFailure(reason: Int) {
                connectRequestInFlight = false
                Log.e(TAG, "Wi-Fi Direct client connect failed reason=$reason peer=${peer.deviceAddress}")
                AppLogger.warn(Module.NETWORK, "p2p-client-connect-failed reason=$reason peer=${peer.deviceAddress}")
                // BUSY (2): framework busy with prior request.
                // ERROR (0): driver rejected connect() because a stale P2P connection is still
                //             partially registered (common when the GO's autonomous group isn't
                //             fully visible yet). Both are transient — cancel and retry.
                if (reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR || activeInfo?.groupFormed == true) {
                    listener?.onVoicePhaseChanged(VoicePhase.Connecting, "Wi-Fi Direct transient failure reason=$reason; retrying")
                    scheduleConnectRetry(peer)
                    return
                }
                listener?.onVoiceError("connect failed reason=$reason")
            }
        })
    }

    /**
     * Starts an 8-second watchdog after a connect() request is sent.
     * If the P2P group hasn't formed by then, cancels the stuck attempt and immediately
     * retries [connectToPeer] without going through rediscovery.
     */
    private fun startGroupFormationWatchdog(peer: WifiP2pDevice) {
        groupFormationWatchdogJob?.cancel()
        groupFormationWatchdogJob = scope.launch {
            delay(GROUP_FORMATION_WATCHDOG_MS)
            if (!pendingOutgoingCall || transportInitializedForGroup) return@launch
            Log.w(TAG, "group formation watchdog: no group after ${GROUP_FORMATION_WATCHDOG_MS}ms — cancelling and retrying")
            AppLogger.warn(Module.NETWORK, "group-formation-watchdog fired after ${GROUP_FORMATION_WATCHDOG_MS}ms; cancelling and retrying")
            connectRequestInFlight = false
            wifiP2pManager?.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = scheduleConnectRetry(peer)
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "watchdog cancelConnect failed reason=$reason; retrying anyway")
                    scheduleConnectRetry(peer)
                }
            })
        }
    }

    private fun scheduleConnectRetry(peer: WifiP2pDevice) {
        if (connectRetryScheduled) {
            Log.d(TAG, "scheduleConnectRetry skipped, retry already pending")
            return
        }
        connectRetryScheduled = true
        connectRetryCount++

        if (connectRetryCount > MAX_CONNECT_RETRIES) {
            connectRetryScheduled = false
            connectRequestInFlight = false
            groupFormationWatchdogJob?.cancel()
            connectionTimeoutJob?.cancel()
            pendingOutgoingCall = false
            val msg = "Wi-Fi Direct connect failed repeatedly (${connectRetryCount - 1} retries)."
            Log.e(TAG, "$msg Stopping retry loop.")
            AppLogger.error(Module.NETWORK, "p2p-connect-max-retries-exceeded retries=${connectRetryCount - 1} peer=${peer.deviceAddress}")
            listener?.onVoiceError("$msg Please toggle Wi-Fi once on both phones, then tap Call again.")
            return
        }

        if (connectRetryCount % HARD_RECOVERY_EVERY_N_RETRIES == 0) {
            // Periodically force a deeper reset of stale framework/driver P2P state.
            hardRecoverP2pAndRetry(peer)
            return
        }

        // Exponential backoff: 400 ms, 800 ms, 1 600 ms, 2 500 ms (capped), plus ±15 % jitter.
        val baseDelayMs = minOf(400L shl minOf(connectRetryCount - 1, 2), 2500L)
        val jitterMs = (baseDelayMs * 0.15 * Math.random()).toLong()
        val delayMs = baseDelayMs + jitterMs
        scope.launch {
            // Cancel any stale P2P connection before retrying so the driver state is clean.
            wifiP2pManager?.cancelConnect(channel, null)
            delay(delayMs)
            if (pendingOutgoingCall && !transportInitializedForGroup) {
                Log.d(TAG, "scheduleConnectRetry: retrying connect to ${peer.deviceAddress} attempt=$connectRetryCount delay=${delayMs}ms")
                AppLogger.info(Module.NETWORK, "p2p-connect-retry attempt=$connectRetryCount delay=${delayMs}ms peer=${peer.deviceAddress}")
                connectRetryScheduled = false
                connectToPeer(peer)
            } else {
                connectRetryScheduled = false
            }
        }
    }

    /**
     * Performs a deeper Wi-Fi Direct recovery sequence before retrying connect:
     * cancel stale connect, remove any stale group, wait briefly, then rediscover.
     */
    @SuppressLint("MissingPermission")
    private fun hardRecoverP2pAndRetry(peer: WifiP2pDevice) {
        Log.w(TAG, "hardRecoverP2pAndRetry retry=$connectRetryCount peer=${peer.deviceAddress}")
        AppLogger.warn(Module.NETWORK, "p2p-hard-recovery retry=$connectRetryCount peer=${peer.deviceAddress}")
        listener?.onVoicePhaseChanged(VoicePhase.Connecting, "recovering Wi-Fi Direct…")

        scope.launch {
            connectRequestInFlight = false
            connectRetryScheduled = false

            // 1) Cancel any stuck in-flight connect transaction.
            wifiP2pManager?.cancelConnect(channel, null)

            // 2) Remove any stale group that may be blocking new negotiation.
            wifiP2pManager?.removeGroup(channel, null)

            // 3) Let framework settle before rediscovery/reconnect.
            delay(HARD_RECOVERY_DELAY_MS)

            if (!pendingOutgoingCall || transportInitializedForGroup) return@launch

            // Refresh state then retry deterministic role connect.
            wifiP2pManager?.requestPeers(channel, peerListListener)
            discoverPeers()
            connectToPeer(peer)
        }
    }

    private fun resolvePeer(peerHint: String): WifiP2pDevice? {
        peersByAddress[peerHint]?.let { return it }

        peersByAddress.values.firstOrNull { device ->
            val name = device.deviceName ?: return@firstOrNull false
            peerHint.contains(name, ignoreCase = true) || name.contains(peerHint, ignoreCase = true)
        }?.let { return it }

        return if (peersByAddress.size == 1) peersByAddress.values.first() else null
    }

    @SuppressLint("MissingPermission")
    private fun hasWifiDirectPermission(): Boolean {
        val nearbyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val locationGranted = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        return nearbyGranted && locationGranted
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        activity.registerReceiver(p2pReceiver, filter)
        receiverRegistered = true
        Log.d(TAG, "Wi-Fi Direct receiver registered")
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        try {
            activity.unregisterReceiver(p2pReceiver)
        } catch (_: IllegalArgumentException) {
        }
        receiverRegistered = false
        Log.d(TAG, "Wi-Fi Direct receiver unregistered")
    }

    /**
     * Returns a human-readable error for STA+P2P concurrency failures.
     *
     * Wi-Fi Direct is completely independent of Wi-Fi — no shared router is needed.
     * However, some Android chipsets cannot run a regular Wi-Fi connection (STA mode)
     * and Wi-Fi Direct (P2P) at the same time. When this happens, P2P discovery fails
     * with BUSY while the device stays connected to a Wi-Fi AP.
     *
     * The message advises the user to turn off Wi-Fi (not just disconnect from the AP)
     * so the radio can be used for the peer-to-peer voice link.
     */
    @SuppressLint("MissingPermission")
    private fun staP2pConflictMessage(): String {
        val wifiConnected = try {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
        return if (wifiConnected) {
            "Wi-Fi Direct unavailable: your device cannot run Wi-Fi and voice simultaneously. " +
                "Turn off Wi-Fi — no shared network is needed, voice connects directly between devices."
        } else {
            "Wi-Fi Direct unavailable on this device. Check that Wi-Fi is enabled and try again."
        }
    }
}