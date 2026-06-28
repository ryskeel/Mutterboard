package com.example.mutterboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRecorder(private val cacheDir: File) {

    @Volatile private var capturing = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var pcmFile: File? = null
    @Volatile private var peakAmplitude: Int = 0

    fun currentPeak(): Int = peakAmplitude

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (capturing) return false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuffer")
            return false
        }
        val bufferSize = minBuffer * 2

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord constructor failed", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${record.state})")
            record.release()
            return false
        }

        val outputFile = File(cacheDir, "rec_${System.currentTimeMillis()}.pcm")
        pcmFile = outputFile
        audioRecord = record
        peakAmplitude = 0

        record.startRecording()
        capturing = true

        captureThread = Thread {
            val buffer = ByteArray(bufferSize)
            FileOutputStream(outputFile).use { out ->
                while (capturing) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        var localPeak = 0
                        var i = 0
                        while (i < read - 1) {
                            val lo = buffer[i].toInt() and 0xFF
                            val hi = buffer[i + 1].toInt()
                            val sample = (hi shl 8) or lo
                            val signed = if (sample > 32767) sample - 65536 else sample
                            val abs = if (signed < 0) -signed else signed
                            if (abs > localPeak) localPeak = abs
                            i += 2
                        }
                        peakAmplitude = localPeak
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read error: $read")
                        break
                    }
                }
            }
        }.apply { start() }
        return true
    }

    fun stopAndWriteWav(): File? {
        if (!capturing && audioRecord == null) return null
        capturing = false
        captureThread?.join(1000)
        captureThread = null

        val record = audioRecord
        audioRecord = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release()

        val pcm = pcmFile
        pcmFile = null
        peakAmplitude = 0

        if (pcm == null || !pcm.exists() || pcm.length() == 0L) {
            pcm?.delete()
            return null
        }

        val wav = pcmToWav(pcm)
        pcm.delete()
        return wav
    }

    fun cancel() {
        capturing = false
        captureThread?.join(500)
        captureThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        pcmFile?.delete()
        pcmFile = null
        peakAmplitude = 0
    }

    private fun pcmToWav(pcm: File): File {
        val wav = File(cacheDir, pcm.nameWithoutExtension + ".wav")
        val pcmSize = pcm.length().toInt()
        val silenceBytes = SAMPLE_RATE * TRAILING_SILENCE_MS / 1000 * 2
        val totalDataSize = pcmSize + silenceBytes
        val header = wavHeader(totalDataSize, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
        FileOutputStream(wav).use { out ->
            out.write(header)
            pcm.inputStream().use { it.copyTo(out) }
            out.write(ByteArray(silenceBytes))
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

    companion object {
        private const val TAG = "Mutterboard"
        private const val TRAILING_SILENCE_MS = 500
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
