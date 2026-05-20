package com.meshtalk.companion.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meshtalk.companion.CompanionApp
import com.meshtalk.companion.MainActivity
import com.meshtalk.companion.R
import com.meshtalk.companion.ble.GlassesGattServer
import com.meshtalk.companion.mesh.AudioRelay
import com.meshtalk.companion.mesh.MeshClientManager
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs the BLE GATT server and per-glass WebSocket
 * mesh connections via [MeshClientManager].
 *
 * Per-glass mesh routing:
 *   Each connected glass gets its own WebSocket to the bridge, identified by
 *   "glass_{last4mac}". Audio from Glass A flows through WS-A to the bridge,
 *   which relays it to WS-B (a different connection), and the phone delivers
 *   it to Glass B. This eliminates the single-connection problem where the
 *   bridge would exclude the phone (the only sender) and Glass B would never
 *   receive audio.
 *
 * Lifecycle:
 *   startService → onCreate → onStartCommand → startForeground → start BLE + mesh
 *   stopService → onDestroy → stop BLE + mesh
 *
 * The mesh bridge connection uses an ADB reverse tunnel:
 *   adb reverse tcp:8440 tcp:8440
 * so 127.0.0.1:8440 on the phone reaches the dev machine's bridge server.
 *
 * Hardened for 24/7 background operation on Samsung Galaxy A13:
 * - START_STICKY + restart alarm on task removal
 * - PARTIAL_WAKE_LOCK held for entire service lifetime
 * - ServiceWatchdog (AlarmManager + WorkManager) keeps service alive
 * - Rich foreground notification (non-dismissible) with live stats
 */
@SuppressLint("MissingPermission")
class CompanionService : Service() {

    companion object {
        private const val TAG = "CompanionService"
        private const val MESH_BRIDGE_HOST = "127.0.0.1"
        private const val MESH_BRIDGE_PORT = 8440
        private const val MESH_CHANNEL = "alpha"
    }

    private var gattServer: GlassesGattServer? = null
    private var meshManager: MeshClientManager? = null
    private var audioRelay: AudioRelay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var statusTask: ScheduledFuture<*>? = null
    private var notificationUpdateTask: ScheduledFuture<*>? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var glassesConnected = false
    private var meshBridgeConnected = false
    private var currentChannel = MESH_CHANNEL
    private var bleConnectionCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        startForeground(CompanionApp.NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        startComponents()

        // Arm watchdog from service side too (belt and suspenders)
        ServiceWatchdog.arm(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedBy = intent?.getStringExtra("started_by") ?: "direct"
        Log.i(TAG, "onStartCommand (startedBy=$startedBy, flags=$flags, startId=$startId)")

        // Re-verify wake lock is held on every start command
        ensureWakeLockHeld()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed (app swiped from recents) — scheduling restart")

        // Schedule a restart alarm as fallback beyond START_STICKY
        ServiceWatchdog.scheduleRestartAlarm(this, delayMs = 3000L)

        super.onTaskRemoved(rootIntent)
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

        // Create per-glass mesh client manager
        val manager = MeshClientManager(
            host = MESH_BRIDGE_HOST,
            port = MESH_BRIDGE_PORT,
            initialChannel = currentChannel,
            listener = meshManagerListener
        )
        meshManager = manager

        // Create audio relay (per-glass WS routing)
        audioRelay = AudioRelay(gatt, manager)

        // Start BLE GATT server (mesh WS connections are started per-glass on BLE connect)
        val bleOk = gatt.start()

        sendLog("BLE: ${if (bleOk) "OK" else "FAILED"} | Mesh: per-glass routing via $MESH_BRIDGE_HOST:$MESH_BRIDGE_PORT")

        // Periodic status broadcast
        statusTask = scheduler.scheduleAtFixedRate({
            broadcastStatus()
        }, 1, 2, TimeUnit.SECONDS)

        // Periodic notification update with live stats
        notificationUpdateTask = scheduler.scheduleAtFixedRate({
            updateNotificationWithStats()
        }, 5, 10, TimeUnit.SECONDS)
    }

    private fun stopComponents() {
        statusTask?.cancel(false)
        notificationUpdateTask?.cancel(false)
        gattServer?.stop()
        meshManager?.stopAll()
        gattServer = null
        meshManager = null
        audioRelay = null
    }

    // ── GATT Listener ────────────────────────────────────────────

    private val gattListener = object : GlassesGattServer.Listener {
        override fun onGlassesConnected(device: BluetoothDevice) {
            glassesConnected = true
            bleConnectionCount++

            // Open a dedicated WebSocket for this glass
            meshManager?.addGlass(device.address)

            updateNotificationWithStats()
            broadcastStatus()
        }

        override fun onGlassesDisconnected(device: BluetoothDevice) {
            // Close the dedicated WebSocket for this glass
            meshManager?.removeGlass(device.address)

            // Only set glassesConnected=false if no other glasses remain connected
            val stillConnected = gattServer?.isGlassesConnected == true
            glassesConnected = stillConnected
            updateNotificationWithStats()
            broadcastStatus()
        }

        override fun onAudioFromGlasses(device: BluetoothDevice, data: ByteArray) {
            // Route through the sender glass's own WS connection
            audioRelay?.onAudioFromGlasses(data, senderMac = device.address)
        }

        override fun onControlMessage(device: BluetoothDevice, json: String) {
            handleControlMessage(json)
        }

        override fun onLog(msg: String) {
            sendLog(msg)
        }
    }

    // ── Mesh Manager Listener ────────────────────────────────────

    private val meshManagerListener = object : MeshClientManager.Listener {
        override fun onAudioForGlass(glassMac: String, data: ByteArray) {
            // Audio arrived on WS-B → deliver to Glass B only
            audioRelay?.onAudioFromMesh(data, forGlassMac = glassMac)
        }

        override fun onPeersChanged() {
            broadcastStatus()
        }

        override fun onLog(msg: String) {
            sendLog(msg)
        }

        override fun onMeshConnectionChanged(glassMac: String, connected: Boolean) {
            // Update aggregate connection state
            meshBridgeConnected = meshManager?.isAnyConnected() == true
            updateNotificationWithStats()
            broadcastStatus()
        }
    }

    // ── Control Messages ─────────────────────────────────────────

    private fun handleControlMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
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
        val peerCount = meshManager?.getPeerCount() ?: 0
        val intent = Intent(MainActivity.ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_BLE_CONNECTED, glassesConnected)
            putExtra(MainActivity.EXTRA_CHANNEL, currentChannel)
            putExtra(MainActivity.EXTRA_PEER_COUNT, peerCount)
            putExtra(MainActivity.EXTRA_PACKET_COUNT, audioRelay?.totalPacketsRelayed ?: 0L)
            putExtra(EXTRA_MESH_CONNECTED, meshBridgeConnected)
        }
        sendBroadcast(intent)

