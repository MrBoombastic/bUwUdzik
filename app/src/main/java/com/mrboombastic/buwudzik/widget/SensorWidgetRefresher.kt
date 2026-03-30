package com.mrboombastic.buwudzik.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.mrboombastic.buwudzik.utils.AppLogger

private const val TAG = "SensorWidgetRefresher"

/** Single entry point for Glance widget refresh; failures are non-fatal. */
object SensorWidgetRefresher {
    suspend fun updateAll(context: Context) {
        try {
            SensorGlanceWidget().updateAll(context)
        } catch (e: Exception) {
            AppLogger.d(TAG, "Widget update failed: ${e.message}")
        }
    }
}
