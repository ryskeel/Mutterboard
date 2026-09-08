package com.example.mutterboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        session.onUpdate = { snapshot.value = it }

        return ComposeView(this).apply {
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
                        onModeChanged = { session.modeChanged(it) },
                    )
                }
            }
        }
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
    private fun overlayLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val bandHeight = (resources.displayMetrics.heightPixels * BAND_FRACTION).toInt()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            bandHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
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
        // How much of the screen the band takes. The rest stays visible, and
        // stays touchable.
        private const val BAND_FRACTION = 0.42f
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
