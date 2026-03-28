package com.netsense.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context

class BleManager(private val context: Context, private val listener: BleListener) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    interface BleListener {
        fun onPeerFound(peerId: String, peerName: String, rssi: Int)
        fun onAdvertiseStarted()
        fun onAdvertiseFailed(errorCode: Int)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val peerId = device.address ?: "unknown"
            val peerName = device.name ?: "NetSensePeer"
            listener.onPeerFound(peerId, peerName, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            // handle scan failure in production
        }
    }

    fun startDiscovery() {
        bluetoothAdapter ?: return
        if (!bluetoothAdapter.isEnabled) return
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    fun stopDiscovery() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun startAdvertising(localNodeId: String) {
        advertiser ?: run {
            listener.onAdvertiseFailed(-1)
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid( android.os.ParcelUuid.fromString("0000180D-0000-1000-8000-00805f9b34fb"))
            .addServiceData(android.os.ParcelUuid.fromString("0000180D-0000-1000-8000-00805f9b34fb"), localNodeId.toByteArray())
            .build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                listener.onAdvertiseStarted()
            }

            override fun onStartFailure(errorCode: Int) {
                listener.onAdvertiseFailed(errorCode)
            }
        })
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
    }
}
