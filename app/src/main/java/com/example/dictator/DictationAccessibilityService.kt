package com.example.dictator

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import android.view.accessibility.AccessibilityWindowInfo

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DictationAccessibilityService? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun getKeyboardHeight(): Int {
        val imeWindow = windows.find { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return 0
        val bounds = Rect()
        imeWindow.getBoundsInScreen(bounds)
        return bounds.height()
    }

    fun pasteText(text: String) {
        mainHandler.post {
            val focused = rootInActiveWindow?.findFocus(FOCUS_INPUT) ?: return@post
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("dictated", text))
            focused.performAction(ACTION_PASTE)
        }
    }
}
