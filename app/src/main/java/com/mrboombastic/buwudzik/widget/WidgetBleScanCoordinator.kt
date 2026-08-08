package com.mrboombastic.buwudzik.widget

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.WidgetPreferencesRepository
import com.mrboombastic.buwudzik.data.normalizedBluetoothMac
import com.mrboombastic.buwudzik.device.BleConstants.UUID_SERVICE_ADVERTISING
import com.mrboombastic.buwudzik.device.SensorAdvertisementParser
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.utils.AppLogger

/** Owns the process-independent BLE scan used by scheduled widget refreshes. */
object WidgetBleScanCoordinator {
    private const val TAG = "WidgetBleScan"
    private const val PREFS_NAME = "widget_ble_scan"
    private const val KEY_PENDING_MACS = "pending_macs"
    private const val ACTION_RESULT = "widget.BLE_SCAN_RESULT"
    private const val ACTION_TIMEOUT = "widget.BLE_SCAN_TIMEOUT"
    private const val RESULT_REQUEST_CODE = 4201
    private const val TIMEOUT_REQUEST_CODE = 4202
    private const val SCAN_TIMEOUT_MS = 30_000L
    private const val SCAN_SUCCESS = 0

    @SuppressLint("MissingPermission")
    suspend fun startScheduledScan(context: Context) {
        val appContext = context.applicationContext
        val allMacs = WidgetPreferencesRepository(appContext)
            .getAllWidgetMacs()
            .values
            .map { it.normalizedBluetoothMac() }
            .toSet()

        val invalidMacs = allMacs.filterTo(mutableSetOf()) {
            !BluetoothAdapter.checkBluetoothAddress(it)
        }
        if (invalidMacs.isNotEmpty()) {
            failDevices(appContext, invalidMacs, "Invalid widget Bluetooth address")
        }

        val targetMacs = allMacs.filterTo(mutableSetOf()) {
            BluetoothAdapter.checkBluetoothAddress(it)
        }
        if (targetMacs.isEmpty()) {
            finishScan(appContext)
            return
        }

        if (!BluetoothUtils.hasBluetoothPermissions(appContext)) {
            failDevices(appContext, targetMacs, "Missing Bluetooth permissions")
            finishScan(appContext)
            return
        }

        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            failDevices(appContext, targetMacs, "Bluetooth unavailable")
            finishScan(appContext)
            return
        }

        // Re-registering the identical PendingIntent replaces a stale scan from a previous cycle.
        stopScanQuietly(appContext, scanner)
        savePendingMacs(appContext, targetMacs)

        val filters = targetMacs.map { mac ->
            ScanFilter.Builder()
                .setDeviceAddress(mac)
                .setServiceData(UUID_SERVICE_ADVERTISING, null)
                .build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        val errorCode = try {
            scanner.startScan(filters, settings, resultPendingIntent(appContext))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not start scheduled PendingIntent scan", e)
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR
        }

        if (errorCode != SCAN_SUCCESS) {
            failDevices(appContext, targetMacs, "BLE scan failed with code $errorCode")
            finishScan(appContext)
            return
        }

        scheduleTimeout(appContext)
        AppLogger.i(TAG, "Scheduled BLE scan started for ${targetMacs.size} widget device(s)")
    }

    @SuppressLint("MissingPermission")
    suspend fun handleResult(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, SCAN_SUCCESS)
        if (errorCode != SCAN_SUCCESS) {
            val pendingMacs = getPendingMacs(appContext)
            failDevices(appContext, pendingMacs, "BLE result delivery failed with code $errorCode")
            finishScan(appContext)
            return
        }

        val results = scanResults(intent)
        if (results.isEmpty()) return

        val pendingMacs = getPendingMacs(appContext).toMutableSet()
        val profileRepo = DeviceProfileRepository(appContext)
        for (result in results) {
            val mac = result.device.address.normalizedBluetoothMac()
            if (mac !in pendingMacs) continue

            val sensorData = SensorAdvertisementParser.parse(result) ?: continue
            SensorRepository(appContext, mac, profileRepo).apply {
                saveSensorData(sensorData)
                setLoading(false)
            }
            SensorWidgetRefresher.updateDeviceData(appContext, mac)
            pendingMacs.remove(mac)
            AppLogger.i(TAG, "[$mac] Scheduled widget refresh completed")
        }

        if (pendingMacs.isEmpty()) {
            finishScan(appContext)
        } else {
            savePendingMacs(appContext, pendingMacs)
        }
    }

    suspend fun handleTimeout(context: Context) {
        val appContext = context.applicationContext
        val pendingMacs = getPendingMacs(appContext)
        if (pendingMacs.isNotEmpty()) {
            failDevices(appContext, pendingMacs, "Scheduled BLE scan timed out")
        }
        finishScan(appContext)
    }

    fun cancel(context: Context) {
        finishScan(context.applicationContext)
    }

    private suspend fun failDevices(context: Context, macs: Set<String>, reason: String) {
        AppLogger.w(TAG, reason)
        val profileRepo = DeviceProfileRepository(context)
        for (mac in macs) {
            SensorRepository(context, mac, profileRepo).apply {
                setUpdateError(true)
                setLoading(false)
            }
            SensorWidgetRefresher.updateDeviceData(context, mac)
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishScan(context: Context) {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.bluetoothLeScanner
        if (scanner != null && BluetoothUtils.hasBluetoothPermissions(context)) {
            stopScanQuietly(context, scanner)
        }
        savePendingMacs(context, emptySet())
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
            ?.cancel(timeoutPendingIntent(context))
    }

    @SuppressLint("MissingPermission")
    private fun stopScanQuietly(context: Context, scanner: BluetoothLeScanner) {
        try {
            scanner.stopScan(resultPendingIntent(context))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not stop previous PendingIntent scan: ${e.message}")
        }
    }

    private fun scheduleTimeout(context: Context) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + SCAN_TIMEOUT_MS,
            timeoutPendingIntent(context)
        )
    }

    private fun resultPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetBleScanReceiver::class.java).apply {
            action = ACTION_RESULT
            data = "${context.packageName}://widget-ble/result".toUri()
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            RESULT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun timeoutPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetBleScanReceiver::class.java).apply {
            action = ACTION_TIMEOUT
            data = "${context.packageName}://widget-ble/timeout".toUri()
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            TIMEOUT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun savePendingMacs(context: Context, macs: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) { putStringSet(KEY_PENDING_MACS, macs.toSet()) }
    }

    private fun getPendingMacs(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PENDING_MACS, emptySet())
            ?.toSet()
            .orEmpty()

    @Suppress("DEPRECATION")
    private fun scanResults(intent: Intent): List<ScanResult> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java
            ).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<ScanResult>(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
            ).orEmpty()
        }
    }

    fun isResultAction(intent: Intent): Boolean = intent.action == ACTION_RESULT
    fun isTimeoutAction(intent: Intent): Boolean = intent.action == ACTION_TIMEOUT
}
