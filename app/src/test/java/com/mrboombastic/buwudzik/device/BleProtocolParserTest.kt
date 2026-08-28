package com.mrboombastic.buwudzik.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleProtocolParserTest {
    @Test
    fun `five byte ack uses index 3 status byte`() {
        assertEquals(
            BleAck(command = 1, payloadSize = 1, status = 0),
            parseBleAck(hex("04 ff 01 00 02"))
        )
    }

    @Test
    fun `auth init ack with payload byte is successful`() {
        assertEquals(
            BleAck(command = 1, payloadSize = 1, status = 0),
            parseBleAck(hex("04 ff 01 00 06"))
        )
    }

    @Test
    fun `non zero status is reported as failure`() {
        assertEquals(
            BleAck(command = 0x10, payloadSize = 0, status = 6),
            parseBleAck(hex("04 ff 10 06"))
        )
    }

    @Test
    fun `five byte error frame reports non-zero status`() {
        // 04 ff 10 06 00 — command 0x10, status 0x06 (error), one payload byte
        assertEquals(
            BleAck(command = 0x10, payloadSize = 1, status = 6),
            parseBleAck(hex("04 ff 10 06 00"))
        )
    }

    @Test
    fun `four byte legacy ack remains supported`() {
        assertEquals(
            BleAck(command = 9, payloadSize = 0, status = 0),
            parseBleAck(hex("04 ff 09 00"))
        )
    }

    @Test
    fun `non ack packet is rejected`() {
        assertNull(parseBleAck(hex("11 06 00 00")))
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
