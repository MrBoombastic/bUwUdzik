package com.mrboombastic.buwudzik.widget

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.data.WidgetPreferencesRepository
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.viewmodels.MainViewModel
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
        private const val FRESH_DATA_THRESHOLD_MS = 5_000L
    }

    override suspend fun doWork(): Result {
        AppLogger.d(TAG, "Starting background scan (attempt ${runAttemptCount + 1})...")

        val settingsRepository = SettingsRepository(applicationContext)
        val intervalMinutes = settingsRepository.updateInterval
        val forceRefresh = inputData.getBoolean("force_refresh", false)

        val widgetPrefs = WidgetPreferencesRepository(applicationContext)
        val uniqueMacs = widgetPrefs.getAllWidgetMacs().values.toSet()
        val profileRepo = DeviceProfileRepository(applicationContext)
        val activeMac = profileRepo.getActiveDeviceId()

        val macsToProcess = when {
            uniqueMacs.isNotEmpty() -> uniqueMacs
            activeMac != null -> setOf(activeMac)
            else -> emptySet()
        }

        return try {
            if (macsToProcess.isEmpty()) {
                AppLogger.d(TAG, "No widgets or active device configured, skipping scan")
                WidgetUpdateScheduler.scheduleUpdates(applicationContext, intervalMinutes)
                Result.success()
            } else {
                var anyError = false
                for (mac in macsToProcess) {
                    val r = scanDevice(mac, forceRefresh, profileRepo)
                    if (r != Result.success()) anyError = true

                    // Push the results (or errors) to Glance widgets
                    SensorWidgetRefresher.updateDeviceData(applicationContext, mac)
                }

                WidgetUpdateScheduler.scheduleUpdates(applicationContext, intervalMinutes)

                if (anyError && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry()
                else Result.success()
            }
        } finally {
            macsToProcess.forEach { mac ->
                SensorRepository(applicationContext, mac, profileRepo).setLoading(false)
                SensorWidgetRefresher.updateDeviceData(applicationContext, mac)
            }
        }
    }

    private suspend fun scanDevice(
        mac: String,
        forceRefresh: Boolean,
        profileRepo: DeviceProfileRepository
    ): Result {
        val repository = SensorRepository(applicationContext, mac, profileRepo)

        if (mac.equals(MainViewModel.FAKE_MAC, ignoreCase = true)) {
            AppLogger.d(TAG, "[$mac] Fake device active, using last data from app")
            if (forceRefresh) {
                // Generate a tiny random variation so the user sees something changed
                val lastData = repository.getSensorData()
                val newTemp = (lastData?.temperature ?: 22.0) + (-2..2).random() / 10.0
                val newHum = (lastData?.humidity ?: 45.0) + (-5..5).random() / 10.0
                repository.saveSensorData(
                    com.mrboombastic.buwudzik.device.SensorData(
                        temperature = newTemp.coerceIn(15.0, 30.0),
                        humidity = newHum.coerceIn(20.0, 80.0),
                        battery = 100,
                        rssi = -50,
                        name = "Fake clOwOck",
                        macAddress = mac,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            return Result.success()
        }

        val lastUpdate = repository.getLastUpdateTimestamp()
        val dataAge = System.currentTimeMillis() - lastUpdate
        val shouldSkipScan = dataAge < FRESH_DATA_THRESHOLD_MS && lastUpdate > 0

        if (shouldSkipScan && !forceRefresh) {
            AppLogger.d(TAG, "[$mac] Data is fresh (${dataAge}ms old), skipping scan")
            return Result.success()
        }

        val bluetoothManager =
            applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager?.adapter?.isEnabled != true) {
            AppLogger.w(TAG, "[$mac] Bluetooth unavailable")
            return Result.failure()
        }

        if (!com.mrboombastic.buwudzik.ui.utils.BluetoothUtils.hasBluetoothPermissions(
                applicationContext
            )
        ) {
            AppLogger.w(TAG, "[$mac] Missing Bluetooth permissions")
            return Result.success()
        }

        val deviceProfileRepository = DeviceProfileRepository(applicationContext)
        val scanner = BluetoothScanner(applicationContext, deviceProfileRepository)
        val scanMode =
            if (forceRefresh) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED

        val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            try {
                scanner.scan(mac, scanMode).first()
            } catch (e: Exception) {
                AppLogger.e(TAG, "[$mac] Error during scan", e)
                null
            }
        }

        return if (result != null) {
            AppLogger.d(
                TAG,
                "[$mac] Got data: temp=${result.temperature}°C, humidity=${result.humidity}%"
            )
            repository.saveSensorData(result)
            Result.success()
        } else {
            AppLogger.w(TAG, "[$mac] No data received within timeout")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else {
                repository.setUpdateError(true)
                Result.success()
            }
        }
    }
}
