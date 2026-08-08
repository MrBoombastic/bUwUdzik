package com.mrboombastic.buwudzik.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleProtocolParserTest {
    @Test
    fun `five byte ack uses final status byte`() {
        assertEquals(
            BleAck(command = 1, payloadLength = 0, status = 2),
            parseBleAck(hex("04 ff 01 00 02"))
        )
    }

    @Test
    fun `four byte legacy ack remains supported`() {
        assertEquals(
            BleAck(command = 9, payloadLength = 0, status = 0),
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
