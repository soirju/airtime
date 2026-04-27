package com.airtime.app.audio

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a 16-bit PCM WAV file from assets into a ShortArray.
 * Supports mono and stereo (stereo is downmixed to mono).
 * Resamples to the target sample rate if needed using simple linear interpolation.
 */
object WavDecoder {

    fun decodeFromAssets(context: Context, path: String, targetSampleRate: Int = 16000): ShortArray? {
        return try {
            context.assets.open(path).use { decode(it, targetSampleRate) }
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(input: InputStream, targetSampleRate: Int): ShortArray? {
        val bytes = input.readBytes()
        if (bytes.size < 44) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Parse WAV header
        buf.position(20)
        val audioFormat = buf.short.toInt()
        val channels = buf.short.toInt()
        val sampleRate = buf.int
        buf.position(34)
        val bitsPerSample = buf.short.toInt()

        if (audioFormat != 1 || bitsPerSample != 16) return null // PCM 16-bit only

        // Find "data" chunk
        var pos = 36
        while (pos + 8 < bytes.size) {
            buf.position(pos)
            val chunkId = ByteArray(4).also { buf.get(it) }
            val chunkSize = buf.int
            if (String(chunkId) == "data") {
                val dataStart = pos + 8
                val sampleCount = chunkSize / 2
                buf.position(dataStart)
                val raw = ShortArray(sampleCount) { buf.short }

                // Downmix stereo to mono
                val mono = if (channels == 2) {
                    ShortArray(sampleCount / 2) { i ->
                        ((raw[i * 2].toInt() + raw[i * 2 + 1].toInt()) / 2).toShort()
                    }
                } else raw

                // Resample if needed
                return if (sampleRate != targetSampleRate) {
                    resample(mono, sampleRate, targetSampleRate)
                } else mono
            }
            pos += 8 + chunkSize
        }
        return null
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        return ShortArray(outLen) { i ->
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceAtMost(input.size - 2)
            val frac = (srcPos - idx).toFloat()
            (input[idx] * (1f - frac) + input[idx + 1] * frac).toInt().toShort()
        }
    }
}
