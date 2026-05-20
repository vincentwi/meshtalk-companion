package com.meshtalk.companion.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meshtalk.companion.CompanionApp
import com.meshtalk.companion.MainActivity
import com.meshtalk.companion.R
import com.meshtalk.companion.ble.GlassesGattServer
import com.meshtalk.companion.mesh.AudioRelay
import com.meshtalk.companion.mesh.MeshDiscovery
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs the BLE GATT server and mesh discovery.
 *
 * Lifecycle:
 *   startService → onCreate → onStartCommand → startForeground → start BLE + mesh
 *   stopService → onDestroy → stop BLE + mesh
 */
@SuppressLint("MissingPermission")
class CompanionService : Service() {

    companion object {
        private const val TAG = "CompanionService"
    }

    private var gattServer: GlassesGattServer? = null
    private var meshDiscovery: MeshDiscovery? = null
    private var audioRelay: AudioRelay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var statusTask: ScheduledFuture<*>? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var glassesConnected = false
    private var currentChannel = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        startForeground(CompanionApp.NOTIFICATION_ID, buildNotification("Starting..."))
        acquireWakeLock()
        startComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopComponents()
        releaseWakeLock()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun startComponents() {
        // Create GATT server
        val gatt = GlassesGattServer(this, gattListener)
        gattServer = gatt

        // Create mesh discovery
        val mesh = MeshDiscovery(this, meshListener)
        meshDiscovery = mesh

        // Create audio relay
        audioRelay = AudioRelay(gatt, mesh)

        // Start components
        val bleOk = gatt.start()
        val meshOk = mesh.start()

        sendLog("BLE: ${if (bleOk) "OK" else "FAILED"} | Mesh: ${if (meshOk) "OK" else "FAILED"}")

        // Periodic status broadcast
        statusTask = scheduler.scheduleAtFixedRate({
            broadcastStatus()
        }, 1, 2, TimeUnit.SECONDS)
    }

    private fun stopComponents() {
        statusTask?.cancel(false)
        gattServer?.stop()
        meshDiscovery?.stop()
        gattServer = null
        meshDiscovery = null
        audioRelay = null
    }

    // ── GATT Listener ────────────────────────────────────────────

    private val gattListener = object : GlassesGattServer.Listener {
        override fun onGlassesConnected(device: BluetoothDevice) {
            glassesConnected = true
            updateNotification("Glasses connected: ${device.address}")
            broadcastStatus()
        }

        override fun onGlassesDisconnected(device: BluetoothDevice) {
            glassesConnected = false
            updateNotification("Waiting for glasses...")
            broadcastStatus()
        }

        override fun onAudioFromGlasses(data: ByteArray) {
            audioRelay?.onAudioFromGlasses(data)
        }

        override fun onControlMessage(json: String) {
            handleControlMessage(json)
        }

        override fun onLog(msg: String) {
            sendLog(msg)
        }
    }

    // ── Mesh Listener ────────────────────────────────────────────

    private val meshListener = object : MeshDiscovery.Listener {
        override fun onPeersChanged(peers: List<MeshDiscovery.PeerInfo>) {
            broadcastStatus()
        }

        override fun onAudioFromMesh(data: ByteArray, fromPeer: MeshDiscovery.PeerInfo) {
            audioRelay?.onAudioFromMesh(data, fromPeer)
        }

        override fun onLog(msg: String) {
            sendLog(msg)
        }
    }

    // ── Control Messages ─────────────────────────────────────────

    private fun handleControlMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                "channel_change" -> {
                    currentChannel = obj.optInt("channel", 0)
                    meshDiscovery?.currentChannel = currentChannel
                    sendLog("Channel changed to $currentChannel")
                    broadcastStatus()
                }
                "ping" -> {
                    sendLog("Ping from glasses")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid control message: $json", e)
        }
    }

    // ── Status Broadcasting ──────────────────────────────────────

    private fun broadcastStatus() {
        val intent = Intent(MainActivity.ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_BLE_CONNECTED, glassesConnected)
            putExtra(MainActivity.EXTRA_CHANNEL, currentChannel)
            putExtra(MainActivity.EXTRA_PEER_COUNT, meshDiscovery?.getPeersOnChannel()?.size ?: 0)
            putExtra(MainActivity.EXTRA_PACKET_COUNT, audioRelay?.totalPacketsRelayed ?: 0L)
        }
        sendBroadcast(intent)

        // Also send status to glasses via BLE
        val statusJson = JSONObject().apply {
            put("peers", meshDiscovery?.getPeersOnChannel()?.size ?: 0)
            put("channel", currentChannel)
            put("mesh_active", meshDiscovery != null)
        }
        gattServer?.sendStatus(statusJson.toString())
    }

    private fun sendLog(msg: String) {
        Log.d(TAG, msg)
        val intent = Intent(MainActivity.ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_BLE_CONNECTED, glassesConnected)
            putExtra(MainActivity.EXTRA_CHANNEL, currentChannel)
            putExtra(MainActivity.EXTRA_PEER_COUNT, meshDiscovery?.getPeersOnChannel()?.size ?: 0)
            putExtra(MainActivity.EXTRA_PACKET_COUNT, audioRelay?.totalPacketsRelayed ?: 0L)
            putExtra(MainActivity.EXTRA_LOG_LINE, msg)
        }
        sendBroadcast(intent)
    }

    // ── Notification ─────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CompanionApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MeshTalk")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(CompanionApp.NOTIFICATION_ID, buildNotification(text))
    }

    // ── Wake Lock ────────────────────────────────────────────────

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "meshtalk:companion_service"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
