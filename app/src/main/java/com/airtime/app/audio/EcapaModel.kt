package com.airtime.app.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Runs ECAPA-TDNN speaker embedding model via ONNX Runtime.
 * Expects the model file at assets/ecapa_tdnn.onnx.
 *
 * Input:  [1, 80, T] log Mel-filterbank features
 * Output: [1, 192] speaker embedding vector
 */
class EcapaModel(context: Context) {

    companion object {
        const val EMBEDDING_DIM = 192
        private const val MODEL_FILE = "ecapa_tdnn.onnx"
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        session = env.createSession(modelBytes)
    }

    /**
     * Extract a 192-dim speaker embedding from raw PCM16 audio.
     * Returns null if the audio is too short.
     */
    fun extractEmbedding(pcm16: ShortArray): FloatArray? {
        val fbank = FbankExtractor.extract(pcm16)
        if (fbank.isEmpty()) return null

        val numFrames = fbank.size
        val flatInput = FbankExtractor.toOnnxInput(fbank)

        val shape = longArrayOf(1, 80, numFrames.toLong())
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatInput), shape)

        val results = session.run(mapOf(session.inputNames.first() to inputTensor))
        val output = results[0].value

        inputTensor.close()

        // Output is [1, 192] — extract the embedding and L2-normalize
        val raw = when (output) {
            is Array<*> -> (output as Array<FloatArray>)[0]
            else -> return null
        }
        results.close()
        return l2Normalize(raw)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        return if (norm > 0f) FloatArray(v.size) { v[it] / norm } else v
    }

    fun close() {
        session.close()
    }
}
