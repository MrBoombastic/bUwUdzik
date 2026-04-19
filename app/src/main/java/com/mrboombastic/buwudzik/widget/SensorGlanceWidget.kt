package com.mrboombastic.buwudzik.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.mrboombastic.buwudzik.MainActivity
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.utils.TimeFormatUtils.formatAbsoluteTime
import java.util.Locale

class SensorGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<WidgetState> = WidgetStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val state = currentState<WidgetState>()

                val locale = if (state.language == "system") {
                    Locale.getDefault()
                } else {
                    Locale.forLanguageTag(state.language)
                }

                val tempText =
                    state.sensorData?.let { "%.1f°C".format(locale, it.temperature) } ?: "—"
                val humidityText =
                    state.sensorData?.let { "💧%.0f%%".format(locale, it.humidity) } ?: ""
                val batteryText = state.sensorData?.let { "🔋${it.battery}%" } ?: ""
                val lastUpdateText = if (state.lastUpdate > 0) {
                    formatAbsoluteTime(state.lastUpdate, locale)
                } else ""
                val hasData = state.sensorData != null

                // Optional footer label: profile alias only (never replaces last-updated line)
                val deviceProfileRepo =
                    com.mrboombastic.buwudzik.data.DeviceProfileRepository(context)
                val deviceAlias = if (state.mac.isEmpty()) {
                    ""
                } else {
                    deviceProfileRepo.getByMac(state.mac)?.alias?.trim().orEmpty()
                }

                val size = LocalSize.current
                WidgetContent(
                    mac = state.mac,
                    tempText = tempText,
                    humidityText = humidityText,
                    batteryText = batteryText,
                    lastUpdateText = lastUpdateText,
                    hasError = state.hasError && state.showWidgetError,
                    isLoading = state.isLoading,
                    hasData = hasData,
                    deviceName = deviceAlias,
                    size = size
                )
            }
        }
    }

    @Composable
    private fun WidgetContent(
        mac: String,
        tempText: String,
        humidityText: String,
        batteryText: String,
        lastUpdateText: String,
        hasError: Boolean,
        isLoading: Boolean,
        hasData: Boolean,
        deviceName: String,
        size: DpSize
    ) {
        val width = size.width
        val height = size.height
        val context = LocalContext.current

        val minDimension = minOf(width.value, height.value)
        // Single-row / near-min-height widgets: tighter layout (spacers, gaps).
        val isCompact = height.value < 100f
        // Footer device alias only when clearly multi-row; many launchers report ~100dp+ for 1 row,
        // which wrongly satisfied the old "!isCompact" check and showed the alias at minimum height.
        val showFooterDeviceAlias =
            deviceName.isNotEmpty() && height.value >= 132f

        // Dynamic font sizing
        val tempSizeVal = (minDimension * 0.25f).coerceIn(14f, 96f)
        val subSizeVal = (tempSizeVal * 0.48f).coerceIn(12f, 44f)
        val footerSizeVal = (subSizeVal * 0.7f).coerceIn(8f, 18f)

        // Material You colors from GlanceTheme (public API, no restricted access)
        val primaryText = GlanceTheme.colors.onSurface
        val humidityColor = GlanceTheme.colors.primary
        val secondaryText = GlanceTheme.colors.onSurfaceVariant
        val dimText = GlanceTheme.colors.outline
        val errorColor = GlanceTheme.colors.error
        val loadingColor = GlanceTheme.colors.tertiary
        val iconTint = GlanceTheme.colors.onSurfaceVariant

        // Dynamic padding that scales with widget size
        val hPadding = (width.value * 0.06f).dp.coerceIn(8.dp, 16.dp)
        val vPadding = (height.value * 0.06f).dp.coerceIn(4.dp, 12.dp)

        val clickAction = if (mac.isNotEmpty()) {
            actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    putExtra("mac", mac)
                    // Set unique data URI to prevent Intent merging by the system
                    data = "buwudzik://device/$mac".toUri()
                }
            )
        } else {
            actionStartActivity<MainActivity>()
        }

        Column(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(horizontal = hPadding, vertical = vPadding)
                .clickable(clickAction),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top spacer — pushes content toward center on tall widgets
            if (!isCompact) {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }

            // Temperature — main hero element
            Box(
                modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.Center
            ) {
                if (hasData || isLoading) {
                    Text(
                        text = if (isLoading && !hasData) "…" else tempText, style = TextStyle(
                            color = primaryText,
                            fontSize = tempSizeVal.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                } else {
                    // No data state
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = LocalContext.current.getString(R.string.widget_tap_to_open),
                            style = TextStyle(
                                color = dimText, fontSize = subSizeVal.sp
                            )
                        )
                    }
                }
            }

            // Gap between temp and secondary info
            val tempToSensorsGap =
                if (isCompact) 2.dp else (height.value * 0.03f).dp.coerceIn(4.dp, 12.dp)
            Spacer(modifier = GlanceModifier.height(tempToSensorsGap))

            // Humidity & Battery Row — tightly grouped below temperature
            if (hasData) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = humidityText, style = TextStyle(
                            color = humidityColor, fontSize = subSizeVal.sp
                        )
                    )
                    if (batteryText.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.width(12.dp))
                        Text(
                            text = batteryText, style = TextStyle(
                                color = secondaryText, fontSize = subSizeVal.sp
                            )
                        )
                    }
                }
            }

            // Bottom spacer — balances against top spacer
            if (!isCompact) {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }

            // Footer — optional device name (line 1) always coexists with last-updated / status (line 2)
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.Start
                ) {
                    if (showFooterDeviceAlias) {
                        Text(
                            text = deviceName,
                            style = TextStyle(
                                color = dimText,
                                fontSize = (footerSizeVal * 0.85f).sp
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(2.dp))
                    }
                    when {
                        isLoading -> Text(
                            text = LocalContext.current.getString(R.string.updating_label),
                            style = TextStyle(
                                color = loadingColor, fontSize = footerSizeVal.sp
                            )
                        )

                        hasError -> Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠ ", style = TextStyle(
                                    color = errorColor, fontSize = footerSizeVal.sp
                                )
                            )
                            Text(
                                text = lastUpdateText.ifEmpty { LocalContext.current.getString(R.string.update_error) },
                                style = TextStyle(
                                    color = errorColor, fontSize = footerSizeVal.sp
                                )
                            )
                        }

                        lastUpdateText.isNotEmpty() -> Text(
                            text = lastUpdateText, style = TextStyle(
                                color = secondaryText, fontSize = footerSizeVal.sp
                            )
                        )

                        else -> Text(
                            text = "—", style = TextStyle(
                                color = dimText, fontSize = footerSizeVal.sp
                            )
                        )
                    }
                }

                // Refresh button
                val refreshTint = when {
                    isLoading -> loadingColor
                    hasError -> errorColor
                    else -> iconTint
                }

                Box(
                    modifier = GlanceModifier.size(maxOf((footerSizeVal * 2f).dp, 32.dp))
                        .clickable(actionRunCallback<RefreshAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_refresh),
                        contentDescription = LocalContext.current.getString(R.string.widget_refresh_description),
                        colorFilter = ColorFilter.tint(refreshTint),
                        modifier = GlanceModifier.size((footerSizeVal * 1.3f).dp)
                    )
                }
            }
        }
    }
}
