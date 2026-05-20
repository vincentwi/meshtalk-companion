package com.meshtalk.companion.mesh

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple [WebSocketMeshClient] instances — one per connected glass.
 *
 * Each glass gets its own WebSocket identity on the bridge:
 *   userId = "glass_" + mac.replace(":", "").takeLast(4).lowercase()
 *
 * This is the key to true mesh routing between glasses on the same phone:
 *   Glass A → BLE → Phone (WS-A) → Bridge → Phone (WS-B) → BLE → Glass B
 *
 * The bridge excludes the *sender connection* when relaying audio. Because
 * Glass A and Glass B each have their own WebSocket connection, audio from
 * Glass A (sent via WS-A) is relayed to WS-B (a different connection), so
 * the phone receives it on WS-B and delivers it to Glass B.
 */
class MeshClientManager(
    private val host: String,
    private val port: Int,
    private val initialChannel: String,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "MeshClientMgr"
    }

    interface Listener {
        /** Audio arrived on the WS connection for [glassMac]; deliver to that glass. */
        fun onAudioForGlass(glassMac: String, data: ByteArray)
        /** Aggregate peer count changed across any connection. */
        fun onPeersChanged()
        /** Human-readable log line. */
        fun onLog(msg: String)
        /** A specific glass's mesh connection state changed. */
        fun onMeshConnectionChanged(glassMac: String, connected: Boolean)
    }

    // ── Per-glass WS clients ──────────────────────────────────────

    private val clients = ConcurrentHashMap<String, WebSocketMeshClient>()
    private val currentChannel = java.util.concurrent.atomic.AtomicReference(initialChannel)

    /**
     * Create and start a new WebSocket connection for the glass with [mac].
     * If a connection already exists for this MAC, it is stopped first.
     */
    fun addGlass(mac: String) {
        // Stop existing client if any (e.g. rapid reconnect)
        removeGlass(mac)

        val userId = glassUserId(mac)
        val channel = currentChannel.get()

        Log.i(TAG, "Adding glass $mac  userId=$userId  ch=$channel")
        listener.onLog("Mesh: opening WS for $mac ($userId)")

        val wsListener = PerGlassListener(mac)
        val client = WebSocketMeshClient(
            host = host,
            port = port,
            userId = userId,
            initialChannel = channel,
            listener = wsListener
        )
        clients[mac] = client
        client.start()
    }

    /** Stop and remove the WebSocket connection for [mac]. */
    fun removeGlass(mac: String) {
        val client = clients.remove(mac) ?: return
        Log.i(TAG, "Removing glass $mac")
        listener.onLog("Mesh: closing WS for $mac")
        client.stop()
        listener.onMeshConnectionChanged(mac, false)
    }

    /** Return the WS client for a specific glass, or null. */
    fun getClientForGlass(mac: String): WebSocketMeshClient? = clients[mac]

    /** Switch ALL per-glass connections to a new channel. */
    fun switchChannelAll(channel: String) {
        currentChannel.set(channel)
        for ((_, client) in clients) {
            client.switchChannel(channel)
        }
        listener.onLog("Mesh: all connections switched to $channel")
    }

    /** Stop ALL per-glass connections. */
    fun stopAll() {
        for ((mac, client) in clients) {
            Log.i(TAG, "Stopping client for $mac")
            client.stop()
        }
        clients.clear()
    }

    /** True if at least one per-glass WS is connected. */
    fun isAnyConnected(): Boolean = clients.values.any { it.isConnected }

    /**
     * Aggregate peer count across all connections, excluding our own glass IDs
     * to avoid double-counting ourselves.
     */
    fun getPeerCount(): Int {
        val ownIds = clients.keys.map { glassUserId(it) }.toSet()
        val allPeers = mutableSetOf<String>()
        for ((_, client) in clients) {
            for (peer in client.getPeers()) {
                if (peer.userId !in ownIds) {
                    allPeers.add(peer.userId)
                }
            }
        }
        return allPeers.size
    }

    /** Current channel name. */
    fun getChannel(): String = currentChannel.get()

    // ── Helpers ───────────────────────────────────────────────────

    /** Deterministic user ID for a glass MAC. */
    private fun glassUserId(mac: String): String =
        "glass_" + mac.replace(":", "").takeLast(4).lowercase()

    // ── Per-glass WS listener ────────────────────────────────────

    /**
     * Each glass's WS connection gets its own listener. When audio arrives
     * on WS-B, we know it's destined for the glass whose MAC is [glassMac].
     */
    private inner class PerGlassListener(
        private val glassMac: String
    ) : WebSocketMeshClient.Listener {

        override fun onConnected() {
            Log.i(TAG, "WS connected for glass $glassMac")
            listener.onMeshConnectionChanged(glassMac, true)
            listener.onLog("Mesh: WS connected for $glassMac")
        }

        override fun onDisconnected() {
            Log.i(TAG, "WS disconnected for glass $glassMac")
            listener.onMeshConnectionChanged(glassMac, false)
            listener.onLog("Mesh: WS disconnected for $glassMac")
        }

        override fun onPeersChanged(peers: List<WebSocketMeshClient.PeerInfo>) {
            listener.onPeersChanged()
        }

        override fun onAudioFromMesh(data: ByteArray, fromPeer: WebSocketMeshClient.PeerInfo) {
            // Audio arrived on this glass's WS connection → deliver to this glass
            listener.onAudioForGlass(glassMac, data)
        }

        override fun onLog(msg: String) {
            listener.onLog("[$glassMac] $msg")
        }
    }
}
