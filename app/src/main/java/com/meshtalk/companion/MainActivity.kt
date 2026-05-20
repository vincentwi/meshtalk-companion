package com.meshtalk.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.meshtalk.companion.service.CompanionService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        const val ACTION_STATUS_UPDATE = "com.meshtalk.companion.STATUS_UPDATE"
        const val EXTRA_BLE_CONNECTED = "ble_connected"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_PEER_COUNT = "peer_count"
        const val EXTRA_PACKET_COUNT = "packet_count"
        const val EXTRA_LOG_LINE = "log_line"
    }

    private lateinit var bleStatusDot: android.view.View
    private lateinit var bleStatusText: TextView
    private lateinit var channelText: TextView
    private lateinit var peerCountText: TextView
    private lateinit var packetCountText: TextView
    private lateinit var serviceStatusText: TextView
    private lateinit var logText: TextView
    private lateinit var startStopButton: MaterialButton

    private var serviceRunning = false
    private val logLines = mutableListOf<String>()

    private val channelNames = arrayOf(
        "Alpha", "Bravo", "Charlie", "Delta",
        "Echo", "Foxtrot", "Golf", "Hotel"
    )

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                ACTION_STATUS_UPDATE -> updateStatusFromIntent(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleStatusDot = findViewById(R.id.bleStatusDot)
        bleStatusText = findViewById(R.id.bleStatusText)
        channelText = findViewById(R.id.channelText)
        peerCountText = findViewById(R.id.peerCountText)
        packetCountText = findViewById(R.id.packetCountText)
        serviceStatusText = findViewById(R.id.serviceStatusText)
        logText = findViewById(R.id.logText)
        startStopButton = findViewById(R.id.startStopButton)

        startStopButton.setOnClickListener { toggleService() }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun updateStatusFromIntent(intent: Intent) {
        val bleConnected = intent.getBooleanExtra(EXTRA_BLE_CONNECTED, false)
        val channel = intent.getIntExtra(EXTRA_CHANNEL, 0)
        val peerCount = intent.getIntExtra(EXTRA_PEER_COUNT, 0)
        val packetCount = intent.getLongExtra(EXTRA_PACKET_COUNT, 0)
        val logLine = intent.getStringExtra(EXTRA_LOG_LINE)

        // Update BLE status
        if (bleConnected) {
            bleStatusDot.setBackgroundResource(R.drawable.status_dot_green)
            bleStatusText.text = "Connected"
            bleStatusText.setTextColor(0xFF00E676.toInt())
        } else {
            bleStatusDot.setBackgroundResource(R.drawable.status_dot_red)
            bleStatusText.text = "Waiting for glasses..."
            bleStatusText.setTextColor(0xFFFF5252.toInt())
        }

        // Update channel
        val chName = if (channel in channelNames.indices) channelNames[channel] else "Ch$channel"
        channelText.text = "$chName ($channel)"

        // Update mesh stats
        peerCountText.text = peerCount.toString()
        packetCountText.text = packetCount.toString()

        // Update log
        if (logLine != null) {
            logLines.add(logLine)
            if (logLines.size > 50) logLines.removeAt(0)
            logText.text = logLines.takeLast(12).joinToString("\n")
        }
    }

    private fun toggleService() {
        if (serviceRunning) {
            stopCompanionService()
        } else {
            startCompanionService()
        }
    }

    private fun startCompanionService() {
        val intent = Intent(this, CompanionService::class.java)
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        serviceStatusText.text = "Running"
        serviceStatusText.setTextColor(0xFF00E676.toInt())
        startStopButton.text = "STOP SERVICE"
        startStopButton.setBackgroundColor(0xFFFF5252.toInt())
        addLog("Service started")
    }

    private fun stopCompanionService() {
        val intent = Intent(this, CompanionService::class.java)
        stopService(intent)
        serviceRunning = false
        serviceStatusText.text = "Stopped"
        serviceStatusText.setTextColor(0xFFFF5252.toInt())
        startStopButton.text = "START SERVICE"
        startStopButton.setBackgroundColor(0xFF00E676.toInt())
        addLog("Service stopped")
    }

    private fun addLog(msg: String) {
        logLines.add(msg)
        if (logLines.size > 50) logLines.removeAt(0)
        logText.text = logLines.takeLast(12).joinToString("\n")
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                addLog("Permissions denied: ${denied.joinToString()}")
            } else {
                addLog("All permissions granted")
            }
        }
    }
}
