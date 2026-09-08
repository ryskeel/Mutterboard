package com.example.mutterboard

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
        registerShortcutButton()
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
     * Makes Android's floating shortcut button start a dictation.
     *
     * The button is not something the app asks for — Android attaches its
     * shortcut to any service the user enables — so for a long time it sat on
     * screen doing nothing and setup told the user to go and remove it. On a
     * Pixel that is the wrong trade: there is no side button to map and Quick Tap
     * is unreliable, which leaves this as the only press-anywhere way in. So the
     * button becomes an entry point rather than litter.
     *
     * The press arrives through a callback rather than an override, which is the
     * only route an AccessibilityService has to it, and it only arrives at all
     * because the service config asks for the button.
     */
    private fun registerShortcutButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) =
                    startDictation()

                override fun onAvailabilityChanged(
                    controller: AccessibilityButtonController,
                    available: Boolean,
                ) = Unit
            }
        )
    }

    /**
     * Routed through [OverlayLauncherActivity] like the tile, and for the same
     * reason: a microphone foreground service cannot be started from the
     * background, and that activity is the one path already allowed to start it.
     * The launch itself is permitted because the app holds SYSTEM_ALERT_WINDOW.
     *
     * Ignored when the user has chosen the keyboard. The service can outlive that
     * choice — Android keeps it enabled — and a button that started an overlay
     * the user turned off would be the app arguing with its own settings screen.
     */
    private fun startDictation() {
        if (!isOverlayLauncherEnabled(this)) {
            Log.d(TAG, "shortcut pressed while the overlay is off; ignoring")
            return
        }
        startActivity(
            Intent(this, OverlayLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Puts [text] into the currently focused text field, returning false when
     * there isn't one to put it in.
     *
     * Tries paste first: [text] is already on the clipboard, and ACTION_PASTE
     * inserts at the cursor and replaces the selection, which is exactly what
     * commitText does on the keyboard path. Plenty of fields refuse it though
     * (it turned up as performed=false on the first real test), so a field that
     * says no falls back to splicing the text in by hand.
     */
    fun insertIntoFocusedField(text: String): Boolean {
        val node = findEditableFocus()
        if (node == null) {
            Log.d(TAG, "insert skipped: no editable field focused")
            return false
        }
        return try {
            Log.d(TAG, "target: ${node.className} pkg=${node.packageName} paste=${node.canPaste()}")
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Log.d(TAG, "inserted by paste")
                return true
            }
            val spliced = spliceAtCursor(node, text)
            Log.d(TAG, "paste refused, splice=$spliced")
            spliced
        } catch (e: Throwable) {
            Log.w(TAG, "insert failed", e)
            false
        }
    }

    private fun AccessibilityNodeInfo.canPaste(): Boolean =
        actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }

    /**
     * Finds the editable node holding input focus.
     *
     * [findFocus] alone was not enough in practice: it kept landing on a
     * non-editable node, because the field we are aiming at lives in a different
     * window from the one the framework considers active while the overlay is up.
     * So fall through to walking the window list (which is why the service
     * declares flagRetrieveInteractiveWindows) and finally to a scan of the
     * active window for anything editable claiming focus.
     */
    private fun findEditableFocus(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }

        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
            focusedEditableIn(root)?.let { return it }
        }

        rootInActiveWindow?.let { root -> focusedEditableIn(root)?.let { return it } }
        return null
    }

    /** Depth-first hunt for an editable node that claims focus. */
    private fun focusedEditableIn(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            focusedEditableIn(child, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Inserts [text] at the cursor by rewriting the field's whole contents, for
     * fields that refuse ACTION_PASTE.
     *
     * This is the sequence paste exists to avoid — SET_TEXT replaces everything,
     * so the surrounding text has to be put back by hand and the caret restored
     * afterward. It is a fallback precisely because it is the fragile path.
     */
    private fun spliceAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        node.refresh()
        val existing = node.text?.toString() ?: ""
        // A field with no reported selection gets the text appended; guessing a
        // caret position we cannot see would be worse than landing at the end.
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        val start = if (rawStart in 0..existing.length) rawStart else existing.length
        val end = if (rawEnd in start..existing.length) rawEnd else start

        val updated = existing.substring(0, start) + text + existing.substring(end)
        val setArgs = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                updated,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false

        // Best effort: the node is stale after SET_TEXT, so a failure to move the
        // caret is not a failure to insert. Leaving the caret at the end of the
        // field is survivable; losing the text is not.
        val caret = start + text.length
        node.refresh()
        val selectArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        }
        val moved = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
        if (!moved) Log.d(TAG, "text inserted but caret could not be restored")
        return true
    }

    companion object {
        private const val TAG = "MutterboardA11y"
        // Deep enough for real view hierarchies, shallow enough that a pathological
        // tree can't stall the commit.
        private const val MAX_TREE_DEPTH = 60

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
