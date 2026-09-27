package com.example.mutterboard

import android.app.Application
import android.os.Handler
import android.os.Looper
import it.palsoftware.pastiera.AppPackageChangeMonitor
import it.palsoftware.pastiera.ClicksPowerKeyboardController
import it.palsoftware.pastiera.SettingsManager
import it.palsoftware.pastiera.inputmethod.mutterboard.voice.ExternalDictation
import it.palsoftware.pastiera.inputmethod.subtype.AdditionalSubtypeUtils

class MutterboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The physical keyboard lives in a library that cannot see this module;
        // this is how its mic button reaches Mutterboard's dictation.
        ExternalDictation.factory = { context, callbacks -> KeyboardDictation(context, callbacks) }
        startKeyboard()
    }

    // What PastieraApplication.onCreate does, since an app has one Application
    // and this is ours. Its software-keyboard-mode launcher shortcut is left
    // out: it would sit on Mutterboard's icon next to Settings.
    private fun startKeyboard() {
        SettingsManager.initializeAltShiftLayoutSwitchDefault(this)
        SettingsManager.enforceTitan2EliteRoundedCornersOnce(this)
        undoPillLayoutOnce()
        AppPackageChangeMonitor.register(this)
        ClicksPowerKeyboardController.initialize(this)
        Handler(Looper.getMainLooper()).post {
            AdditionalSubtypeUtils.registerAdditionalSubtypes(this)
        }
    }

    // The pill bar (dropped with the move to Pastiera stable) forced the
    // one-row Pastierina layout on everyone once. Put back Pastiera's default
    // for those installs; anyone who picks Pastierina later keeps it.
    private fun undoPillLayoutOnce() {
        val prefs = getSharedPreferences("mutterboard_keyboard", MODE_PRIVATE)
        if (!prefs.getBoolean("one_row_layout_applied_v1", false)) return
        SettingsManager.setStatusBarPresentationMode(this, SettingsManager.StatusBarPresentationMode.FULL_STATUS_BAR)
        prefs.edit().remove("one_row_layout_applied_v1").apply()
    }
}
