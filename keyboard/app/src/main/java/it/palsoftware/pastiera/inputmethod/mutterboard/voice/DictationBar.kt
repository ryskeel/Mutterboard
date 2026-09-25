package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout

/**
 * Puts one of the keyboard's bars into its dictating look and takes it out
 * again: the suggestion row gives way to the wave and a cancel button, and the
 * whole bar sits in the overlay's mist, which thins away when the dictation
 * ends rather than switching off.
 *
 * The keyboard keeps two bars (full, and candidates-only), so there is one of
 * these per bar. Owned by Pastiera's StatusBarController, which only forwards.
 */
class DictationBar(
    private val context: Context,
    private val layout: () -> LinearLayout?,
    private val suggestions: () -> View?,
) {
    private var strip: DictationStripView? = null
    private var aura: AuraDrawable? = null
    private var backgroundBefore: Drawable? = null
    private var suggestionsVisibilityBefore: Int? = null

    /**
     * Keeps the suggestion row down for as long as the strip is up. Pastiera
     * re-shows it whenever suggestions refresh, which the text landing does;
     * with the strip still settling that stacked both rows and the bar jumped
     * taller for a moment. Whatever it asked for is remembered and restored.
     */
    private val holdSuggestionsDown = ViewTreeObserver.OnPreDrawListener {
        val view = suggestions()
        if (view != null && view.visibility != View.GONE) {
            suggestionsVisibilityBefore = view.visibility
            view.visibility = View.GONE
            false
        } else {
            true
        }
    }
    private var holding = false

    private fun trace(what: String) {
        val bar = layout()
        Log.d(TAG, "$what bar=${bar?.height} attached=${bar?.isAttachedToWindow} bg=${bar?.background?.javaClass?.simpleName} strip=${strip?.visibility} sugg=${suggestions()?.visibility}")
    }

    init {
        // Debug aid while the dictating look is being tuned: every height change.
        layout()?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) trace("height ${oldBottom - oldTop}->${bottom - top}")
        }
    }

    fun begin(onCancel: () -> Unit) {
        trace("begin")
        val bar = layout() ?: return
        val strip = strip ?: DictationStripView(context, dp(36f)).also {
            strip = it
            bar.addView(it, 0)
        }
        strip.onCancel = onCancel
        strip.setCaption(null)
        strip.setLevel(0f)
        strip.setPosture(DictationStripView.Posture.LISTENING)
        strip.visibility = View.VISIBLE
        val suggestionsView = suggestions()
        if (!holding) {
            suggestionsVisibilityBefore = suggestionsView?.visibility
            bar.viewTreeObserver.addOnPreDrawListener(holdSuggestionsDown)
            holding = true
        }
        suggestionsView?.visibility = View.GONE

        // A dictation started while the last one's mist is still thinning takes
        // that mist over rather than stacking a second one on the first.
        val existing = aura
        if (existing != null) {
            existing.appear()
        } else {
            backgroundBefore = bar.background
            val base = (backgroundBefore as? ColorDrawable)?.color ?: Color.BLACK
            aura = AuraDrawable(DictationLook.palette(context), base).also {
                bar.background = it
                it.start()
                it.appear()
            }
        }
    }

    fun setPhase(phase: ExternalDictation.Phase, caption: String?) {
        trace("phase $phase")
        val strip = strip ?: return
        when (phase) {
            ExternalDictation.Phase.RECORDING -> {
                strip.setPosture(DictationStripView.Posture.LISTENING)
                strip.setCaption(null)
            }
            ExternalDictation.Phase.TRANSCRIBING -> {
                strip.setPosture(DictationStripView.Posture.THINKING)
                strip.setCaption(caption ?: "Transcribing…")
            }
            ExternalDictation.Phase.NEEDS_ATTENTION -> {
                strip.setPosture(DictationStripView.Posture.MISSED)
                strip.setCaption(caption)
            }
        }
    }

    fun setLevel(level: Float) {
        strip?.setLevel(level)
        aura?.setLevel(level)
    }

    /**
     * The dictation is over. With text [committed], the mist poofs; thrown
     * away, it just thins. Either way the strip stays until the mist has gone,
     * so the suggestions come back into a bar that is already still.
     */
    fun end(committed: Boolean) {
        trace("end committed=$committed aura=${aura != null}")
        val strip = strip ?: return
        strip.onCancel = null
        strip.setCaption(null)
        strip.setPosture(DictationStripView.Posture.DONE)
        strip.setLevel(0f)
        val restore = restore@{
            // Only if no new dictation has started in the meantime.
            if (strip.onCancel != null) return@restore
            strip.visibility = View.GONE
            if (holding) {
                layout()?.viewTreeObserver?.removeOnPreDrawListener(holdSuggestionsDown)
                holding = false
            }
            suggestionsVisibilityBefore?.let { suggestions()?.visibility = it }
            suggestionsVisibilityBefore = null
        }
        val fading = aura
        if (fading == null) {
            restore()
            return
        }
        val gone = {
            trace("mist gone, current=${aura === fading}")
            if (aura === fading) {
                fading.stop()
                layout()?.background = backgroundBefore
                aura = null
                backgroundBefore = null
                restore()
            }
        }
        if (committed) fading.poof(gone) else fading.dissipate(gone)
    }

    private companion object {
        const val TAG = "MutterboardDictation"
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
}
