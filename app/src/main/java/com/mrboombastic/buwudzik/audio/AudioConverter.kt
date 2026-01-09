package com.mrboombastic.buwudzik.audio


import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mrboombastic.buwudzik.utils.AppLogger
import java.io.ByteArrayOutputStream
import java.io.File

enum class ChannelMode {
    LEFT, RIGHT, MIX
}

/**
 * Converts audio files to PCM Unsigned 8-bit, 8kHz, Mono
 */
class AudioConverter(private val context: Context) {

    companion object {
        private const val TAG = "AudioConverter"
        const val SAMPLE_RATE = 8000
        const val PADDING_BOUNDARY = 512
        const val END_VALUE = 0x00.toByte()
        const val PADDING_VALUE = 0xFF.toByte()

        fun saveAsWav(pcmData: ByteArray, file: File) {
            val header = ByteArray(44)
            val totalDataLen = pcmData.size.toLong()
            val byteRate = SAMPLE_RATE * 1 // 8 bit mono
            val totalAudioLen = totalDataLen + 36

            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalAudioLen and 0xff).toByte()
            header[5] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[6] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[7] = ((totalAudioLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // Format = PCM
            header[21] = 0
            header[22] = 1 // Channels = 1
            header[23] = 0
            header[24] = (SAMPLE_RATE and 0xff).toByte()
            header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
            header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
            header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = (0).toByte()
            header[31] = (0).toByte()
            header[32] = 1 // Block align
            header[33] = 0
            header[34] = 8 // Bits per sample
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalDataLen and 0xff).toByte()
            header[41] = ((totalDataLen shr 8) and 0xff).toByte()
            header[42] = ((totalDataLen shr 16) and 0xff).toByte()
            header[43] = ((totalDataLen shr 24) and 0xff).toByte()

            val fos = java.io.FileOutputStream(file)
            fos.write(header)
            fos.write(pcmData)
            fos.close()
        }
    }

    data class ConversionResult(
        val pcmData: ByteArray, val durationMs: Long, val originalSampleRate: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ConversionResult
            return pcmData.contentEquals(other.pcmData) && durationMs == other.durationMs && originalSampleRate == other.originalSampleRate
        }

        override fun hashCode(): Int {
            var result = pcmData.contentHashCode()
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + originalSampleRate
            return result
        }
    }

    /**
     * Convert audio file to PCM U8 8kHz mono
     */
    fun convertToPcm(
        uri: Uri,
        startMs: Long = 0,
        durationMs: Long = 3000,
        channelMode: ChannelMode = ChannelMode.MIX
    ): ConversionResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIdx = -1
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIdx = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIdx < 0 || audioFormat == null) {
            throw IllegalArgumentException("No audio track found")
        }

        extractor.selectTrack(audioTrackIdx)

        if (startMs > 0) {
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
        val originalSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        AppLogger.d(TAG, "Source: $mime, $originalSampleRate Hz, $channels ch")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        val outputStream = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        val endTimeUs = (startMs + durationMs) * 1000
        var inputDone = false
        var outputDone = false

        var decodingSampleRate = originalSampleRate
        var decodingChannels = channels

        while (!outputDone) {
            if (!inputDone) {
                val inputIdx = codec.dequeueInputBuffer(10000)
                if (inputIdx >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIdx)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0 || extractor.sampleTime > endTimeUs) {
                        codec.queueInputBuffer(
                            inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIdx = codec.dequeueOutputBuffer(info, 10000)
            if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                decodingSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                decodingChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                AppLogger.d(
                    TAG,
                    "Output format changed: $decodingSampleRate Hz, $decodingChannels ch"
                )
            } else if (outputIdx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }

                if (info.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIdx)!!
                    val pcm16 = ShortArray(info.size / 2)
                    outputBuffer.asShortBuffer().get(pcm16)

                    val monoSamples = if (decodingChannels > 1) {
                        ShortArray(pcm16.size / decodingChannels) { i ->
                            when (channelMode) {
                                ChannelMode.LEFT -> pcm16[i * decodingChannels]
                                ChannelMode.RIGHT -> if (decodingChannels > 1) pcm16[i * decodingChannels + 1] else pcm16[i * decodingChannels]
                                ChannelMode.MIX -> {
                                    var sum = 0
                                    for (c in 0 until decodingChannels) {
                                        sum += pcm16[i * decodingChannels + c]
                                    }
                                    (sum / decodingChannels).toShort()
                                }
                            }
                        }
                    } else {
                        pcm16
                    }

                    val resampled = if (decodingSampleRate != SAMPLE_RATE) {
                        resample(monoSamples, decodingSampleRate)
                    } else {
                        monoSamples
                    }

                    for (sample in resampled) {
                        val u8 = ((sample.toInt() + 32768) shr 8).coerceIn(0, 255)
                        outputStream.write(u8)
                    }
                }

                codec.releaseOutputBuffer(outputIdx, false)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val pcmData = outputStream.toByteArray()
        val actualDurationMs = (pcmData.size * 1000L) / SAMPLE_RATE

        AppLogger.d(TAG, "Output: ${pcmData.size} bytes, ${actualDurationMs}ms")

        return ConversionResult(pcmData, actualDurationMs, originalSampleRate)
    }

    private fun resample(input: ShortArray, inputRate: Int): ShortArray {
        val ratio = inputRate.toDouble() / SAMPLE_RATE
        val outputSize = (input.size / ratio).toInt()
        val output = ShortArray(outputSize)

        for (i in 0 until outputSize) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            val sample1 = input.getOrElse(srcIdx) { input.last() }
            val sample2 = input.getOrElse(srcIdx + 1) { input.last() }

            output[i] = (sample1 + (sample2 - sample1) * frac).toInt().toShort()
        }

        return output
    }

    /**
     * Add padding to align to block boundary.
     * First 2 bytes are 0x00 (end marker), rest is 0xFF (silence).
     */
    fun addPadding(data: ByteArray): ByteArray {
        val remainder = data.size % PADDING_BOUNDARY
        if (remainder == 0) return data

        val paddingNeeded = PADDING_BOUNDARY - remainder
        val padding = ByteArray(paddingNeeded) { i ->
            if (i == 0) END_VALUE else PADDING_VALUE
        }

        return data + padding
    }

}



