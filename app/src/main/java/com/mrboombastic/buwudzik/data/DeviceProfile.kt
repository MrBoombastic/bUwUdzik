package com.mrboombastic.buwudzik.data

import kotlinx.serialization.Serializable

/**
 * Represents a saved CGD1 clock device.
 * @param mac Bluetooth MAC address (primary key, normalised to uppercase).
 * @param alias User-editable display name.
 * @param batteryType "alkaline" or "nimh".
 * @param addedAt Epoch ms when this profile was created (used for sorting).
 */
@Serializable
data class DeviceProfile(
    val mac: String,
    val alias: String,
    val batteryType: String = "alkaline",
    val addedAt: Long = System.currentTimeMillis()
) {
    /** Returns a SharedPreferences-safe key derived from the MAC address. */
    fun macSafeKey(): String = mac.lowercase().replace(":", "_")
}
