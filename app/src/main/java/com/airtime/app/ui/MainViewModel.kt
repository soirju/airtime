package com.airtime.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.airtime.app.audio.SpeakerIdentifier
import com.airtime.app.service.ListeningService

data class SpeakerUiState(
    val id: Int,
    val name: String?,
    val talkTimeMs: Long
)

class MainViewModel : ViewModel() {

    val speakers = mutableStateListOf<SpeakerUiState>()
    val isListening = mutableStateOf(false)

    /** Refresh UI state from the service's live data. */
    fun refresh() {
        isListening.value = ListeningService.isRunning
        val identifier = ListeningService.identifier ?: return
        val times = synchronized(ListeningService.talkTimeMs) {
            ListeningService.talkTimeMs.toMap()
        }
        val profiles = identifier.getProfiles()
        speakers.clear()
        speakers.addAll(
            profiles.map { p ->
                SpeakerUiState(p.id, p.name, times[p.id] ?: 0L)
            }.sortedByDescending { it.talkTimeMs }
        )
    }

    fun renameSpeaker(id: Int, name: String) {
        ListeningService.identifier?.updateName(id, name)
        refresh()
    }
}
