package com.meshtalk.companion.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Two-layer watchdog ensuring CompanionService stays alive 24/7:
 *
 * Layer 1: AlarmManager exact repeating alarm every 5 minutes → WatchdogAlarmReceiver
 * Layer 2: WorkManager PeriodicWorkRequest every 15 minutes (minimum interval)
 *
 * Both check if CompanionService is running and restart it if not.
 */
object ServiceWatchdog {

    private const val TAG = "ServiceWatchdog"
    private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
    private const val ALARM_REQUEST_CODE = 9001
    private const val WORK_NAME = "meshtalk_service_watchdog"

    /**
     * Call from MainActivity.onCreate() and CompanionService.onCreate()
     * to arm both watchdog layers.
     */
    fun arm(context: Context) {
        armAlarm(context)
        armWorkManager(context)
        Log.i(TAG, "Watchdog armed (alarm + WorkManager)")
    }

    /**
     * Call from stopService flow if user explicitly stops.
     */
    fun disarm(context: Context) {
        disarmAlarm(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "Watchdog disarmed")
    }

    // ── Layer 1: AlarmManager ─────────────────────────────────────

    private fun armAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = makeAlarmPendingIntent(context)
        alarmManager.cancel(pi)  // Cancel existing before re-arming
        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
            ALARM_INTERVAL_MS,
            pi
        )
    }

    private fun disarmAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(makeAlarmPendingIntent(context))
    }

    private fun makeAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Schedule a one-shot restart alarm — used from onTaskRemoved().
     */
    fun scheduleRestartAlarm(context: Context, delayMs: Long = 3000L) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WatchdogAlarmReceiver::class.java).apply {
            putExtra("force_restart", true)
        }
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pi
        )
        Log.i(TAG, "Restart alarm scheduled in ${delayMs}ms")
    }

    // ── Layer 2: WorkManager ──────────────────────────────────────

    private fun armWorkManager(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(
            15, TimeUnit.MINUTES  // Minimum interval for periodic work
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    // ── Service Check ─────────────────────────────────────────────

    fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in am.getRunningServices(Integer.MAX_VALUE)) {
            if (CompanionService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun ensureServiceRunning(context: Context) {
        if (!isServiceRunning(context)) {
            Log.w(TAG, "CompanionService not running — restarting!")
            try {
                val intent = Intent(context, CompanionService::class.java).apply {
                    putExtra("started_by", "watchdog")
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog failed to restart service: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "CompanionService is running — watchdog OK")
        }
    }
}

// ── Alarm Receiver ────────────────────────────────────────────────

class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("WatchdogAlarm", "Alarm fired, checking service...")
        ServiceWatchdog.ensureServiceRunning(context)
    }
}

// ── WorkManager Worker ────────────────────────────────────────────

class WatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        Log.d("WatchdogWorker", "Periodic check, ensuring service alive...")
        ServiceWatchdog.ensureServiceRunning(applicationContext)
        return Result.success()
    }
}
