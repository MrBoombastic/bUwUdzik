package com.mrboombastic.buwudzik.widget

import com.mrboombastic.buwudzik.utils.AppLogger


import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

/**
 * Helper object for widget-related operations.
 * Centralizes widget update logic to avoid code duplication.
 */
object WidgetHelper {

    private const val TAG = "WidgetHelper"

    /**
     * Updates all instances of SensorWidget.
     * Call this when settings change or new data is available.
     */
    fun updateAllWidgets(context: Context, isLoading: Boolean = false) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, SensorWidget::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (widgetIds.isEmpty()) {
                AppLogger.d(TAG, "No widgets to update")
                return
            }

            AppLogger.d(TAG, "Updating ${widgetIds.size} widget(s), isLoading=$isLoading")
            for (widgetId in widgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId, isLoading)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widgets", e)
        }
    }

    /**
     * Triggers an immediate background scan and widget update.
     * Use this for manual refresh or when the app detects new data.
     */
    fun triggerImmediateUpdate(context: Context) {
        AppLogger.d(TAG, "Triggering immediate widget update...")

        // Show loading state first
        updateAllWidgets(context, isLoading = true)

        // Enqueue background work
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequest.Builder(SensorUpdateWorker::class.java).build()
        )
    }
}




