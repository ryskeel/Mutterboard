package com.example.mutterboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat

/**
 * The keyboard host for a [DictationSession].
 *
 * All of the dictation itself — engines, refiners, recording, the UI — lives in
 * the session. What's left here is the part only a keyboard can do: commit
 * through the InputConnection, and hand control back to the user's previous IME
 * when the dictation ends.
 */
class MutterboardInputMethodService : InputMethodService(), DictationSession.Host {

    private lateinit var session: DictationSession

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "IME onCreate")
        session = DictationSession(this, this)
    }

    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView")
        val view = session.createView()
        applyNavigationBarStyling(view.context)
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
        Log.d(TAG, "onStartInputView restarting=$restarting")
        session.onShown()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        session.onHidden()
    }

    override fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun dismiss() {
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

    override fun onDestroy() {
        session.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MutterboardIME"
        const val PREFS = "mutterboard_prefs"
        const val KEY_API_KEY = "groq_api_key"
        const val KEY_ENGINE = "engine"
        // Which refine pass runs on the cloud path. Written from the keyboard's
        // own mode toggle, not from the setup screen.
        const val KEY_REFINE_MODE = "refine_mode"
        // Custom vocabulary, stored as a newline-separated list of words/phrases.
        const val KEY_CUSTOM_WORDS = "custom_words"

        /** Parses the stored custom-words blob into a clean, de-duplicated list. */
        fun parseCustomWords(raw: String?): List<String> =
            raw.orEmpty()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
