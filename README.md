# Airtime

Android app that listens to conversations via the device microphone and identifies **who** is speaking — not *what* they say. It tracks cumulative talk time per speaker for the duration of a session and displays it in a live-updating list.

## What it does

- **Speaker recognition** using [ECAPA-TDNN](https://arxiv.org/abs/2005.07143), a neural speaker embedding model trained on VoxCeleb. The model produces 192-dimensional voice fingerprints that are compared via cosine similarity to distinguish speakers.
- **Cumulative talk time tracking** per identified speaker, displayed in real time. This data is ephemeral — it resets when the service stops.
- **Background operation** via an Android foreground service with a persistent notification, so listening continues when the app is not in the foreground.
- **Speaker profiles** (name + voice embedding) are stored persistently in a local SQLite database (Speaker DB). New speakers are auto-detected; you can assign names to them from the UI.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  ListeningService (Foreground Service)              │
│                                                     │
│  AudioRecord (16kHz mono PCM)                       │
│       │  2-second chunks                            │
│       ▼                                             │
│  FbankExtractor                                     │
│  (80-bin log Mel-filterbank, Hamming window, FFT)   │
│       │  [1, 80, T] tensor                          │
│       ▼                                             │
│  EcapaModel (ONNX Runtime)                          │
│  (ECAPA-TDNN → 192-dim L2-normalized embedding)     │
│       │                                             │
│       ▼                                             │
│  SpeakerIdentifier                                  │
│  (cosine similarity against known profiles,         │
│   auto-creates new profile if no match > 0.55)      │
│       │                                             │
│       ▼                                             │
│  Talk time accumulator (in-memory map)              │
└─────────────────────────────────────────────────────┘
         │                          ▲
         │ profiles persisted       │ profiles restored
         ▼                          │
   ┌───────────┐             ┌──────────────┐
   │ Speaker DB │             │  App startup  │
   │ (SQLite)   │◄───────────│              │
   └───────────┘             └──────────────┘
```

### Key components

| File | Role |
|---|---|
| `audio/EcapaModel.kt` | Loads `ecapa_tdnn.onnx` from assets, runs inference via ONNX Runtime |
| `audio/FbankExtractor.kt` | Computes 80-bin log Mel-filterbank features with an in-place Cooley-Tukey FFT |
| `audio/SpeakerIdentifier.kt` | Matches embeddings against known speaker profiles, creates new profiles for unknown speakers |
| `db/SpeakerDb.kt` | SQLite persistence for speaker names and voice embeddings |
| `service/ListeningService.kt` | Foreground service: records audio, runs the identification pipeline, accumulates talk time |
| `ui/MainActivity.kt` | Jetpack Compose UI: live speaker list with talk times, rename capability, start/stop control |
| `ui/MainViewModel.kt` | Bridges service state to the Compose UI with 1-second polling |

## Setup

### 1. Export the ONNX model

The app requires `ecapa_tdnn.onnx` in the assets directory. Generate it from the SpeechBrain pretrained model:

```bash
pip install speechbrain onnx onnxruntime torch
python export_model.py
```

This downloads the [spkrec-ecapa-voxceleb](https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb) model and exports it to `app/src/main/assets/ecapa_tdnn.onnx` (~25 MB).

### 2. Build

Open the project in Android Studio, sync Gradle, and run on a device or emulator with microphone access. Targets API 26+ (Android 8.0).

## Usage

1. Tap the **mic button** to start listening. The app requests microphone and notification permissions on first launch.
2. A persistent notification appears while the service is active.
3. Speakers are auto-detected as they talk. Each gets a card showing cumulative talk time.
4. Tap the **edit icon** on a speaker card to assign a name. Named speakers and their voice profiles persist across app restarts.
5. Tap the mic button again to stop listening. Talk time data resets; speaker profiles remain.

## Dependencies

- [ONNX Runtime Android](https://onnxruntime.ai/) 1.19.2 — on-device model inference
- [ECAPA-TDNN](https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb) — speaker embedding model (VoxCeleb-trained)
- Jetpack Compose + Material 3 — UI
- AndroidX Lifecycle — foreground service and ViewModel

## Tuning

- **`SpeakerIdentifier.MATCH_THRESHOLD`** (default 0.55): cosine similarity threshold for matching a known speaker. Raise it to reduce false merges in noisy environments; lower it if the same speaker keeps getting split into multiple profiles.
- **`SpeakerIdentifier.SILENCE_RMS_THRESHOLD`** (default 500): RMS energy below which a chunk is treated as silence and skipped.
- **`ListeningService.CHUNK_SECONDS`** (default 2): length of each audio analysis window. Longer chunks give more stable embeddings but less granular time tracking.
