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
import android.os.IBinder
import android.util.Log
import android.view.Gravity
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
     * Where the puck was left, as insets from the bottom-right corner in px.
     *
     * Remembered across dictations because where the band is in the way is a fact
     * about the user's screen, not about this one recording; making them drag it
     * clear again every time would be the annoyance minimizing exists to remove.
     */
    private var puckX = 0
    private var puckY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
        puckX = prefs.getInt(KEY_PUCK_X, MINIMIZED_INSET_DP.dpToPx())
        // Clear of the navigation bar as well as the screen edge: the overlay
        // window now runs to the bottom of the display, so an inset measured from
        // the edge alone would park the puck on top of the gesture pill.
        puckY = prefs.getInt(KEY_PUCK_Y, MINIMIZED_INSET_DP.dpToPx() + bottomInsetPx())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Already up: a second press shouldn't stack another overlay on the first.
        if (overlayView != null) return START_NOT_STICKY

        startInForeground()

        val s = DictationSession(this, this)
        session = s
        val view = createBandView(s)
        overlayView = view
        windowManager = getSystemService(WindowManager::class.java)
        try {
            windowManager?.addView(view, overlayLayoutParams())
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
            if (it.state != DictationSession.State.RECORDING &&
                it.state != DictationSession.State.TRANSCRIBING
            ) {
                setMinimized(false)
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
                        bottomInset = (bottomInsetPx() / resources.displayMetrics.density).dp,
                        onMinimizedChanged = { setMinimized(it) },
                        onModeChanged = { session.modeChanged(it) },
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
     * Lets the collapsed puck be dragged anywhere on the screen, and treats a
     * press that never travelled as a tap to bring the band back.
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

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = minimized.value

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!minimized.value) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = puckX
                    startY = puckY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // Below the slop it is still a tap: a thumb never presses
                    // perfectly still.
                    if (!dragging && abs(dx) + abs(dy) > slop) dragging = true
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        // Insets from the bottom-right corner, so both axes run
                        // against the finger.
                        puckX = (startX - dx).toInt()
                            .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
                        puckY = (startY - dy).toInt()
                            .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
                        moveOverlay()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) savePuckPosition() else setMinimized(false)
                }
                MotionEvent.ACTION_CANCEL -> {
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
            .putInt(KEY_PUCK_X, puckX)
            .putInt(KEY_PUCK_Y, puckY)
            .apply()
    }

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
     */
    private fun setMinimized(value: Boolean) {
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
            // Wherever the user last dragged the puck to.
            if (minimized) {
                x = puckX
                y = puckY
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
        val pasted = MutterboardAccessibilityService.instance?.insertIntoFocusedField(text) ?: false
        Log.d(TAG, "commit pasted=$pasted")
        // No "copied" toast on the clipboard-only path: Android already shows its
        // own clipboard confirmation on every write, and two notices for one
        // event is worse than none.
    }

    override fun dismiss() {
        teardown()
    }

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
        private const val KEY_PUCK_X = "overlay_puck_x"
        private const val KEY_PUCK_Y = "overlay_puck_y"
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
