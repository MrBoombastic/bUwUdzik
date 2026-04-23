package com.mrboombastic.buwudzik.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SensorRepository
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

    /**
     * Finds all widgets mapped to [mac] and pushes the latest data from [SensorRepository]
     * directly into their Glance Preferences.
     */
    suspend fun updateDeviceData(context: Context, mac: String) {
        try {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(SensorGlanceWidget::class.java)
            val sensorRepo = SensorRepository(context, mac)
            val profileRepo = DeviceProfileRepository(context)

            val data = sensorRepo.getSensorData()
            val alias = profileRepo.getByMac(mac)?.alias?.trim().orEmpty()
            val hasError = sensorRepo.hasUpdateError()
            val isLoading = sensorRepo.isLoading()
            val lastUpdate = sensorRepo.getLastUpdateTimestamp()

            ids.forEach { id ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                    // Only update if this widget is explicitly assigned to this MAC in its internal state
                    if (prefs[SensorGlanceWidget.KEY_MAC] == mac) {
                        prefs.toMutablePreferences().apply {
                            if (data != null) {
                                set(SensorGlanceWidget.KEY_TEMP, data.temperature)
                                set(SensorGlanceWidget.KEY_HUMIDITY, data.humidity)
                                set(SensorGlanceWidget.KEY_BATTERY, data.battery)
                            }
                            set(SensorGlanceWidget.KEY_LAST_UPDATE, lastUpdate)
                            set(SensorGlanceWidget.KEY_HAS_ERROR, hasError)
                            set(SensorGlanceWidget.KEY_IS_LOADING, isLoading)
                            set(SensorGlanceWidget.KEY_DEVICE_ALIAS, alias)
                        }
                    } else {
                        prefs
                    }
                }
                SensorGlanceWidget().update(context, id)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to push data to widgets for $mac", e)
        }
    }
}
