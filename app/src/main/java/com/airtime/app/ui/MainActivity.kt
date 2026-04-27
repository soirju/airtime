package com.airtime.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airtime.app.service.ListeningService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startListening(pendingAudioFile)
    }

    private var pendingAudioFile: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val testFiles = loadTestAudioFiles()
        setContent {
            MaterialTheme {
                AirtimeScreen(
                    onStart = { audioFile ->
                        pendingAudioFile = audioFile
                        requestPermissionsAndStart()
                    },
                    onStop = { stopListening() },
                    testAudioFiles = testFiles
                )
            }
        }
    }

    private fun loadTestAudioFiles(): List<String> {
        return try {
            assets.list("test-audio")
                ?.filter { it.endsWith(".wav", ignoreCase = true) }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            perms.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startListening(pendingAudioFile)
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startListening(audioFile: String?) {
        val intent = Intent(this, ListeningService::class.java)
        audioFile?.let { intent.putExtra(ListeningService.EXTRA_AUDIO_FILE, "test-audio/$it") }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopListening() {
        stopService(Intent(this, ListeningService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirtimeScreen(
    onStart: (audioFile: String?) -> Unit,
    onStop: () -> Unit,
    testAudioFiles: List<String> = emptyList(),
    vm: MainViewModel = viewModel()
) {
    // Auto-refresh every second
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(1000)
        }
    }

    var showFileMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Airtime") })
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (testAudioFiles.isNotEmpty() && !vm.isListening.value) {
                    Box {
                        FloatingActionButton(
                            onClick = { showFileMenu = true },
                            modifier = Modifier.padding(bottom = 12.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play test audio")
                        }
                        DropdownMenu(
                            expanded = showFileMenu,
                            onDismissRequest = { showFileMenu = false }
                        ) {
                            testAudioFiles.forEach { file ->
                                DropdownMenuItem(
                                    text = { Text(file) },
                                    onClick = {
                                        showFileMenu = false
                                        onStart(file)
                                    }
                                )
                            }
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { if (vm.isListening.value) onStop() else onStart(null) }
                ) {
                    Icon(
                        if (vm.isListening.value) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (vm.isListening.value) "Stop listening" else "Start listening"
                    )
                }
            }
        }
    ) { padding ->
        if (vm.speakers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.isListening.value) "Listening… waiting for speech"
                    else "Tap the mic button to start",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.speakers, key = { it.id }) { speaker ->
                    SpeakerCard(speaker) { newName -> vm.renameSpeaker(speaker.id, newName) }
                }
            }
        }
    }
}

@Composable
fun SpeakerCard(speaker: SpeakerUiState, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var nameField by remember(speaker.name) { mutableStateOf(speaker.name ?: "") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = nameField,
                        onValueChange = { nameField = it },
                        label = { Text("Speaker name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextButton(onClick = {
                            onRename(nameField)
                            editing = false
                        }) { Text("Save") }
                        TextButton(onClick = { editing = false }) { Text("Cancel") }
                    }
                } else {
                    Text(
                        speaker.name ?: "Speaker ${speaker.id}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    formatDuration(speaker.talkTimeMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!editing) {
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename speaker")
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
