package com.mrboombastic.buwudzik.data

import android.util.Base64
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data class for sharing device configuration via QR code.
 * Contains all information needed to connect to a paired device.
 */
@Serializable
data class DeviceShareData(
    val version: Int = 1,           // For future compatibility
    val mac: String,
    val token: String,
    val batteryType: String,
    val alarmTitles: Map<Int, String> = emptyMap()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Decode from Base64-encoded JSON string (from QR code).
         * Returns null if decoding fails.
         */
        fun fromQrContent(content: String): DeviceShareData? {
            return try {
                val jsonString = String(Base64.decode(content, Base64.NO_WRAP))
                json.decodeFromString<DeviceShareData>(jsonString)
            } catch (e: Exception) {
                AppLogger.d("QR", "Error decoding QR content: ${e.message}")
                null
            }
        }
    }

    /**
     * Encode to Base64-encoded JSON string for QR code.
     */
    fun toQrContent(): String {
        val jsonString = json.encodeToString(this)
        return Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
    }
}
