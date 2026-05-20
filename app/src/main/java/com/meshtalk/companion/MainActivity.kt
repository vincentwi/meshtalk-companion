package com.meshtalk.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.meshtalk.companion.service.CompanionService
import com.meshtalk.companion.service.ServiceWatchdog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val BATTERY_OPT_REQUEST_CODE = 101
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

        // Request battery optimization exemption (critical for 24/7 operation)
        requestBatteryOptimizationExemption()

        // Samsung-specific: warn about aggressive battery management
        checkSamsungBatteryOptimization()

        // Arm the service watchdog
        ServiceWatchdog.arm(this)

        // Auto-start service on launch
        startCompanionService()
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
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: "alpha"
        val peerCount = intent.getIntExtra(EXTRA_PEER_COUNT, 0)
        val packetCount = intent.getLongExtra(EXTRA_PACKET_COUNT, 0)
        val logLine = intent.getStringExtra(EXTRA_LOG_LINE)
        val meshConnected = intent.getBooleanExtra(
            com.meshtalk.companion.service.EXTRA_MESH_CONNECTED, false)

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
        channelText.text = channel.replaceFirstChar { it.uppercase() }

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
        // Disarm watchdog so it doesn't restart the service
        ServiceWatchdog.disarm(this)
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

    // ── Battery Optimization Exemption ────────────────────────────

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.w(TAG, "App is NOT exempt from battery optimizations — requesting exemption")
            addLog("Requesting battery optimization exemption...")
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, BATTERY_OPT_REQUEST_CODE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization exemption: ${e.message}", e)
                addLog("Could not request battery exemption")
            }
        } else {
            Log.i(TAG, "Battery optimization exemption already granted")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BATTERY_OPT_REQUEST_CODE) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                addLog("Battery optimization exemption GRANTED")
                Log.i(TAG, "Battery optimization exemption granted")
            } else {
                addLog("Battery optimization exemption DENIED — service may be killed")
                Log.w(TAG, "Battery optimization exemption denied")
            }
        }
    }

    // ── Samsung-specific Battery Optimization ─────────────────────

    private fun checkSamsungBatteryOptimization() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer != "samsung") return

        Log.w(TAG, "Samsung device detected (${Build.MODEL}) — aggressive battery management active")
        addLog("Samsung detected: check battery settings!")

        // Check shared prefs to avoid nagging every launch
        val prefs = getSharedPreferences("meshtalk_prefs", MODE_PRIVATE)
        val dismissed = prefs.getBoolean("samsung_battery_dialog_dismissed", false)
        if (dismissed) {
            Log.i(TAG, "Samsung battery dialog previously dismissed")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Samsung Battery Optimization")
            .setMessage(
                "Samsung devices aggressively kill background apps. " +
                "For MeshTalk to run reliably 24/7:\n\n" +
                "1. Go to Settings → Battery → Background usage limits\n" +
                "2. Tap 'Never sleeping apps'\n" +
                "3. Add MeshTalk to the list\n\n" +
                "Also: Settings → Apps → MeshTalk → Battery → Unrestricted\n\n" +
                "Without this, Samsung will kill MeshTalk within minutes."
            )
            .setPositiveButton("Open Battery Settings") { _, _ ->
                try {
                    // Try Samsung-specific battery settings
                    val intent = Intent().apply {
                        component = android.content.ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity"
                        )
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fall back to generic battery settings
                    try {
                        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Could not open battery settings", e2)
                        addLog("Please manually open Settings → Battery")
                    }
                }
            }
            .setNegativeButton("Remind Later", null)
            .setNeutralButton("Don't Show Again") { _, _ ->
                prefs.edit().putBoolean("samsung_battery_dialog_dismissed", true).apply()
            }
            .setCancelable(true)
            .show()
    }

    // ── Permissions ───────────────────────────────────────────────

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
