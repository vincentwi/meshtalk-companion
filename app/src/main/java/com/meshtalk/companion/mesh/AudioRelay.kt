package com.meshtalk.companion.mesh

import android.util.Log
import com.meshtalk.companion.ble.GlassesGattServer
import java.util.concurrent.atomic.AtomicLong

/**
 * AudioRelay routes audio between local BLE glasses and the mesh network,
 * using per-glass WebSocket connections managed by [MeshClientManager].
 *
 * Per-glass routing flow:
 *   Glass A → BLE write (ea01) → onAudioFromGlasses(frame, macA)
 *           → MeshClientManager.getClientForGlass(macA).sendAudioToMesh()
 *           → Bridge relays to all OTHER WS connections (not macA's)
 *           → WS-B receives audio → onAudioFromMesh(frame, macB)
 *           → gattServer.sendAudioToSingleGlass(macB, frame)
 *           → BLE notify (ea02) → Glass B
 *
 * Echo prevention is structural: the bridge never sends audio back to the
 * sender's WebSocket connection. Because each glass has its own connection,
 * Glass A's audio is never echoed back to Glass A.
 */
class AudioRelay(
    private val gattServer: GlassesGattServer,
    private val meshManager: MeshClientManager
) {
    companion object {
        private const val TAG = "AudioRelay"
    }

    private val packetsRelayed = AtomicLong(0)

    val totalPacketsRelayed: Long get() = packetsRelayed.get()

    // -- Glasses → Mesh -------------------------------------------

    /**
     * Called when glasses send an Opus audio frame via BLE write on ea01.
     * Looks up the sender's dedicated WS connection and sends through it.
     *
     * @param opusFrame Raw Opus-encoded audio bytes.
     * @param senderMac BLE MAC of the glasses that originated this frame.
     */
    fun onAudioFromGlasses(opusFrame: ByteArray, senderMac: String) {
        val client = meshManager.getClientForGlass(senderMac)
        if (client != null) {
            client.sendAudioToMesh(opusFrame)
            packetsRelayed.incrementAndGet()
            Log.v(TAG, "glasses($senderMac)->mesh: ${opusFrame.size}B")
        } else {
            Log.w(TAG, "No WS client for glass $senderMac — audio dropped")
        }
    }

    // -- Mesh → Glasses -------------------------------------------

    /**
     * Called when a mesh peer sends audio that arrived on the WS connection
     * belonging to [forGlassMac]. Deliver to that specific glass only.
     *
     * @param opusFrame Raw Opus-encoded audio bytes from the mesh.
     * @param forGlassMac The glass MAC whose WS connection received this audio.
     */
    fun onAudioFromMesh(opusFrame: ByteArray, forGlassMac: String) {
        val sent = gattServer.sendAudioToSingleGlass(forGlassMac, opusFrame)
        if (sent > 0) {
            packetsRelayed.incrementAndGet()
            Log.v(TAG, "mesh->glasses($forGlassMac): ${opusFrame.size}B")
        }
    }

    // -- Stats ----------------------------------------------------

    fun resetStats() {
        packetsRelayed.set(0)
    }
}
