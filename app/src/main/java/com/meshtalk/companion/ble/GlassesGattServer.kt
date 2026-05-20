package com.meshtalk.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE GATT Server for MeshTalk glasses connection.
 *
 * The phone acts as a GATT server — glasses connect to us.
 * Supports MULTIPLE simultaneous glasses connections.
 *
 * Protocol:
 *   Service UUID: 6ba1b218-15a8-461f-9fa8-5dcae273ea00
 *   ea01 — Audio TX from glasses (Write): glasses push Opus frames here
 *   ea02 — Audio RX to glasses (Notify): we push mesh audio here
 *   ea03 — Control (Read/Write/Notify): bidirectional JSON control
 *   ea04 — Status (Read/Notify): we broadcast status JSON
 */
@SuppressLint("MissingPermission")
class GlassesGattServer(
    private val context: Context,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "GattServer"

        val SERVICE_UUID: UUID       = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea00")
        val CHAR_AUDIO_TX: UUID      = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea01")
        val CHAR_AUDIO_RX: UUID      = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea02")
        val CHAR_CONTROL: UUID       = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea03")
        val CHAR_STATUS: UUID        = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea04")

        // Standard CCCD for notifications
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onGlassesConnected(device: BluetoothDevice)
        fun onGlassesDisconnected(device: BluetoothDevice)
        fun onAudioFromGlasses(device: BluetoothDevice, data: ByteArray)
        fun onControlMessage(device: BluetoothDevice, json: String)
        fun onLog(msg: String)
    }

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var audioRxChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    /** All currently connected glasses, keyed by MAC address */
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    /** Per-device notification subscriptions: MAC → set of characteristic UUIDs with notifications enabled */
    private val deviceNotifications = ConcurrentHashMap<String, MutableSet<UUID>>()

    fun start(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            listener.onLog("ERROR: Bluetooth not available")
            return false
        }

        // Open GATT server
        gattServer = bluetoothManager?.openGattServer(context, gattCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            listener.onLog("ERROR: Failed to open GATT server")
            return false
        }

        // Build service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // ea01: Audio from glasses → Write
        val audioTx = BluetoothGattCharacteristic(
            CHAR_AUDIO_TX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(audioTx)

        // ea02: Audio to glasses → Notify
        audioRxChar = BluetoothGattCharacteristic(
            CHAR_AUDIO_RX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        audioRxChar!!.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))
        service.addCharacteristic(audioRxChar!!)

        // ea03: Control → Read/Write/Notify
        val control = BluetoothGattCharacteristic(
            CHAR_CONTROL,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        control.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))
        service.addCharacteristic(control)

        // ea04: Status → Read/Notify
        statusChar = BluetoothGattCharacteristic(
            CHAR_STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        statusChar!!.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))
        service.addCharacteristic(statusChar!!)

        gattServer?.addService(service)

        // Start BLE advertising
        startAdvertising(adapter)

        Log.i(TAG, "GATT server started, advertising MeshTalk service")
        listener.onLog("BLE GATT server started")
        return true
    }

    fun stop() {
        stopAdvertising()
        // Disconnect all connected devices
        for ((_, device) in connectedDevices) {
            gattServer?.cancelConnection(device)
        }
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        deviceNotifications.clear()
        Log.i(TAG, "GATT server stopped")
        listener.onLog("BLE GATT server stopped")
    }

    /**
     * Send an Opus audio frame to ALL connected glasses via notification on ea02.
     * Returns the number of devices the frame was sent to.
     */
    fun sendAudioToGlasses(opusFrame: ByteArray): Int {
        return sendAudioToGlasses(opusFrame, excludeDevice = null)
    }

    /**
     * Send an Opus audio frame to all connected glasses EXCEPT [excludeDevice].
     * This prevents echo: audio from Glass A is forwarded to Glass B (and vice versa)
     * but not back to Glass A.
     *
     * @param opusFrame  The Opus-encoded audio frame to send
     * @param excludeDevice  MAC address of the device to exclude (typically the sender), or null to send to all
     * @return the number of devices the frame was actually sent to
     */
    fun sendAudioToGlasses(opusFrame: ByteArray, excludeDevice: String?): Int {
        val char = audioRxChar ?: return 0
        val server = gattServer ?: return 0
        var sentCount = 0

        for ((mac, device) in connectedDevices) {
            // Skip the excluded device (anti-echo)
            if (mac == excludeDevice) continue

            // Only send if this device has notifications enabled for audio RX
            val notifications = deviceNotifications[mac] ?: continue
            if (CHAR_AUDIO_RX !in notifications) continue

            char.value = opusFrame
            val sent = server.notifyCharacteristicChanged(device, char, false)
            if (sent) sentCount++
        }
        return sentCount
    }

    /**
     * Send a status JSON to ALL connected glasses via notification on ea04.
     * Returns the number of devices the status was sent to.
     */
    fun sendStatus(json: String): Int {
        val char = statusChar ?: return 0
        val server = gattServer ?: return 0
        val data = json.toByteArray(Charsets.UTF_8)
        var sentCount = 0

        for ((mac, device) in connectedDevices) {
            val notifications = deviceNotifications[mac] ?: continue
            if (CHAR_STATUS !in notifications) continue

            char.value = data
            val sent = server.notifyCharacteristicChanged(device, char, false)
            if (sent) sentCount++
        }
        return sentCount
    }

    /**
     * Send an Opus audio frame to a SINGLE glass identified by [mac].
     * Used by per-glass mesh routing: audio arriving on WS-B is destined
     * only for the glass whose MAC corresponds to WS-B.
     *
     * @param mac  BLE MAC address of the target glass
     * @param opusFrame  The Opus-encoded audio frame to send
     * @return 1 if sent successfully, 0 if device not found or notifications not enabled
     */
    fun sendAudioToSingleGlass(mac: String, opusFrame: ByteArray): Int {
        val char = audioRxChar ?: return 0
        val server = gattServer ?: return 0
        val device = connectedDevices[mac] ?: return 0

        val notifications = deviceNotifications[mac] ?: return 0
        if (CHAR_AUDIO_RX !in notifications) return 0

        char.value = opusFrame
        return if (server.notifyCharacteristicChanged(device, char, false)) 1 else 0
    }

    /** True if at least one pair of glasses is connected */
    val isGlassesConnected: Boolean get() = connectedDevices.isNotEmpty()

    /** Number of currently connected glasses */
    val connectedDeviceCount: Int get() = connectedDevices.size

    /** Snapshot of currently connected device MAC addresses */
    val connectedDeviceAddresses: Set<String> get() = connectedDevices.keys.toSet()

    // ── Advertising ──────────────────────────────────────────────

    private fun startAdvertising(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            listener.onLog("WARN: BLE advertising not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE advertising started")
            listener.onLog("BLE advertising active")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
            listener.onLog("BLE advertising failed (error $errorCode)")
        }
    }

    // ── GATT Callback ────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val mac = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Glasses connected: $mac (total: ${connectedDevices.size + 1})")
                    connectedDevices[mac] = device
                    deviceNotifications[mac] = ConcurrentHashMap.newKeySet()
                    listener.onGlassesConnected(device)
                    listener.onLog("Glasses connected: $mac (${connectedDevices.size} total)")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Glasses disconnected: $mac (remaining: ${connectedDevices.size - 1})")
                    connectedDevices.remove(mac)
                    deviceNotifications.remove(mac)
                    listener.onGlassesDisconnected(device)
                    listener.onLog("Glasses disconnected: $mac (${connectedDevices.size} remaining)")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                CHAR_AUDIO_TX -> {
                    // Opus audio frame from glasses — pass device so caller knows the source
                    listener.onAudioFromGlasses(device, value)
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                CHAR_CONTROL -> {
                    // Control message (JSON) — pass device so caller knows the source
                    val json = String(value, Charsets.UTF_8)
                    listener.onControlMessage(device, json)
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val mac = device.address
                val charUuid = descriptor.characteristic.uuid
                // Ensure we have a notification set for this device
                val notifications = deviceNotifications.getOrPut(mac) {
                    ConcurrentHashMap.newKeySet()
                }
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    notifications.add(charUuid)
                    Log.d(TAG, "Notifications enabled for $charUuid on device $mac")
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    notifications.remove(charUuid)
                    Log.d(TAG, "Notifications disabled for $charUuid on device $mac")
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val mac = device.address
                val charUuid = descriptor.characteristic.uuid
                val notifications = deviceNotifications[mac]
                val value = if (notifications != null && charUuid in notifications) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.i(TAG, "MTU changed to $mtu for device ${device?.address}")
            listener.onLog("MTU: $mtu (${device?.address})")
        }
    }
}
