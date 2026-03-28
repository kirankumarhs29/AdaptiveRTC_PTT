package com.netsense.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import com.netsense.mesh.AppLogger.Module
import net.sense.mesh.ConnectionState
import net.sense.mesh.DeliveryStatus
import net.sense.mesh.MeshCallback
import net.sense.mesh.PeerHealth
import net.sense.mesh.PeerHealthState
import net.sense.mesh.SessionSecurityState
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android-side manager for peer discovery and reliable secure message signaling.
 */
class MeshManager(
    private val activity: Activity,
    private val localNodeId: String
) {
    companion object {
        private const val TAG = "MeshManager"
        private const val PERMISSION_REQUEST_CODE = 1009
        private val SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        private val MESSAGE_CHAR_UUID: UUID = UUID.fromString("00002A39-0000-1000-8000-00805F9B34FB")
        private const val ADVERTISE_FAILED_ALREADY_STARTED = 3
        private const val FALLBACK_SCAN_DELAY_MS = 4000L

        private const val ACK_TIMEOUT_MS = 2200L
        private const val MAX_ACK_RETRIES = 3
        private const val PING_INTERVAL_MS = 7000L
        private const val HEALTH_CHECK_INTERVAL_MS = 4000L
        private const val RSSI_POLL_INTERVAL_MS = 5000L
        private const val PEER_DEGRADED_MS = 14000L
        private const val PEER_UNREACHABLE_MS = 22000L
        private const val RECONNECT_BASE_DELAY_MS = 1600L
        private const val MAX_RECONNECT_ATTEMPTS = 4

        private const val PROTOCOL_PREFIX = "NS1"
        private const val TYPE_KEY_EXCHANGE = "KEY"
        private const val TYPE_KEY_ACK = "KEY_ACK"
        private const val TYPE_DATA = "DATA"
        private const val TYPE_ACK = "ACK"
        private const val TYPE_PING = "PING"
        private const val TYPE_PONG = "PONG"

        // Keep chunks <= 20-byte ATT payload for devices that never negotiate larger MTU.
        private const val BLE_SAFE_CHUNK_BYTES = 20
        private const val CHUNK_HEADER_BYTES = 5
        private const val FRAGMENT_TTL_MS = 12000L
        // Max MTU to request; Android BLE stack caps this at the chipset maximum (~517 typical).
        private const val MTU_REQUEST = 512
        // If onMtuChanged doesn't fire within this window, proceed with the default 20-byte payload.
        private const val MTU_NEGOTIATION_TIMEOUT_MS = 400L
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val bluetoothManager: BluetoothManager?
        get() = activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser: BluetoothLeAdvertiser? get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private var callback: MeshCallback? = null
    private val discoveredPeers = mutableSetOf<String>()
    private val peerAddressById = mutableMapOf<String, String>()
    private val peerIdByAddress = mutableMapOf<String, String>()
    // Cached live BluetoothDevice handle from ScanResult — required for Android 12+ random/private MACs.
    private val peerDeviceById = mutableMapOf<String, BluetoothDevice>()

    private var advertisingStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanFallbackRunnable: Runnable? = null
    private var pendingStartAfterPermission = false
    // True when the fallback unfiltered scan is running — used to reject non-NetSense devices.
    private var unfilteredScanActive = false

    private var gattServer: BluetoothGattServer? = null
    private var connectedGatt: BluetoothGatt? = null
    private var connectedPeerId: String? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val sessionsByPeerId = mutableMapOf<String, SecureSession>()
    private val handshakeLocalKeys = mutableMapOf<String, KeyPair>()
    private val pendingControlByPeerId = mutableMapOf<String, MutableList<String>>()

    private val pendingOutbound = mutableMapOf<String, OutboundMessage>()
    private var messageSequence: Long = 0L

    private val peerHealth = mutableMapOf<String, PeerHealth>()
    private val reconnectRunnables = mutableMapOf<String, Runnable>()
    private val manualDisconnectPeers = mutableSetOf<String>()

    private var pingRunnable: Runnable? = null
    private var healthCheckRunnable: Runnable? = null
    private var rssiPollRunnable: Runnable? = null
    private val secureRandom = SecureRandom()
    private val outboundWriteQueue = ArrayDeque<String>()
    private var writeInFlight = false
    private var fragmentSequence: Int = 0
    private val inboundFragments = mutableMapOf<String, InboundFragmentBuffer>()
    // Effective ATT payload size in bytes; updated after MTU negotiation.
    // ATT overhead = 3 bytes, so payload = negotiatedMtu - 3.
    @Volatile private var blePayloadBytes: Int = BLE_SAFE_CHUNK_BYTES
    private var mtuTimeoutRunnable: Runnable? = null

    private data class SecureSession(
        val key: SecretKeySpec,
        val state: SessionSecurityState,
        val establishedAtEpochMs: Long
    )

    private data class OutboundMessage(
        val messageId: String,
        val peerId: String,
        val payload: String,
        var attempt: Int,
        var timeoutRunnable: Runnable?
    )

    private data class InboundFragmentBuffer(
        val expectedParts: Int,
        val parts: MutableMap<Int, String>,
        var updatedAtEpochMs: Long
    )

    private data class PeerResolution(
        val canonicalPeerId: String,
        val resolvedAddress: String?
    )

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESSAGE_CHAR_UUID) {
                val payload = value.toString(StandardCharsets.UTF_8)
                val address = device.address ?: ""
                val peerId = resolvePeerId(address)
                Log.d(TAG, "GattServer onWrite from=$peerId/$address payload=$payload")
                handleIncomingFrame(peerId, payload)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "GattClient onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                AppLogger.info(Module.BLE, "gatt-connected peer=${connectedPeerId} status=$status")
                callback?.onConnectionStateChanged(ConnectionState.Connecting)
                gatt.discoverServices()
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val disconnectedPeer = connectedPeerId
                AppLogger.info(Module.BLE, "gatt-disconnected peer=$disconnectedPeer status=$status")
                clearGattClient()
                callback?.onConnectionStateChanged(ConnectionState.Disconnected)

                if (disconnectedPeer != null) {
                    if (manualDisconnectPeers.remove(disconnectedPeer)) {
                        updatePeerHealth(disconnectedPeer, PeerHealthState.Unreachable, keepReconnectAttempts = true)
                    } else {
                        scheduleReconnect(disconnectedPeer, reason = "gatt_disconnected")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "GattClient onServicesDiscovered status=$status")
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(MESSAGE_CHAR_UUID)
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                connectedGatt = gatt
                writeCharacteristic = characteristic
                callback?.onConnectionStateChanged(ConnectionState.Connected)
                AppLogger.info(Module.BLE, "gatt-services-discovered peer=${connectedPeerId} - starting MTU negotiation")

                val peerId = connectedPeerId
                if (peerId != null) {
                    resetReconnectAttempts(peerId)
                    updateSessionState(peerId, SessionSecurityState.Handshaking)
                    flushPendingControlMessages(peerId)
                    startPeerHealthLoops(peerId)

                    // Request larger MTU so the EC public key fits in one write instead of 9.
                    // initiateKeyExchange is called from onMtuChanged (or the timeout fallback).
                    val mtuTimeout = Runnable {
                        Log.w(TAG, "MTU negotiation timeout; proceeding with blePayloadBytes=$blePayloadBytes")
                        mtuTimeoutRunnable = null
                        initiateKeyExchange(peerId)
                    }
                    mtuTimeoutRunnable = mtuTimeout
                    mainHandler.postDelayed(mtuTimeout, MTU_NEGOTIATION_TIMEOUT_MS)
                    val requested = gatt.requestMtu(MTU_REQUEST)
                    Log.d(TAG, "requestMtu($MTU_REQUEST) requested=$requested peer=$peerId")
                    if (!requested) {
                        // requestMtu failed synchronously (rare); cancel timeout and go direct.
                        mainHandler.removeCallbacks(mtuTimeout)
                        mtuTimeoutRunnable = null
                        initiateKeyExchange(peerId)
                    }
                }
            } else {
                callback?.onConnectionStateChanged(ConnectionState.Error)
                clearGattClient()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // ATT protocol overhead = 3 bytes; the rest is usable payload.
            val payload = (mtu - 3).coerceAtLeast(BLE_SAFE_CHUNK_BYTES)
            blePayloadBytes = payload
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status effectivePayload=$payload")
            AppLogger.info(Module.BLE, "mtu-changed mtu=$mtu effectivePayload=$payload peer=${connectedPeerId}")

            // Cancel the timeout fallback and start the key exchange with the larger chunk size.
            val timeout = mtuTimeoutRunnable
            if (timeout != null) {
                mainHandler.removeCallbacks(timeout)
                mtuTimeoutRunnable = null
                val peerId = connectedPeerId
                if (peerId != null) initiateKeyExchange(peerId)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val peerId = connectedPeerId ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onReadRemoteRssi peerId=$peerId rssi=$rssi")
                callback?.onPeerRssiUpdated(peerId, rssi)
            } else {
                Log.w(TAG, "onReadRemoteRssi failed status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onCharacteristicWrite failed status=$status")
            }
            drainWriteQueue()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            cancelFallbackScan()

            val device: BluetoothDevice = result.device
            val serviceData = result.scanRecord
                ?.getServiceData(ParcelUuid(SERVICE_UUID))
                ?.toString(StandardCharsets.UTF_8)
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            // During fallback unfiltered scan the OS returns every BLE device nearby.
            // Only accept devices that carry our NetSense service data.
            if (unfilteredScanActive && serviceData == null) {
                return
            }

            val localAdvertisedId = localNodeId.take(16)
            if (serviceData != null && serviceData == localAdvertisedId) {
                Log.d(TAG, "onScanResult ignored self advertisement id=$serviceData")
                return
            }

            val peerId = serviceData ?: device.address ?: return
            val address = device.address
            if (address != null) {
                peerAddressById[peerId] = address
                peerIdByAddress[address] = peerId
            }
            // Always refresh the device handle — the address may rotate on subsequent scans.
            peerDeviceById[peerId] = device
            if (!discoveredPeers.add(peerId)) return

            val peerName = if (serviceData != null) "NetSense $serviceData" else (device.name ?: "NetSense peer")
            Log.d(TAG, "onScanResult peerId=$peerId peerName=$peerName rssi=${result.rssi} callbackType=$callbackType")
            AppLogger.info(Module.BLE, "ble-peer-discovered id=$peerId name=$peerName rssi=${result.rssi}")
            callback?.onPeerDiscovered(peerId, peerName, result.rssi)
            updatePeerHealth(peerId, PeerHealthState.Degraded, reconnectAttempts = 0)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed errorCode=$errorCode")
            AppLogger.error(Module.BLE, "ble-scan-failed errorCode=$errorCode")
            callback?.onConnectionStateChanged(ConnectionState.Error)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertisingStarted = true
            Log.d(TAG, "startAdvertising success mode=${settingsInEffect?.mode} txPower=${settingsInEffect?.txPowerLevel}")
            AppLogger.info(Module.BLE, "ble-advertising started mode=${settingsInEffect?.mode}")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertisingStarted = true
                Log.w(TAG, "startAdvertising already started (code=3)")
                callback?.onMessageReceived("system", localNodeId, "advertise_already_started")
                return
            }

            advertisingStarted = false
            Log.e(TAG, "startAdvertising failed errorCode=$errorCode")
            AppLogger.error(Module.BLE, "ble-advertising failed errorCode=$errorCode")
            callback?.onConnectionStateChanged(ConnectionState.Error)
            callback?.onMessageReceived("system", localNodeId, "advertise_failed:$errorCode")
        }
    }

    fun setCallback(meshCallback: MeshCallback) {
        Log.d(TAG, "setCallback registered")
        callback = meshCallback
    }

    @SuppressLint("MissingPermission")
    fun start() {
        Log.d(TAG, "start called localNodeId=$localNodeId sdk=${Build.VERSION.SDK_INT}")
        AppLogger.info(Module.BLE, "ble-start localNodeId=$localNodeId")
        callback?.onConnectionStateChanged(ConnectionState.Discovering)

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter unavailable or disabled adapterNull=${adapter == null} enabled=${adapter?.isEnabled}")
            callback?.onConnectionStateChanged(ConnectionState.Error)
            return
        }

        Log.d(
            TAG,
            "Adapter capabilities: le2M=${adapter.isLe2MPhySupported} leCoded=${adapter.isLeCodedPhySupported} multiAdv=${adapter.isMultipleAdvertisementSupported}"
        )

        if (!isLocationEnabled()) {
            Log.e(TAG, "Location services OFF on pre-Android 12; scan blocked")
            callback?.onConnectionStateChanged(ConnectionState.Error)
            callback?.onMessageReceived("system", localNodeId, "location_services_off")
            return
        }

        if (!hasPermissions()) {
            Log.w(TAG, "Missing runtime permissions, requesting now")
            pendingStartAfterPermission = true
            requestPermissions()
            return
        }
        pendingStartAfterPermission = false

        discoveredPeers.clear()
        peerAddressById.clear()
        peerIdByAddress.clear()
        peerDeviceById.clear()
        sessionsByPeerId.clear()
        handshakeLocalKeys.clear()
        pendingControlByPeerId.clear()

        cancelAllRetryTimers()

        if (advertisingStarted) {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            advertisingStarted = false
        }

        ensureGattServer()

        val scanner = bleScanner
        if (scanner != null) {
            Log.d(TAG, "Starting filtered BLE scan with serviceUuid=$SERVICE_UUID")
            scanner.stopScan(scanCallback)
            unfilteredScanActive = false
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(filters, settings, scanCallback)
            scheduleFallbackUnfilteredScan(scanner)
        }
        startAdvertising()

        if (scanner == null) {
            Log.e(TAG, "BLE scanner unavailable")
            callback?.onConnectionStateChanged(ConnectionState.Error)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        Log.d(TAG, "stop called")
        AppLogger.info(Module.BLE, "ble-stop")
        if (hasPermissions()) {
            bleScanner?.stopScan(scanCallback)
            if (advertisingStarted) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
            }
        }

        cancelAllRetryTimers()
        stopPeerHealthLoops()
        reconnectRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        reconnectRunnables.clear()
        resetWireBuffers()

        clearGattClient()
        gattServer?.close()
        gattServer = null

        cancelFallbackScan()
        pendingStartAfterPermission = false
        advertisingStarted = false
        callback?.onConnectionStateChanged(ConnectionState.Disconnected)
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_REQUEST_CODE) return false

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Log.d(TAG, "onRequestPermissionsResult granted=$granted results=${grantResults.joinToString()}")
        if (granted && pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            start()
        } else if (!granted) {
            pendingStartAfterPermission = false
            callback?.onConnectionStateChanged(ConnectionState.Error)
            callback?.onMessageReceived("system", localNodeId, "permissions_denied")
        }
        return true
    }

    fun handshake(peerId: String) {
        if (!hasPermissions()) {
            callback?.onConnectionStateChanged(ConnectionState.Error)
            return
        }

        val address = peerAddressById[peerId] ?: peerId.takeIf { it.contains(':') }
        if (address == null) {
            Log.e(TAG, "handshake failed: no address mapping for peerId=$peerId")
            AppLogger.error(Module.BLE, "handshake failed no-address-mapping peerId=$peerId")
            callback?.onConnectionStateChanged(ConnectionState.Error)
            return
        }

        val existingReconnect = reconnectRunnables.remove(peerId)
        if (existingReconnect != null) {
            mainHandler.removeCallbacks(existingReconnect)
        }

        clearGattClient()
        connectedPeerId = peerId
        registerPeerAlias(peerId, address)

        updateSessionState(peerId, SessionSecurityState.Handshaking)
        callback?.onConnectionStateChanged(ConnectionState.Connecting)

        // Prefer the cached BluetoothDevice from ScanResult — this is the only safe way to
        // connect on Android 12+ when the peer uses a non-resolvable or resolvable private MAC.
        val device: BluetoothDevice? = peerDeviceById[peerId] ?: run {
            try {
                bluetoothAdapter?.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        if (device == null) {
            Log.e(TAG, "handshake failed: no cached device handle and getRemoteDevice failed for address=$address; trigger a new scan so the device handle is refreshed")
            AppLogger.error(Module.BLE, "handshake failed no-device-handle peerId=$peerId address=$address")
            callback?.onConnectionStateChanged(ConnectionState.Error)
            return
        }

        connectedGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(activity, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(activity, false, gattClientCallback)
        }
    }

    fun disconnectPeer(peerId: String) {
        if (connectedPeerId == peerId || connectedPeerId == null) {
            manualDisconnectPeers += peerId
            clearGattClient()
            stopPeerHealthLoops()
            callback?.onConnectionStateChanged(ConnectionState.Disconnected)
            updatePeerHealth(peerId, PeerHealthState.Unreachable, keepReconnectAttempts = true)
        }
    }

    fun sendMessage(destinationId: String, payload: String, ttl: Int = 8): Boolean {
        val activePeer = connectedPeerId
        if (activePeer == null || activePeer != destinationId) {
            Log.e(TAG, "sendMessage failed: no active gatt connection for destination=$destinationId")
            return false
        }

        val session = resolveSessionForPeer(destinationId)
        if (session == null || session.state != SessionSecurityState.Established) {
            Log.e(TAG, "sendMessage failed: secure session not established for peer=$destinationId")
            callback?.onMessageDeliveryUpdated(destinationId, "pending", DeliveryStatus.Failed, 0)
            return false
        }

        val messageId = "${localNodeId.take(8)}-${System.currentTimeMillis()}-${messageSequence++}"
        val outbound = OutboundMessage(
            messageId = messageId,
            peerId = destinationId,
            payload = payload,
            attempt = 0,
            timeoutRunnable = null
        )

        pendingOutbound[messageId] = outbound
        callback?.onMessageDeliveryUpdated(destinationId, messageId, DeliveryStatus.Queued, 0)
        dispatchOutbound(outbound)
        return true
    }

    private fun dispatchOutbound(outbound: OutboundMessage) {
        outbound.attempt += 1
        val session = sessionsByPeerId[outbound.peerId]
        if (session == null || session.state != SessionSecurityState.Established) {
            pendingOutbound.remove(outbound.messageId)
            callback?.onMessageDeliveryUpdated(outbound.peerId, outbound.messageId, DeliveryStatus.Failed, outbound.attempt)
            return
        }

        val encrypted = encryptPayload(session.key, outbound.payload)
        if (encrypted == null) {
            pendingOutbound.remove(outbound.messageId)
            callback?.onMessageDeliveryUpdated(outbound.peerId, outbound.messageId, DeliveryStatus.Failed, outbound.attempt)
            return
        }

        val queued = sendWireMessage(
            TYPE_DATA,
            outbound.messageId,
            outbound.attempt.toString(),
            encrypted.nonceBase64,
            encrypted.ciphertextBase64
        )

        if (!queued) {
            scheduleRetryOrTimeout(outbound)
            return
        }

        callback?.onMessageDeliveryUpdated(outbound.peerId, outbound.messageId, DeliveryStatus.Sent, outbound.attempt)
        scheduleAckTimeout(outbound)
    }

    private fun scheduleAckTimeout(outbound: OutboundMessage) {
        outbound.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            val current = pendingOutbound[outbound.messageId] ?: return@Runnable
            scheduleRetryOrTimeout(current)
        }
        outbound.timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, ACK_TIMEOUT_MS)
    }

    private fun scheduleRetryOrTimeout(outbound: OutboundMessage) {
        if (outbound.attempt < MAX_ACK_RETRIES) {
            callback?.onMessageDeliveryUpdated(outbound.peerId, outbound.messageId, DeliveryStatus.RetryScheduled, outbound.attempt)
            dispatchOutbound(outbound)
        } else {
            outbound.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingOutbound.remove(outbound.messageId)
            callback?.onMessageDeliveryUpdated(outbound.peerId, outbound.messageId, DeliveryStatus.TimedOut, outbound.attempt)
        }
    }

    private fun onAckReceived(messageId: String) {
        val delivered = pendingOutbound.remove(messageId) ?: return
        delivered.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        callback?.onMessageDeliveryUpdated(delivered.peerId, delivered.messageId, DeliveryStatus.Delivered, delivered.attempt)
        markPeerSeen(delivered.peerId)
    }

    private fun handleWireMessage(peerId: String, wirePayload: String) {
        val packet = decodeWireMessage(wirePayload)
        if (packet == null) {
            callback?.onMessageReceived(peerId, localNodeId, wirePayload)
            return
        }

        when (packet.type) {
            TYPE_KEY_EXCHANGE -> onKeyExchange(peerId, packet.parts)
            TYPE_KEY_ACK -> onKeyAck(peerId, packet.parts)
            TYPE_DATA -> onEncryptedData(peerId, packet.parts)
            TYPE_ACK -> {
                val messageId = packet.parts.firstOrNull() ?: return
                onAckReceived(messageId)
            }
            TYPE_PING -> onPing(peerId, packet.parts)
            TYPE_PONG -> onPong(peerId, packet.parts)
            else -> Log.w(TAG, "Unknown wire packet type=${packet.type}")
        }
    }

    private fun onKeyExchange(peerId: String, parts: List<String>) {
        val remotePublicKey = parts.firstOrNull() ?: return
        Log.d(TAG, "onKeyExchange peer=$peerId base64Len=${remotePublicKey.length} sessionState=${sessionsByPeerId[peerId]?.state}")
        val localKeyPair = generateEcKeyPair() ?: run {
            updateSessionState(peerId, SessionSecurityState.Failed)
            return
        }

        val key = deriveAesKey(localKeyPair, remotePublicKey, peerId) ?: run {
            if (sessionsByPeerId[peerId]?.state == SessionSecurityState.Established) {
                Log.w(TAG, "Ignoring malformed duplicate KEY for established session peer=$peerId")
                return
            }
            updateSessionState(peerId, SessionSecurityState.Failed)
            return
        }

        handshakeLocalKeys[peerId] = localKeyPair
        sessionsByPeerId[peerId] = SecureSession(
            key = key,
            state = SessionSecurityState.Established,
            establishedAtEpochMs = System.currentTimeMillis()
        )
        updateSessionState(peerId, SessionSecurityState.Established)

        val localPublicKey = encodeBase64(localKeyPair.public.encoded)
        AppLogger.info(Module.BLE, "key-exchange-received peer=$peerId -> session Established")
        val sent = sendWireMessage(TYPE_KEY_ACK, localPublicKey)
        if (!sent) {
            queueControlMessage(peerId, encodeWireMessage(TYPE_KEY_ACK, localPublicKey))
            Log.w(TAG, "KEY_ACK queued peer=$peerId waiting for outbound path")
            ensureOutboundPath(peerId)
        }
    }

    private fun onKeyAck(peerId: String, parts: List<String>) {
        val remotePublicKey = parts.firstOrNull() ?: return
        Log.d(TAG, "onKeyAck peer=$peerId base64Len=${remotePublicKey.length} hasLocalKey=${handshakeLocalKeys.containsKey(peerId)}")
        val localKeyPair = handshakeLocalKeys[peerId] ?: run {
            Log.w(TAG, "KEY_ACK ignored: missing local key pair for peer=$peerId")
            return
        }

        val key = deriveAesKey(localKeyPair, remotePublicKey, peerId) ?: run {
            if (sessionsByPeerId[peerId]?.state == SessionSecurityState.Established) {
                Log.w(TAG, "Ignoring malformed duplicate KEY_ACK for established session peer=$peerId")
                return
            }
            updateSessionState(peerId, SessionSecurityState.Failed)
            return
        }

        sessionsByPeerId[peerId] = SecureSession(
            key = key,
            state = SessionSecurityState.Established,
            establishedAtEpochMs = System.currentTimeMillis()
        )
        handshakeLocalKeys.remove(peerId)
        updateSessionState(peerId, SessionSecurityState.Established)
        AppLogger.info(Module.BLE, "key-ack-received peer=$peerId -> session Established")
    }

    private fun onEncryptedData(peerId: String, parts: List<String>) {
        if (parts.size < 4) {
            Log.w(TAG, "DATA packet malformed from peer=$peerId")
            return
        }

        val messageId = parts[0]
        val attempt = parts[1].toIntOrNull() ?: 0
        val nonce = parts[2]
        val ciphertext = parts[3]

        val session = sessionsByPeerId[peerId]
        if (session == null || session.state != SessionSecurityState.Established) {
            Log.e(TAG, "DATA packet dropped; secure session missing for peer=$peerId")
            updateSessionState(peerId, SessionSecurityState.Failed)
            return
        }

        val decrypted = decryptPayload(session.key, nonce, ciphertext)
        if (decrypted == null) {
            Log.e(TAG, "DATA decrypt failed peer=$peerId")
            AppLogger.error(Module.BLE, "decrypt-failed peer=$peerId")
            updateSessionState(peerId, SessionSecurityState.Failed)
            return
        }

        sendWireMessage(TYPE_ACK, messageId)
        callback?.onMessageDeliveryUpdated(peerId, messageId, DeliveryStatus.Delivered, attempt)
        callback?.onMessageReceived(peerId, localNodeId, decrypted)
        AppLogger.debug(Module.BLE, "msg-received peer=$peerId msgId=$messageId attempt=$attempt")
        markPeerSeen(peerId)
    }

    private fun onPing(peerId: String, parts: List<String>) {
        val sentAt = parts.firstOrNull() ?: System.currentTimeMillis().toString()
        val sent = sendWireMessage(TYPE_PONG, sentAt)
        if (!sent) {
            // GATT client may be briefly absent during reconnect. Queue the PONG so it
            // is flushed as soon as the outbound characteristic is restored.
            queueControlMessage(peerId, encodeWireMessage(TYPE_PONG, sentAt))
            Log.w(TAG, "PONG queued for peer=$peerId (no active write path)")
        }
        markPeerSeen(peerId)
    }

    private fun onPong(peerId: String, parts: List<String>) {
        val sentAt = parts.firstOrNull()?.toLongOrNull() ?: return
        val rtt = (System.currentTimeMillis() - sentAt).coerceAtLeast(0L)
        AppLogger.debug(Module.BLE, "ble-pong peer=$peerId rttMs=$rtt")
        // A PONG proves the link is alive — cancel any pending reconnect runnable so it
        // does not tear down a healthy GATT connection and trigger a reconnect storm.
        val pendingReconnect = reconnectRunnables.remove(peerId)
        if (pendingReconnect != null) {
            mainHandler.removeCallbacks(pendingReconnect)
            updatePeerHealth(peerId, PeerHealthState.Healthy, reconnectAttempts = 0)
            Log.d(TAG, "onPong: cancelled pending reconnect for peer=$peerId (link confirmed healthy)")
        }
        markPeerSeen(peerId, roundTripMs = rtt)
    }

    private fun initiateKeyExchange(peerId: String) {
        val localKeyPair = generateEcKeyPair() ?: run {
            updateSessionState(peerId, SessionSecurityState.Failed)
            return
        }

        clearPeerFragments(peerId, reason = "new_key_exchange")
        handshakeLocalKeys[peerId] = localKeyPair
        val publicKey = encodeBase64(localKeyPair.public.encoded)
        Log.d(TAG, "initiateKeyExchange peer=$peerId base64Len=${publicKey.length}")
        AppLogger.info(Module.BLE, "initiating-key-exchange peer=$peerId")
        val sent = sendWireMessage(TYPE_KEY_EXCHANGE, publicKey)
        if (!sent) {
            queueControlMessage(peerId, encodeWireMessage(TYPE_KEY_EXCHANGE, publicKey))
            ensureOutboundPath(peerId)
        }
    }

    private fun startPeerHealthLoops(peerId: String) {
        stopPeerHealthLoops()
        markPeerSeen(peerId)

        pingRunnable = Runnable {
            val activePeer = connectedPeerId
            if (activePeer == null || activePeer != peerId) return@Runnable

            val session = sessionsByPeerId[peerId]
            if (session?.state == SessionSecurityState.Established) {
                sendWireMessage(TYPE_PING, System.currentTimeMillis().toString())
            }
            mainHandler.postDelayed(pingRunnable!!, PING_INTERVAL_MS)
        }

        healthCheckRunnable = Runnable {
            val activePeer = connectedPeerId
            if (activePeer == null || activePeer != peerId) return@Runnable

            val now = System.currentTimeMillis()
            val current = peerHealth[peerId]
            val lastSeen = current?.lastSeenEpochMs ?: now
            val idle = now - lastSeen

            when {
                idle >= PEER_UNREACHABLE_MS -> {
                    updatePeerHealth(peerId, PeerHealthState.Unreachable, keepReconnectAttempts = true)
                    scheduleReconnect(peerId, reason = "heartbeat_timeout")
                }
                idle >= PEER_DEGRADED_MS -> updatePeerHealth(peerId, PeerHealthState.Degraded, keepReconnectAttempts = true)
                else -> updatePeerHealth(peerId, PeerHealthState.Healthy, keepReconnectAttempts = true)
            }

            mainHandler.postDelayed(healthCheckRunnable!!, HEALTH_CHECK_INTERVAL_MS)
        }

        mainHandler.postDelayed(pingRunnable!!, PING_INTERVAL_MS)
        mainHandler.postDelayed(healthCheckRunnable!!, HEALTH_CHECK_INTERVAL_MS)

        rssiPollRunnable = Runnable {
            val gatt = connectedGatt
            val activePeer = connectedPeerId
            if (gatt != null && activePeer == peerId) {
                @Suppress("MissingPermission")
                gatt.readRemoteRssi()
            }
            rssiPollRunnable?.let { mainHandler.postDelayed(it, RSSI_POLL_INTERVAL_MS) }
        }
        mainHandler.postDelayed(rssiPollRunnable!!, RSSI_POLL_INTERVAL_MS)
    }

    private fun stopPeerHealthLoops() {
        pingRunnable?.let { mainHandler.removeCallbacks(it) }
        healthCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        rssiPollRunnable?.let { mainHandler.removeCallbacks(it) }
        pingRunnable = null
        healthCheckRunnable = null
        rssiPollRunnable = null
    }

    private fun scheduleReconnect(peerId: String, reason: String) {
        if (reconnectRunnables.containsKey(peerId)) return

        val current = peerHealth[peerId]
        val nextAttempt = (current?.reconnectAttempts ?: 0) + 1
        if (nextAttempt > MAX_RECONNECT_ATTEMPTS) {
            updatePeerHealth(peerId, PeerHealthState.Unreachable, reconnectAttempts = nextAttempt)
            callback?.onMessageReceived("system", localNodeId, "reconnect_failed:$peerId reason=$reason")
            return
        }

        updatePeerHealth(peerId, PeerHealthState.Recovering, reconnectAttempts = nextAttempt)

        val delay = RECONNECT_BASE_DELAY_MS * nextAttempt
        AppLogger.warn(Module.BLE, "ble-reconnect-scheduled peer=$peerId attempt=$nextAttempt/$MAX_RECONNECT_ATTEMPTS delayMs=$delay reason=$reason")
        val runnable = Runnable {
            reconnectRunnables.remove(peerId)
            if (connectedPeerId == peerId) return@Runnable
            callback?.onMessageReceived("system", localNodeId, "reconnect_attempt:$peerId #$nextAttempt reason=$reason")
            handshake(peerId)
        }
        reconnectRunnables[peerId] = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun resetReconnectAttempts(peerId: String) {
        updatePeerHealth(peerId, PeerHealthState.Healthy, reconnectAttempts = 0)
    }

    private fun markPeerSeen(peerId: String, roundTripMs: Long? = null) {
        val existing = peerHealth[peerId]
        val state = when (existing?.state) {
            PeerHealthState.Recovering, PeerHealthState.Unreachable, PeerHealthState.Degraded -> PeerHealthState.Healthy
            else -> existing?.state ?: PeerHealthState.Healthy
        }

        val updated = PeerHealth(
            peerId = peerId,
            state = state,
            reconnectAttempts = existing?.reconnectAttempts ?: 0,
            lastSeenEpochMs = System.currentTimeMillis(),
            roundTripMs = roundTripMs ?: existing?.roundTripMs
        )
        peerHealth[peerId] = updated
        callback?.onPeerHealthUpdated(updated)
    }

    private fun updatePeerHealth(
        peerId: String,
        state: PeerHealthState,
        reconnectAttempts: Int? = null,
        keepReconnectAttempts: Boolean = false
    ) {
        val existing = peerHealth[peerId]
        val attempts = when {
            reconnectAttempts != null -> reconnectAttempts
            keepReconnectAttempts -> existing?.reconnectAttempts ?: 0
            else -> 0
        }

        val updated = PeerHealth(
            peerId = peerId,
            state = state,
            reconnectAttempts = attempts,
            lastSeenEpochMs = existing?.lastSeenEpochMs ?: System.currentTimeMillis(),
            roundTripMs = existing?.roundTripMs
        )
        peerHealth[peerId] = updated
        callback?.onPeerHealthUpdated(updated)
    }

    private fun updateSessionState(peerId: String, state: SessionSecurityState) {
        val existing = sessionsByPeerId[peerId]
        if (existing != null && existing.state != state) {
            sessionsByPeerId[peerId] = existing.copy(state = state)
        } else if (existing == null && state == SessionSecurityState.NotEstablished) {
            sessionsByPeerId.remove(peerId)
        }
        callback?.onSessionSecurityStateChanged(peerId, state)
    }

    private data class Packet(
        val type: String,
        val parts: List<String>
    )

    private data class EncryptedPayload(
        val nonceBase64: String,
        val ciphertextBase64: String
    )

    private fun encodeWireMessage(type: String, vararg parts: String): String {
        val encodedParts = parts.map { escapePart(it) }
        return listOf(PROTOCOL_PREFIX, type).plus(encodedParts).joinToString("|")
    }

    private fun decodeWireMessage(payload: String): Packet? {
        val tokens = payload.split('|')
        if (tokens.size < 2 || tokens[0] != PROTOCOL_PREFIX) return null
        val type = tokens[1]
        val parts = tokens.drop(2).map { unescapePart(it) }
        return Packet(type, parts)
    }

    private fun sendWireMessage(type: String, vararg parts: String): Boolean {
        val gatt = connectedGatt
        val characteristic = writeCharacteristic

        if (gatt == null || characteristic == null) {
            Log.e(TAG, "sendWireMessage failed: no active write characteristic for type=$type")
            return false
        }

        val encoded = encodeWireMessage(type, *parts)
        enqueueWirePayload(encoded)
        Log.d(TAG, "sendWireMessage type=$type enqueued=true")
        return true
    }

    private fun sendPreEncodedWire(encoded: String): Boolean {
        val gatt = connectedGatt
        val characteristic = writeCharacteristic

        if (gatt == null || characteristic == null) {
            return false
        }

        enqueueWirePayload(encoded)
        return true
    }

    private fun handleIncomingFrame(peerId: String, payload: String) {
        cleanupStaleFragments()
        val chunk = decodeChunkFrame(payload)
        if (chunk == null) {
            markPeerSeen(peerId)
            handleWireMessage(peerId, payload)
            return
        }

        val key = "$peerId:${chunk.fragmentId}"
        val existing = inboundFragments[key]
        val current = when {
            existing == null -> {
                Log.d(TAG, "fragment start peer=$peerId fragment=${chunk.fragmentId} total=${chunk.total}")
                InboundFragmentBuffer(
                    expectedParts = chunk.total,
                    parts = mutableMapOf(),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }

            chunk.index == 0 -> {
                Log.w(
                    TAG,
                    "fragment restart peer=$peerId fragment=${chunk.fragmentId} previousParts=${existing.parts.keys.sorted()} total=${chunk.total}"
                )
                InboundFragmentBuffer(
                    expectedParts = chunk.total,
                    parts = mutableMapOf(),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }

            existing.expectedParts != chunk.total -> {
                Log.w(
                    TAG,
                    "fragment total mismatch peer=$peerId fragment=${chunk.fragmentId} expected=${existing.expectedParts} actual=${chunk.total}; resetting"
                )
                InboundFragmentBuffer(
                    expectedParts = chunk.total,
                    parts = mutableMapOf(),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }

            else -> existing
        }
        current.parts[chunk.index] = chunk.data
        current.updatedAtEpochMs = System.currentTimeMillis()
        inboundFragments[key] = current
        Log.v(TAG, "fragment part peer=$peerId fragment=${chunk.fragmentId} index=${chunk.index}/${chunk.total - 1} collected=${current.parts.size}")

        if (current.parts.size == current.expectedParts) {
            val combined = buildString {
                for (idx in 0 until current.expectedParts) {
                    append(current.parts[idx].orEmpty())
                }
            }
            inboundFragments.remove(key)
            Log.d(TAG, "fragment complete peer=$peerId fragment=${chunk.fragmentId} payloadLen=${combined.length}")
            markPeerSeen(peerId)
            handleWireMessage(peerId, combined)
        }
    }

    private fun enqueueWirePayload(payload: String) {
        val chunks = encodeChunks(payload)
        chunks.forEach { outboundWriteQueue.addLast(it) }
        drainWriteQueue()
    }

    private fun drainWriteQueue() {
        if (writeInFlight) return
        if (outboundWriteQueue.isEmpty()) return

        val gatt = connectedGatt ?: return
        val characteristic = writeCharacteristic ?: return

        val next = outboundWriteQueue.first()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = next.toByteArray(StandardCharsets.UTF_8)
        val queued = gatt.writeCharacteristic(characteristic)
        if (queued) {
            outboundWriteQueue.removeFirst()
            writeInFlight = true
        } else {
            Log.e(TAG, "drainWriteQueue failed to queue write")
        }
    }

    private data class FragmentChunk(
        val fragmentId: String,
        val index: Int,
        val total: Int,
        val data: String
    )

    private fun encodeChunks(payload: String): List<String> {
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val chunkDataSize = (blePayloadBytes - CHUNK_HEADER_BYTES).coerceAtLeast(4)
        if (payloadBytes.size <= blePayloadBytes) {
            return listOf(payload)
        }

        val fragmentId = fragmentIdToken()
        val total = ((payload.length + chunkDataSize - 1) / chunkDataSize)
            .coerceAtMost(35)

        val chunks = mutableListOf<String>()
        for (idx in 0 until total) {
            val start = idx * chunkDataSize
            val end = (start + chunkDataSize).coerceAtMost(payload.length)
            val part = payload.substring(start, end)
            val frame = "~$fragmentId${base36(idx)}${base36(total)}$part"
            chunks += frame
        }
        return chunks
    }

    private fun decodeChunkFrame(payload: String): FragmentChunk? {
        if (payload.length < CHUNK_HEADER_BYTES || payload[0] != '~') return null

        val fragmentId = payload.substring(1, 3)
        val index = fromBase36(payload[3]) ?: return null
        val total = fromBase36(payload[4]) ?: return null
        if (total <= 0 || index < 0 || index >= total) return null

        val data = payload.substring(5)
        return FragmentChunk(fragmentId = fragmentId, index = index, total = total, data = data)
    }

    private fun fragmentIdToken(): String {
        fragmentSequence = (fragmentSequence + 1) % (36 * 36)
        val hi = fragmentSequence / 36
        val lo = fragmentSequence % 36
        return "${base36(hi)}${base36(lo)}"
    }

    private fun base36(value: Int): Char {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return alphabet[value.coerceIn(0, 35)]
    }

    private fun fromBase36(value: Char): Int? {
        val normalized = value.uppercaseChar()
        return when {
            normalized in '0'..'9' -> normalized - '0'
            normalized in 'A'..'Z' -> 10 + (normalized - 'A')
            else -> null
        }
    }

    private fun cleanupStaleFragments() {
        val now = System.currentTimeMillis()
        val iterator = inboundFragments.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.updatedAtEpochMs > FRAGMENT_TTL_MS) {
                Log.w(TAG, "dropping stale fragment key=${entry.key} parts=${entry.value.parts.keys.sorted()}")
                iterator.remove()
            }
        }
    }

    private fun clearPeerFragments(peerId: String, reason: String) {
        val prefix = "$peerId:"
        val keysToRemove = inboundFragments.keys.filter { it.startsWith(prefix) }
        if (keysToRemove.isNotEmpty()) {
            Log.w(TAG, "clearing ${keysToRemove.size} fragment buffers for peer=$peerId reason=$reason")
            keysToRemove.forEach { inboundFragments.remove(it) }
        }
    }

    private fun resetWireBuffers() {
        outboundWriteQueue.clear()
        inboundFragments.clear()
        pendingControlByPeerId.clear()
        writeInFlight = false
    }

    private fun escapePart(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")

    private fun unescapePart(value: String): String = value
        .replace("%7C", "|")
        .replace("%25", "%")

    private fun generateEcKeyPair(): KeyPair? {
        return try {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(256)
            generator.generateKeyPair()
        } catch (e: Exception) {
            Log.e(TAG, "Failed generating EC key pair", e)
            null
        }
    }

    private fun deriveAesKey(localKeyPair: KeyPair, remotePublicKeyBase64: String, peerId: String): SecretKeySpec? {
        return try {
            val remoteBytes = decodeBase64(remotePublicKeyBase64)
            Log.d(TAG, "deriveAesKey peer=$peerId base64Len=${remotePublicKeyBase64.length} decodedLen=${remoteBytes.size}")
            val keyFactory = KeyFactory.getInstance("EC")
            val remotePublic = keyFactory.generatePublic(X509EncodedKeySpec(remoteBytes))

            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(localKeyPair.private)
            agreement.doPhase(remotePublic, true)
            val sharedSecret = agreement.generateSecret()

            val digest = MessageDigest.getInstance("SHA-256")
            val salt = listOf(localNodeId, peerId).sorted().joinToString("|")
            digest.update(sharedSecret)
            digest.update(salt.toByteArray(StandardCharsets.UTF_8))
            val keyBytes = digest.digest().copyOf(16)

            SecretKeySpec(keyBytes, "AES")
        } catch (e: Exception) {
            Log.e(TAG, "Failed deriving AES key peer=$peerId base64Len=${remotePublicKeyBase64.length}", e)
            null
        }
    }

    private fun encryptPayload(key: SecretKeySpec, plaintext: String): EncryptedPayload? {
        return try {
            val nonce = ByteArray(12)
            secureRandom.nextBytes(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            EncryptedPayload(encodeBase64(nonce), encodeBase64(encrypted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed encrypting payload", e)
            null
        }
    }

    private fun decryptPayload(key: SecretKeySpec, nonceBase64: String, ciphertextBase64: String): String? {
        return try {
            val nonce = decodeBase64(nonceBase64)
            val encrypted = decodeBase64(ciphertextBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            val plaintext = cipher.doFinal(encrypted)
            plaintext.toString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed decrypting payload", e)
            null
        }
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private fun resolvePeerId(address: String): String {
        if (address.isBlank()) return "peer"
        peerIdByAddress[address]?.let { return it }

        val activePeer = connectedPeerId
        if (!activePeer.isNullOrBlank()) {
            val knownAddress = peerAddressById[activePeer]
            if (knownAddress == null || knownAddress == address) {
                registerPeerAlias(activePeer, address)
                return activePeer
            }
        }

        if (peerAddressById.size == 1) {
            val aliasPeer = peerAddressById.keys.first()
            registerPeerAlias(aliasPeer, address)
            return aliasPeer
        }

        return address
    }

    private fun resolveSessionForPeer(peerId: String): SecureSession? {
        sessionsByPeerId[peerId]?.let { return it }
        val address = peerAddressById[peerId] ?: return null
        val aliased = sessionsByPeerId[address] ?: return null
        sessionsByPeerId[peerId] = aliased
        return aliased
    }

    private fun registerPeerAlias(peerId: String, address: String) {
        peerAddressById[peerId] = address
        peerIdByAddress[address] = peerId

        val addressSession = sessionsByPeerId[address]
        if (addressSession != null && sessionsByPeerId[peerId] == null) {
            sessionsByPeerId[peerId] = addressSession
        }
    }

    private fun queueControlMessage(peerId: String, wire: String) {
        val queue = pendingControlByPeerId.getOrPut(peerId) { mutableListOf() }
        queue += wire
    }

    private fun flushPendingControlMessages(peerId: String) {
        val queue = pendingControlByPeerId[peerId] ?: return
        val stillPending = mutableListOf<String>()
        queue.forEach { wire ->
            if (!sendPreEncodedWire(wire)) {
                stillPending += wire
            }
        }

        if (stillPending.isEmpty()) {
            pendingControlByPeerId.remove(peerId)
        } else {
            pendingControlByPeerId[peerId] = stillPending
        }
    }

    private fun ensureOutboundPath(peerId: String) {
        val activePeer = connectedPeerId
        val hasWriter = connectedGatt != null && writeCharacteristic != null
        if (hasWriter && activePeer == peerId) {
            flushPendingControlMessages(peerId)
            return
        }

        if (activePeer != peerId) {
            scheduleReconnect(peerId, reason = "control_channel_needed")
        }
    }

    private fun cancelAllRetryTimers() {
        pendingOutbound.values.forEach { message ->
            message.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            message.timeoutRunnable = null
        }
        pendingOutbound.clear()
    }

    private fun hasPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            permissions += Manifest.permission.BLUETOOTH
            permissions += Manifest.permission.BLUETOOTH_ADMIN
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        val denied = permissions.filter { permission ->
            activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            Log.w(TAG, "Permissions denied: ${denied.joinToString()}")
        }
        return denied.isEmpty()
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        Log.d(TAG, "requestPermissions ${permissions.joinToString()}")
        activity.requestPermissions(permissions, PERMISSION_REQUEST_CODE)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            Log.d(TAG, "isLocationEnabled=$enabled")
            enabled
        } catch (_: Exception) {
            Log.e(TAG, "Failed reading location provider state")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleFallbackUnfilteredScan(scanner: BluetoothLeScanner) {
        cancelFallbackScan()
        Log.d(TAG, "Scheduling fallback unfiltered scan in ${FALLBACK_SCAN_DELAY_MS}ms")
        scanFallbackRunnable = Runnable {
            if (discoveredPeers.isNotEmpty()) return@Runnable
            scanner.stopScan(scanCallback)
            unfilteredScanActive = true
            scanner.startScan(scanCallback)
            Log.w(TAG, "Fallback to unfiltered BLE scan due to no peers")
            callback?.onMessageReceived("system", localNodeId, "scan_fallback_unfiltered")
        }
        mainHandler.postDelayed(scanFallbackRunnable!!, FALLBACK_SCAN_DELAY_MS)
    }

    private fun cancelFallbackScan() {
        scanFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        scanFallbackRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun ensureGattServer() {
        if (gattServer != null) return

        val manager = bluetoothManager
        if (manager == null) {
            Log.e(TAG, "BluetoothManager unavailable; cannot open GATT server")
            return
        }

        gattServer = manager.openGattServer(activity, gattServerCallback)
        val server = gattServer
        if (server == null) {
            Log.e(TAG, "openGattServer returned null")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageCharacteristic)
        val added = server.addService(service)
        Log.d(TAG, "GATT server service added=$added")
    }

    @SuppressLint("MissingPermission")
    private fun clearGattClient() {
        val timeout = mtuTimeoutRunnable
        if (timeout != null) {
            mainHandler.removeCallbacks(timeout)
            mtuTimeoutRunnable = null
        }
        blePayloadBytes = BLE_SAFE_CHUNK_BYTES
        try {
            connectedGatt?.disconnect()
            connectedGatt?.close()
        } catch (_: Exception) {
            // Ignore cleanup failures.
        }
        connectedGatt = null
        writeCharacteristic = null
        connectedPeerId = null
        resetWireBuffers()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertiser = bleAdvertiser ?: return
        Log.d(TAG, "startAdvertising requested")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val idBytes = localNodeId.take(16).toByteArray(StandardCharsets.UTF_8)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), idBytes)
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }
}
