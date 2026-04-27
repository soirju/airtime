package com.airtime.app.audio

import kotlin.math.*

/**
 * Computes 80-bin log Mel-filterbank features from raw PCM16 audio.
 * This is the input format expected by ECAPA-TDNN.
 *
 * Parameters match SpeechBrain's defaults:
 * - 16kHz sample rate, 25ms frame, 10ms hop, 80 Mel bins
 */
object FbankExtractor {

    private const val SAMPLE_RATE = 16000
    private const val FRAME_MS = 25
    private const val HOP_MS = 10
    private const val N_MELS = 80
    private const val N_FFT = 512 // next power of 2 >= SAMPLE_RATE * FRAME_MS / 1000

    private val FRAME_LEN = SAMPLE_RATE * FRAME_MS / 1000  // 400
    private val HOP_LEN = SAMPLE_RATE * HOP_MS / 1000      // 160

    /** Returns [numFrames][80] log Mel-filterbank features. */
    fun extract(pcm16: ShortArray): Array<FloatArray> {
        // Normalize to [-1, 1]
        val signal = FloatArray(pcm16.size) { pcm16[it].toFloat() / 32768f }

        val numFrames = maxOf(0, (signal.size - FRAME_LEN) / HOP_LEN + 1)
        if (numFrames == 0) return emptyArray()

        val melFilterbank = buildMelFilterbank()
        val result = Array(numFrames) { FloatArray(N_MELS) }

        for (f in 0 until numFrames) {
            val offset = f * HOP_LEN

            // Windowed frame → power spectrum via DFT
            val powerSpec = powerSpectrum(signal, offset)

            // Apply Mel filterbank
            for (m in 0 until N_MELS) {
                var energy = 0f
                for (k in melFilterbank[m].indices) {
                    energy += powerSpec[melFilterbank[m][k].first] * melFilterbank[m][k].second
                }
                result[f][m] = ln(maxOf(energy, 1e-10f))
            }
        }
        return result
    }

    /** Flatten [numFrames][80] to [1][80][numFrames] for ONNX input (batch=1, features, time). */
    fun toOnnxInput(fbank: Array<FloatArray>): FloatArray {
        val numFrames = fbank.size
        val flat = FloatArray(N_MELS * numFrames)
        for (m in 0 until N_MELS) {
            for (t in 0 until numFrames) {
                flat[m * numFrames + t] = fbank[t][m]
            }
        }
        return flat
    }

    private fun powerSpectrum(signal: FloatArray, offset: Int): FloatArray {
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        // Apply Hamming window
        for (i in 0 until FRAME_LEN) {
            val w = (0.54f - 0.46f * cos(2.0 * PI * i / (FRAME_LEN - 1))).toFloat()
            re[i] = if (offset + i < signal.size) signal[offset + i] * w else 0f
        }
        fft(re, im)
        val halfSpec = N_FFT / 2 + 1
        return FloatArray(halfSpec) { k -> re[k] * re[k] + im[k] * im[k] }
    }

    /** In-place Cooley-Tukey FFT. Arrays must be power-of-2 length. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        // FFT butterfly
        var len = 2
        while (len <= n) {
            val ang = (-2.0 * PI / len).toFloat()
            val wRe = cos(ang.toDouble()).toFloat()
            val wIm = sin(ang.toDouble()).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val tRe = curRe * re[i + k + len / 2] - curIm * im[i + k + len / 2]
                    val tIm = curRe * im[i + k + len / 2] + curIm * re[i + k + len / 2]
                    re[i + k + len / 2] = re[i + k] - tRe
                    im[i + k + len / 2] = im[i + k] - tIm
                    re[i + k] += tRe
                    im[i + k] += tIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newCurRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Build sparse Mel filterbank: for each Mel bin, list of (fft_bin, weight) pairs. */
    private fun buildMelFilterbank(): Array<Array<Pair<Int, Float>>> {
        val fMax = SAMPLE_RATE / 2f
        val melLow = hzToMel(0f)
        val melHigh = hzToMel(fMax)
        val melPoints = FloatArray(N_MELS + 2) { i ->
            melToHz(melLow + i * (melHigh - melLow) / (N_MELS + 1))
        }
        val bins = IntArray(melPoints.size) { i ->
            ((melPoints[i] / SAMPLE_RATE * N_FFT).toInt()).coerceIn(0, N_FFT / 2)
        }

        return Array(N_MELS) { m ->
            val pairs = mutableListOf<Pair<Int, Float>>()
            for (k in bins[m]..bins[m + 2]) {
                val w = when {
                    k < bins[m + 1] && bins[m + 1] != bins[m] ->
                        (k - bins[m]).toFloat() / (bins[m + 1] - bins[m])
                    bins[m + 2] != bins[m + 1] ->
                        (bins[m + 2] - k).toFloat() / (bins[m + 2] - bins[m + 1])
                    else -> 0f
                }
                if (w > 0f) pairs.add(k to w)
            }
            pairs.toTypedArray()
        }
    }

    private fun hzToMel(hz: Float) = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float) = 700f * (10f.pow(mel / 2595f) - 1f)
}
