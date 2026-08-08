package com.mrboombastic.buwudzik.device

/** Parsed acknowledgement sent by the CGD1 notify characteristics. */
internal data class BleAck(
    val command: Int,
    val payloadLength: Int,
    val status: Int
)

/**
 * Parses the documented five-byte ACK: `04 ff [command] [length] [status]`.
 *
 * Four-byte ACKs are accepted as a compatibility fallback for firmware that omits the length byte.
 */
internal fun parseBleAck(value: ByteArray): BleAck? {
    if (value.size < 4 || value[0] != 0x04.toByte() || value[1] != 0xff.toByte()) return null

    return if (value.size >= 5) {
        BleAck(
            command = value[2].toInt() and 0xff,
            payloadLength = value[3].toInt() and 0xff,
            status = value[4].toInt() and 0xff
        )
    } else {
        BleAck(
            command = value[2].toInt() and 0xff,
            payloadLength = 0,
            status = value[3].toInt() and 0xff
        )
    }
}
