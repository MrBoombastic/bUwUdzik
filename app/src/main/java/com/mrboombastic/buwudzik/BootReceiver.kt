package com.mrboombastic.buwudzik


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.widget.SensorUpdateWorker
import java.util.concurrent.TimeUnit

/**
 * Receiver that triggers widget update after device boot.
 * Also re-schedules periodic updates since AlarmManager alarms are cleared on reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {

            AppLogger.d(TAG, "Boot completed or package replaced, re-scheduling updates...")

            val settingsRepository = SettingsRepository(context)
            val intervalMinutes = settingsRepository.updateInterval

            // Re-schedule periodic updates (AlarmManager alarms are cleared on reboot)
            MainActivity.scheduleUpdates(context, intervalMinutes)

            // Schedule an immediate update with a small delay to let the system settle
            val initialWorkRequest = OneTimeWorkRequestBuilder<SensorUpdateWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(initialWorkRequest)

            AppLogger.d(
                TAG,
                "Scheduled initial update and periodic updates every $intervalMinutes minutes"
            )
        }
    }
}


