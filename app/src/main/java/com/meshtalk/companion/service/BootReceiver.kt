package com.meshtalk.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receives BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts to auto-start
 * CompanionService after device boot or app update.
 *
 * Registered in AndroidManifest.xml with RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",  // HTC/Samsung fast boot
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot/update received ($action), starting CompanionService")
                startCompanionService(context)
            }
        }
    }

    private fun startCompanionService(context: Context) {
        try {
            val serviceIntent = Intent(context, CompanionService::class.java).apply {
                putExtra("started_by", "boot_receiver")
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "CompanionService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CompanionService on boot: ${e.message}", e)
        }
    }
}
