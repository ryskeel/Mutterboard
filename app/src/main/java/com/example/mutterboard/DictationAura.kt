package com.example.mutterboard

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The mist the dictation band floats in: a slow drift of overlapping soft radial
 * blooms in the theme's own colours, so it follows Material You wherever the
 * wallpaper goes. It thickens and reaches further as [amplitude] rises, which is
 * what makes the whole surface feel like it is listening.
 *
 * Ported from Checkr's CheckrAura, which is where this look was designed.
 *
 * Sized off the band's **width**, and each bloom sits at its own spot rather than
 * on the centre — the cloudiness comes from blobs overlapping at different places,
 * not from stacking them concentrically, which only reads as a vignette.
 *
 * Deliberately built from wide gradients rather than `Modifier.blur`: blur is
 * API 31+, and this app runs back to 24.
 */
@Composable
fun DictationAura(
    amplitude: () -> Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val clouds = remember(scheme) {
        // Ordered back-to-front, spread across the band rather than stacked on its
        // centre. The containers are the body of the mist and carry most of the
        // alpha; the accents are the colour you actually notice, and they are dark
        // enough that a little goes a long way.
        //
        // The drift speeds matter as much as the alpha: much slower than this and a
        // bloom takes so long to come round that it reads as a static wash rather
        // than as cloud.
        listOf(
            //    colour                     alpha  radius   x      y     speed  phase orbit
            Cloud(scheme.primaryContainer, 0.70f, 0.62f, 0.44f, 0.72f, 0.235f, 0.00f, 0.150f),
            Cloud(scheme.tertiaryContainer, 0.62f, 0.48f, 0.20f, 0.60f, 0.310f, 2.10f, 0.190f),
            Cloud(scheme.secondaryContainer, 0.56f, 0.52f, 0.80f, 0.66f, 0.268f, 4.05f, 0.175f),
            Cloud(scheme.primary, 0.30f, 0.38f, 0.34f, 0.86f, 0.375f, 1.15f, 0.215f),
            Cloud(scheme.tertiary, 0.26f, 0.33f, 0.70f, 0.90f, 0.442f, 3.40f, 0.235f),
            Cloud(scheme.secondary, 0.21f, 0.29f, 0.55f, 0.54f, 0.336f, 5.20f, 0.255f),
        )
    }
    val time = rememberAuraClock()

    Canvas(modifier) {
        val amp = amplitude().coerceIn(0f, 1f)
        for (cloud in clouds) cloud.draw(this, size.width, size.height, time.value, amp)
    }
}

private class Cloud(
    val color: Color,
    val alpha: Float,
    /** Bloom radius as a fraction of the band's width. */
    val radius: Float,
    /** Where it sits in the band, as fractions of width and height. */
    val baseX: Float,
    val baseY: Float,
    /** Radians per second — all slow, all different, so it never visibly loops. */
    val speed: Float,
    val phase: Float,
    /** How far its centre wanders from where it sits. */
    val orbit: Float,
)

private fun Cloud.draw(scope: DrawScope, width: Float, height: Float, t: Float, amp: Float) {
    // Louder speech pushes the mist outward and brightens it, but never so far
    // that the blooms separate into discs.
    val swell = 1f + amp * 0.42f
    val wander = orbit * width * (1f + amp * 0.30f)
    val at = Offset(
        baseX * width + cos(t * speed + phase) * wander,
        baseY * height + sin(t * speed * 0.78f + phase * 1.37f) * wander * 0.62f,
    )
    val r = radius * width * swell
    val a = alpha * (0.86f + amp * 0.34f)
    scope.drawCircle(
        brush = Brush.radialGradient(
            // A long, soft tail — a two-stop gradient has a visible edge, and the
            // whole point is that you can't tell where the cloud stops.
            colorStops = arrayOf(
                0.00f to color.copy(alpha = a),
                0.34f to color.copy(alpha = a * 0.62f),
                0.62f to color.copy(alpha = a * 0.24f),
                0.82f to color.copy(alpha = a * 0.07f),
                1.00f to Color.Transparent,
            ),
            center = at,
            radius = r,
        ),
        radius = r,
        center = at,
    )
}

@Composable
private fun rememberAuraClock(): State<Float> {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> seconds.floatValue = (now - start) / 1_000_000_000f }
        }
    }
    return seconds
}

/**
 * Mic level smoothed with a ~120ms attack and a ~260ms release. Raw per-frame
 * peak jitters and reads as a glitch, and the slower release is what gives the
 * mist its settling feel between words.
 *
 * Returns a lambda so a level changing 60x a second never recomposes anything.
 */
@Composable
fun rememberMicAmplitude(level: () -> Float): () -> Float {
    val target = rememberUpdatedState(level)
    val amp = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                val want = target.value().coerceIn(0f, 1f)
                val tau = if (want > amp.floatValue) 0.12f else 0.26f
                amp.floatValue += (want - amp.floatValue) * (1f - exp(-dt / tau))
            }
        }
    }
    return { amp.floatValue }
}
