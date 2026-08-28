package com.mrboombastic.buwudzik.device

/** Parsed acknowledgement sent by the CGD1 notify characteristics. */
internal data class BleAck(
    val command: Int,
    val payloadSize: Int,
    val status: Int
)

/**
 * Parses the ACK sent by the device: `04 ff [command] [status] [payload]`.
 *
 * The leading `04` is a length byte counting the four bytes that follow it, so an ACK is always
 * exactly five bytes long: the status sits at index 3 and index 4 holds a single command specific
 * payload byte, e.g. `04 ff 01 00 06` means "command 01 succeeded" with the payload byte `06`.
 * Shorter or longer frames are still accepted in case some firmware deviates from that layout.
 */
internal fun parseBleAck(value: ByteArray): BleAck? {
    if (value.size < 4 || value[0] != 0x04.toByte() || value[1] != 0xff.toByte()) return null

    return BleAck(
        command = value[2].toInt() and 0xff,
        payloadSize = maxOf(0, value.size - 4),
        status = value[3].toInt() and 0xff
    )
}
