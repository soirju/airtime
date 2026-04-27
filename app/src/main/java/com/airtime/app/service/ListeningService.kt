package com.airtime.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airtime.app.R
import com.airtime.app.audio.EcapaModel
import com.airtime.app.audio.SpeakerIdentifier
import com.airtime.app.db.SpeakerDb
import com.airtime.app.ui.MainActivity

/**
 * Foreground service that continuously records audio, identifies speakers
 * via ECAPA-TDNN, and tracks cumulative talk time per speaker.
 */
class ListeningService : Service() {

    companion object {
        const val CHANNEL_ID = "airtime_listening"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 16000
        const val CHUNK_SECONDS = 2

        var identifier: SpeakerIdentifier? = null
            private set
        val talkTimeMs = mutableMapOf<Int, Long>()
        var isRunning = false
            private set
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private lateinit var speakerDb: SpeakerDb
    private var ecapaModel: EcapaModel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        speakerDb = SpeakerDb(this)

        ecapaModel = EcapaModel(this)
        identifier = SpeakerIdentifier(ecapaModel!!)

        for (p in speakerDb.loadAll()) {
            identifier!!.addKnownProfile(p.id, p.name, p.embedding)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Listening for speakers…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        startRecording()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        recordingThread?.interrupt()
        identifier?.getProfiles()?.forEach { speakerDb.saveSpeaker(it) }
        identifier = null
        ecapaModel?.close()
        ecapaModel = null
        super.onDestroy()
    }

    private fun startRecording() {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            SAMPLE_RATE * CHUNK_SECONDS * 2
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()

        recordingThread = Thread {
            val chunkSamples = SAMPLE_RATE * CHUNK_SECONDS
            val buffer = ShortArray(chunkSamples)

            while (!Thread.currentThread().isInterrupted && isRunning) {
                val read = audioRecord?.read(buffer, 0, chunkSamples) ?: break
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    val speakerId = identifier?.identify(chunk) ?: continue
                    if (speakerId != -1) {
                        val chunkMs = (read.toLong() * 1000) / SAMPLE_RATE
                        synchronized(talkTimeMs) {
                            talkTimeMs[speakerId] = (talkTimeMs[speakerId] ?: 0L) + chunkMs
                        }
                        updateNotification()
                    }
                }
            }
        }.also { it.start() }
    }

    private fun updateNotification() {
        val count = synchronized(talkTimeMs) { talkTimeMs.size }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Tracking $count speaker(s)"))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Airtime")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Listening Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows when Airtime is listening" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
