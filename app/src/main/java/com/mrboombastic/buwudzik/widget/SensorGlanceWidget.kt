package com.mrboombastic.buwudzik.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.mrboombastic.buwudzik.MainActivity
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.utils.TimeFormatUtils.formatAbsoluteTime
import java.util.Locale

class SensorGlanceWidget : GlanceAppWidget() {

    companion object {
        val KEY_MAC = stringPreferencesKey("device_mac")
        val KEY_TEMP = doublePreferencesKey("temp")
        val KEY_HUMIDITY = doublePreferencesKey("humidity")
        val KEY_BATTERY = intPreferencesKey("battery")
        val KEY_LAST_UPDATE = longPreferencesKey("last_update")
        val KEY_HAS_ERROR = booleanPreferencesKey("has_error")
        val KEY_IS_LOADING = booleanPreferencesKey("is_loading")
        val KEY_DEVICE_ALIAS = stringPreferencesKey("device_alias")
    }

    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs = currentState<Preferences>()
                val mac = prefs[KEY_MAC] ?: ""

                val sensorRepo = com.mrboombastic.buwudzik.data.SensorRepository(context, mac)
                val settingsRepo = com.mrboombastic.buwudzik.data.SettingsRepository(context)

                // Fallback to repo if prefs are empty (e.g. first run or migration)
                val temp = prefs[KEY_TEMP] ?: sensorRepo.getSensorData()?.temperature
                val humidity = prefs[KEY_HUMIDITY] ?: sensorRepo.getSensorData()?.humidity
                val battery = prefs[KEY_BATTERY] ?: sensorRepo.getSensorData()?.battery ?: 0
                val lastUpdate = prefs[KEY_LAST_UPDATE] ?: sensorRepo.getLastUpdateTimestamp()
                val hasError = prefs[KEY_HAS_ERROR] ?: sensorRepo.hasUpdateError()
                val isLoading = prefs[KEY_IS_LOADING] ?: sensorRepo.isLoading()

                val language = settingsRepo.language
                val showWidgetError = settingsRepo.showWidgetError

                val ctx = LocalContext.current
                val locale = if (language == "system") {
                    ctx.resources.configuration.locales[0]
                } else {
                    Locale.forLanguageTag(language)
                }

                val tempText = temp?.let { "%.1f°C".format(locale, it) } ?: "—"
                val humidityText = humidity?.let { "💧%.0f%%".format(locale, it) } ?: ""
                val batteryText = if (battery > 0) "🔋$battery%" else ""
                val lastUpdateText = if (lastUpdate > 0) {
                    formatAbsoluteTime(lastUpdate, locale)
                } else ""

                // Alias from prefs or repo
                val deviceAlias = prefs[KEY_DEVICE_ALIAS] ?: if (mac.isEmpty()) {
                    ""
                } else {
                    com.mrboombastic.buwudzik.data.DeviceProfileRepository(context)
                        .getByMac(mac)?.alias?.trim().orEmpty()
                }

                val size = LocalSize.current
                WidgetContent(
                    mac = mac,
                    tempText = tempText,
                    humidityText = humidityText,
                    batteryText = batteryText,
                    lastUpdateText = lastUpdateText,
                    hasError = hasError && showWidgetError,
                    isLoading = isLoading,
                    hasData = temp != null,
                    deviceName = deviceAlias,
                    size = size,
                    isOutdated = mac.isEmpty()
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
        size: DpSize,
        isOutdated: Boolean = false
    ) {
        val width = size.width
        val height = size.height
        val context = LocalContext.current

        val minDimension = minOf(width.value, height.value)
        val isCompact = height.value < 100f
        val showFooterDeviceAlias = deviceName.isNotEmpty() && height.value >= 132f

        // Dynamic font sizing
        val tempSizeVal = (minDimension * 0.25f).coerceIn(14f, 96f)
        val subSizeVal = (tempSizeVal * 0.48f).coerceIn(12f, 44f)
        val footerSizeVal = (subSizeVal * 0.7f).coerceIn(8f, 18f)

        val primaryText = GlanceTheme.colors.onSurface
        val humidityColor = GlanceTheme.colors.primary
        val secondaryText = GlanceTheme.colors.onSurfaceVariant
        val dimText = GlanceTheme.colors.outline
        val errorColor = GlanceTheme.colors.error
        val loadingColor = GlanceTheme.colors.tertiary
        val iconTint = GlanceTheme.colors.onSurfaceVariant

        val hPadding = (width.value * 0.06f).dp.coerceIn(8.dp, 16.dp)
        val vPadding = (height.value * 0.06f).dp.coerceIn(4.dp, 12.dp)

        val clickAction = if (mac.isNotEmpty()) {
            actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    putExtra("mac", mac)
                    data = "buwudzik://device/$mac".toUri()
                    setPackage(context.packageName)
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
            if (!isCompact) {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }

            Box(
                modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.Center
            ) {
                if (isOutdated) {
                    Text(
                        text = LocalContext.current.getString(R.string.widget_outdated),
                        style = TextStyle(
                            color = errorColor,
                            fontSize = (subSizeVal * 0.8f).sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = GlanceModifier.padding(4.dp)
                    )
                } else if (hasData || isLoading) {
                    Text(
                        text = if (isLoading && !hasData) "…" else tempText, style = TextStyle(
                            color = primaryText,
                            fontSize = tempSizeVal.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                } else {
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

            val tempToSensorsGap =
                if (isCompact) 2.dp else (height.value * 0.03f).dp.coerceIn(4.dp, 12.dp)
            if (!isOutdated) {
                Spacer(modifier = GlanceModifier.height(tempToSensorsGap))
            }

            if (hasData && !isOutdated) {
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

            if (!isCompact) {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }

            if (!isOutdated) {
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

                    val refreshTint = when {
                        isLoading -> loadingColor
                        hasError -> errorColor
                        else -> iconTint
                    }

                    val refreshModifier = if (isLoading) {
                        GlanceModifier.size(maxOf((footerSizeVal * 2f).dp, 32.dp))
                    } else {
                        GlanceModifier.size(maxOf((footerSizeVal * 2f).dp, 32.dp))
                            .clickable(actionRunCallback<RefreshAction>())
                    }

                    Box(
                        modifier = refreshModifier,
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
}
