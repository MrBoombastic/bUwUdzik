package com.mrboombastic.buwudzik.data

import kotlinx.serialization.Serializable

/**
 * Represents a saved CGD1 clock device.
 * @param mac Bluetooth MAC address (primary key, normalised to uppercase).
 * @param alias User-editable display name.
 * @param batteryType [BatteryType.ALKALINE] or [BatteryType.NIMH].
 * @param addedAt Epoch ms when this profile was created (used for sorting).
 */
@Serializable
data class DeviceProfile(
    val mac: String,
    val alias: String,
    val batteryType: String = BatteryType.ALKALINE,
    val addedAt: Long = System.currentTimeMillis()
)
