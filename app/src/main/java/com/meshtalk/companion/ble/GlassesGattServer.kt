package com.meshtalk.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE GATT Server for MeshTalk glasses connection.
 *
 * The phone acts as a GATT server — glasses connect to us.
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
        fun onAudioFromGlasses(data: ByteArray)
        fun onControlMessage(json: String)
        fun onLog(msg: String)
    }

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var audioRxChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    // Track which characteristics have notifications enabled
    private val notificationsEnabled = mutableSetOf<UUID>()

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
        connectedDevice?.let {
            gattServer?.cancelConnection(it)
        }
        gattServer?.close()
        gattServer = null
        connectedDevice = null
        notificationsEnabled.clear()
        Log.i(TAG, "GATT server stopped")
        listener.onLog("BLE GATT server stopped")
    }

    /** Send an Opus audio frame to the connected glasses via notification on ea02 */
    fun sendAudioToGlasses(opusFrame: ByteArray): Boolean {
        val device = connectedDevice ?: return false
        val char = audioRxChar ?: return false
        if (CHAR_AUDIO_RX !in notificationsEnabled) return false

        char.value = opusFrame
        return gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
    }

    /** Send a status JSON to the connected glasses via notification on ea04 */
    fun sendStatus(json: String): Boolean {
        val device = connectedDevice ?: return false
        val char = statusChar ?: return false
        if (CHAR_STATUS !in notificationsEnabled) return false

        char.value = json.toByteArray(Charsets.UTF_8)
        return gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
    }

    val isGlassesConnected: Boolean get() = connectedDevice != null

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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Glasses connected: ${device.address}")
                    connectedDevice = device
                    listener.onGlassesConnected(device)
                    listener.onLog("Glasses connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Glasses disconnected: ${device.address}")
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        notificationsEnabled.clear()
                    }
                    listener.onGlassesDisconnected(device)
                    listener.onLog("Glasses disconnected")
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
                    // Opus audio frame from glasses
                    listener.onAudioFromGlasses(value)
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                CHAR_CONTROL -> {
                    // Control message (JSON)
                    val json = String(value, Charsets.UTF_8)
                    listener.onControlMessage(json)
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
                val charUuid = descriptor.characteristic.uuid
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    notificationsEnabled.add(charUuid)
                    Log.d(TAG, "Notifications enabled for $charUuid")
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    notificationsEnabled.remove(charUuid)
                    Log.d(TAG, "Notifications disabled for $charUuid")
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
                val charUuid = descriptor.characteristic.uuid
                val value = if (charUuid in notificationsEnabled) {
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
            Log.i(TAG, "MTU changed to $mtu")
            listener.onLog("MTU: $mtu")
        }
    }
}
