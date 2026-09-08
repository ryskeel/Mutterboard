package com.example.mutterboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast

/**
 * The thing a side-button mapper can point at.
 *
 * Those mappers only offer you launchable apps, so starting the overlay needs a
 * real activity even though there's nothing to show. This one starts the service
 * and finishes immediately; the visible part is the overlay the service floats.
 *
 * It also has to be the one to start the service. A foreground service that uses
 * the microphone can't be started from the background, so the press has to arrive
 * through something Android considers foreground — which is exactly what an
 * activity launch is.
 *
 * Disabled as a component until the user turns the overlay on in settings, so
 * nobody gets a second launcher icon for a feature they aren't using.
 */
class OverlayLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Both icons can be enabled at once on an install that predates the icon
        // changing hands, and this is the entry point such a user actually
        // presses, so it is the first chance to put that right.
        syncLauncherIcons(this)

        // Launching an activity normally takes input focus, which would close the
        // keyboard and drop the cursor in the field we are about to paste into —
        // the one thing this whole design exists to avoid. Coming up unfocusable
        // leaves the field below us untouched.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        if (!Settings.canDrawOverlays(this)) {
            // Can't float anything without this, and it's a settings screen the
            // user has to visit themselves. Send them there rather than failing
            // silently on a button press that appeared to do nothing.
            Toast.makeText(this, "Allow Mutterboard to display over other apps", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            finish()
            return
        }

        val intent = Intent(this, OverlayDictationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
    }
}
