package com.mrboombastic.buwudzik.device

import android.bluetooth.le.ScanResult
import com.mrboombastic.buwudzik.device.BleConstants.Advertise
import com.mrboombastic.buwudzik.device.BleConstants.UUID_SERVICE_ADVERTISING

/** Parses the passive CGD1 advertisement used by both callback and PendingIntent scans. */
object SensorAdvertisementParser {
    fun parse(result: ScanResult, displayName: String? = null): SensorData? {
        val serviceData = result.scanRecord?.getServiceData(UUID_SERVICE_ADVERTISING) ?: return null
        val payload = parseServiceData(serviceData) ?: return null

        return SensorData(
            temperature = payload.temperature,
            humidity = payload.humidity,
            battery = payload.battery ?: -1,
            rssi = result.rssi,
            name = displayName ?: result.scanRecord?.deviceName,
            macAddress = result.device.address
        )
    }

    /** Parses the Qingping FDCD type-length-value payload independently of Android scan APIs. */
    internal fun parseServiceData(serviceData: ByteArray): ParsedSensorAdvertisement? {
        if (serviceData.size < Advertise.MIN_PAYLOAD_SIZE) return null

        val packetType = serviceData[Advertise.INDEX_PACKET_TYPE].toInt() and 0xff
        val maskedPacketType = packetType and 0x0F
        if (maskedPacketType != Advertise.PACKET_TYPE_QINGPING) return null

        val deviceId = serviceData[Advertise.INDEX_DEVICE_ID].toInt() and 0xff
        if (deviceId != Advertise.DEVICE_ID_CGD1) return null

        var temperature: Double? = null
        var humidity: Double? = null
        var battery: Int? = null
        var offset = Advertise.HEADER_SIZE

        while (offset + 2 <= serviceData.size) {
            val type = serviceData[offset].toInt() and 0xff
            val length = serviceData[offset + 1].toInt() and 0xff
            val valueOffset = offset + 2
            val nextOffset = valueOffset + length
            if (nextOffset > serviceData.size) return null

            when (type) {
                Advertise.OBJECT_TEMPERATURE_HUMIDITY -> {
                    if (length == Advertise.TEMPERATURE_HUMIDITY_LENGTH) {
                        val tempRaw = littleEndianInt16(serviceData, valueOffset)
                        val humidityRaw = littleEndianUInt16(serviceData, valueOffset + 2)
                        temperature = tempRaw / 10.0
                        humidity = humidityRaw / 10.0
                    }
                }

                Advertise.OBJECT_BATTERY -> {
                    if (length == Advertise.BATTERY_LENGTH) {
                        battery = serviceData[valueOffset].toInt() and 0xff
                    }
                }
            }
            offset = nextOffset
        }

        val parsedTemperature = temperature ?: return null
        val parsedHumidity = humidity ?: return null
        return ParsedSensorAdvertisement(parsedTemperature, parsedHumidity, battery)
    }

    private fun littleEndianInt16(value: ByteArray, offset: Int): Int =
        littleEndianUInt16(value, offset).toShort().toInt()

    private fun littleEndianUInt16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xff) or ((value[offset + 1].toInt() and 0xff) shl 8)
}

internal data class ParsedSensorAdvertisement(
    val temperature: Double,
    val humidity: Double,
    val battery: Int?
)
