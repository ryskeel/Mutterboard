package com.example.mutterboard

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** What the wave is saying, which is the only thing the band says while listening. */
enum class WavePosture { Listening, Thinking, Done, Missed, Idle }

/**
 * One long squiggle that doubles as the level meter. Ported from Checkr's
 * CheckrWave.
 *
 * It keeps the postures, which is the point of it rather than a generic meter:
 *
 * - **Listening** — travels, and its peaks ride the mic.
 * - **Thinking** — the squiggle rolls up into a spinning ring.
 * - **Done** — flattens to one level rule. Straightness is the confirmation.
 * - **Missed** — goes quiet and nearly flat.
 *
 * The envelope tapers to nothing at both ends, so it reads as something reaching
 * out from a centre rather than as a band of noise.
 *
 * Thinking is a shape change rather than a quieter wave because of what it has to
 * say from inside the puck. A tap there ends the dictation, and the two seconds of
 * transcribing that follow used to look exactly like the two seconds before the
 * tap - so the tap read as a press that missed. A wave that curls into a spinner
 * is the one posture change you cannot mistake for the same wave continuing.
 *
 * It is one path throughout, not two drawings swapped at a threshold: every sample
 * is interpolated between where it sits on the wave and where it sits on the ring,
 * so the squiggle visibly rolls in rather than cutting.
 */
@Composable
fun DictationWave(
    posture: WavePosture,
    amplitude: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    /** Thinner when the wave is drawn small, or it fills in and reads as a blob. */
    strokeWidth: Dp = 4.dp,
) {
    val postureNow = rememberUpdatedState(posture)
    val ampNow = rememberUpdatedState(amplitude)
    // Phase accumulates rather than being derived from the clock, so a change of
    // travel speed between states never makes the wave jump.
    val phase = remember { mutableFloatStateOf(0f) }
    // Peak height as a fraction of the half-height, eased toward its target so
    // state changes glide. Attack quicker than release, like the mic envelope.
    val peak = remember { mutableFloatStateOf(0f) }
    // How far the line has rolled from squiggle (0) to ring (1).
    val morph = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                val amp = ampNow.value().coerceIn(0f, 1f)
                val current = postureNow.value
                phase.floatValue += dt * when (current) {
                    // Louder speech travels faster, but only a little — the wave
                    // should look like it's carrying a voice, not racing.
                    WavePosture.Listening -> 2.1f + amp * 1.5f
                    // The same phase that travelled the wave now spins the ring,
                    // which is why the roll-in has no seam in it - fast enough to
                    // read as working, short of the blur that reads as stuck.
                    WavePosture.Thinking -> 4.2f
                    else -> 0.55f
                }
                val target = when (current) {
                    WavePosture.Listening -> 0.09f + amp * 0.80f
                    WavePosture.Thinking -> 0.24f
                    WavePosture.Done -> 0f
                    WavePosture.Missed -> 0.05f
                    WavePosture.Idle -> 0.08f
                }
                val tau = if (target > peak.floatValue) 0.10f else 0.22f
                peak.floatValue += (target - peak.floatValue) * (1f - exp(-dt / tau))
                // Rolls in a little quicker than it unrolls: arriving at the ring
                // is the answer to a tap and wants to feel prompt, while leaving
                // it is just the wave coming back.
                val wantRing = if (current == WavePosture.Thinking) 1f else 0f
                val morphTau = if (wantRing > morph.floatValue) 0.13f else 0.20f
                morph.floatValue += (wantRing - morph.floatValue) *
                    (1f - exp(-dt / morphTau))
            }
        }
    }

    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val centreY = size.height / 2f
        val room = (size.height - stroke) / 2f
        val reach = peak.floatValue * room
        val rolled = morph.floatValue
        // Bounded by the short side, so the ring stays a ring in the band's wide
        // box instead of stretching into an ellipse.
        val radius = (minOf(size.width, size.height) - stroke) / 2f
        val centreX = size.width / 2f
        val path = Path()
        for (i in 0..SAMPLES) {
            val f = i / SAMPLES.toFloat()
            // Zero at both ends, one in the middle: the wave grows out of the
            // centre and settles back to level before it runs off the edge.
            val envelope = sin(PI * f).pow(0.75).toFloat()
            val waveX = f * size.width
            val waveY = centreY + reach * envelope *
                sin(f * CYCLES * 2f * PI.toFloat() - phase.floatValue)
            // Short of a full turn, so the ring keeps a gap and reads as spinning.
            // A closed circle turning looks like a circle standing still.
            val angle = f * RING_TURNS * 2f * PI.toFloat() - phase.floatValue
            val ringX = centreX + radius * cos(angle)
            val ringY = centreY + radius * sin(angle)
            val x = waveX + (ringX - waveX) * rolled
            val y = waveY + (ringY - waveY) * rolled
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

private const val SAMPLES = 160
private const val CYCLES = 2.5f

/** Turns of the ring the wave rolls into. Under 1 leaves the gap that spins. */
private const val RING_TURNS = 0.82f
