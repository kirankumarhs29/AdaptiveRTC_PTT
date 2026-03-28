package com.netsense.mesh

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import net.sense.mesh.MeshCallback

class MeshManager(private val activity: Activity, private val localNodeId: String) : BleManager.BleListener {
    private val netSenseMesh = NetSenseMesh(localNodeId)
    private val bleManager = BleManager(activity.applicationContext, this)
    private var callback: MeshCallback? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1008
    }

    fun start() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }
        bleManager.startDiscovery()
        bleManager.startAdvertising(localNodeId)
    }

    fun stop() {
        bleManager.stopDiscovery()
        bleManager.stopAdvertising()
        netSenseMesh.shutdown()
    }

    private fun hasPermissions(): Boolean {
        return (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onPeerFound(peerId: String, peerName: String, rssi: Int) {
        netSenseMesh.peerDiscovered(peerId, peerName, rssi)
        netSenseMesh.handshake(peerId)
    }

    override fun onAdvertiseStarted() {
        // no-op or log status
    }

    override fun onAdvertiseFailed(errorCode: Int) {
        // handle advertise errors
    }

    fun setCallback(meshCallback: net.sense.mesh.MeshCallback) {
        callback = meshCallback
        netSenseMesh.setCallback(meshCallback)
    }

    override fun onPeerFound(peerId: String, peerName: String, rssi: Int) {
        // Discover in C++ engine; C++ will callback through MeshCallback when registered.
        netSenseMesh.peerDiscovered(peerId, peerName, rssi)
        netSenseMesh.handshake(peerId)
    }

    fun sendMessage(destinationId: String, payload: String, ttl: Int = 8): Boolean {
        val sent = netSenseMesh.sendMessage(destinationId, payload, ttl)
        return sent
    }

    fun receiveMessage(sourceId: String, destinationId: String, payload: String) {
        callback?.onMessageReceived(net.sense.mesh.MeshMessage(sourceId, destinationId, payload, 0))
        netSenseMesh.receiveMessage(sourceId, destinationId, payload)
    }
}

