package com.example.mutterboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.mutterboard.ui.theme.MutterboardTheme
import kotlin.math.abs

/**
 * Dictation that isn't tied to a text field.
 *
 * The keyboard can only run while an input is focused and you've switched to it.
 * This runs from a single button press anywhere: it floats the same dictation UI
 * over whatever you're doing, keeps recording while you move between apps, and
 * puts the finished transcript on the clipboard — pasting it for you when a text
 * field happens to be focused, and simply leaving it there when one isn't.
 *
 * A foreground service rather than a bare overlay because an overlay window does
 * not make the app foreground, and Android cuts microphone access to apps that
 * aren't. The ongoing notification that buys us is worth having anyway: a
 * dictation here can outlive the app it started in.
 */
class OverlayDictationService : Service(), DictationSession.Host {

    private var session: DictationSession? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var viewHost: OverlayViewHost? = null

    /**
     * Whether the band has been collapsed to its puck. Held here rather than
     * inside the composable because the window has to shrink with it: leaving a
     * full-width window in place would go on swallowing every touch along the
     * bottom of the screen, which is the whole thing being complained about.
     */
    private val minimized = mutableStateOf(false)

    /**
     * Whether the band is standing in for a paste that could not happen because
     * the accessibility service is no longer running.
     *
     * Held here, not in the session: the session's job ends when the text is
     * delivered, and this is a fact about where it could be delivered to.
     */
    private val pasteWarning = mutableStateOf(false)

    /**
     * Whether the mist is playing out the end of a dictation. The window outlives
     * the session by exactly that long - see [dismiss].
     */
    private val poofing = mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Where the puck was left: a column across, and a free inset up from the
     * bottom in px.
     *
     * Remembered across dictations because where the band is in the way is a fact
     * about the user's screen, not about this one recording; making them drag it
     * clear again every time would be the annoyance minimizing exists to remove.
     *
     * Horizontal is a column rather than a coordinate because a puck parked by
     * hand is never quite anywhere: dragged freely it ends up a few pixels off the
     * edge, or a few pixels off centre, and every one of those is a position the
     * user did not mean. Three columns are all the horizontal choices that exist
     * on a phone - out of the way left, out of the way right, or deliberately in
     * the middle - so the drag picks between them instead of between pixels.
     * Vertical stays free, because height is where the thing you are covering
     * actually varies.
     */
    private var puckColumn = COLUMN_RIGHT
    private var puckX = 0
    private var puckY = 0

    /**
     * Whether the last dictation was left as a puck, and so whether the next one
     * should come up as one.
     *
     * The puck is a way of working, not a state of one recording: someone who
     * dictates from the puck wants the puck every time, and having to collapse
     * the band again on every launch is the friction they minimized to escape.
     * Only a deliberate collapse or expand writes this - the automatic expand
     * that surfaces an error must not silently end puck mode on their behalf.
     */
    private var puckMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
        puckColumn = prefs.getInt(KEY_PUCK_COLUMN, COLUMN_RIGHT)
        puckX = columnInsetPx(puckColumn)
        // Clear of the navigation bar as well as the screen edge: the overlay
        // window now runs to the bottom of the display, so an inset measured from
        // the edge alone would park the puck on top of the gesture pill.
        puckY = prefs.getInt(KEY_PUCK_Y, MINIMIZED_INSET_DP.dpToPx() + bottomInsetPx())
        puckMode = prefs.getBoolean(KEY_PUCK_MODE, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Already up: a second press shouldn't stack another overlay on the first.
        if (overlayView != null) return START_NOT_STICKY

        startInForeground()

        // Before the view is built, so the puck composes as the puck rather than
        // as a band that snaps shut a frame later.
        minimized.value = puckMode

        val s = DictationSession(this, this)
        session = s
        val view = createBandView(s)
        overlayView = view
        windowManager = getSystemService(WindowManager::class.java)
        try {
            windowManager?.addView(view, overlayLayoutParams(minimized.value))
        } catch (e: Throwable) {
            // Almost always the "display over other apps" grant being missing or
            // revoked. Nothing useful to show from here, so bow out cleanly
            // rather than leaving a foreground service with no UI attached.
            Log.e(TAG, "could not add overlay", e)
            overlayView = null
            teardown()
            return START_NOT_STICKY
        }
        s.onShown()
        return START_NOT_STICKY
    }

