package com.mrboombastic.buwudzik.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.WidgetPreferencesRepository
import com.mrboombastic.buwudzik.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * ActionCallback for handling refresh button clicks in the Glance widget.
 * Triggers a one-time WorkManager request to scan for sensor data.
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

        if (!mac.isNullOrEmpty()) {
            SensorRepository(context, mac).setLoading(true)
            AppLogger.d(TAG, "Set loading=true for widget device $mac")
        } else {
            AppLogger.w(TAG, "No MAC mapped for widget $appWidgetId; worker will still run")
        }

        SensorGlanceWidget().update(context, glanceId)
        SensorWidgetRefresher.updateAll(context)

        val workRequest = OneTimeWorkRequestBuilder<SensorUpdateWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean("force_refresh", true)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 3, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "SensorWidgetRefresh",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
