package com.mrboombastic.buwudzik.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.data.DeviceProfile
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.WidgetPreferencesRepository
import com.mrboombastic.buwudzik.ui.theme.ClOwOckTheme
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "WidgetConfigureActivity"
val MAC_KEY = stringPreferencesKey("device_mac")

/**
 * Activity shown when a user adds a new widget to the home screen.
 * Lets the user pick which device the widget should show.
 */
class WidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract widget ID from the launch intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If called with an invalid ID, cancel
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // The default result is CANCELED in case the user presses back
        setResult(RESULT_CANCELED)

        setContent {
            ClOwOckTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WidgetConfigureScreen(
                        onDeviceSelected = { profile -> onDevicePicked(profile) }
                    )
                }
            }
        }
    }

    private fun onDevicePicked(profile: DeviceProfile) {
        val context = applicationContext
        val widgetPrefs = WidgetPreferencesRepository(context)
        widgetPrefs.setDeviceMacForWidget(appWidgetId, profile.mac)
        AppLogger.d(TAG, "Widget $appWidgetId configured for device ${profile.mac}")

        // Trigger initial widget update
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val glanceManager = GlanceAppWidgetManager(context)
                val glanceId = glanceManager.getGlanceIdBy(appWidgetId)

                // Save MAC to Glance Preferences for a reliable lookup in provideGlance
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences().apply {
                        set(MAC_KEY, profile.mac)
                    }
                }

                // Set loading=true so the user sees the spinner immediately
                SensorRepository(context, profile.mac).setLoading(true)
                SensorWidgetRefresher.updateDeviceData(context, profile.mac)

                // Register a process-independent scan for the initial reading.
                WidgetBleScanCoordinator.startScheduledScan(context)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to trigger initial widget update", e)
            }
        }

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigureScreen(onDeviceSelected: (DeviceProfile) -> Unit) {
    val context = LocalContext.current
    val deviceProfileRepo = remember { DeviceProfileRepository(context) }
    val devices = remember { deviceProfileRepo.getProfiles().sortedBy { it.addedAt } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.widget_configure_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.widget_configure_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (devices.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_devices_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.mac }) { profile ->
                        Card(
                            onClick = { onDeviceSelected(profile) },
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = profile.alias,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = profile.mac,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
