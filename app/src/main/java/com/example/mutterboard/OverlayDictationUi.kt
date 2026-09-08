package com.example.mutterboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The listening surface for the overlay: a band across the bottom of the screen
 * that floats over whatever the user is doing.
 *
 * The look is ported from Checkr's VoiceOverlay, with one deliberate departure.
 * Checkr fills the screen and treats a tap anywhere outside the band as cancel,
 * which it can afford because its dictation happens inside its own app. Here the
 * window is only the band, so every touch above it goes to the app underneath —
 * dictation is meant to survive you moving around while you talk, and a
 * full-screen tap catcher would make that impossible.
 *
 * The band is translucent rather than the near-solid surface Checkr uses, for the
 * same reason: it is admitting that it is on top of something.
 */
@Composable
fun OverlayDictationBand(
    snapshot: DictationSession.Snapshot,
    amplitude: () -> Float,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
    onModeChanged: (Boolean) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    // Height comes from the content rather than a fraction of the screen. A fixed
    // fraction is either taller than the controls need or too short to hold them
    // once a caption appears, and the band should never be either.
    Box(modifier = Modifier.fillMaxWidth()) {
        // The band's own surface. It comes up from nothing at the top edge so
        // there is no hard boundary — the band ends in a feather, not a seam —
        // and it stops short of opaque so what is behind stays faintly readable.
        // The ramp is proportional, so it still feathers at any height.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.22f to surface.copy(alpha = 0.50f),
                        0.50f to surface.copy(alpha = 0.78f),
                        1f to surface.copy(alpha = 0.88f),
                    ),
                ),
        )
        // The mist sits ON the surface rather than under it, so it reads as
        // colour in the band rather than as a scrim over the app. Same feather at
        // the top so it cannot outrun the surface beneath it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.30f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            DictationAura(amplitude = amplitude, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Hidden whenever no refiner exists, exactly as on the keyboard: a
            // toggle naming a mode that isn't running would be lying.
            if (snapshot.showModeToggle) {
                ModePill(casual = snapshot.casual, onChange = onModeChanged)
            }
            DictationWave(
                posture = snapshot.state.posture(),
                amplitude = amplitude,
                color = MaterialTheme.colorScheme.primary,
                // Held in from the edges — running the full width made it read as
                // a divider across the screen.
                modifier = Modifier.fillMaxWidth(0.72f).height(30.dp),
            )
            // Recording needs no caption, the wave says it. Every other state is
            // telling you something you cannot see, so it speaks. Fixed minimum
            // height so the controls never shift underneath.
            snapshot.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(
                    icon = Icons.Outlined.Close,
                    description = "Cancel",
                    diameter = 44.dp,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onCancel,
                )
                CircleButton(
                    icon = snapshot.state.actionIcon(),
                    description = snapshot.actionDescription,
                    diameter = 56.dp,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onAction,
                )
                CircleButton(
                    icon = Icons.Outlined.Settings,
                    description = "Settings",
                    diameter = 44.dp,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onSettings,
                )
            }
        }
    }
}

/**
 * A tick rather than a square stop, carried over from Checkr: while recording,
 * this button means "I have stopped talking", not "abort".
 */
private fun DictationSession.State.actionIcon(): ImageVector = when (this) {
    DictationSession.State.IDLE -> Icons.Outlined.Mic
    DictationSession.State.RECORDING, DictationSession.State.TRANSCRIBING -> Icons.Outlined.Check
    DictationSession.State.ERROR,
    DictationSession.State.NO_NETWORK,
    DictationSession.State.NO_SPEECH,
    -> Icons.Outlined.Refresh
    // The three setup states all send you into the app to fix something.
    DictationSession.State.NO_PERMISSION,
    DictationSession.State.NO_API_KEY,
    DictationSession.State.NO_MODEL,
    -> Icons.Outlined.Settings
}

private fun DictationSession.State.posture(): WavePosture = when (this) {
    DictationSession.State.RECORDING -> WavePosture.Listening
    DictationSession.State.TRANSCRIBING -> WavePosture.Thinking
    DictationSession.State.IDLE -> WavePosture.Idle
    else -> WavePosture.Missed
}

@Composable
private fun CircleButton(
    icon: ImageVector,
    description: String,
    diameter: androidx.compose.ui.unit.Dp,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(diameter * 0.42f),
        )
    }
}

/** The Default/Casual refine toggle, as a compact segmented pill. */
@Composable
private fun ModePill(casual: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(3.dp),
    ) {
        ModeChip("Default", selected = !casual) { onChange(false) }
        ModeChip("Casual", selected = casual) { onChange(true) }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
