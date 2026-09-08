package com.example.mutterboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.sqrt

/**
 * Everything a dictation takes, from tapping Start to handing over finished text:
 * the engine/refiner lifecycle, the recording, the transcribe-then-refine pipeline
 * and the UI that reports on it.
 *
 * Deliberately knows nothing about *where* the text ends up or *how* the UI goes
 * away. Those are the two things that differ between the keyboard — which commits
 * through an InputConnection and switches back to the previous IME — and the
 * overlay, which has no InputConnection and simply hides its own window. Both are
 * supplied by the [Host].
 *
 * Extracted from [MutterboardInputMethodService] with no behavior change; the
 * keyboard remains the only host until the overlay lands.
 */
class DictationSession(
    private val context: Context,
    private val host: Host,
) {

    /**
     * The two things a dictation can't do for itself. Implemented by the keyboard
     * today and by the overlay later.
     */
    interface Host {
        /**
         * Deliver finished text to wherever text goes for this host. Already
         * trailing-space-normalized; the host should not reshape it.
         */
        fun commit(text: String)

        /** The dictation is over — get this UI out of the user's way. */
        fun dismiss()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING, ERROR, NO_PERMISSION, NO_API_KEY, NO_MODEL, NO_NETWORK, NO_SPEECH }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder: WavRecorder = WavRecorder(context.cacheDir)
    private var transcriber: Transcriber? = null
    private var refiner: GroqRefiner? = null
    private var casualRefiner: CasualRefiner? = null
    private var engine: Engine = Engine.CLOUD
    private var refineMode: RefineMode = RefineMode.DEFAULT
    private var cloudKey: String = ""
    private var customWords: List<String> = emptyList()
    private var modelManager: ParakeetModelManager? = null

    /**
     * True when the user chose Default (cloud) but the device has no internet, so
     * we're dictating with the on-device engine instead. Distinguishes "cloud user
     * gone offline" from a deliberate Offline selection: it drives the "Offline
     * mode" label while recording and the NO_NETWORK (rather than NO_MODEL) state
     * when the local model isn't downloaded.
     */
    private var offlineFallback = false

    private var rootView: View? = null
    private var statusText: TextView? = null
    private var micButton: MaterialButton? = null
    private var cancelButton: MaterialButton? = null
    private var settingsButton: ImageButton? = null
    private var modeToggle: ModeToggleView? = null
    private var waveform: WaveformView? = null
    private var progress: LinearProgressIndicator? = null

    private var state: State = State.IDLE
    private var waveformAnimator: Runnable? = null

    init {
        refreshTranscriber()
    }

    /**
     * Rebuilds [transcriber] from the current engine preference, but only when
     * something relevant changed — so the heavy local recognizer isn't torn down
     * and reloaded every time the keyboard reappears.
     */
    private fun refreshTranscriber() {
        val prefs = context.getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
        val newEngine = Engine.fromPref(prefs.getString(MutterboardInputMethodService.KEY_ENGINE, Engine.CLOUD.prefValue))
        // Custom vocabulary applies to both engines; re-read it every refresh so
        // edits made in the app take effect the next time the keyboard appears.
        customWords = MutterboardInputMethodService.parseCustomWords(
            prefs.getString(MutterboardInputMethodService.KEY_CUSTOM_WORDS, null)
        )
        // Which refine pass runs. Re-read every refresh so a flip made from the
        // keyboard's own toggle (which writes the pref directly) survives a
        // keyboard teardown, and so the two never drift apart.
        refineMode = RefineMode.fromPref(prefs.getString(MutterboardInputMethodService.KEY_REFINE_MODE, RefineMode.DEFAULT.prefValue))
        // Default (cloud) users shouldn't be dead in the water in airplane mode
        // or a dead zone: with no internet, run the on-device engine instead when
        // its model is downloaded. Re-checked every refresh, so connectivity
        // coming back flips us to the cloud path the next time the keyboard shows.
        offlineFallback = newEngine == Engine.CLOUD && !isOnline()
        if (newEngine == Engine.LOCAL || offlineFallback) {
            // The cloud refiners can't run without internet and never apply to
            // on-device output; the cloud refresh below rebuilds them when needed.
            refiner?.close()
            refiner = null
            casualRefiner?.close()
            casualRefiner = null
            val mm = modelManager ?: ParakeetModelManager(context).also { modelManager = it }
            if (transcriber !is LocalParakeetTranscriber) {
                transcriber?.close()
                transcriber = if (mm.isReady()) LocalParakeetTranscriber(mm.modelDir) else null
            }
        } else {
            val key = prefs.getString(MutterboardInputMethodService.KEY_API_KEY, "") ?: ""
            val keyChanged = key != cloudKey
            if (transcriber !is GroqWhisperClient || keyChanged) {
                transcriber?.close()
                transcriber = if (key.isNotEmpty()) GroqWhisperClient(key) else null
            }
            // Bias Whisper toward the user's vocabulary via its prompt field.
            // Set every refresh (not just on rebuild) so word-list edits apply
            // even when the client itself was kept.
            (transcriber as? GroqWhisperClient)?.vocabularyPrompt =
                customWords.joinToString(", ").ifBlank { null }
            // The refiner is baked into the Default (cloud) experience — it
            // always runs when a key is present. Rebuild only when there's no
            // refiner yet or the key changed, so it isn't reallocated every
            // time the keyboard reappears.
            // Both refiners are built, warmed and torn down together rather than
            // on demand: the mode toggle is tappable mid-recording, so whichever one
            // the user lands on at Stop must already have a warm connection.
            if (key.isEmpty()) {
                refiner?.close()
                refiner = null
                casualRefiner?.close()
                casualRefiner = null
            } else if (refiner == null || keyChanged) {
                refiner?.close()
                refiner = GroqRefiner(key)
                casualRefiner?.close()
                casualRefiner = CasualRefiner(key)
            }
            cloudKey = key
        }
        engine = newEngine
    }

    /**
     * Inflates the dictation UI and wires it up. The returned view's context is
     * the themed one, so a host that needs to style its own window (the keyboard
     * does, for the navigation bar) can resolve attributes from it.
     */
    fun createView(): View {
        val themedContext = DynamicColors.wrapContextIfAvailable(context, R.style.Theme_Mutterboard)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.keyboard_view, null)
        statusText = view.findViewById(R.id.status_text)
        micButton = view.findViewById(R.id.mic_button)
        cancelButton = view.findViewById(R.id.cancel_button)
        settingsButton = view.findViewById(R.id.settings_button)
        modeToggle = view.findViewById(R.id.mode_toggle)
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
        settingsButton?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onSettingsTapped()
        }
        modeToggle?.onModeChanged = { casual ->
            modeToggle?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onModeChanged(casual)
        }
        renderMode()

        rootView = view
        return view
    }

    /**
     * The dictation UI just became visible. Re-reads settings and, unless a
     * dictation is already in flight, starts listening immediately.
     */
    fun onShown() {
        Log.d(TAG, "onShown state=$state")
        refreshTranscriber()
        if (state == State.IDLE || state == State.ERROR || state == State.NO_SPEECH) {
            tryStartRecording()
        } else {
            renderState()
        }
    }

    /** The dictation UI went away without finishing; never leave the mic hot. */
    fun onHidden() {
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
            state = noTranscriberState()
            renderState()
            return
        }
        // Cloud uploads go out as Ogg/Opus; encode it live during recording so
        // Stop pays no compression delay. Parakeet consumes the WAV, so the
        // offline path skips the parallel encode.
        if (recorder.start(streamOpus = transcriber is GroqWhisperClient)) {
            state = State.RECORDING
            renderState()
            startWaveform()
            // Open the network connection now, while the user is still speaking,
            // so the upload at Stop rides an already-warm connection.
            transcriber?.warmUp()
            warmRefiners()
            startRewarmLoop()
        } else {
            state = State.ERROR
            renderState()
        }
    }

    private fun onMicTapped() {
        when (state) {
            State.RECORDING -> stopAndTranscribe()
            State.IDLE, State.ERROR, State.NO_SPEECH -> tryStartRecording()
            // Connectivity may have come back since the keyboard appeared, so
            // Retry re-evaluates the engine choice before recording.
            State.NO_NETWORK -> {
                refreshTranscriber()
                tryStartRecording()
            }
            State.NO_PERMISSION -> openSetupActivity()
            State.NO_API_KEY -> openSetupActivity()
            State.NO_MODEL -> openSetupActivity()
            State.TRANSCRIBING -> Unit
        }
    }

    /**
     * Why recording can't start when no transcriber exists. Order matters: a
     * cloud user with no internet gets NO_NETWORK (downloading a model or setting
     * a key can't help them right now), an Offline user without the model gets
     * NO_MODEL, and a cloud user online but keyless gets NO_API_KEY.
     */
    private fun noTranscriberState(): State = when {
        offlineFallback -> State.NO_NETWORK
        engine == Engine.LOCAL -> State.NO_MODEL
        else -> State.NO_API_KEY
    }

    /**
     * Connectivity check used to decide the cloud→on-device fallback. Requires a
     * VALIDATED network so captive portals and dead Wi-Fi count as offline. Errs
     * toward "online" when the answer is unknowable, keeping the cloud path (and
     * its clearer failure states) the default.
     */
    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun onCancelTapped() {
        if (state == State.RECORDING) {
            recorder.cancel()
            stopWaveform()
        }
        state = State.IDLE
        host.dismiss()
    }

    /**
     * Opens the app's settings and gets out of the way. Leaving for settings must
     * never leave the mic hot, so we cancel any recording and hand control back to
     * the user's previous keyboard — returning to a text field then takes a
     * deliberate switch back to this keyboard (via the globe key) rather than
     * silently reappearing and listening again.
     */
    private fun onSettingsTapped() {
        if (state == State.RECORDING) {
            recorder.cancel()
            stopWaveform()
        }
        state = State.IDLE
        openSetupActivity()
        host.dismiss()
    }

    /**
     * While recording, re-warm the pooled connections every [REWARM_INTERVAL_MS].
     * The warm-up at record start only helps if the connection survives until
     * Stop, and idle server-side timeouts can close it during a long dictation;
     * refreshing it keeps the upload handshake-free no matter how long the user
     * talks. The state guard stops the loop when recording ends, however it ends.
     */
    private fun startRewarmLoop() {
        val runnable = object : Runnable {
            override fun run() {
                if (state != State.RECORDING) return
                transcriber?.warmUp()
                warmRefiners()
                mainHandler.postDelayed(this, REWARM_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(runnable, REWARM_INTERVAL_MS)
    }

    private fun stopAndTranscribe() {
        stopWaveform()
        state = State.TRANSCRIBING
        renderState()
        // The refiner won't be called until Whisper returns, a second or more
        // from now; its warmed connection from record start may have idled out.
        // Re-warm it here so its TLS handshake overlaps the Whisper round trip
        // instead of delaying the refine call.
        warmRefiners()

        mainHandler.postDelayed({
            val rec = recorder.stopAndFinalize()
            if (rec == null) {
                state = State.ERROR
                renderState()
                return@postDelayed
            }
            val client = transcriber
            if (client == null) {
                rec.wav.delete()
                rec.opus?.delete()
                state = noTranscriberState()
                renderState()
                return@postDelayed
            }
            // The cloud client gets the stream-encoded Opus when it exists;
            // Parakeet (and any streaming failure) gets the WAV.
            val audio = if (client is GroqWhisperClient && rec.opus != null) rec.opus else rec.wav
            val sent = SystemClock.elapsedRealtime()
            client.transcribe(audio) { text ->
                Log.d(TAG, "transcribe took ${SystemClock.elapsedRealtime() - sent}ms")
                rec.wav.delete()
                rec.opus?.delete()
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
        // On-device transcription can't be biased toward the user's vocabulary,
        // so fuzzy-correct the output against the custom word list here. The cloud
        // path already biases Whisper via its prompt, so it's left untouched.
        val ranOnDevice = engine == Engine.LOCAL || offlineFallback
        val corrected = if (ranOnDevice && customWords.isNotEmpty()) {
            TextCorrector.apply(text, customWords)
        } else {
            text
        }
        // If a cloud refiner is on, run the cleanup pass before committing. The
        // mode toggle picks which one; the two are built and torn down together, so
        // in practice both are present or neither is. We stay in TRANSCRIBING
        // (progress shown) during the extra round-trip, and fall back to the raw
        // text if it fails so the message is never lost.
        val sent = SystemClock.elapsedRealtime()
        val onRefined: (String?) -> Unit = { refined ->
            Log.d(TAG, "refine took ${SystemClock.elapsedRealtime() - sent}ms")
            mainHandler.post { commitAndFinish(refined ?: corrected) }
        }
        val default = refiner
        val casual = casualRefiner
        when {
            refineMode == RefineMode.CASUAL && casual != null -> casual.refine(corrected, onRefined)
            default != null -> default.refine(corrected, onRefined)
            else -> commitAndFinish(corrected)
        }
    }

    private fun commitAndFinish(text: String) {
        // Append a trailing space so you can keep typing — or dictate again —
        // without manually hitting the space bar first. trimEnd() guards against
        // a double space if the engine already returned trailing whitespace.
        host.commit(text.trimEnd() + " ")
        state = State.IDLE
        host.dismiss()
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
        renderMode()
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
                // A cloud user silently switched to the on-device engine should
                // know: no polish pass will run, and accuracy may differ.
                if (offlineFallback) {
                    status.text = "Offline mode"
                    status.visibility = View.VISIBLE
                } else {
                    status.visibility = View.GONE
                }
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
            State.NO_NETWORK -> {
                status.text = "No internet. Offline model not downloaded"
                status.visibility = View.VISIBLE
                mic.text = "Retry"
                mic.contentDescription = "Retry after reconnecting"
            }
            State.NO_SPEECH -> {
                status.text = "Didn't catch any audio"
                status.visibility = View.VISIBLE
                mic.text = "Retry"
                mic.contentDescription = "Retry recording"
            }
        }
    }

    /**
     * Warm both refine paths, not just the active one. The toggle can be tapped at
     * any point up to Stop, so the cost of a second HEAD request buys the
     * guarantee that the mode the user actually lands on is never cold.
     */
    private fun warmRefiners() {
        refiner?.warmUp()
        casualRefiner?.warmUp()
    }

    /**
     * Persist the refine mode the user just selected. The toggle has already
     * animated itself, so this only records the choice. Deliberately usable
     * mid-recording: the mode is read once the transcript comes back, so a tap
     * made while still speaking applies to the message being dictated.
     */
    private fun onModeChanged(casual: Boolean) {
        refineMode = if (casual) RefineMode.CASUAL else RefineMode.DEFAULT
        context.getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(MutterboardInputMethodService.KEY_REFINE_MODE, refineMode.prefValue)
            .apply()
    }

    /**
     * Paint the mode toggle. Hidden entirely when no refiner exists — on the
     * Offline engine, during the offline fallback, and before a key is set,
     * nothing polishes the transcript at all, so a toggle claiming a mode would
     * be lying about what the keyboard is going to do.
     *
     * Never animates: this only ever runs on a state render, where the thumb is
     * already where it belongs, and sliding it here would signal a change the
     * user did not make. The animation belongs to the tap alone.
     */
    private fun renderMode() {
        val toggle = modeToggle ?: return
        if (refiner == null && casualRefiner == null) {
            toggle.visibility = View.GONE
            return
        }
        toggle.visibility = View.VISIBLE
        toggle.setMode(refineMode == RefineMode.CASUAL, animate = false)
    }

    private fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openSetupActivity() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun destroy() {
        recorder.cancel()
        transcriber?.close()
        refiner?.close()
        casualRefiner?.close()
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "DictationSession"
        // Tail kept recording after Stop so the last word isn't clipped. Trimmed
        // from 800ms to cut latency; the appended trailing silence in the WAV
        // still gives the model a moment of run-off.
        private const val STOP_BUFFER_MS = 400L
        // How often to refresh the warmed connections during a recording. Kept
        // under typical server/NAT idle timeouts (about a minute) so the pooled
        // connection never goes cold before Stop.
        private const val REWARM_INTERVAL_MS = 45_000L
        private const val WAVEFORM_INTERVAL_MS = 50L
        // Gain applied before the sqrt curve; ~0.25 normalized peak saturates
        // the bars, so normal speaking volume drives them near full height.
        private const val WAVEFORM_GAIN = 4f
    }
}
