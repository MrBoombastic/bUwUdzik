package com.mrboombastic.buwudzik

import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class SensorUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("SensorUpdateWorker", "Starting background scan...")

        val bluetoothManager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter == null) {
            Log.e("SensorUpdateWorker", "Bluetooth adapter not available.")
            return Result.failure()
        }
        
        if (!bluetoothManager.adapter.isEnabled) {
            Log.w("SensorUpdateWorker", "Bluetooth is disabled. Cannot scan for sensors.")
            // Return success to keep periodic work going, but don't update anything
            return Result.success()
        }

        val scanner = BluetoothScanner(applicationContext)
        val repository = SensorRepository(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)
        val targetMac = settingsRepository.targetMacAddress

        // Attempt to scan for 10 seconds max
        val result = withTimeoutOrNull(10_000L) {
            try {
                // Collect the first emission and stop
                scanner.scan(targetMac).first()
            } catch (e: Exception) {
                Log.e("SensorUpdateWorker", "Error scanning", e)
                null
            }
        }

        if (result != null) {
            Log.d("SensorUpdateWorker", "Got data: $result")
            repository.saveSensorData(result)
            updateWidget()
            return Result.success()
        } else {
            Log.w("SensorUpdateWorker", "No sensor found within timeout.")
            // We return success even if we didn't find it to keep the periodic work going
            // Or we could return Result.retry() if we want aggressive retries
            return Result.success()
        }
    }

    private fun updateWidget() {
        val intent = Intent(applicationContext, SensorWidget::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val ids = AppWidgetManager.getInstance(applicationContext)
            .getAppWidgetIds(ComponentName(applicationContext, SensorWidget::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        applicationContext.sendBroadcast(intent)
    }
}