        // Also send status to glasses via BLE
        val statusJson = JSONObject().apply {
            put("peers", peerCount)
            put("channel", currentChannel)
            put("mesh_connected", meshBridgeConnected)
            put("mesh_active", meshManager != null)
        }
        gattServer?.sendStatus(statusJson.toString())
    }

    private fun sendLog(msg: String) {
        Log.d(TAG, msg)
        val intent = Intent(MainActivity.ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_BLE_CONNECTED, glassesConnected)
            putExtra(MainActivity.EXTRA_CHANNEL, currentChannel)
            putExtra(MainActivity.EXTRA_PEER_COUNT, meshManager?.getPeerCount() ?: 0)
            putExtra(MainActivity.EXTRA_PACKET_COUNT, audioRelay?.totalPacketsRelayed ?: 0L)
            putExtra(EXTRA_MESH_CONNECTED, meshBridgeConnected)
            putExtra(MainActivity.EXTRA_LOG_LINE, msg)
        }
        sendBroadcast(intent)
    }

    // ── Notification ─────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val peerCount = meshManager?.getPeerCount() ?: 0
        val packetsRelayed = audioRelay?.totalPacketsRelayed ?: 0L
        val bleStatus = if (glassesConnected) "BLE: ✓ Connected" else "BLE: ✗ Waiting"
        val meshStatus = if (meshBridgeConnected) "Mesh: ✓ Online" else "Mesh: ✗ Offline"

        val contentText = "$bleStatus | $meshStatus"
        val expandedText = buildString {
            appendLine("BLE Connections: $bleConnectionCount (current: ${if (glassesConnected) "active" else "none"})")
            appendLine("Mesh Bridge: ${if (meshBridgeConnected) "connected" else "disconnected"}")
            appendLine("Channel: ${currentChannel.replaceFirstChar { it.uppercase() }}")
            appendLine("Peers on channel: $peerCount")
            appendLine("Audio packets relayed: $packetsRelayed")
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CompanionApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MeshTalk — ${currentChannel.replaceFirstChar { it.uppercase() }}")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)          // Can't be swiped away
            .setSilent(true)
            .setOnlyAlertOnce(true)    // Don't buzz on updates
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationWithStats() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(CompanionApp.NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    // ── Wake Lock ────────────────────────────────────────────────

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "meshtalk:companion_service"
        ).apply {
            setReferenceCounted(false)  // Prevent double-release issues
            acquire()
        }
        Log.i(TAG, "PARTIAL_WAKE_LOCK acquired (referenceCounted=false, indefinite hold)")
    }

    /**
     * Verify wake lock is still held — can be lost after certain system events.
     * Called on every onStartCommand to ensure robustness.
     */
    @SuppressLint("WakelockTimeout")
    private fun ensureWakeLockHeld() {
        val wl = wakeLock
        if (wl == null || !wl.isHeld) {
            Log.w(TAG, "Wake lock was NOT held — re-acquiring!")
            acquireWakeLock()
        } else {
            Log.d(TAG, "Wake lock verified: held=true")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
}

/** Extra key for mesh bridge connection status in status broadcasts. */
const val EXTRA_MESH_CONNECTED = "mesh_connected"
