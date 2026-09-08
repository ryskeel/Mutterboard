package com.example.mutterboard

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets the overlay put text into a field it doesn't own.
 *
 * The keyboard commits through an InputConnection, which the overlay has no
 * equivalent of — it deliberately never takes focus, so the field it's aiming at
 * belongs to some other app entirely. Reaching that field from outside is what
 * an AccessibilityService is for.
 *
 * Optional by design. With it off, [OverlayDictationService] still puts every
 * transcript on the clipboard; this only adds the automatic paste when a text
 * field happens to be focused.
 */
class MutterboardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // Nothing is driven by events; the service exists purely so the overlay can
    // reach the focused node on demand. Both callbacks are required overrides.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Pastes whatever is on the clipboard into the currently focused text field,
     * returning false when there isn't one to paste into.
     *
     * ACTION_PASTE rather than ACTION_SET_TEXT on purpose. SET_TEXT replaces the
     * entire field, so matching the keyboard's insert-at-cursor behavior would
     * mean reading the node's text, splicing at the selection and restoring the
     * cursor afterward — the exact sequence that goes wrong in Compose and
     * WebView fields. Paste already inserts at the cursor and replaces the
     * selection, which is what commitText does.
     */
    fun pasteIntoFocusedField(): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            Log.d(TAG, "paste skipped: nothing focused")
            return false
        }
        return try {
            if (!node.isEditable) {
                Log.d(TAG, "paste skipped: focused node is not editable")
                return false
            }
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "paste performed=$pasted")
            pasted
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    companion object {
        private const val TAG = "MutterboardA11y"

        /**
         * The running instance, or null when the user hasn't enabled the service.
         * An AccessibilityService is constructed by the system, so a static handle
         * is the only way for the overlay to reach it.
         */
        @Volatile
        var instance: MutterboardAccessibilityService? = null
            private set
    }
}
