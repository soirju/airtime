package com.airtime.app.audio

import kotlin.math.sqrt

/**
 * Manages speaker identification using ECAPA-TDNN embeddings (192-dim).
 * Compares incoming voice segments against known speaker profiles
 * using cosine similarity on L2-normalized embeddings.
 */
class SpeakerIdentifier(private val model: EcapaModel) {

    companion object {
        /** Cosine similarity threshold to match a known speaker. */
        const val MATCH_THRESHOLD = 0.55f
        /** Minimum RMS energy to consider a frame as speech (not silence). */
        const val SILENCE_RMS_THRESHOLD = 500
    }

    data class SpeakerProfile(
        val id: Int,
        var name: String?,
        val embedding: FloatArray
    ) {
        override fun equals(other: Any?) = other is SpeakerProfile && id == other.id
        override fun hashCode() = id
    }

    private val profiles = mutableListOf<SpeakerProfile>()
    private var nextId = 1

    fun getProfiles(): List<SpeakerProfile> = profiles.toList()

    fun addKnownProfile(id: Int, name: String?, embedding: FloatArray) {
        profiles.add(SpeakerProfile(id, name, embedding))
        if (id >= nextId) nextId = id + 1
    }

    /**
     * Identify the speaker from a PCM audio chunk.
     * Returns the speaker ID, or -1 if silence / too short.
     */
    fun identify(pcm16: ShortArray): Int {
        if (isSilence(pcm16)) return -1

        val embedding = model.extractEmbedding(pcm16) ?: return -1

        var bestId = -1
        var bestSim = MATCH_THRESHOLD

        for (profile in profiles) {
            val sim = cosineSimilarity(embedding, profile.embedding)
            if (sim > bestSim) {
                bestSim = sim
                bestId = profile.id
            }
        }

        if (bestId != -1) return bestId

        // New speaker
        val newProfile = SpeakerProfile(nextId++, null, embedding)
        profiles.add(newProfile)
        return newProfile.id
    }

    fun updateName(speakerId: Int, name: String) {
        profiles.find { it.id == speakerId }?.name = name
    }

    fun removeProfile(speakerId: Int) {
        profiles.removeAll { it.id == speakerId }
    }

    private fun isSilence(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true
        var sum = 0L
        for (s in pcm) sum += s.toLong() * s.toLong()
        val rms = sqrt(sum.toDouble() / pcm.size)
        return rms < SILENCE_RMS_THRESHOLD
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }
}
