package com.example.dictator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File

class DictationService : Service() {

    private enum class State { IDLE, ACTIVE, STOPPING }

    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var overlayManager: OverlayManager
    private var groqClient: GroqWhisperClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var state = State.IDLE

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector(::onShake)
        overlayManager = OverlayManager(this, ::stopRecordingAndTranscribe)

        refreshGroqClient()
        registerShakeDetector()
    }

    private fun refreshGroqClient() {
        val key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""
        groqClient = if (key.isNotEmpty()) GroqWhisperClient(key) else null
    }

    private fun onShake() {
        when (state) {
            State.IDLE -> startRecording()
            State.ACTIVE -> stopRecordingAndTranscribe()
            State.STOPPING -> Unit
        }
    }

    private fun startRecording() {
        if (groqClient == null) return
        val keyboardHeight = DictationAccessibilityService.instance?.getKeyboardHeight() ?: 0

        val outputFile = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        currentAudioFile = outputFile

        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }

        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            state = State.ACTIVE

            mainHandler.postDelayed({
                if (state == State.ACTIVE) overlayManager.show(keyboardHeight)
            }, START_BUFFER_MS)
        } catch (e: Exception) {
            recorder.release()
            outputFile.delete()
            currentAudioFile = null
            state = State.IDLE
        }
    }

    private fun stopRecordingAndTranscribe() {
        state = State.STOPPING
        overlayManager.hide()

        mainHandler.postDelayed({
            val recorder = mediaRecorder
            mediaRecorder = null
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()

            val audioFile = currentAudioFile
            currentAudioFile = null
            state = State.IDLE

            if (audioFile != null) {
                groqClient?.transcribe(audioFile) { text ->
                    audioFile.delete()
                    if (!text.isNullOrEmpty()) {
                        DictationAccessibilityService.instance?.pasteText(text)
                    }
                }
            }
        }, STOP_BUFFER_MS)
    }

    private fun registerShakeDetector() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH_KEY -> refreshGroqClient()
            ACTION_TRIGGER -> onShake()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(shakeDetector)
        mainHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaRecorder = null
        currentAudioFile?.delete()
        currentAudioFile = null
        state = State.IDLE
        overlayManager.hide()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "dictation_service"
        val channel = NotificationChannel(
            channelId,
            "Dictation Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Running in background to detect shake gestures" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val triggerIntent = Intent(this, DictationService::class.java).apply {
            action = ACTION_TRIGGER
        }
        val triggerPending = PendingIntent.getService(
            this, 1, triggerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dictator")
            .setContentText("Shake to start dictating")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Trigger", triggerPending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PREFS = "dictator_prefs"
        const val KEY_API_KEY = "groq_api_key"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_REFRESH_KEY = "com.example.dictator.REFRESH_KEY"
        private const val ACTION_TRIGGER = "com.example.dictator.TRIGGER"
        private const val START_BUFFER_MS = 350L
        private const val STOP_BUFFER_MS = 800L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DictationService::class.java))
        }

        fun refreshApiKey(context: Context) {
            val intent = Intent(context, DictationService::class.java).apply {
                action = ACTION_REFRESH_KEY
            }
            context.startService(intent)
        }
    }
}
