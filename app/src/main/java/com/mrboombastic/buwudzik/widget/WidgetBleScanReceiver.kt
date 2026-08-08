package com.mrboombastic.buwudzik.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives BLE results even when the app process was not already running. */
class WidgetBleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when {
                    WidgetBleScanCoordinator.isResultAction(intent) ->
                        WidgetBleScanCoordinator.handleResult(context, intent)

                    WidgetBleScanCoordinator.isTimeoutAction(intent) ->
                        WidgetBleScanCoordinator.handleTimeout(context)
                }
            } catch (e: Exception) {
                AppLogger.e("WidgetBleScanReceiver", "Failed to handle BLE scan broadcast", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
