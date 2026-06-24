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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DictationService : Service() {

    private enum class State { IDLE, ACTIVE, STOPPING }

    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var overlayManager: OverlayManager
    private var groqClient: GroqWhisperClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var pcmFile: File? = null
    @Volatile private var isCapturing = false
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

    @SuppressWarnings("MissingPermission")
    private fun startRecording() {
        if (groqClient == null) return
        val keyboardHeight = DictationAccessibilityService.instance?.getKeyboardHeight() ?: 0

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) return
        val bufferSize = minBuffer * 2

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        val outputFile = File(cacheDir, "rec_${System.currentTimeMillis()}.pcm")
        pcmFile = outputFile
        audioRecord = record

        record.startRecording()
        isCapturing = true
        state = State.ACTIVE

        captureThread = Thread {
            val buffer = ByteArray(bufferSize)
            FileOutputStream(outputFile).use { out ->
                while (isCapturing) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) out.write(buffer, 0, read)
                }
            }
        }.apply { start() }

        mainHandler.postDelayed({
            if (state == State.ACTIVE) overlayManager.show(keyboardHeight)
        }, START_BUFFER_MS)
    }

    private fun stopRecordingAndTranscribe() {
        if (state != State.ACTIVE) return
        state = State.STOPPING
        overlayManager.hide()

        mainHandler.postDelayed({
            isCapturing = false
            captureThread?.join(1000)
            captureThread = null

            val record = audioRecord
            audioRecord = null
            try { record?.stop() } catch (_: Exception) {}
            record?.release()

            val pcm = pcmFile
            pcmFile = null
            state = State.IDLE

            if (pcm == null || !pcm.exists() || pcm.length() == 0L) {
                pcm?.delete()
                return@postDelayed
            }

            val wav = pcmToWav(pcm)
            pcm.delete()

            groqClient?.transcribe(wav) { text ->
                wav.delete()
                if (!text.isNullOrEmpty()) {
                    DictationAccessibilityService.instance?.pasteText(text)
                }
            }
        }, STOP_BUFFER_MS)
    }

    private fun pcmToWav(pcm: File): File {
        val wav = File(cacheDir, pcm.nameWithoutExtension + ".wav")
        val pcmSize = pcm.length().toInt()
        val header = wavHeader(pcmSize, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
        FileOutputStream(wav).use { out ->
            out.write(header)
            pcm.inputStream().use { it.copyTo(out) }
        }
        return wav
    }

    private fun wavHeader(pcmSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcmSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(pcmSize)
        }.array()
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
        isCapturing = false
        captureThread?.join(500)
        captureThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        pcmFile?.delete()
        pcmFile = null
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
        private const val START_BUFFER_MS = 250L
        private const val STOP_BUFFER_MS = 400L
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

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
