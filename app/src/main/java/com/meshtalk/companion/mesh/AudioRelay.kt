package com.meshtalk.companion.mesh

import android.util.Log
import com.meshtalk.companion.ble.GlassesGattServer
import java.util.concurrent.atomic.AtomicLong

/**
 * AudioRelay routes audio between the local BLE glasses and the mesh network.
 *
 * Supports both the legacy UDP [MeshDiscovery] backend and the new
 * [WebSocketMeshClient] backend. Use the appropriate constructor.
 *
 * WebSocket flow:
 *   glasses -> BLE write (ea01) -> onAudioFromGlasses() -> ws.sendAudioToMesh() -> bridge -> peers
 *   bridge -> ws binary message -> onAudioFromMesh() -> sendAudioToGlasses() -> BLE notify (ea02)
 *
 * Legacy UDP flow:
 *   glasses -> BLE write (ea01) -> onAudioFromGlasses() -> sendAudioToMesh() -> UDP to peers
 *   UDP from peers -> onAudioFromMesh() -> sendAudioToGlasses() -> BLE notify (ea02)
 *
 * Echo prevention: when glasses send audio, the sender device MAC is tracked so
 * the GATT server can exclude it from the broadcast back (prevents hearing your
 * own voice echoed through the mesh).
 */
class AudioRelay private constructor(
    private val gattServer: GlassesGattServer,
    private val meshDiscovery: MeshDiscovery?,
    private val wsMeshClient: WebSocketMeshClient?
) {
    companion object {
        private const val TAG = "AudioRelay"
    }

    private val packetsRelayed = AtomicLong(0)

    val totalPacketsRelayed: Long get() = packetsRelayed.get()

    // -- Constructors ----------------------------------------------

    /**
     * WebSocket-backed relay (preferred).
     * Audio flows through the MeshTalk bridge server.
     */
    constructor(
        gattServer: GlassesGattServer,
        wsMeshClient: WebSocketMeshClient
    ) : this(gattServer, meshDiscovery = null, wsMeshClient = wsMeshClient)

    /**
     * Legacy UDP-backed relay.
     * Audio is sent directly peer-to-peer via UDP multicast/unicast.
     */
    constructor(
        gattServer: GlassesGattServer,
        meshDiscovery: MeshDiscovery
    ) : this(gattServer, meshDiscovery = meshDiscovery, wsMeshClient = null)

    // -- Glasses -> Mesh -------------------------------------------

    /**
     * Called when glasses send an Opus audio frame via BLE write on ea01.
     * Forward it to all mesh peers.
     *
     * @param opusFrame Raw Opus-encoded audio bytes.
     * @param senderDeviceMac BLE MAC of the glasses that originated this frame.
     *   Tracked for echo prevention: when mesh audio comes back, the GATT server
     *   should skip notifying this device (so the user doesn't hear themselves).
     *   Pass null if the sender device is unknown.
     */
    fun onAudioFromGlasses(opusFrame: ByteArray, senderDeviceMac: String? = null) {
        // Store the last sender so onAudioFromMesh can exclude it
        senderDeviceMac?.let { lastSenderMac = it }

        if (wsMeshClient != null) {
            wsMeshClient.sendAudioToMesh(opusFrame)
        } else {
            meshDiscovery?.sendAudioToMesh(opusFrame)
        }

        packetsRelayed.incrementAndGet()
        Log.v(TAG, "glasses->mesh: ${opusFrame.size}B" +
                (senderDeviceMac?.let { " from $it" } ?: ""))
    }

    // -- Mesh -> Glasses -------------------------------------------

    /**
     * Called when a mesh peer sends an Opus audio frame (via WebSocket).
     * Forward it to the connected glasses via BLE notification on ea02.
     *
     * @param opusFrame Raw Opus-encoded audio bytes from the mesh.
     * @param fromPeer Peer that originated the frame (WebSocket variant).
     */
    fun onAudioFromMesh(opusFrame: ByteArray, fromPeer: WebSocketMeshClient.PeerInfo) {
        deliverToGlasses(opusFrame, fromPeer.userId)
    }

    /**
     * Called when a mesh peer sends an Opus audio frame (legacy UDP variant).
     *
     * @param opusFrame Raw Opus-encoded audio bytes from the mesh.
     * @param fromPeer Peer that originated the frame (UDP variant).
     */
    fun onAudioFromMesh(opusFrame: ByteArray, fromPeer: MeshDiscovery.PeerInfo) {
        deliverToGlasses(opusFrame, fromPeer.userId)
    }

    /**
     * Internal: deliver mesh audio to glasses, with echo-prevention logging.
     *
     * Currently broadcasts to ALL connected glasses via sendAudioToGlasses().
     * The [lastSenderMac] field holds the MAC of the glasses that most recently
     * sent audio. When GlassesGattServer is updated to support multi-device
     * broadcast with exclusion, pass lastSenderMac to skip the originating
     * device and prevent echo.
     */
    private fun deliverToGlasses(opusFrame: ByteArray, fromUserId: String) {
        // TODO: pass lastSenderMac to gattServer.sendAudioToGlasses() once
        // it supports an excludeDevice parameter for echo prevention.
        val sent = gattServer.sendAudioToGlasses(opusFrame, lastSenderMac)
        if (sent > 0) {
            packetsRelayed.incrementAndGet()
            Log.v(TAG, "mesh($fromUserId)->glasses: ${opusFrame.size}B")
        }
    }

    // -- Echo prevention state -------------------------------------

    /**
     * MAC address of the last glasses device that sent audio.
     * Used for echo prevention: when relaying mesh audio back to glasses,
     * the GATT server should skip this device to avoid echo.
     *
     * Thread-safe via volatile; acceptable to have brief race windows
     * since echo prevention is best-effort.
     */
    @Volatile
    var lastSenderMac: String? = null
        private set

    // -- Stats -----------------------------------------------------

    fun resetStats() {
        packetsRelayed.set(0)
    }
}
