package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
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

    fun begin(onCancel: () -> Unit) {
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
        if (suggestionsVisibilityBefore == null) suggestionsVisibilityBefore = suggestionsView?.visibility
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
        val strip = strip ?: return
        when (phase) {
            ExternalDictation.Phase.RECORDING -> {
                strip.setPosture(DictationStripView.Posture.LISTENING)
                strip.setCaption(null)
            }
            ExternalDictation.Phase.TRANSCRIBING -> {
                strip.setPosture(DictationStripView.Posture.THINKING)
                strip.setCaption(null)
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

    /** The wave settles flat, the mist thins away, and the suggestions return. */
    fun end() {
        val strip = strip ?: return
        strip.onCancel = null
        strip.setCaption(null)
        strip.setPosture(DictationStripView.Posture.DONE)
        strip.setLevel(0f)
        strip.postDelayed({
            // Only if no new dictation has started in the meantime.
            if (strip.onCancel != null) return@postDelayed
            strip.visibility = View.GONE
            suggestionsVisibilityBefore?.let { suggestions()?.visibility = it }
            suggestionsVisibilityBefore = null
        }, SETTLE_MS)
        val fading = aura ?: return
        fading.dissipate {
            if (aura !== fading) return@dissipate
            fading.stop()
            layout()?.background = backgroundBefore
            aura = null
            backgroundBefore = null
        }
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private companion object {
        /** Long enough to see the wave go flat, which is the "got it". */
        const val SETTLE_MS = 450L
    }
}
