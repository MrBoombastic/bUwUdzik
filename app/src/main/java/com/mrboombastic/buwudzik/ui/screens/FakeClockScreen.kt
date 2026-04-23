package com.mrboombastic.buwudzik.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.ui.components.BackNavigationButton
import com.mrboombastic.buwudzik.ui.utils.adaptiveContentWidth
import com.mrboombastic.buwudzik.viewmodels.MainViewModel

/**
 * Debug-only screen for injecting a fake Bluetooth clock into the app.
 *
 * Available only in canaryDebug builds (gated by [BuildConfig.DEBUG] in [MainActivity]).
 * Lets you configure sensor values, alarm count, and device name — then inject the fake
 * state directly into [MainViewModel], bypassing BLE entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeClockScreen(navController: NavController, viewModel: MainViewModel) {
    val deviceConnected by viewModel.deviceConnected.collectAsState()
    val sensorData by viewModel.sensorData.collectAsState()
    val isFakeActive = deviceConnected && sensorData?.macAddress == MainViewModel.FAKE_MAC

    // Configurable fake values
    var fakeName by remember { mutableStateOf("Fake clOwOck") }
    var temperature by remember { mutableFloatStateOf(21.5f) }
    var humidity by remember { mutableFloatStateOf(55.0f) }
    var battery by remember { mutableIntStateOf(72) }
    var rssi by remember { mutableIntStateOf(-65) }
    var alarmCount by remember { mutableIntStateOf(3) }

    // Inject immediately if not active
    LaunchedEffect(isFakeActive) {
        if (!isFakeActive) {
            viewModel.injectFakeDevice(
                name = fakeName.ifBlank { "Fake clOwOck" },
                temperature = temperature.toDouble(),
                humidity = humidity.toDouble(),
                battery = battery,
                rssi = rssi,
                alarmCount = alarmCount,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🤖 Fake Clock Injector") },
                navigationIcon = { BackNavigationButton(navController) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .adaptiveContentWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(12.dp))

                // Status banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isFakeActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = if (isFakeActive)
                            "✅ Fake device is ACTIVE — UI is driven by fake data"
                        else
                            "💤 No fake device. App uses real BLE scanning.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isFakeActive)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text("Device Name", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = fakeName,
                    onValueChange = { fakeName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Fake clOwOck") }
                )

                Spacer(Modifier.height(16.dp))

                // Temperature slider
                Text(
                    "Temperature: ${"%.1f".format(temperature)} °C",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = -20f..60f,
                    steps = 159  // 0.5°C steps
                )

                Spacer(Modifier.height(8.dp))

                // Humidity slider
                Text(
                    "Humidity: ${"%.1f".format(humidity)} %",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = humidity,
                    onValueChange = { humidity = it },
                    valueRange = 0f..100f,
                    steps = 99
                )

                Spacer(Modifier.height(8.dp))

                // Battery slider
                Text(
                    "Battery: $battery %",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = battery.toFloat(),
                    onValueChange = { battery = it.toInt() },
                    valueRange = 0f..100f,
                    steps = 99
                )

                Spacer(Modifier.height(8.dp))

                // RSSI slider
                Text(
                    "RSSI: $rssi dBm",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = rssi.toFloat(),
                    onValueChange = { rssi = it.toInt() },
                    valueRange = -100f..-30f,
                    steps = 69
                )

                Spacer(Modifier.height(8.dp))

                // Alarm count
                Text(
                    "Alarm count: $alarmCount",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = alarmCount.toFloat(),
                    onValueChange = { alarmCount = it.toInt() },
                    valueRange = 0f..8f,
                    steps = 7
                )

                Spacer(Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.injectFakeDevice(
                                name = fakeName.ifBlank { "Fake clOwOck" },
                                temperature = temperature.toDouble(),
                                humidity = humidity.toDouble(),
                                battery = battery,
                                rssi = rssi,
                                alarmCount = alarmCount,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isFakeActive
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("  Inject", modifier = Modifier.padding(start = 4.dp))
                    }

                    OutlinedButton(
                        onClick = { viewModel.clearFakeDevice() },
                        modifier = Modifier.weight(1f),
                        enabled = isFakeActive,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text("  Clear", modifier = Modifier.padding(start = 4.dp))
                    }
                }

                if (isFakeActive) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.injectFakeDevice(
                                name = fakeName.ifBlank { "Fake clOwOck" },
                                temperature = temperature.toDouble(),
                                humidity = humidity.toDouble(),
                                battery = battery,
                                rssi = rssi,
                                alarmCount = alarmCount,
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("↻ Update Fake Values")
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Quick-preset buttons
                Text("Quick Presets", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { temperature = -5f; humidity = 80f; battery = 12 },
                        modifier = Modifier.weight(1f)
                    ) { Text("❄️ Cold\nLow batt", style = MaterialTheme.typography.bodySmall) }

                    OutlinedButton(
                        onClick = { temperature = 38f; humidity = 90f; battery = 99 },
                        modifier = Modifier.weight(1f)
                    ) { Text("🔥 Hot\nFull batt", style = MaterialTheme.typography.bodySmall) }

                    OutlinedButton(
                        onClick = { temperature = 21.5f; humidity = 55f; battery = 72; rssi = -65 },
                        modifier = Modifier.weight(1f)
                    ) { Text("🏠 Normal\nComfort", style = MaterialTheme.typography.bodySmall) }
                }

                Spacer(Modifier.height(16.dp))

                // Info footer
                Text(
                    text = "ℹ️  This screen is only visible in DEBUG builds.\n" +
                            "Injecting replaces the live BLE scan — real data resumes after Clear.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
