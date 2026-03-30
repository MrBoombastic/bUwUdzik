package com.mrboombastic.buwudzik.device

import java.util.UUID

/**
 * BLE constants and ringtone signatures for the CGD1 alarm clock.
 */
object BleConstants {
    // UUIDs for QP CGD1
    val UUID_AUTH_WRITE: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    val UUID_AUTH_NOTIFY: UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
    val UUID_DATA_WRITE: UUID = UUID.fromString("0000000b-0000-1000-8000-00805f9b34fb")
    val UUID_DATA_NOTIFY: UUID = UUID.fromString("0000000c-0000-1000-8000-00805f9b34fb")
    val UUID_SENSOR_NOTIFY: UUID = UUID.fromString("00000100-0000-1000-8000-00805f9b34fb")
    val UUID_CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Known ringtone signatures from https://qingplus.cleargrass.com/raw/rings
    val RINGTONE_SIGNATURES = mapOf(
        "Beep" to byteArrayOf(0xfd.toByte(), 0xc3.toByte(), 0x66.toByte(), 0xa5.toByte()),
        "Digital Ringtone" to byteArrayOf(
            0x09.toByte(), 0x61.toByte(), 0xbb.toByte(), 0x77.toByte()
        ),
        "Digital Ringtone 2" to byteArrayOf(
            0xba.toByte(), 0x2c.toByte(), 0x2c.toByte(), 0x8c.toByte()
        ),
        "Cuckoo" to byteArrayOf(0xea.toByte(), 0x2d.toByte(), 0x4c.toByte(), 0x02.toByte()),
        "Telephone" to byteArrayOf(0x79.toByte(), 0x1b.toByte(), 0xac.toByte(), 0xb3.toByte()),
        "Exotic Guitar" to byteArrayOf(
            0x1d.toByte(), 0x01.toByte(), 0x9f.toByte(), 0xd6.toByte()
        ),
        "Lively Piano" to byteArrayOf(
            0x6e.toByte(), 0x70.toByte(), 0xb6.toByte(), 0x59.toByte()
        ),
        "Story Piano" to byteArrayOf(
            0x8f.toByte(), 0x00.toByte(), 0x48.toByte(), 0x86.toByte()
        ),
        "Forest Piano" to byteArrayOf(
            0x26.toByte(), 0x52.toByte(), 0x25.toByte(), 0x19.toByte()
        )
    )

    // Custom ringtone slots with alternating IDs (dead/beef)
    val CUSTOM_RINGTONE_SLOT_1 =
        byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xde.toByte(), 0xad.toByte()) // dead
    val CUSTOM_RINGTONE_SLOT_2 =
        byteArrayOf(0xbe.toByte(), 0xef.toByte(), 0xbe.toByte(), 0xef.toByte()) // beef

    /**
     * Get the appropriate custom slot to use (alternates between dead and beef based on current)
     */
    fun getCustomSlotSignature(currentSignature: ByteArray?): ByteArray {
        return if (currentSignature?.contentEquals(CUSTOM_RINGTONE_SLOT_1) == true) {
            CUSTOM_RINGTONE_SLOT_2 // Current is dead, use beef
        } else {
            CUSTOM_RINGTONE_SLOT_1 // Current is beef or other, use dead
        }
    }

    /**
     * Check if the signature is a custom slot (for i18n - caller should use localized string)
     */
    fun isCustomSlot(signature: ByteArray): Boolean {
        return signature.contentEquals(CUSTOM_RINGTONE_SLOT_1) || signature.contentEquals(
            CUSTOM_RINGTONE_SLOT_2
        )
    }
}
