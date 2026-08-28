package com.mrboombastic.buwudzik.device

/** Parsed acknowledgement sent by the CGD1 notify characteristics. */
internal data class BleAck(
    val command: Int,
    val payloadLength: Int,
    val status: Int
)

/**
 * Parses the ACK sent by the device: `04 ff [command] [status] [optional payload...]`.
 *
 * The status always sits at index 3; anything after it is command specific payload, e.g. the
 * Auth Init reply `04 ff 01 00 06` means "command 01 succeeded" with a single payload byte.
 */
internal fun parseBleAck(value: ByteArray): BleAck? {
    if (value.size < 4 || value[0] != 0x04.toByte() || value[1] != 0xff.toByte()) return null

    return BleAck(
        command = value[2].toInt() and 0xff,
        payloadLength = maxOf(0, value.size - 4),
        status = value[3].toInt() and 0xff
    )
}
