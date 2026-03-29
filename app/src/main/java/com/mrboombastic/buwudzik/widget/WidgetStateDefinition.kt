package com.mrboombastic.buwudzik.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.glance.state.GlanceStateDefinition
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Widget state data class that holds all the data needed to render the widget.
 */
data class WidgetState(
    val sensorData: SensorData? = null,
    val lastUpdate: Long = 0,
    val hasError: Boolean = false,
    val isLoading: Boolean = false,
    val language: String = "system",
    val showWidgetError: Boolean = true,
    val deviceName: String = "",
    val mac: String = ""
)

/**
 * Custom DataStore that reads widget state from repositories for a specific device (mac).
 * Uses callbackFlow to reactively observe SharedPreferences changes.
 */
class WidgetStateDataStore(private val context: Context, private val mac: String) :
    DataStore<WidgetState> {

    companion object {
        private const val TAG = "WidgetStateDataStore"

        private const val SENSOR_PREFS_NAME = "sensor_prefs"
        private const val SETTINGS_PREFS_NAME = "settings_prefs"
        private const val SETTINGS_KEY_LANGUAGE = "language"
        private const val SETTINGS_KEY_SHOW_WIDGET_ERROR = "show_widget_error"

        // Cache: mac → instance
        @SuppressLint("StaticFieldLeak")
        private val instances = ConcurrentHashMap<String, WidgetStateDataStore>()

        fun getInstance(context: Context, mac: String): WidgetStateDataStore {
            return instances.getOrPut(mac) {
                WidgetStateDataStore(context.applicationContext, mac)
            }
        }
    }

    private fun sensorKeyPrefix() =
        if (mac.isEmpty()) "" else "${mac.lowercase().replace(":", "_")}_"

    private val sensorKeys = setOf(
        "temp", "humidity", "battery", "rssi", "name",
        "mac_address", "timestamp", "has_error", "is_loading"
    ).map { "${sensorKeyPrefix()}$it" }.toSet()

    override val data: Flow<WidgetState>
        get() = callbackFlow {
            val sensorRepo = SensorRepository(context, mac)
            val settingsRepo = SettingsRepository(context)

            val sensorPrefs =
                context.getSharedPreferences(SENSOR_PREFS_NAME, Context.MODE_PRIVATE)
            val settingsPrefs =
                context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

            val currentJob = AtomicReference<Job?>(null)

            fun emitCurrentState() {
                val job = launch(Dispatchers.Default) {
                    try {
                        val result = trySend(
                            WidgetState(
                                sensorData = sensorRepo.getSensorData(),
                                lastUpdate = sensorRepo.getLastUpdateTimestamp(),
                                hasError = sensorRepo.hasUpdateError(),
                                isLoading = sensorRepo.isLoading(),
                                language = settingsRepo.language,
                                showWidgetError = settingsRepo.showWidgetError,
                                deviceName = mac,
                                mac = mac
                            )
                        )
                        if (!result.isSuccess) {
                            AppLogger.w(
                                TAG,
                                "Failed to emit widget state: ${result.exceptionOrNull()?.message}"
                            )
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error reading widget state", e)
                    }
                }
                currentJob.getAndSet(job)?.cancel()
            }

            emitCurrentState()

            val listenerRefs = object {
                val sensorListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key != null && key in sensorKeys) emitCurrentState()
                }

                val settingsListener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == SETTINGS_KEY_LANGUAGE || key == SETTINGS_KEY_SHOW_WIDGET_ERROR) {
                            emitCurrentState()
                        }
                    }
            }

            sensorPrefs.registerOnSharedPreferenceChangeListener(listenerRefs.sensorListener)
            settingsPrefs.registerOnSharedPreferenceChangeListener(listenerRefs.settingsListener)

            awaitClose {
                sensorPrefs.unregisterOnSharedPreferenceChangeListener(listenerRefs.sensorListener)
                settingsPrefs.unregisterOnSharedPreferenceChangeListener(listenerRefs.settingsListener)
                currentJob.getAndSet(null)?.cancel()
            }
        }

    override suspend fun updateData(transform: suspend (t: WidgetState) -> WidgetState): WidgetState {
        val sensorRepo = SensorRepository(context, mac)
        val settingsRepo = SettingsRepository(context)

        return WidgetState(
            sensorData = sensorRepo.getSensorData(),
            lastUpdate = sensorRepo.getLastUpdateTimestamp(),
            hasError = sensorRepo.hasUpdateError(),
            isLoading = sensorRepo.isLoading(),
            language = settingsRepo.language,
            showWidgetError = settingsRepo.showWidgetError,
            deviceName = mac,
            mac = mac
        )
    }
}

/**
 * GlanceStateDefinition that provides a per-device WidgetStateDataStore.
 * The fileKey passed by Glance is used as the widget's instance identifier;
 * we look up the associated MAC from WidgetPreferencesRepository.
 */
object WidgetStateDefinition : GlanceStateDefinition<WidgetState> {

    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<WidgetState> {
        // Derive from fileKey -> appWidgetId -> MAC
        val mac = resolveMac(context, fileKey)
        return WidgetStateDataStore.getInstance(context, mac)
    }

    override fun getLocation(context: Context, fileKey: String): File {
        return File(context.cacheDir, "widget_state_placeholder_$fileKey")
    }

    /** Try to parse an appWidgetId from the Glance fileKey and look up the MAC. */
    fun resolveMac(context: Context, fileKey: String): String {
        // Glance's default fileKey format is typically the GlanceId's toString or an index.
        // We try to extract an integer from the key to use as appWidgetId.
        val widgetPrefs = com.mrboombastic.buwudzik.data.WidgetPreferencesRepository(context)
        val appWidgetId = fileKey.filter { it.isDigit() }.toIntOrNull()
        return if (appWidgetId != null) {
            widgetPrefs.getDeviceMacForWidget(appWidgetId) ?: ""
        } else {
            ""
        }
    }
}
