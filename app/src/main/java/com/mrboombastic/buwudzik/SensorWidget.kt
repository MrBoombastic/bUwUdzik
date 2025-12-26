package com.mrboombastic.buwudzik

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
        const val ACTION_FORCE_UPDATE = "com.mrboombastic.buwudzik.ACTION_FORCE_UPDATE"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_FORCE_UPDATE) {
            // Show loading state immediately
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, SensorWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, isLoading = true)
            }

            // Trigger immediate background update
            val workRequest = OneTimeWorkRequest.Builder(SensorUpdateWorker::class.java).build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}

internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    isLoading: Boolean = false
) {
    val repository = SensorRepository(context)
    val settingsRepository = SettingsRepository(context)
    val data = repository.getSensorData()
    val lastUpdate = repository.getLastUpdateTimestamp()

    val lang = settingsRepository.language
    val theme = settingsRepository.theme

    val locale = if (lang == "system") Locale.getDefault() else Locale.forLanguageTag(lang)

    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    context.createConfigurationContext(config)

    val layoutId = when (theme) {
        "light" -> R.layout.widget_layout_light
        "dark" -> R.layout.widget_layout_dark
        else -> R.layout.widget_layout
    }

    val views = RemoteViews(context.packageName, layoutId)

    // Visibility Logic
    if (isLoading) {
        views.setViewVisibility(R.id.widget_refresh_btn, View.GONE)
        views.setViewVisibility(R.id.widget_loading, View.VISIBLE)
    } else {
        views.setViewVisibility(R.id.widget_refresh_btn, View.VISIBLE)
        views.setViewVisibility(R.id.widget_loading, View.GONE)
    }

    // Refresh Button Intent
    val refreshIntent = Intent(context, SensorWidget::class.java).apply {
        action = SensorWidget.ACTION_FORCE_UPDATE
    }
    val refreshPendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        refreshIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPendingIntent)

    // App Launch Intent
    val selectedPackage = settingsRepository.selectedAppPackage
    if (!selectedPackage.isNullOrEmpty()) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(selectedPackage)
            if (launchIntent != null) {
                val launchPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, launchPendingIntent)
            }
        } catch (e: Exception) {
            Log.e("SensorWidget", "Failed to create launch intent", e)
        }
    } else {
        // Open Settings or Main App if no app selected
        val mainIntent = Intent(context, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)
    }

    if (data != null) {
        val tempRounded = "%.1f".format(locale, data.temperature)
        val humFormatted = "%.1f".format(locale, data.humidity)

        views.setTextViewText(R.id.widget_temp, "${tempRounded}°C")
        views.setTextViewText(R.id.widget_humidity, "💧 ${humFormatted}%")
        views.setTextViewText(R.id.widget_battery, "🔋 ${data.battery}%")

        val sdf = SimpleDateFormat("dd.MM HH:mm", locale)
        val dateStr = sdf.format(Date(lastUpdate))
        views.setTextViewText(R.id.widget_last_update, dateStr)
    } else {
        views.setTextViewText(R.id.widget_temp, "--")
        views.setTextViewText(R.id.widget_humidity, "No Data")
    }

    appWidgetManager.updateAppWidget(appWidgetId, views)
}
