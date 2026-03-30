package com.mrboombastic.buwudzik.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mrboombastic.buwudzik.BuildConfig
import com.mrboombastic.buwudzik.utils.AppLogger

/**
 * BroadcastReceiver triggered by AlarmManager to initiate widget updates.
 * This ensures reliable periodic updates even with aggressive battery optimization.
 */
class WidgetUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WidgetUpdateReceiver"
        // Use a unique work name to avoid conflicts with manual refresh (force_refresh=true)
        private const val PERIODIC_UPDATE_WORK_NAME = "SensorWidgetPeriodicUpdate"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == BuildConfig.WIDGET_UPDATE_ACTION) {
            AppLogger.d(TAG, "AlarmManager triggered widget update")

            // Enqueue a one-time work request to fetch sensor data
            // Use KEEP policy to avoid canceling in-flight manual refreshes
            val workRequest = OneTimeWorkRequestBuilder<SensorUpdateWorker>()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                PERIODIC_UPDATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
