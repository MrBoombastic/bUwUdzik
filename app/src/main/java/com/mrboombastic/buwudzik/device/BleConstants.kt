package com.mrboombastic.buwudzik.device

import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE constants and protocol definitions for the Qingping CGD1 alarm clock.
 */
object BleConstants {
    // UUIDs for QP CGD1
    val UUID_DEVICE_SERVICE: UUID =
        UUID.fromString("22210000-554a-4546-5542-46534450464d")
    val UUID_AUTH_WRITE: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    val UUID_AUTH_NOTIFY: UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
    val UUID_DATA_WRITE: UUID = UUID.fromString("0000000b-0000-1000-8000-00805f9b34fb")
    val UUID_DATA_NOTIFY: UUID = UUID.fromString("0000000c-0000-1000-8000-00805f9b34fb")
    val UUID_SENSOR_NOTIFY: UUID = UUID.fromString("00000100-0000-1000-8000-00805f9b34fb")
    val UUID_CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Standard Battery Service, exposed by supported CGD1 firmware while connected.
    val UUID_BATTERY_SERVICE: UUID =
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val UUID_BATTERY_LEVEL: UUID =
        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // Passive advertising service
    val UUID_SERVICE_ADVERTISING: ParcelUuid =
        ParcelUuid.fromString("0000fdcd-0000-1000-8000-00805f9b34fb")

    // Protocol Constants
    object Command {
        const val AUTH_INIT = 0x01
        const val AUTH_CONFIRM = 0x02
        const val GET_SETTINGS = 0x02
        const val SET_SETTINGS = 0x01
        const val GET_ALARMS = 0x06
        const val SET_ALARM = 0x05
        const val GET_FIRMWARE = 0x0d
        const val PREVIEW_BRIGHTNESS = 0x03
        const val PREVIEW_RINGTONE = 0x04
        const val TIME_SYNC = 0x09
        const val AUDIO_INIT = 0x10
        const val AUDIO_BLOCK = 0x08
    }

    object Header {
        const val AUTH = 0x11.toByte()
        const val TIME = 0x05.toByte()
        const val GET_DATA = 0x01.toByte()
        const val SET_ALARM = 0x07.toByte()
        const val BRIGHTNESS = 0x02.toByte()
        const val RINGTONE_V1 = 0x01.toByte()
        const val RINGTONE_V2 = 0x02.toByte()
        const val SET_SETTINGS = 0x13.toByte()
        const val AUDIO_INIT = 0x08.toByte()
        const val AUDIO_PACKET = 0x81.toByte()

        val ACK = byteArrayOf(0x04.toByte(), 0xFF.toByte())
        val ALARM_DATA = byteArrayOf(0x11.toByte(), 0x06.toByte())
        val SETTINGS_DATA_V1 = byteArrayOf(0x13.toByte(), 0x01.toByte())
        val SETTINGS_DATA_V2 = byteArrayOf(0x13.toByte(), 0x02.toByte())
        const val SENSOR_DATA = 0x00.toByte()
        const val FIRMWARE_DATA = 0x0b.toByte()

        // Fixed bytes in the settings payload
        const val SETTINGS_FIXED_BYTE_3 = 0x58.toByte()
        const val SETTINGS_FIXED_BYTE_4 = 0x02.toByte()
    }

    object Flags {
        const val LANG_ENGLISH = 0x01
        const val TIME_FORMAT_12H = 0x02
        const val TEMP_UNIT_F = 0x04
        const val MASTER_ALARM_DISABLE = 0x10
    }

    object Status {
        const val SUCCESS = 0x00
    }

    object Advertise {
        const val PACKET_TYPE_QINGPING = 0x08
        const val DEVICE_ID_CGD1 = 0x0C

        const val OBJECT_TEMPERATURE_HUMIDITY = 0x01
        const val OBJECT_BATTERY = 0x02
        const val TEMPERATURE_HUMIDITY_LENGTH = 4
        const val BATTERY_LENGTH = 1

        // Fixed header indices. Sensor values after the header use type-length-value objects.
        const val INDEX_PACKET_TYPE = 0
        const val INDEX_DEVICE_ID = 1
        const val HEADER_SIZE = 8
        const val MIN_PAYLOAD_SIZE = HEADER_SIZE + 2
    }

    object Alarm {
        const val ENTRY_LENGTH = 5
        const val START_OFFSET = 3
        const val TOTAL_SLOTS = 16
    }

    object Settings {
        const val MIN_PAYLOAD_SIZE = 15

        // Payload indices
        const val INDEX_VOLUME = 2
        const val INDEX_FLAGS = 5
        const val INDEX_TZ_OFFSET = 6
        const val INDEX_BACKLIGHT_DUR = 7
        const val INDEX_PACKED_BRIGHTNESS = 8
        const val INDEX_NIGHT_START_H = 9
        const val INDEX_NIGHT_START_M = 10
        const val INDEX_NIGHT_END_H = 11
        const val INDEX_NIGHT_END_M = 12
        const val INDEX_TZ_SIGN = 13
        const val INDEX_NIGHT_MODE_EN = 14
        const val INDEX_RINGTONE_SIG = 16
    }

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
