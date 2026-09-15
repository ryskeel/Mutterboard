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
import kotlin.math.pow
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

/**
 * The mist letting go: the same blooms the band floats in, thrown outward and
 * faded to nothing over about half a second.
 *
 * It plays where the dictation UI was, at the moment the text lands somewhere
 * else. The overlay used to vanish on the same frame it committed, which gave the
 * one event worth confirming - your words arriving in the field - no acknowledgement
 * at all. Something has to say "that went somewhere", and the mist was already the
 * app's way of saying a thing is happening.
 *
 * Built from the same wide radial gradients as [DictationAura] rather than from a
 * particle system: it has to read as the mist that was already there dispersing,
 * and anything with visible edges would read as new objects appearing instead.
 *
 * Draws nothing once [POOF_MS] has elapsed, so it cannot outlive the window being
 * torn down behind it.
 */
@Composable
fun PoofBurst(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val puffs = remember(scheme) {
        // Spread around the compass rather than evenly, so the cloud comes apart
        // unevenly the way real mist does. Radians, then a distance multiplier.
        listOf(
            Puff(scheme.primaryContainer, 0.78f, 0.52f, -1.95f, 1.00f),
            Puff(scheme.tertiaryContainer, 0.70f, 0.44f, -0.62f, 0.86f),
            Puff(scheme.secondaryContainer, 0.66f, 0.47f, -2.65f, 0.78f),
            Puff(scheme.primary, 0.34f, 0.33f, -1.20f, 1.12f),
            Puff(scheme.tertiary, 0.30f, 0.30f, -2.35f, 0.64f),
            Puff(scheme.secondary, 0.26f, 0.28f, 0.15f, 0.92f),
        )
    }
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (progress.floatValue < 1f) {
            withFrameNanos { now ->
                progress.floatValue =
                    ((now - start) / 1_000_000f / POOF_MS).coerceIn(0f, 1f)
            }
        }
    }

    Canvas(modifier) {
        val p = progress.floatValue
        if (p >= 1f) return@Canvas
        // Ease out: the mist leaves quickly and then drifts, which is what makes
        // it read as released rather than as animated.
        val eased = 1f - (1f - p).pow(2.2f)
        // Up fast, then away. Starting at zero is what gives the puff its push;
        // starting lit would just be a fade.
        val bloom = (p / BLOOM_FRACTION).coerceAtMost(1f)
        val fade = (1f - ((p - BLOOM_FRACTION) / (1f - BLOOM_FRACTION)).coerceIn(0f, 1f))
            .pow(1.5f)
        val envelope = bloom * fade
        if (envelope <= 0f) return@Canvas
        val reach = minOf(size.width, size.height) * 0.5f
        val centre = Offset(size.width / 2f, size.height / 2f)
        for (puff in puffs) {
            val at = Offset(
                centre.x + cos(puff.direction) * reach * puff.distance * eased,
                centre.y + sin(puff.direction) * reach * puff.distance * eased,
            )
            val r = reach * puff.radius * (0.55f + 0.95f * eased)
            val a = puff.alpha * envelope
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to puff.color.copy(alpha = a),
                        0.34f to puff.color.copy(alpha = a * 0.62f),
                        0.62f to puff.color.copy(alpha = a * 0.24f),
                        0.82f to puff.color.copy(alpha = a * 0.07f),
                        1.00f to Color.Transparent,
                    ),
                    center = at,
                    radius = r,
                ),
                radius = r,
                center = at,
            )
        }
    }
}

private class Puff(
    val color: Color,
    val alpha: Float,
    /** Bloom radius as a fraction of the burst's reach. */
    val radius: Float,
    /** Which way it leaves, in radians. */
    val direction: Float,
    /** How far it travels, as a multiple of the reach. */
    val distance: Float,
)

/**
 * How long the burst lasts. The window is torn down on this same number, so the
 * two must not drift apart: a shorter window cuts the mist off mid-air, and a
 * longer one leaves an invisible window sitting over the app the user has just
 * gone back to.
 */
const val POOF_MS = 520f

/** The share of the burst spent arriving, before it starts leaving. */
private const val BLOOM_FRACTION = 0.22f

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
