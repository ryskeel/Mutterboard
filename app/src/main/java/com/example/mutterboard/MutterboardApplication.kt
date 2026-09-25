package com.example.mutterboard

import android.app.Application
import it.palsoftware.pastiera.inputmethod.mutterboard.voice.ExternalDictation

class MutterboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The physical keyboard lives in a library that cannot see this module;
        // this is how its mic button reaches Mutterboard's dictation.
        ExternalDictation.factory = { context, callbacks -> KeyboardDictation(context, callbacks) }
    }
}
