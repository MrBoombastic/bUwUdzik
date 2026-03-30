package com.mrboombastic.buwudzik.device

import android.icu.util.TimeZone
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Device configuration settings for the Qingping CGD1 alarm clock.
 */
data class DeviceSettings(
    val tempUnit: TempUnit = TempUnit.Celsius,
    val timeFormat: TimeFormat = TimeFormat.H24,
    val language: Language = Language.English,
    val volume: Int = 3,
    val timeZone: TimeZone = TimeZone.GMT_ZONE,
    val nightModeBrightness: Int = 10,
    val backlightDuration: Int = 60,
    val screenBrightness: Int = 100,
    val nightStartHour: Int = 22,
    val nightStartMinute: Int = 0,
    val nightEndHour: Int = 7,
    val nightEndMinute: Int = 0,
    val nightModeEnabled: Boolean = true,
    val masterAlarmDisabled: Boolean = true,
    val firmwareVersion: String = "",
    val ringtoneSignature: ByteArray = byteArrayOf(
        0xba.toByte(), 0x2c.toByte(), 0x2c.toByte(), 0x8c.toByte()
    )
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceSettings
        return tempUnit == other.tempUnit && timeFormat == other.timeFormat && language == other.language && volume == other.volume && timeZone == other.timeZone && nightModeBrightness == other.nightModeBrightness && backlightDuration == other.backlightDuration && screenBrightness == other.screenBrightness && nightStartHour == other.nightStartHour && nightStartMinute == other.nightStartMinute && nightEndHour == other.nightEndHour && nightEndMinute == other.nightEndMinute && nightModeEnabled == other.nightModeEnabled && masterAlarmDisabled == other.masterAlarmDisabled && firmwareVersion == other.firmwareVersion && ringtoneSignature.contentEquals(
            other.ringtoneSignature
        )
    }

    override fun hashCode(): Int {
        var result = tempUnit.hashCode()
        result = 31 * result + timeFormat.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + volume
        result = 31 * result + timeZone.hashCode()
        result = 31 * result + nightModeBrightness
        result = 31 * result + backlightDuration
        result = 31 * result + screenBrightness
        result = 31 * result + nightStartHour
        result = 31 * result + nightStartMinute
        result = 31 * result + nightEndHour
        result = 31 * result + nightEndMinute
        result = 31 * result + nightModeEnabled.hashCode()
        result = 31 * result + masterAlarmDisabled.hashCode()
        result = 31 * result + firmwareVersion.hashCode()
        result = 31 * result + ringtoneSignature.contentHashCode()
        return result
    }

    /**
     * Get the ringtone name. Returns null for custom ringtones - caller should use localized string.
     */
    fun getRingtoneName(): String? {
        // Check for custom slots first - return null so the caller can use localized string
        if (BleConstants.isCustomSlot(ringtoneSignature)) return null
        // Then check standard ringtones
        return BleConstants.RINGTONE_SIGNATURES.entries.find {
            it.value.contentEquals(ringtoneSignature)
        }?.key ?: "Unknown"
    }
}

enum class TempUnit { Celsius, Fahrenheit }
enum class TimeFormat { H24, H12 }
enum class Language { Chinese, English }

fun TimeZone.encodeOffset(): Byte {
    val offsetMillis = this.getOffset(System.currentTimeMillis())
    return abs(offsetMillis.toLong().milliseconds.inWholeMinutes.div(6)).toByte()
}

fun TimeZone.encodeOffsetSign(): Byte {
    val offsetMillis = this.getOffset(System.currentTimeMillis())
    return if (offsetMillis >= 0) 1 else 0
}

fun createTimeZone(offset: Int, isPositive: Boolean): TimeZone {
    val totalMinutes = offset * 6
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    val id = "GMT${if (isPositive) "+" else "-"}${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    return TimeZone.getTimeZone(id)
}
