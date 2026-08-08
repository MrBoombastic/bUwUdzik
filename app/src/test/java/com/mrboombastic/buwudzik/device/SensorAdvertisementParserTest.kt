package com.mrboombastic.buwudzik.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SensorAdvertisementParserTest {
    @Test
    fun `parses captured CGD1 payload`() {
        val parsed = SensorAdvertisementParser.parseServiceData(
            hex("08 0c bf 65 52 34 2d 58 01 04 f1 00 ad 01 02 01 25")
        )

        assertEquals(24.1, parsed?.temperature)
        assertEquals(42.9, parsed?.humidity)
        assertEquals(37, parsed?.battery)
    }

    @Test
    fun `parses objects in any order and signed temperature`() {
        val parsed = SensorAdvertisementParser.parseServiceData(
            hex("08 0c bf 65 52 34 2d 58 02 01 64 01 04 c9 ff f5 01")
        )

        assertEquals(-5.5, parsed?.temperature)
        assertEquals(50.1, parsed?.humidity)
        assertEquals(100, parsed?.battery)
    }

    @Test
    fun `allows missing battery but requires sensor object`() {
        val withoutBattery = SensorAdvertisementParser.parseServiceData(
            hex("08 0c bf 65 52 34 2d 58 01 04 fa 00 f4 01")
        )
        val withoutSensor = SensorAdvertisementParser.parseServiceData(
            hex("08 0c bf 65 52 34 2d 58 02 01 50")
        )

        assertEquals(null, withoutBattery?.battery)
        assertNull(withoutSensor)
    }

    @Test
    fun `rejects truncated TLV`() {
        assertNull(
            SensorAdvertisementParser.parseServiceData(
                hex("08 0c bf 65 52 34 2d 58 01 04 f1 00")
            )
        )
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
