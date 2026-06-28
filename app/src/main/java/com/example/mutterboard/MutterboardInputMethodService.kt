package com.example.mutterboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.sqrt

class MutterboardInputMethodService : InputMethodService() {

    private enum class State { IDLE, RECORDING, TRANSCRIBING, ERROR, NO_PERMISSION, NO_API_KEY, NO_MODEL, NO_SPEECH }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var recorder: WavRecorder
    private var transcriber: Transcriber? = null
    private var engine: Engine = Engine.CLOUD
    private var cloudKey: String = ""
    private var modelManager: ParakeetModelManager? = null

    private var keyboardView: View? = null
    private var statusText: TextView? = null
    private var micButton: MaterialButton? = null
    private var cancelButton: MaterialButton? = null
    private var waveform: WaveformView? = null
    private var progress: LinearProgressIndicator? = null

    private var state: State = State.IDLE
    private var waveformAnimator: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "IME onCreate")
        recorder = WavRecorder(cacheDir)
        refreshTranscriber()
    }

    /**
     * Rebuilds [transcriber] from the current engine preference, but only when
     * something relevant changed — so the heavy local recognizer isn't torn down
     * and reloaded every time the keyboard reappears.
     */
    private fun refreshTranscriber() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newEngine = Engine.fromPref(prefs.getString(KEY_ENGINE, Engine.CLOUD.prefValue))
        when (newEngine) {
            Engine.CLOUD -> {
                val key = prefs.getString(KEY_API_KEY, "") ?: ""
                if (engine != Engine.CLOUD || transcriber !is GroqWhisperClient || key != cloudKey) {
                    transcriber?.close()
                    cloudKey = key
                    transcriber = if (key.isNotEmpty()) GroqWhisperClient(key) else null
                }
            }
            Engine.LOCAL -> {
                val mm = modelManager ?: ParakeetModelManager(this).also { modelManager = it }
                if (engine != Engine.LOCAL || transcriber !is LocalParakeetTranscriber) {
                    transcriber?.close()
                    transcriber = if (mm.isReady()) LocalParakeetTranscriber(mm.modelDir) else null
                }
            }
        }
        engine = newEngine
    }

    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView")
        val themedContext = DynamicColors.wrapContextIfAvailable(this, R.style.Theme_Mutterboard)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.keyboard_view, null)
        statusText = view.findViewById(R.id.status_text)
        micButton = view.findViewById(R.id.mic_button)
        cancelButton = view.findViewById(R.id.cancel_button)
        waveform = view.findViewById(R.id.waveform)
        progress = view.findViewById(R.id.progress)

        micButton?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onMicTapped()
        }
        cancelButton?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onCancelTapped()
        }

        applyNavigationBarStyling(themedContext)

        keyboardView = view
        return view
    }

    private fun applyNavigationBarStyling(themedContext: Context) {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.isNavigationBarContrastEnforced = false
        }
        w.navigationBarColor = resolveColor(themedContext, com.google.android.material.R.attr.colorSurfaceContainer)
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = true
    }

    private fun resolveColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting state=$state")
        refreshTranscriber()
        if (state == State.IDLE || state == State.ERROR || state == State.NO_SPEECH) {
            tryStartRecording()
        } else {
            renderState()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (state == State.RECORDING) {
            recorder.cancel()
            stopWaveform()
            state = State.IDLE
        }
    }

    private fun tryStartRecording() {
        if (!hasRecordAudioPermission()) {
            state = State.NO_PERMISSION
            renderState()
            return
        }
        if (transcriber == null) {
            state = if (engine == Engine.LOCAL) State.NO_MODEL else State.NO_API_KEY
            renderState()
            return
        }
        if (recorder.start()) {
            state = State.RECORDING
            renderState()
            startWaveform()
            // Open the network connection now, while the user is still speaking,
            // so the upload at Stop rides an already-warm connection.
            transcriber?.warmUp()
        } else {
            state = State.ERROR
            renderState()
        }
    }

    private fun onMicTapped() {
        when (state) {
            State.RECORDING -> stopAndTranscribe()
            State.IDLE, State.ERROR, State.NO_SPEECH -> tryStartRecording()
            State.NO_PERMISSION -> openSetupActivity()
            State.NO_API_KEY -> openSetupActivity()
            State.NO_MODEL -> openSetupActivity()
            State.TRANSCRIBING -> Unit
        }
    }

    private fun onCancelTapped() {
        if (state == State.RECORDING) {
            recorder.cancel()
            stopWaveform()
        }
        state = State.IDLE
        switchToPrevious()
    }

    private fun stopAndTranscribe() {
        stopWaveform()
        state = State.TRANSCRIBING
        renderState()

        mainHandler.postDelayed({
            val wav = recorder.stopAndWriteWav()
            if (wav == null) {
                state = State.ERROR
                renderState()
                return@postDelayed
            }
            val client = transcriber
            if (client == null) {
                wav.delete()
                state = if (engine == Engine.LOCAL) State.NO_MODEL else State.NO_API_KEY
                renderState()
                return@postDelayed
            }
            client.transcribe(wav) { text ->
                wav.delete()
                mainHandler.post { onTranscriptionResult(text) }
            }
        }, STOP_BUFFER_MS)
    }

    private fun onTranscriptionResult(text: String?) {
        // A null result means the engine genuinely failed (network/API/exception).
        // A blank-but-non-null result means it ran fine and simply heard no speech —
        // let the engine be the judge of silence rather than gating on amplitude.
        if (text == null) {
            state = State.ERROR
            renderState()
            return
        }
        if (text.isBlank()) {
            state = State.NO_SPEECH
            renderState()
            return
        }
        // Append a trailing space so you can keep typing — or dictate again —
        // without manually hitting the space bar first. trimEnd() guards against
        // a double space if the engine already returned trailing whitespace.
        currentInputConnection?.commitText(text.trimEnd() + " ", 1)
        state = State.IDLE
        switchToPrevious()
    }

    private fun switchToPrevious() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val switched = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                val token = window?.window?.attributes?.token
                token != null && imm.switchToLastInputMethod(token)
            }
        } catch (_: Throwable) {
            false
        }
        if (!switched) imm.showInputMethodPicker()
    }

    private fun startWaveform() {
        val view = waveform ?: return
        val runnable = object : Runnable {
            override fun run() {
                if (state != State.RECORDING) return
                // Raw peak is linear, so normal speech barely moves the bars.
                // Apply gain plus a sqrt (compressive) curve so quiet and normal
                // speech register strongly while loud speech still has headroom.
                val normalized = recorder.currentPeak() / 32767f
                val level = sqrt((normalized * WAVEFORM_GAIN).coerceIn(0f, 1f))
                view.setLevel(level)
                mainHandler.postDelayed(this, WAVEFORM_INTERVAL_MS)
            }
        }
        waveformAnimator = runnable
        mainHandler.post(runnable)
    }

    private fun stopWaveform() {
        waveformAnimator?.let { mainHandler.removeCallbacks(it) }
        waveformAnimator = null
        waveform?.setLevel(0f)
    }

    private fun renderState() {
        val status = statusText ?: return
        val mic = micButton ?: return
        // While transcribing, swap the listening waveform for an indeterminate
        // progress bar so it's clear we're working, not still recording.
        val transcribing = state == State.TRANSCRIBING
        waveform?.visibility = if (transcribing) View.GONE else View.VISIBLE
        progress?.visibility = if (transcribing) View.VISIBLE else View.GONE
        when (state) {
            State.IDLE -> {
                status.text = "Tap Start"
                status.visibility = View.VISIBLE
                mic.text = "Start"
                mic.contentDescription = "Start recording"
            }
            State.RECORDING -> {
                status.visibility = View.GONE
                mic.text = "Stop"
                mic.contentDescription = "Stop recording"
            }
            State.TRANSCRIBING -> {
                status.text = "Transcribing…"
                status.visibility = View.VISIBLE
                mic.text = "Stop"
                mic.contentDescription = "Transcribing"
            }
            State.ERROR -> {
                status.text = "Something went wrong"
                status.visibility = View.VISIBLE
                mic.text = "Retry"
                mic.contentDescription = "Retry"
            }
            State.NO_PERMISSION -> {
                status.text = "Mic permission needed"
                status.visibility = View.VISIBLE
                mic.text = "Open app"
                mic.contentDescription = "Open app to grant permission"
            }
            State.NO_API_KEY -> {
                status.text = "Set Groq API key"
                status.visibility = View.VISIBLE
                mic.text = "Open app"
                mic.contentDescription = "Open app to set API key"
            }
            State.NO_MODEL -> {
                status.text = "Download model in app"
                status.visibility = View.VISIBLE
                mic.text = "Open app"
                mic.contentDescription = "Open app to download the on-device model"
            }
            State.NO_SPEECH -> {
                status.text = "Didn't catch any audio"
                status.visibility = View.VISIBLE
                mic.text = "Retry"
                mic.contentDescription = "Retry recording"
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openSetupActivity() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onDestroy() {
        recorder.cancel()
        transcriber?.close()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MutterboardIME"
        const val PREFS = "mutterboard_prefs"
        const val KEY_API_KEY = "groq_api_key"
        const val KEY_ENGINE = "engine"
        // Tail kept recording after Stop so the last word isn't clipped. Trimmed
        // from 800ms to cut latency; the appended trailing silence in the WAV
        // still gives the model a moment of run-off.
        private const val STOP_BUFFER_MS = 400L
        private const val WAVEFORM_INTERVAL_MS = 50L
        // Gain applied before the sqrt curve; ~0.25 normalized peak saturates
        // the bars, so normal speaking volume drives them near full height.
        private const val WAVEFORM_GAIN = 4f
    }
}
