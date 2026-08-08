package com.mrboombastic.buwudzik.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.data.WidgetPreferencesRepository
import com.mrboombastic.buwudzik.utils.AppLogger

class SensorGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SensorGlanceWidget()

    companion object {
        private const val TAG = "SensorGlanceReceiver"
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AppLogger.d(TAG, "Widget enabled - scheduling periodic updates with AlarmManager")
        val settingsRepository = SettingsRepository(context)
        val intervalMinutes = settingsRepository.updateInterval
        WidgetUpdateScheduler.scheduleUpdates(context, intervalMinutes)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        AppLogger.d(TAG, "Widget disabled - canceling periodic updates")
        WidgetUpdateScheduler.cancelUpdates(context)
        WidgetBleScanCoordinator.cancel(context)
    }

    /**
     * Called when one or more widget instances are deleted.
     * Cleans up the appWidgetId → MAC mapping.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val widgetPrefs = WidgetPreferencesRepository(context)
        for (id in appWidgetIds) {
            AppLogger.d(TAG, "Widget $id deleted - removing MAC mapping")
            widgetPrefs.removeWidget(id)
        }
    }
}
