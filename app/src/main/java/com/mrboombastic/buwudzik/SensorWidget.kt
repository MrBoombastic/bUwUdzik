package com.mrboombastic.buwudzik

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "SensorWidget"
        const val ACTION_FORCE_UPDATE = "com.mrboombastic.buwudzik.ACTION_FORCE_UPDATE"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        
        Log.d(TAG, "onUpdate called for ${appWidgetIds.size} widget(s)")
        
        // Batch update all widgets with shared data
        val widgetData = WidgetData.load(context)
        for (appWidgetId in appWidgetIds) {
            updateAppWidgetInternal(context, appWidgetManager, appWidgetId, widgetData, isLoading = false)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "First widget added, scheduling updates...")

        val settingsRepository = SettingsRepository(context)
        MainActivity.scheduleUpdates(context, settingsRepository.updateInterval)
        WidgetHelper.triggerImmediateUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "Last widget removed")
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_FORCE_UPDATE) {
            Log.d(TAG, "Force update requested")
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, SensorWidget::class.java)
            )
            
            if (widgetIds.isNotEmpty()) {
                // Show loading state with cached data
                val widgetData = WidgetData.load(context)
                for (appWidgetId in widgetIds) {
                    updateAppWidgetInternal(context, appWidgetManager, appWidgetId, widgetData, isLoading = true)
                }
                
                // Trigger background update
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequest.Builder(SensorUpdateWorker::class.java).build()
                )
            }
        }
    }
}

/**
 * Cached widget data to avoid repeated SharedPreferences reads
 */
private data class WidgetData(
    val sensorData: SensorData?,
    val lastUpdate: Long,
    val locale: Locale,
    val selectedAppPackage: String?
) {
    companion object {
        fun load(context: Context): WidgetData {
            val sensorRepo = SensorRepository(context)
            val settingsRepo = SettingsRepository(context)
            
            val lang = settingsRepo.language
            val locale = if (lang == "system") Locale.getDefault() else Locale.forLanguageTag(lang)
            
            return WidgetData(
                sensorData = sensorRepo.getSensorData(),
                lastUpdate = sensorRepo.getLastUpdateTimestamp(),
                locale = locale,
                selectedAppPackage = settingsRepo.selectedAppPackage
            )
        }
    }
}

/**
 * Public function for external callers (WidgetHelper, SensorUpdateWorker)
 */
internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    isLoading: Boolean = false
) {
    val widgetData = WidgetData.load(context)
    updateAppWidgetInternal(context, appWidgetManager, appWidgetId, widgetData, isLoading)
}

/**
 * Internal optimized update function
 */
private fun updateAppWidgetInternal(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    data: WidgetData,
    isLoading: Boolean
) {
    val views = RemoteViews(context.packageName, R.layout.widget_layout)

    // Loading state
    views.setViewVisibility(R.id.widget_refresh_btn, if (isLoading) View.GONE else View.VISIBLE)
    views.setViewVisibility(R.id.widget_loading, if (isLoading) View.VISIBLE else View.GONE)

    // Refresh button intent
    views.setOnClickPendingIntent(
        R.id.widget_refresh_btn,
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SensorWidget::class.java).apply { action = SensorWidget.ACTION_FORCE_UPDATE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )

    // Root click intent
    views.setOnClickPendingIntent(R.id.widget_root, createRootClickIntent(context, data.selectedAppPackage))

    // Sensor data
    val sensorData = data.sensorData
    if (sensorData != null) {
        views.setTextViewText(R.id.widget_temp, "%.1f°C".format(data.locale, sensorData.temperature))
        views.setTextViewText(R.id.widget_humidity, "💧 %.1f%%".format(data.locale, sensorData.humidity))
        views.setTextViewText(R.id.widget_battery, "🔋 ${sensorData.battery}%")
        views.setTextViewText(
            R.id.widget_last_update,
            SimpleDateFormat("dd.MM HH:mm", data.locale).format(Date(data.lastUpdate))
        )
    } else {
        views.setTextViewText(R.id.widget_temp, "--")
        views.setTextViewText(R.id.widget_humidity, "No Data")
        views.setTextViewText(R.id.widget_battery, "")
        views.setTextViewText(R.id.widget_last_update, "--")
    }

    appWidgetManager.updateAppWidget(appWidgetId, views)
}

private fun createRootClickIntent(context: Context, selectedAppPackage: String?): PendingIntent {
    val intent = if (!selectedAppPackage.isNullOrEmpty()) {
        context.packageManager.getLaunchIntentForPackage(selectedAppPackage)
    } else null
    
    val finalIntent = intent ?: Intent(context, MainActivity::class.java)
    
    return PendingIntent.getActivity(
        context,
        0,
        finalIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

