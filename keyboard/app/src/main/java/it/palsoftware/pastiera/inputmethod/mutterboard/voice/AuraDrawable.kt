package it.palsoftware.pastiera.inputmethod.mutterboard.voice

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.exp
import kotlin.math.sin

/**
 * The overlay's mist (DictationAura in the app), ported to a Drawable so it can
 * be the keyboard bar's background while dictating: the keys and buttons sit in
 * it rather than under it, and it takes no touches.
 *
 * Same blooms, same drift, same swell with the voice. Two differences: the
 * radius comes off the bar's height as well as its width, because a bar is far
 * flatter than the overlay's band and width alone washes it out to one colour;
 * and it ends, either with a [poof] when the text lands or by [dissipate] when
 * the dictation was thrown away.
 */
internal class AuraDrawable(
    palette: DictationLook.Palette,
    private val base: Int,
) : Drawable(), Animatable {

    private class Cloud(
        val color: Int, val alpha: Float, val radius: Float,
        val baseX: Float, val baseY: Float, val speed: Float, val phase: Float, val orbit: Float,
    )

    private val clouds = listOf(
        Cloud(palette.primaryContainer, 0.70f, 0.62f, 0.44f, 0.72f, 0.235f, 0.00f, 0.150f),
        Cloud(palette.tertiaryContainer, 0.62f, 0.48f, 0.20f, 0.60f, 0.310f, 2.10f, 0.190f),
        Cloud(palette.secondaryContainer, 0.56f, 0.52f, 0.80f, 0.66f, 0.268f, 4.05f, 0.175f),
        Cloud(palette.primary, 0.30f, 0.38f, 0.34f, 0.86f, 0.375f, 1.15f, 0.215f),
        Cloud(palette.tertiary, 0.26f, 0.33f, 0.70f, 0.90f, 0.442f, 3.40f, 0.235f),
        Cloud(palette.secondary, 0.21f, 0.29f, 0.55f, 0.54f, 0.336f, 5.20f, 0.255f),
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val startedAt = SystemClock.uptimeMillis()
    private var lastFrame = startedAt
    private var running = false

    /** 0..1, how much mist there is. Fades in on start and out on [dissipate]. */
    private var presence = 0f
    private var presenceAnim: ValueAnimator? = null

    private var targetLevel = 0f
    private var level = 0f

    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    /** 0..1 through a poof: the blooms swell outward as they thin. */
    private var burst = 0f

    fun appear() {
        burst = 0f
        animatePresence(1f, APPEAR_MS, null)
    }

    /**
     * The text landed: the mist gives one last swell outward and brightens
     * slightly as it thins, so it reads as released rather than switched off.
     */
    fun poof(onGone: () -> Unit) {
        presenceAnim?.cancel()
        presenceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = POOF_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                burst = it.animatedValue as Float
                // A flash of brightness at the start, then gone: the "poof".
                presence = (1f - burst).pow(1.3f) * (1f + 0.9f * sin(PI.toFloat() * burst))
                invalidateSelf()
            }
            addListener(endListener(onGone))
            start()
        }
    }

    /** Thins the mist to nothing, then calls [onGone]. */
    fun dissipate(onGone: () -> Unit) = animatePresence(0f, DISSIPATE_MS, onGone)

    private fun animatePresence(to: Float, ms: Long, onEnd: (() -> Unit)?) {
        presenceAnim?.cancel()
        presenceAnim = ValueAnimator.ofFloat(presence, to).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener { presence = it.animatedValue as Float; invalidateSelf() }
            if (onEnd != null) addListener(endListener(onEnd))
            start()
        }
    }

    private fun endListener(onEnd: () -> Unit) = object : android.animation.AnimatorListenerAdapter() {
        private var cancelled = false
        override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
        override fun onAnimationEnd(animation: android.animation.Animator) {
            if (!cancelled) onEnd()
        }
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidateSelf()
            scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS)
        }
    }

    override fun start() {
        if (running) return
        running = true
        scheduleSelf(frame, SystemClock.uptimeMillis())
    }

    override fun stop() {
        running = false
        unscheduleSelf(frame)
        presenceAnim?.cancel()
    }

    override fun isRunning() = running

    override fun draw(canvas: Canvas) {
        canvas.drawColor(base)
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f || presence <= 0.001f) return

        // Mic envelope: quicker up than down, as in the app's rememberMicAmplitude,
        // so the mist settles between words instead of flickering.
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        val tau = if (targetLevel > level) 0.12f else 0.26f
        level += (targetLevel - level) * (1f - exp(-dt / tau))

        val t = (now - startedAt) / 1000f
        val scale = (w * 0.42f).coerceAtMost(h * 2.4f)
        for (cloud in clouds) {
            val swell = (1f + level * 0.42f) * (1f + burst * 1.4f)
            val wander = cloud.orbit * w * (1f + level * 0.30f)
            val x = b.left + cloud.baseX * w + cos(t * cloud.speed + cloud.phase) * wander
            val y = b.top + cloud.baseY * h + sin(t * cloud.speed * 0.78f + cloud.phase * 1.37f) * wander * 0.25f
            val r = cloud.radius * scale * swell
            val a = cloud.alpha * (0.86f + level * 0.34f) * presence
            paint.shader = RadialGradient(
                x, y, r,
                intArrayOf(
                    withAlpha(cloud.color, a), withAlpha(cloud.color, a * 0.62f),
                    withAlpha(cloud.color, a * 0.24f), withAlpha(cloud.color, a * 0.07f),
                    withAlpha(cloud.color, 0f),
                ),
                floatArrayOf(0f, 0.34f, 0.62f, 0.82f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(x, y, r, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    private companion object {
        const val FRAME_MS = 16L
        const val APPEAR_MS = 350L
        const val DISSIPATE_MS = 450L
        const val POOF_MS = 900L
    }
}
