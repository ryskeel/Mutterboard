package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import it.palsoftware.pastiera.inputmethod.StatusBarController

/**
 * Puts one of the keyboard's bars into its dictating look and takes it out
 * again: the suggested words give way to the wave and a cancel button, and the
 * whole bar sits in the overlay's mist.
 *
 * Only the words are covered, not the row they sit in. In the one-row bar the
 * menu and the mic share that row, and the mic is the stop button while
 * recording, so hiding the row took stop away with it.
 *
 * The keyboard keeps two bars (full, and candidates-only), so there is one of
 * these per bar. Owned by Pastiera's StatusBarController, which only forwards.
 */
class DictationBar(
    private val context: Context,
    private val layout: () -> LinearLayout?,
    private val suggestions: () -> View?,
    private val words: () -> View?,
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
        keepMistVisible()
        fitStripToWords()
        val view = words()
        if (view != null && view.visibility != View.GONE) {
            suggestionsVisibilityBefore = view.visibility
            view.visibility = View.GONE
            false
        } else {
            true
        }
    }
    private var holding = false

    /**
     * The mist is the bar's background, so anything painting a flat colour on
     * top of it hides it. Stable had nothing doing that; nightly has two: the
     * themed rows fill themselves, and the Titan 2 Elite rounded corners paint
     * solid fills beside and below the bottom row. Both are cleared while the
     * mist is up and put back after. Pastiera re-applies its theme on every
     * status refresh, which the dictation causes, so this runs every frame.
     */
    private val clearedFills = mutableMapOf<View, Drawable>()
    private var chromeColorsBefore: List<Any>? = null

    private fun keepMistVisible() {
        val bar = layout() ?: return
        val aura = aura ?: return
        if (bar.background !== aura) {
            (bar.background as? ColorDrawable)?.let { backgroundBefore = it }
            bar.background = aura
        }
        clearFlatFills(bar)
        (bar as? StatusBarController.ImeChromeLayout)?.let { chrome ->
            if (chromeColorsBefore == null) {
                chromeColorsBefore = listOf(
                    chrome.regularCornerColors, chrome.compactCornerColors,
                    chrome.bottomFillColors, chrome.expandedCloseColor
                )
            }
            val clear = Color.TRANSPARENT to Color.TRANSPARENT
            if (chrome.bottomFillColors != clear || chrome.regularCornerColors != clear) {
                chrome.regularCornerColors = clear
                chrome.compactCornerColors = clear
                chrome.bottomFillColors = clear
                chrome.expandedCloseColor = Color.TRANSPARENT
                chrome.invalidate()
            }
        }
    }

    // Flat colour fills only: buttons and suggestion pills draw shapes, which
    // are what the mist is supposed to sit behind.
    private fun clearFlatFills(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child === strip) continue
            val bg = child.background
            if (bg is ColorDrawable && bg.alpha > 0) {
                clearedFills.putIfAbsent(child, bg)
                child.background = null
            }
            if (child is ViewGroup) clearFlatFills(child)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreFills() {
        clearedFills.forEach { (view, bg) -> if (view.background == null) view.background = bg }
        clearedFills.clear()
        val before = chromeColorsBefore ?: return
        (layout() as? StatusBarController.ImeChromeLayout)?.let { chrome ->
            chrome.regularCornerColors = before[0] as Pair<Int, Int>
            chrome.compactCornerColors = before[1] as Pair<Int, Int>
            chrome.bottomFillColors = before[2] as Pair<Int, Int>
            chrome.expandedCloseColor = before[3] as Int
            chrome.invalidate()
        }
        chromeColorsBefore = null
    }

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
        val row = suggestions() as? FrameLayout ?: return
        val strip = strip ?: DictationStripView(context, dp(36f)).also {
            strip = it
            row.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Drawn over the words, under the row's buttons.
        row.removeView(strip)
        row.addView(strip, 1, strip.layoutParams)
        fitStripToWords()
        strip.onCancel = onCancel
        strip.setCaption(null)
        strip.setLevel(0f)
        strip.setPosture(DictationStripView.Posture.LISTENING)
        strip.visibility = View.VISIBLE
        val suggestionsView = words()
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
        keepMistVisible()
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
     * The dictation is over: the bar goes straight back to how it was. There
     * was a poof here (the mist swelling away as the text landed), and Ry asked
     * for it gone - with Messages resizing its box as the text arrived, the two
     * together read as the keyboard jumping.
     */
    fun end() {
        val strip = strip ?: return
        strip.onCancel = null
        strip.visibility = View.GONE
        aura?.let {
            it.stop()
            layout()?.background = backgroundBefore
        }
        aura = null
        backgroundBefore = null
        restoreFills()
        if (holding) {
            layout()?.viewTreeObserver?.removeOnPreDrawListener(holdSuggestionsDown)
            holding = false
        }
        suggestionsVisibilityBefore?.let { words()?.visibility = it }
        suggestionsVisibilityBefore = null
    }

    // The words' container is padded clear of the row's side buttons; the
    // strip takes the same margins so it sits exactly where the words were.
    private fun fitStripToWords() {
        val strip = strip ?: return
        val words = words() ?: return
        val params = strip.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.leftMargin != words.paddingLeft || params.rightMargin != words.paddingRight) {
            params.leftMargin = words.paddingLeft
            params.rightMargin = words.paddingRight
            strip.layoutParams = params
        }
    }

    private companion object {
        const val TAG = "MutterboardDictation"
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
}
