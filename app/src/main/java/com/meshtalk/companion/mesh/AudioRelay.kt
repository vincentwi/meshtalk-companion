package com.meshtalk.companion.mesh

import android.util.Log
import com.meshtalk.companion.ble.GlassesGattServer
import java.util.concurrent.atomic.AtomicLong

/**
 * AudioRelay routes audio between the local BLE glasses and the UDP mesh network.
 *
 * Flow:
 *   glasses → BLE write (ea01) → onAudioFromGlasses() → sendAudioToMesh() → UDP to peers
 *   UDP from peers → onAudioFromMesh() → sendAudioToGlasses() → BLE notify (ea02)
 *
 * Channel isolation: only relays to/from peers on the same channel.
 */
class AudioRelay(
    private val gattServer: GlassesGattServer,
    private val meshDiscovery: MeshDiscovery
) {
    companion object {
        private const val TAG = "AudioRelay"
    }

    private val packetsRelayed = AtomicLong(0)

    val totalPacketsRelayed: Long get() = packetsRelayed.get()

    /**
     * Called when glasses send an Opus audio frame via BLE write on ea01.
     * Forward it to all mesh peers on the same channel.
     */
    fun onAudioFromGlasses(opusFrame: ByteArray) {
        meshDiscovery.sendAudioToMesh(opusFrame)
        packetsRelayed.incrementAndGet()
        Log.v(TAG, "glasses→mesh: ${opusFrame.size}B")
    }

    /**
     * Called when a mesh peer sends an Opus audio frame via UDP.
     * Forward it to the connected glasses via BLE notification on ea02.
     */
    fun onAudioFromMesh(opusFrame: ByteArray, fromPeer: MeshDiscovery.PeerInfo) {
        val sent = gattServer.sendAudioToGlasses(opusFrame)
        if (sent) {
            packetsRelayed.incrementAndGet()
            Log.v(TAG, "mesh(${fromPeer.userId})→glasses: ${opusFrame.size}B")
        }
    }

    fun resetStats() {
        packetsRelayed.set(0)
    }
}
