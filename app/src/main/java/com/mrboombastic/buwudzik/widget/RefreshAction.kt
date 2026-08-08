package com.mrboombastic.buwudzik.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.WidgetPreferencesRepository
import com.mrboombastic.buwudzik.utils.AppLogger

/**
 * ActionCallback for handling the refresh button clicks in the Glance widget.
 * Starts a user-initiated connected-device foreground service so the refresh is not
 * delayed by JobScheduler quota on recent Android versions.
 */
class RefreshAction : ActionCallback {
    companion object {
        private const val TAG = "RefreshAction"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        AppLogger.d(TAG, "Refresh button clicked, triggering sensor update")

        val appWidgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not resolve AppWidget id for glance", e)
            AppWidgetManager.INVALID_APPWIDGET_ID
        }

        val mac = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetPreferencesRepository(context).getDeviceMacForWidget(appWidgetId)
        } else {
            null
        }

        if (mac.isNullOrEmpty()) {
            AppLogger.w(TAG, "No MAC mapped for widget $appWidgetId; refresh ignored")
            return
        }

        // Start the service immediately while the app still has the widget-interaction exemption.
        try {
            ContextCompat.startForegroundService(
                context,
                WidgetManualRefreshService.intent(context, mac)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not start manual widget refresh", e)
            SensorRepository(context, mac).apply {
                setLoading(false)
                setUpdateError(true)
            }
            SensorWidgetRefresher.updateDeviceData(context, mac)
        }
    }
}
