package com.example.mutterboard

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * The entry point for phones with no button to map.
 *
 * A side button is the best way in, but plenty of devices have nothing
 * remappable, and the launcher icon means leaving whatever app you're in to go
 * find it. A Quick Settings tile is reachable from inside any app on any phone:
 * swipe, tap, start talking.
 *
 * Goes through [OverlayLauncherActivity] rather than starting the service
 * directly. A tile click doesn't make us foreground, and a microphone foreground
 * service can't be started from the background — routing through the activity
 * reuses the one path that is already allowed to start it.
 */
class MutterboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        // A tile is tappable from the lock screen, where launching an activity
        // silently does nothing until the device is unlocked.
        if (isLocked) {
            unlockAndRun { launchOverlay() }
        } else {
            launchOverlay()
        }
    }

    private fun launchOverlay() {
        val intent = Intent(this, OverlayLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
