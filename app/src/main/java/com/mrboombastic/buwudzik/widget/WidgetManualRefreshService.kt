package com.mrboombastic.buwudzik.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mrboombastic.buwudzik.MainActivity
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** Executes a user-requested widget refresh outside JobScheduler quota. */
class WidgetManualRefreshService : Service() {
    companion object {
        private const val TAG = "WidgetManualRefresh"
        private const val EXTRA_MAC = "device_mac"
        private const val CHANNEL_ID = "widget_manual_refresh"
        private const val NOTIFICATION_ID = 4102
        private const val SCAN_TIMEOUT_MS = 20_000L

        fun intent(context: Context, mac: String): Intent =
            Intent(context, WidgetManualRefreshService::class.java)
                .putExtra(EXTRA_MAC, mac)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mac = intent?.getStringExtra(EXTRA_MAC).orEmpty()
        if (mac.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        serviceScope.launch {
            scanMutex.withLock { refresh(mac) }
            if (stopSelfResult(startId)) {
                ServiceCompat.stopForeground(
                    this@WidgetManualRefreshService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE
                )
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun refresh(mac: String) {
        val profileRepo = DeviceProfileRepository(applicationContext)
        val repository = SensorRepository(applicationContext, mac, profileRepo)
        repository.setLoading(true)
        SensorWidgetRefresher.updateDeviceData(applicationContext, mac)

        try {


            if (!BluetoothUtils.hasBluetoothPermissions(applicationContext)) {
                AppLogger.w(TAG, "[$mac] Missing Bluetooth permissions")
                repository.setUpdateError(true)
                return
            }

            val bluetoothManager =
                applicationContext.getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
            if (bluetoothManager?.adapter?.isEnabled != true) {
                AppLogger.w(TAG, "[$mac] Bluetooth unavailable")
                repository.setUpdateError(true)
                return
            }

            val result = withTimeoutOrNull(SCAN_TIMEOUT_MS.milliseconds) {
                BluetoothScanner(applicationContext, profileRepo)
                    .scan(mac, ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .first()
            }

            if (result == null) {
                AppLogger.w(TAG, "[$mac] No data received within timeout")
                repository.setUpdateError(true)
            } else {
                repository.saveSensorData(result)
                AppLogger.i(TAG, "[$mac] Manual widget refresh completed")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[$mac] Manual widget refresh failed", e)
            repository.setUpdateError(true)
        } finally {
            repository.setLoading(false)
            withContext(NonCancellable) {
                SensorWidgetRefresher.updateDeviceData(applicationContext, mac)
            }
        }
    }


    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.widget_refresh_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.widget_refresh_channel_description)
            }
        )
    }

    private fun createNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { setPackage(packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.widget_refresh_notification_title))
            .setContentText(getString(R.string.widget_refresh_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
