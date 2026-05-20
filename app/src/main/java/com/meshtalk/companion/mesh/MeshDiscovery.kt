package com.meshtalk.companion.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP multicast peer discovery for phone-to-phone mesh networking.
 *
 * Multicast group: 239.42.42.42 port 18431
 * Announce format: {"app":"meshtalk","user":"<id>","channel":<n>,"ts":<epoch_ms>}
 *
 * Discovered peers on the same channel are eligible for audio relay.
 * Audio relay uses unicast UDP on port 18432.
 */
class MeshDiscovery(
    private val context: Context,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "MeshDiscovery"
        private const val MULTICAST_GROUP = "239.42.42.42"
        private const val DISCOVERY_PORT = 18431
        const val AUDIO_PORT = 18432
        private const val ANNOUNCE_INTERVAL_MS = 3000L
        private const val PEER_TIMEOUT_MS = 10000L
        private const val MAX_PACKET_SIZE = 1500
    }

    interface Listener {
        fun onPeersChanged(peers: List<PeerInfo>)
        fun onAudioFromMesh(data: ByteArray, fromPeer: PeerInfo)
        fun onLog(msg: String)
    }

    data class PeerInfo(
        val userId: String,
        val address: InetAddress,
        val channel: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )

    private val running = AtomicBoolean(false)
    private val executor = Executors.newScheduledThreadPool(3)
    private var announceTask: ScheduledFuture<*>? = null
    private var discoverySocket: MulticastSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val userId: String = "phone_${android.os.Build.MODEL.replace(" ","_")}_${System.currentTimeMillis() % 10000}"

    var currentChannel: Int = 0
        set(value) {
            field = value
            // Peers on different channel are irrelevant but we keep them for display
        }

    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        try {
            // Acquire multicast lock
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("meshtalk_discovery").apply {
                setReferenceCounted(false)
                acquire()
            }

            // Discovery socket (multicast)
            discoverySocket = MulticastSocket(DISCOVERY_PORT).apply {
                reuseAddress = true
                joinGroup(InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT), NetworkInterface.getByInetAddress(getLocalAddress()))
                soTimeout = 0
            }

            // Audio relay socket (unicast)
            audioSocket = DatagramSocket(AUDIO_PORT).apply {
                reuseAddress = true
                soTimeout = 0
            }

            // Start listening threads
            executor.execute { discoveryListenLoop() }
            executor.execute { audioListenLoop() }

            // Start periodic announce
            announceTask = executor.scheduleAtFixedRate(
                { sendAnnounce() },
                0, ANNOUNCE_INTERVAL_MS, TimeUnit.MILLISECONDS
            )

            // Start peer cleanup
            executor.scheduleAtFixedRate(
                { cleanupPeers() },
                PEER_TIMEOUT_MS, PEER_TIMEOUT_MS / 2, TimeUnit.MILLISECONDS
            )

            Log.i(TAG, "Mesh discovery started as $userId on $MULTICAST_GROUP:$DISCOVERY_PORT")
            listener.onLog("Mesh: listening on $MULTICAST_GROUP:$DISCOVERY_PORT")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh discovery", e)
            listener.onLog("Mesh error: ${e.message}")
            running.set(false)
            return false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        announceTask?.cancel(false)
        try { discoverySocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        multicastLock?.release()
        peers.clear()
        Log.i(TAG, "Mesh discovery stopped")
        listener.onLog("Mesh: stopped")
    }

    /** Send an Opus audio frame to all mesh peers on the same channel */
    fun sendAudioToMesh(opusFrame: ByteArray) {
        if (!running.get()) return
        val socket = audioSocket ?: return

        val sameChanPeers = peers.values.filter { it.channel == currentChannel }
        for (peer in sameChanPeers) {
            try {
                val packet = DatagramPacket(opusFrame, opusFrame.size, peer.address, AUDIO_PORT)
                socket.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send audio to ${peer.userId}: ${e.message}")
            }
        }
    }

    fun getPeers(): List<PeerInfo> = peers.values.toList()

    fun getPeersOnChannel(): List<PeerInfo> = peers.values.filter { it.channel == currentChannel }

    private fun getLocalAddress(): InetAddress {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr
                    }
                }
            }
        } catch (_: Exception) {}
        return InetAddress.getByName("0.0.0.0")
    }

    // ── Announce ─────────────────────────────────────────────────

    private fun sendAnnounce() {
        try {
            val json = JSONObject().apply {
                put("app", "meshtalk")
                put("user", userId)
                put("channel", currentChannel)
                put("ts", System.currentTimeMillis())
            }
            val data = json.toString().toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName(MULTICAST_GROUP)
            val packet = DatagramPacket(data, data.size, group, DISCOVERY_PORT)
            discoverySocket?.send(packet)
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "Announce failed: ${e.message}")
            }
        }
    }

    // ── Discovery Listen Loop ────────────────────────────────────

    private fun discoveryListenLoop() {
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                discoverySocket?.receive(packet) ?: break

                val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                if (json.optString("app") != "meshtalk") continue

                val peerId = json.getString("user")
                if (peerId == userId) continue  // Skip self

                val channel = json.optInt("channel", 0)
                val peer = PeerInfo(
                    userId = peerId,
                    address = packet.address,
                    channel = channel,
                    lastSeen = System.currentTimeMillis()
                )

                val isNew = !peers.containsKey(peerId)
                peers[peerId] = peer

                if (isNew) {
                    Log.i(TAG, "Discovered peer: $peerId at ${packet.address.hostAddress} ch=$channel")
                    listener.onLog("Peer found: $peerId (ch $channel)")
                    listener.onPeersChanged(peers.values.toList())
                }

            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Discovery receive error: ${e.message}")
                }
            }
        }
    }

    // ── Audio Listen Loop ────────────────────────────────────────

    private fun audioListenLoop() {
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                audioSocket?.receive(packet) ?: break

                // Find which peer sent this
                val senderAddr = packet.address
                val peer = peers.values.find { it.address == senderAddr }
                    ?: PeerInfo("unknown", senderAddr, currentChannel)

                // Only relay audio from same channel
                if (peer.channel == currentChannel) {
                    val data = packet.data.copyOf(packet.length)
                    listener.onAudioFromMesh(data, peer)
                }

            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Audio receive error: ${e.message}")
                }
            }
        }
    }

    // ── Peer Cleanup ─────────────────────────────────────────────

    private fun cleanupPeers() {
        val now = System.currentTimeMillis()
        val removed = mutableListOf<String>()
        peers.entries.removeAll { entry ->
            val stale = (now - entry.value.lastSeen) > PEER_TIMEOUT_MS
            if (stale) removed.add(entry.key)
            stale
        }
        if (removed.isNotEmpty()) {
            Log.d(TAG, "Removed stale peers: $removed")
            listener.onLog("Peers lost: ${removed.joinToString()}")
            listener.onPeersChanged(peers.values.toList())
        }
    }
}
