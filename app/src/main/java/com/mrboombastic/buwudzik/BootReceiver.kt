package com.mrboombastic.buwudzik


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.widget.WidgetUpdateScheduler

/**
 * Receiver that triggers widget update after device boot.
 * Also, re-schedules periodic updates since AlarmManager alarms are cleared on reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {

            val settingsRepository = SettingsRepository(context)
            val intervalMinutes = settingsRepository.updateInterval

            // Re-schedule periodic updates using AlarmManager (alarms are cleared on reboot)
            WidgetUpdateScheduler.scheduleUpdates(context, intervalMinutes)

            // Use a separate alarm so it does not replace the repeating update PendingIntent.
            WidgetUpdateScheduler.scheduleOneTimeUpdate(context, 10_000L)

            AppLogger.d(
                TAG,
                "Scheduled initial and periodic widget updates every $intervalMinutes minutes"
            )
        }
    }
}


