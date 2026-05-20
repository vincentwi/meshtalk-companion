package com.meshtalk.companion.mesh

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket-based mesh client that connects to the MeshTalk bridge server.
 *
 * Replaces the UDP multicast [MeshDiscovery] with a centralized WebSocket relay.
 * The bridge server runs on a Mac Mini; phones connect via WiFi or ADB tunnel.
 *
 * WebSocket endpoint: ws://<host>:<port>/ws/talk?id=<client_id>&channel=<channel>&user=<user>
 *
 * Protocol:
 *   Binary messages = raw Opus audio frames (40-100 bytes typical)
 *   Text messages   = JSON control: {"cmd":"switch","channel":"alpha"}, {"cmd":"ping"}
 *   Server events   = JSON: {"event":"join",...}, {"event":"leave",...}, {"event":"pong",...}
 */
class WebSocketMeshClient(
    private val host: String,
    private val port: Int,
    private val userId: String,
    initialChannel: String,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "WebSocketMesh"

        /** Base delay for reconnect backoff (milliseconds). */
        private const val RECONNECT_BASE_MS = 500L

        /** Maximum reconnect delay cap (milliseconds). */
        private const val RECONNECT_MAX_MS = 30_000L

        /** Interval between WebSocket-level ping commands (milliseconds). */
        private const val PING_INTERVAL_MS = 10_000L

        /** Normal WebSocket close code. */
        private const val CLOSE_NORMAL = 1000
    }

    // -- Listener (mirrors MeshDiscovery.Listener contract) --------

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onPeersChanged(peers: List<PeerInfo>)
        fun onAudioFromMesh(data: ByteArray, fromPeer: PeerInfo)
        fun onLog(msg: String)
    }

    /**
     * Peer descriptor. Compatible with the old [MeshDiscovery.PeerInfo] shape
     * but address is unused for WebSocket mode (peers are behind the relay).
     */
    data class PeerInfo(
        val userId: String,
        val channel: String,
        val lastSeen: Long = System.currentTimeMillis()
    )

    // -- State -----------------------------------------------------

    private val running = AtomicBoolean(false)
    private val wsRef = AtomicReference<WebSocket?>(null)
    private val currentChannel = AtomicReference(initialChannel)
    private val reconnectAttempt = AtomicInteger(0)
    private val connected = AtomicBoolean(false)

    private val peers = ConcurrentHashMap<String, PeerInfo>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout on WS
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)       // OkHttp-level keepalive
        .retryOnConnectionFailure(false)           // we handle reconnect ourselves
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Recurring app-level ping posted via Handler. */
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (running.get() && connected.get()) {
                sendText("{\"cmd\":\"ping\"}")
                mainHandler.postDelayed(this, PING_INTERVAL_MS)
            }
        }
    }

    // -- Public API ------------------------------------------------

    /**
     * Open the WebSocket connection. Automatically reconnects on failure
     * with exponential backoff until [stop] is called.
     */
    fun start() {
        if (running.getAndSet(true)) return
        Log.i(TAG, "Starting WebSocket mesh client -> $host:$port  user=$userId  ch=${currentChannel.get()}")
        listener.onLog("WS Mesh: connecting to $host:$port")
        connect()
    }

    /** Gracefully close the WebSocket and stop reconnect attempts. */
    fun stop() {
        if (!running.getAndSet(false)) return
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        connected.set(false)

        wsRef.getAndSet(null)?.close(CLOSE_NORMAL, "client stop")
        peers.clear()
        Log.i(TAG, "WebSocket mesh client stopped")
        listener.onLog("WS Mesh: stopped")
    }

    /**
     * Send a raw Opus audio frame to the mesh (binary WebSocket message).
     * Safe to call from any thread. Silently drops if not connected.
     */
    fun sendAudioToMesh(opusFrame: ByteArray) {
        if (!connected.get()) return
        val ws = wsRef.get() ?: return
        try {
            ws.send(opusFrame.toByteString())
        } catch (e: Exception) {
            Log.w(TAG, "sendAudioToMesh failed: ${e.message}")
        }
    }

    /**
     * Switch to a different channel. Sends a JSON switch command to the
     * server; the server will remove us from the old channel and add us
     * to the new one (generating join/leave events for other peers).
     */
    fun switchChannel(channelName: String) {
        currentChannel.set(channelName)
        peers.clear()
        listener.onPeersChanged(emptyList())
        val json = JSONObject().apply {
            put("cmd", "switch")
            put("channel", channelName)
        }
        sendText(json.toString())
        listener.onLog("WS Mesh: switch -> $channelName")
    }

    /** Current channel name. */
    fun getChannel(): String = currentChannel.get()

    /** Snapshot of known peers on the current channel. */
    fun getPeers(): List<PeerInfo> = peers.values.toList()

    /** Snapshot of known peers on the current channel (alias for getPeers). */
    fun getPeersOnChannel(): List<PeerInfo> = peers.values.toList()

    /** Number of peers on the current channel. */
    fun getPeerCount(): Int = peers.size

    /** Whether the WebSocket is currently connected. */
    val isConnected: Boolean get() = connected.get()

    // -- Internal: Connect / Reconnect -----------------------------

    private fun connect() {
        if (!running.get()) return

        val channel = currentChannel.get()
        val url = "ws://$host:$port/ws/talk?id=$userId&channel=$channel&user=$userId&format=opus"
        Log.d(TAG, "Connecting -> $url")

        val request = Request.Builder()
            .url(url)
            .build()

        client.newWebSocket(request, wsSocketListener)
    }

    private fun scheduleReconnect() {
        if (!running.get()) return

        val attempt = reconnectAttempt.getAndIncrement()
        val delayMs = (RECONNECT_BASE_MS * (1L shl attempt.coerceAtMost(6)))
            .coerceAtMost(RECONNECT_MAX_MS)

        Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${attempt + 1})")
        listener.onLog("WS Mesh: reconnect in ${delayMs / 1000}s")

        mainHandler.postDelayed({
            if (running.get()) connect()
        }, delayMs)
    }

    // -- Internal: WebSocket callbacks -----------------------------

    private val wsSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected")
            wsRef.set(webSocket)
            connected.set(true)
            reconnectAttempt.set(0)
            listener.onConnected()
            listener.onLog("WS Mesh: connected to $host:$port")

            // Start app-level pings
            mainHandler.removeCallbacks(pingRunnable)
            mainHandler.post(pingRunnable)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Binary message = raw Opus audio frame from another peer
            if (!running.get()) return
            val data = bytes.toByteArray()
            if (data.isEmpty()) return

            // Server-relayed audio comes without explicit sender info.
            // Attribute to a synthetic "mesh" peer.
            val meshPeer = PeerInfo(userId = "mesh", channel = currentChannel.get())
            listener.onAudioFromMesh(data, meshPeer)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!running.get()) return
            try {
                handleServerEvent(text)
            } catch (e: Exception) {
                Log.w(TAG, "Bad server event: $text", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Server closing: $code $reason")
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            listener.onLog("WS Mesh: error - ${t.message}")
            onDisconnected()
        }
    }

    private fun onDisconnected() {
        wsRef.set(null)
        connected.set(false)
        mainHandler.removeCallbacks(pingRunnable)

        peers.clear()
        listener.onDisconnected()
        listener.onPeersChanged(emptyList())

        scheduleReconnect()
    }

    // -- Internal: Server event handling ---------------------------

    private fun handleServerEvent(text: String) {
        val json = JSONObject(text)

        when (json.optString("event")) {
            "join" -> {
                val peerId = json.optString("user", "")
                val channel = json.optString("channel", currentChannel.get())
                if (peerId.isNotEmpty() && peerId != userId) {
                    val peer = PeerInfo(
                        userId = peerId,
                        channel = channel,
                        lastSeen = System.currentTimeMillis()
                    )
                    peers[peerId] = peer
                    Log.i(TAG, "Peer joined: $peerId (ch $channel)")
                    listener.onLog("Peer joined: $peerId")
                    listener.onPeersChanged(peers.values.toList())
                }
            }

            "leave" -> {
                val peerId = json.optString("user", "")
                if (peerId.isNotEmpty()) {
                    peers.remove(peerId)
                    Log.i(TAG, "Peer left: $peerId")
                    listener.onLog("Peer left: $peerId")
                    listener.onPeersChanged(peers.values.toList())
                }
            }

            "pong" -> {
                Log.v(TAG, "Pong received")
            }

            "peers" -> {
                // Optional bulk peer list from server on connect
                val arr = json.optJSONArray("users")
                if (arr != null) {
                    peers.clear()
                    val ch = currentChannel.get()
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i, "")
                        if (id.isNotEmpty() && id != userId) {
                            peers[id] = PeerInfo(userId = id, channel = ch)
                        }
                    }
                    listener.onPeersChanged(peers.values.toList())
                    listener.onLog("Peers on channel: ${peers.size}")
                }
            }

            "switched" -> {
                val newChannel = json.optString("channel", "")
                if (newChannel.isNotEmpty()) {
                    currentChannel.set(newChannel)
                    peers.clear()
                    Log.i(TAG, "Server confirmed channel switch -> $newChannel")
                    listener.onLog("Channel switched: $newChannel")
                    listener.onPeersChanged(peers.values.toList())
                }
            }

            "error" -> {
                val msg = json.optString("message", "unknown error")
                Log.e(TAG, "Server error: $msg")
                listener.onLog("WS Mesh error: $msg")
            }

            else -> {
                Log.d(TAG, "Unhandled server event: $text")
            }
        }
    }

    // -- Internal: helpers -----------------------------------------

    private fun sendText(text: String) {
        try {
            wsRef.get()?.send(text)
        } catch (e: Exception) {
            Log.w(TAG, "sendText failed: ${e.message}")
        }
    }
}
