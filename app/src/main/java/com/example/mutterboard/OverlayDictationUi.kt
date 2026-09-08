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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
 *
 * [minimized] collapses all of that to a single puck. The band is deliberately
 * cheap to get out of the way because it still covers the bottom of the screen,
 * which is where the thing you wanted to tap usually is.
 */
@Composable
fun OverlayDictationBand(
    snapshot: DictationSession.Snapshot,
    amplitude: () -> Float,
    minimized: Boolean,
    bottomInset: Dp,
    pasteWarning: Boolean,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
    onMinimizedChanged: (Boolean) -> Unit,
    onModeChanged: (Boolean) -> Unit,
    onFixPaste: () -> Unit,
    onDismissPasteWarning: () -> Unit,
) {
    // Checked before minimized: the warning replaces the dictation that has just
    // finished, and there is no dictation left to be out of the way of.
    if (pasteWarning) {
        PasteWarningBand(
            bottomInset = bottomInset,
            onFix = onFixPaste,
            onDismiss = onDismissPasteWarning,
        )
        return
    }
    if (minimized) {
        MinimizedPuck(
            posture = snapshot.state.posture(),
            amplitude = amplitude,
            onExpand = { onMinimizedChanged(false) },
        )
        return
    }
    val surface = MaterialTheme.colorScheme.surface
    // The content sets the floor: a fixed fraction alone is too short to hold the
    // controls once a caption appears, and the band should never be that.
    //
    // But content alone was wrong too. The band was measured on a small square
    // phone, where the controls happen to come out about as tall as a keyboard;
    // on a full-size display the same content is a fifth of the screen and the
    // keyboard it is standing in front of goes on showing underneath it, which
    // reads as a widget floating on a keyboard rather than as the thing that
    // replaced it. So the band also claims a share of the screen, and the two
    // rules take whichever is larger. The cap is for tablets, where a third of
    // the screen is a wall.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val minHeight = (screenHeight * BAND_SCREEN_FRACTION).coerceAtMost(BAND_MAX_HEIGHT)
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = minHeight)) {
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
                // The window itself now runs to the bottom edge of the screen so
                // the band has no seam above the navigation bar; the inset is
                // paid back here, so no control sits under the gesture pill.
                .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp + bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The band's top line: the mode pill centred, minimize off to the
            // right of it. Minimize is not a step in a dictation — it is a way to
            // get the band off whatever it is covering — so it sits up here
            // rather than in the row where a mis-tap is expensive.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Hidden whenever no refiner exists, exactly as on the keyboard: a
                // toggle naming a mode that isn't running would be lying.
                if (snapshot.showModeToggle) {
                    ModePill(casual = snapshot.casual, onChange = onModeChanged)
                }
                CircleButton(
                    icon = Icons.Outlined.KeyboardArrowDown,
                    description = "Minimize",
                    diameter = 36.dp,
                    container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    // No label and small enough that the usual 42% glyph reads as a smudge.
                    iconScale = 0.62f,
                    onClick = { onMinimizedChanged(true) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
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
            // Pushed out to the band's edges rather than clustered in the middle.
            // Stop and Cancel next to each other is the one misfire that costs you
            // the whole dictation, so the two are as far apart as the band allows
            // and Stop is the only large target in the middle of the sweep a thumb
            // actually makes.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(
                    icon = Icons.Outlined.Close,
                    description = "Cancel",
                    diameter = 52.dp,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onCancel,
                )
                CircleButton(
                    icon = snapshot.state.actionIcon(),
                    description = snapshot.actionDescription,
                    diameter = 72.dp,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onAction,
                )
                CircleButton(
                    icon = Icons.Outlined.Settings,
                    description = "Settings",
                    diameter = 52.dp,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onSettings,
                )
            }
        }
    }
}

/**
 * What the band says when the transcript went to the clipboard because the
 * accessibility service is gone.
 *
 * It stays where the dictation was, at the moment the paste did not happen,
 * because that is the only place the user is looking. The settings screen knows
 * this too, but nobody opens settings to find out why something they were not
 * told about did not occur - they conclude the app broke.
 *
 * Only ever shown to someone who had the service running before (see
 * KEY_ACCESSIBILITY_SEEN). A user who never turned it on is not owed a warning:
 * for them the clipboard is the design, not a failure.
 */
@Composable
private fun PasteWarningBand(
    bottomInset: Dp,
    onFix: () -> Unit,
    onDismiss: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.fillMaxWidth()) {
        // The dictation band can afford to be translucent: it is a wave and three
        // circles, and admitting what is behind it is the point. This one is two
        // paragraphs, and the same ramp put grey text on whatever the user
        // happened to be looking at - the message was unreadable on a light app.
        // So the feather stays, because it is what makes this look like the same
        // surface, but it reaches opaque well above the first line of text.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.14f to surface.copy(alpha = 0.72f),
                        0.32f to surface,
                        1f to surface,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Top padding is deeper than the dictation band's because the
                // first line has to clear the part of the ramp that is still
                // see-through.
                .padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 18.dp + bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Your dictation is on the clipboard",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            // Names Android as the one that did it. The user changed nothing, and
            // an app that says only "permission missing" is letting itself be
            // blamed for a switch it did not touch.
            Text(
                text = "Android turned Mutterboard's accessibility permission off " +
                    "during the last update, so it could not paste for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(
                    label = "Not now",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onDismiss,
                )
                PillButton(
                    label = "Turn it back on",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onFix,
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** How much of the screen the band covers when its content does not need more. */
private const val BAND_SCREEN_FRACTION = 0.34f

/** Above this the fraction stops being a band and starts being a wall. */
private val BAND_MAX_HEIGHT = 340.dp

/**
 * What is left of the band once it is out of the way: a small pill in the bottom
 * corner with the same wave still running in it.
 *
 * The wave rather than a mic glyph, because a mic reads as a button that would
 * *start* something. The point of the collapsed state is the opposite — the
 * dictation is already running, you just moved it out of the way — and the wave
 * is the one thing in this app that already says "still listening".
 */
@Composable
private fun MinimizedPuck(
    posture: WavePosture,
    amplitude: () -> Float,
    onExpand: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 78.dp, height = 46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClickLabel = "Expand Mutterboard", onClick = onExpand),
        contentAlignment = Alignment.Center,
    ) {
        DictationWave(
            posture = posture,
            amplitude = amplitude,
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(width = 54.dp, height = 22.dp),
        )
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
    diameter: Dp,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconScale: Float = 0.42f,
) {
    Box(
        modifier = modifier
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
            modifier = Modifier.size(diameter * iconScale),
        )
    }
}

/**
 * The Default/Casual refine toggle, as a compact segmented pill.
 *
 * Kept deliberately quiet. It was the loudest thing in the band, which is exactly
 * backwards: it is a setting you change once in a while, not part of dictating.
 * So it is small, and the selected side is a lift rather than a filled accent.
 */
@Composable
private fun ModePill(casual: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(2.dp),
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
                if (selected) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            },
        )
    }
}