    /**
     * Builds the Compose band and gives it the owners it needs to run outside an
     * Activity. A ComposeView added straight to the window manager has no
     * lifecycle, no ViewModel store and no saved-state registry to inherit, and
     * refuses to compose without all three.
     */
    private fun createBandView(session: DictationSession): View {
        val host = OverlayViewHost().also { viewHost = it; it.create() }
        val snapshot = mutableStateOf(session.currentSnapshot())
        session.onUpdate = {
            snapshot.value = it
            // Anything that needs the user is worth un-minimizing for. A puck
            // cannot carry "Mic permission needed", so a failure that happened
            // while the band was out of the way would otherwise be invisible.
            //
            // IDLE is not one of those: it is what a dictation that worked passes
            // through, and expanding on it popped the band open at the end of
            // every recording - the one moment someone in puck mode is most
            // certain they are done. The expand is also deliberately not
            // remembered, because the app asked for the band here, the user did
            // not.
            if (it.state != DictationSession.State.RECORDING &&
                it.state != DictationSession.State.TRANSCRIBING &&
                it.state != DictationSession.State.IDLE
            ) {
                setMinimized(false, remember = false)
            }
        }

        val band = ComposeView(this).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent {
                MutterboardTheme {
                    OverlayDictationBand(
                        snapshot = snapshot.value,
                        amplitude = rememberMicAmplitude { session.micLevel() },
                        onAction = { session.micTapped() },
                        onCancel = { session.cancelTapped() },
                        onSettings = { session.settingsTapped() },
                        minimized = minimized.value,
                        poofing = poofing.value,
                        bottomInset = (bottomInsetPx() / resources.displayMetrics.density).dp,
                        pasteWarning = pasteWarning.value,
                        onMinimizedChanged = { setMinimized(it, remember = true) },
                        onModeChanged = { session.modeChanged(it) },
                        onFixPaste = { openAccessibilitySettings() },
                        onDismissPasteWarning = { dismissPasteWarning() },
                    )
                }
            }
        }
        // The band goes inside a layer that can take the touch stream away from
        // it, which is what makes the puck draggable. See PuckDragLayout.
        //
        // The owners go on this root rather than on the ComposeView: Compose
        // resolves its recomposer from the root of the window, so with them one
        // level down it finds nothing and throws the moment the view attaches.
        return PuckDragLayout(this).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            addView(
                band,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /**
     * Carries all three of the puck's gestures: drag it anywhere, tap it to
     * finish the dictation, hold it to bring the band back.
     *
     * Tap finishes rather than expands because the puck is the whole interface
     * for someone working this way - they collapsed the band to get it out of
     * the way, and the next thing they want is the text, not the band back. The
     * band is the rarer need, so it takes the deliberate gesture.
     *
     * Drag and hold do not compete for the same press. The hold is a timer armed
     * on touch-down and cancelled the moment the finger passes the slop the drag
     * already measures, so a press that moves is a drag and never a hold. Only a
     * press that stays still long enough is a hold, and it says so with a haptic
     * tick at the moment it stops being a tap.
     *
     * Done on raw screen coordinates rather than as a Compose gesture, because
     * the thing being moved is the window itself. Compose only ever reports a
     * position *within* that window, and a window that keeps jumping out from
     * under the finger makes those numbers meaningless.
     *
     * It has to intercept rather than listen, too. A ViewGroup offers touches to
     * its children first, so the puck's own clickable swallowed every press
     * before an OnTouchListener on the ComposeView ever saw it.
     *
     * Expanded, every touch goes straight through to Compose as before.
     */
    private inner class PuckDragLayout(context: Context) : FrameLayout(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var expanded = false

        /**
         * The hold. Fires only if the press is still on the puck and still
         * within the slop when the timeout lands, so a drag never reaches it.
         *
         * The haptic is not decoration: it is the only signal that the press has
         * crossed from tap to hold, and it is what stops a hesitant drag being a
         * surprise. The finger is still down afterwards and the band is now
         * underneath it, so the rest of the gesture is swallowed rather than
         * delivered to whatever button the band just put there.
         */
        private val expandOnHold = Runnable {
            expanded = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            setMinimized(false, remember = true)
        }

        // Not while the burst is playing: the puck is gone by then, and a press
        // landing on the mist would be read as a tap on a dictation that is over.
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
            minimized.value && !poofing.value

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Expanded, every touch goes straight through to Compose.
                    if (!minimized.value || poofing.value) return false
                    downX = event.rawX
                    downY = event.rawY
                    startX = puckX
                    startY = puckY
                    dragging = false
                    expanded = false
                    postDelayed(expandOnHold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (expanded) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // Below the slop it is still a tap: a thumb never presses
                    // perfectly still.
                    if (!dragging && abs(dx) + abs(dy) > slop) {
                        dragging = true
                        // Past the slop the press is a drag, and a drag is never
                        // also a hold. Cancelling here rather than checking the
                        // distance when the timer lands is what keeps the two
                        // gestures off each other.
                        removeCallbacks(expandOnHold)
                    }
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        // Insets from the bottom-right corner, so both axes run
                        // against the finger.
                        //
                        // Across, the finger picks a column rather than a
                        // position: the puck jumps the moment the free inset
                        // passes the halfway mark between two of them, which is
                        // what makes the snap something you aim with rather than
                        // something that happens to you when you let go.
                        val column = nearestColumn((startX - dx).toInt())
                        val snappedX = columnInsetPx(column)
                        val snappedY = (startY - dy).toInt()
                            .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
                        if (column != puckColumn || snappedX != puckX || snappedY != puckY) {
                            puckColumn = column
                            puckX = snappedX
                            puckY = snappedY
                            moveOverlay()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(expandOnHold)
                    when {
                        // The hold already did its work on the way down.
                        expanded -> Unit
                        dragging -> savePuckPosition()
                        // Stop, refine, paste - the same thing the band's own
                        // button does, which is why it goes through the session
                        // rather than reimplementing any of it.
                        else -> session?.micTapped()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(expandOnHold)
                    if (dragging) savePuckPosition()
                }
            }
            return true
        }
    }

    private fun moveOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.updateViewLayout(view, overlayLayoutParams(minimized.value))
        } catch (e: Throwable) {
            Log.w(TAG, "could not move overlay", e)
        }
    }

    private fun savePuckPosition() {
        getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PUCK_COLUMN, puckColumn)
            .putInt(KEY_PUCK_Y, puckY)
            .apply()
    }

    /**
     * Where a column sits, as an inset from the right edge.
     *
     * Measured against the puck's declared width rather than the view's, because
     * this is needed before the view has ever been laid out - the window's first
     * position is decided while its width is still zero, and a centre computed
     * from zero is half a puck off.
     */
    private fun columnInsetPx(column: Int): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val puckWidth = PUCK_WIDTH_DP.dpToPx()
        val padding = PUCK_EDGE_PADDING_DP.dpToPx()
        return when (column) {
            COLUMN_LEFT -> (screenWidth - puckWidth - padding).coerceAtLeast(0)
            COLUMN_CENTER -> ((screenWidth - puckWidth) / 2).coerceAtLeast(0)
            else -> padding
        }
    }

    /** The column whose resting place is closest to a freely dragged [insetPx]. */
    private fun nearestColumn(insetPx: Int): Int =
        COLUMNS.minByOrNull { abs(insetPx - columnInsetPx(it)) } ?: COLUMN_RIGHT

    /**
     * Floats the dictation UI along the bottom edge, where the keyboard would be.
     *
     * FLAG_NOT_FOCUSABLE is the whole trick: without focus the text field you were
     * in stays focused and keeps its cursor, so there is something to paste into
     * when the transcript arrives. Touches still reach the buttons.
     *
     * Sized to the band rather than the screen on purpose. Every touch above it
     * goes to the app underneath, which is what lets a dictation carry on while
     * the user moves around — a full-screen window would swallow all of it.
     */
    /**
     * Collapses the band to its puck, or brings it back, resizing the window to
     * match. Cheap enough to call with the value it already holds.
     *
     * [remember] carries the difference between the user choosing a shape and the
     * app forcing one. Only a choice sets the shape the next dictation opens in;
     * an expand the app did to show an error must leave puck mode alone, or a
     * missing mic permission would quietly switch someone back to the band for
     * good.
     */
    private fun setMinimized(value: Boolean, remember: Boolean) {
        if (remember && puckMode != value) {
            puckMode = value
            getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PUCK_MODE, value)
                .apply()
        }
        if (minimized.value == value) return
        minimized.value = value
        moveOverlay()
    }

    private fun overlayLayoutParams(minimized: Boolean = false): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            // Minimized, the window is only as wide as the puck, so the rest of
            // the bottom row of the screen is the app's again.
            if (minimized) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                WindowManager.LayoutParams.MATCH_PARENT
            },
            // The band measures itself. A fraction of the screen was either taller
            // than the controls needed or too short once a caption appeared.
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Reach the actual bottom of the screen. Without this the window is
            // fitted above the navigation bar, which left a strip of the app
            // underneath showing below the band - a hard edge across the bottom
            // of the phone with the keyboard's white background in it. The band
            // pads its own content back out of that strip instead (see
            // navigationBarInsetDp), so nothing lands under the gesture pill.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
            // The puck sits in the corner the minimize button was in, so it comes
            // to rest where the thumb just left.
            gravity = if (minimized) {
                Gravity.BOTTOM or Gravity.END
            } else {
                Gravity.BOTTOM or Gravity.START
            }
            // Wherever the user last dragged the puck to. The burst needs room
            // around the puck to disperse into, and the window is only as big as
            // what it holds - so while it plays, the window grows by the margin
            // the content has just added and steps back by the same amount, which
            // leaves the puck itself exactly where it was.
            if (minimized) {
                val margin = if (poofing.value) POOF_MARGIN_DP.dpToPx() else 0
                x = (puckX - margin).coerceAtLeast(0)
                y = (puckY - margin).coerceAtLeast(0)
            }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * How much of the bottom of the screen belongs to the navigation bar.
     *
     * The band's window deliberately opts out of being fitted above it (see
     * fitInsetsTypes), which is what removes the seam across the bottom of the
     * screen. The price is that the band has to keep its own controls out of that
     * strip, so the number has to be read rather than assumed - it is 0 on a phone
     * with hardware keys and around 48dp on three-button navigation.
     */
    private fun bottomInsetPx(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            .bottom
    }

    /**
     * Clipboard first, then paste — in that order, because the paste action reads
     * what we just put there.
     *
     * The clipboard write is unconditional rather than a fallback for when paste
     * fails. Dictation here often has no destination yet (you talk, then go find
     * the field), and a transcript that landed nowhere is the one failure worth
     * engineering against.
     */
    override fun commit(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Log.e(TAG, "no ClipboardManager")
            return
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        } catch (e: Throwable) {
            Log.e(TAG, "clipboard write failed", e)
            Toast.makeText(this, "Couldn't save the transcript", Toast.LENGTH_LONG).show()
            return
        }
        val service = MutterboardAccessibilityService.instance
        val pasted = service?.insertIntoFocusedField(text) ?: false
        Log.d(TAG, "commit pasted=$pasted")
        // The service being gone entirely is the case worth speaking up about.
        // A running service that simply found no editable field is the designed
        // behaviour - you dictated with nowhere to put it yet - and warning about
        // that would be warning about the feature.
        // Stays until it is answered. It had a timeout, and the timeout ate the
        // one press that fixes the problem: the band was gone before a hand had
        // finished moving to it. A notice with a repair in it is worth the bottom
        // of the screen for as long as it takes to read.
        if (service == null && accessibilityWasSeen()) pasteWarning.value = true
        // No "copied" toast on the clipboard-only path: Android already shows its
        // own clipboard confirmation on every write, and two notices for one
        // event is worse than none.
    }

    /**
     * The dictation is over, and normally that means the window goes.
     *
     * Except when there is something to say about where the text ended up. The
     * band is the only place the user is looking at that moment, and a warning
     * they have to go and find in settings is a warning nobody reads, so the
     * window outlives the session it was showing.
     */
    override fun dismiss() {
        if (pasteWarning.value) return
        // Already playing: commit and dismiss arrive together, and a second pass
        // here would restart the burst and post a second teardown.
        if (poofing.value) return
        // The text has landed somewhere else by now, and the window vanishing on
        // the same frame is what made that moment silent. Hold it open for exactly
        // as long as the mist takes to clear.
        poofing.value = true
        moveOverlay()
        mainHandler.postDelayed({ teardown() }, POOF_MS.toLong())
    }

    /** Sends the user to the switch, and gets out of the way behind them. */
    private fun openAccessibilitySettings() {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        teardown()
    }

    /**
     * Take the warning at its word: they know, and they are not turning it back
     * on. Forgetting that the service was ever seen is what stops this becoming
     * a notice at the end of every dictation for someone who removed it on
     * purpose. Turning it on again sets the flag afresh.
     */
    private fun dismissPasteWarning() {
        getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MutterboardInputMethodService.KEY_ACCESSIBILITY_SEEN, false)
            .apply()
        teardown()
    }

    private fun accessibilityWasSeen(): Boolean =
        getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MutterboardInputMethodService.KEY_ACCESSIBILITY_SEEN, false)

    private fun teardown() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Throwable) {
                Log.w(TAG, "overlay already gone", e)
            }
        }
        overlayView = null
        session?.onUpdate = null
        session?.destroy()
        session = null
        viewHost?.destroy()
        viewHost = null
        minimized.value = false
        pasteWarning.value = false
        poofing.value = false
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // teardown() is the normal path; this catches the system killing us.
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {
            }
        }
        overlayView = null
        session?.onUpdate = null
        session?.destroy()
        session = null
        viewHost?.destroy()
        viewHost = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mutterboard is listening")
            .setContentText("Tap Stop in the overlay when you're done")
            .setSmallIcon(R.drawable.ic_mutterboard_mark)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        // Declaring the microphone type without holding RECORD_AUDIO is a hard
        // error on Android 14+, and the overlay is reachable before the user has
        // granted it. Come up untyped in that case so the session can render its
        // own "Mic permission needed" state instead of the service dying here.
        val type = if (hasRecordAudioPermission()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        // Channels only exist from O; NotificationCompat ignores the id below it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // LOW so a dictation doesn't ping every time it starts; the notification
        // is a status indicator and a way back, not an alert.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dictation overlay",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MutterboardOverlay"
        private const val CHANNEL_ID = "dictation_overlay"
        private const val NOTIFICATION_ID = 1
        private const val CLIP_LABEL = "Mutterboard transcript"

        /** Where the puck starts out: clear of the gesture bar, not sitting on it. */
        private const val MINIMIZED_INSET_DP = 24
        private const val KEY_PUCK_COLUMN = "overlay_puck_column"
        private const val KEY_PUCK_Y = "overlay_puck_y"

        private const val COLUMN_LEFT = 0
        private const val COLUMN_CENTER = 1
        private const val COLUMN_RIGHT = 2
        private val COLUMNS = listOf(COLUMN_LEFT, COLUMN_CENTER, COLUMN_RIGHT)

        /**
         * How far the puck sits off the screen edge in its outer columns. Flush
         * against the edge reads as something that has fallen off the screen
         * rather than as something parked there, and on a curved display it is
         * also where the glass stops being flat.
         */
        private const val PUCK_EDGE_PADDING_DP = 16

        /** Must match MinimizedPuck's width - the columns are measured off it. */
        private const val PUCK_WIDTH_DP = 78

        /** How far the mist is allowed to travel past the puck it leaves. */
        const val POOF_MARGIN_DP = 56
        private const val KEY_PUCK_MODE = "overlay_puck_mode"
    }
}

/**
 * The three owners Compose insists on, for a view that belongs to a window rather
 * than to an Activity. Held RESUMED for as long as the overlay is up, because
 * there is nothing else that could ever pause it.
 */
private class OverlayViewHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
