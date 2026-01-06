package com.mrboombastic.buwudzik.widget


import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class SensorUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SensorUpdateWorker"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val FRESH_DATA_THRESHOLD_MS = 30_000L // 30 seconds
    }

    override suspend fun doWork(): Result {
        AppLogger.d(TAG, "Starting background scan (attempt ${runAttemptCount + 1})...")

        val repository = SensorRepository(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)

        // Check if we have fresh data already (from foreground app)
        val lastUpdate = repository.getLastUpdateTimestamp()
        val dataAge = System.currentTimeMillis() - lastUpdate
        if (dataAge < FRESH_DATA_THRESHOLD_MS && lastUpdate > 0) {
            AppLogger.d(TAG, "Data is fresh (${dataAge}ms old), skipping scan and updating widget")
            updateWidget()
            return Result.success()
        }

        val bluetoothManager =
            applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager?.adapter == null) {
            Log.e(TAG, "Bluetooth adapter not available.")
            updateWidgetWithError()
            return Result.failure()
        }

        if (!bluetoothManager.adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled. Cannot scan for sensors.")
            updateWidget()
            return Result.success()
        }

        val scanner = BluetoothScanner(applicationContext)
        val targetMac = settingsRepository.targetMacAddress

        // Attempt to scan with timeout
        val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            try {
                scanner.scan(targetMac).first()
            } catch (e: Exception) {
                Log.e(TAG, "Error during scan", e)
                null
            }
        }

        return if (result != null) {
            AppLogger.d(
                TAG,
                "Got data: temp=${result.temperature}°C, humidity=${result.humidity}%, battery=${result.battery}%"
            )
            repository.saveSensorData(result)
            updateWidget()
            Result.success()
        } else {
            Log.w(TAG, "No sensor data received within timeout.")

            // Check again if data arrived from foreground while we were scanning
            val freshLastUpdate = repository.getLastUpdateTimestamp()
            if (freshLastUpdate > lastUpdate) {
                AppLogger.d(TAG, "Fresh data arrived during scan, updating widget")
                updateWidget()
                return Result.success()
            }

            // Retry if we haven't exceeded max attempts
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                AppLogger.d(
                    TAG,
                    "Will retry (attempt ${runAttemptCount + 1} of $MAX_RETRY_ATTEMPTS)"
                )
                Result.retry()
            } else {
                Log.w(TAG, "Max retry attempts reached, giving up for this cycle.")
                updateWidget() // Update widget to show last known data
                Result.success() // Return success to keep periodic work scheduled
            }
        }
    }

    private fun updateWidget() {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            val componentName = ComponentName(applicationContext, SensorWidget::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)

            if (ids.isNotEmpty()) {
                AppLogger.d(TAG, "Updating ${ids.size} widget(s)")
                for (id in ids) {
                    updateAppWidget(applicationContext, appWidgetManager, id, isLoading = false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widget", e)
        }
    }

    private fun updateWidgetWithError() {
        // Still update widget to show whatever data we have
        updateWidget()
    }
}





