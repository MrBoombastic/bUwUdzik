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
        val uniqueMacs = widgetPrefs.getAllWidgetMacs().values
            .filter { !it.equals(MainViewModel.FAKE_MAC, ignoreCase = true) }
            .toSet()
        val profileRepo = DeviceProfileRepository(applicationContext)
        val activeMac = profileRepo.getActiveDeviceId()

        val macsToClearLoading = when {
            uniqueMacs.isNotEmpty() -> uniqueMacs
            activeMac != null -> setOf(activeMac)
            else -> emptySet()
        }

        return try {
            if (uniqueMacs.isEmpty()) {
                if (activeMac != null) {
                    val result = scanDevice(activeMac, forceRefresh, profileRepo)
                    updateWidget(result != Result.success(), intervalMinutes)
                    result
                } else {
                    AppLogger.d(TAG, "No widgets or active device configured, skipping scan")
                    updateWidget(hasError = false, intervalMinutes = intervalMinutes)
                    Result.success()
                }
            } else {
                var anyError = false
                for (mac in uniqueMacs) {
                    val r = scanDevice(mac, forceRefresh, profileRepo)
                    if (r != Result.success()) anyError = true
                }
                updateWidget(hasError = anyError, intervalMinutes = intervalMinutes)
                if (anyError && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry()
                else Result.success()
            }
        } finally {
            macsToClearLoading.forEach { mac ->
                SensorRepository(applicationContext, mac, profileRepo).setLoading(false)
            }
        }
    }

    private suspend fun scanDevice(
        mac: String,
        forceRefresh: Boolean,
        profileRepo: DeviceProfileRepository
    ): Result {
        if (mac.equals(MainViewModel.FAKE_MAC, ignoreCase = true)) {
            AppLogger.d(TAG, "[$mac] Fake device active, skipping background scan")
            return Result.success()
        }

        val repository = SensorRepository(applicationContext, mac, profileRepo)

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

    private suspend fun updateWidget(hasError: Boolean, intervalMinutes: Long) {
        try {
            AppLogger.d(TAG, "Updating all Glance widgets, hasError=$hasError")
            SensorWidgetRefresher.updateAll(applicationContext)
        } finally {
            WidgetUpdateScheduler.scheduleUpdates(applicationContext, intervalMinutes)
        }
    }
}
