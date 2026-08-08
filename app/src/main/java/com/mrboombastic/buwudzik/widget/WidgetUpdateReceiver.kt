package com.mrboombastic.buwudzik.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mrboombastic.buwudzik.BuildConfig
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered by AlarmManager to initiate widget updates.
 * This ensures reliable periodic updates even with aggressive battery optimization.
 */
class WidgetUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WidgetUpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == BuildConfig.WIDGET_UPDATE_ACTION) {
            AppLogger.d(TAG, "AlarmManager triggered widget update")

            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    WidgetBleScanCoordinator.startScheduledScan(context)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Could not start scheduled BLE scan", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
