package com.meshtalk.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

class CompanionApp : Application() {

    companion object {
        const val TAG = "MeshTalk"
        const val NOTIFICATION_CHANNEL_ID = "meshtalk_service"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "MeshTalk Companion initialized")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "MeshTalk Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MeshTalk BLE relay service"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
