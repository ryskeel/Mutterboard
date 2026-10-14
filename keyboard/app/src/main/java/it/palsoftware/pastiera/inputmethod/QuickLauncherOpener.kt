package it.palsoftware.pastiera.inputmethod

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import it.palsoftware.pastiera.SettingsManager

object QuickLauncherOpener {
    private const val TAG = "QuickLauncherOpener"
    private const val NIAGARA_PACKAGE = "bitpit.launcher"
    private val NIAGARA_SEARCH_URI: Uri = Uri.parse("niagara://search")
    private const val KEYBOARD_LAUNCHER_PRO_PACKAGE = "com.illuminati.keyboardlauncher"

    fun open(context: Context): Boolean {
        return when (SettingsManager.getQuickLauncherBehavior(context)) {
            SettingsManager.QUICK_LAUNCHER_BEHAVIOR_NIAGARA ->
                openNiagaraSearch(context) || openPastieraQuickLauncher(context)
            SettingsManager.QUICK_LAUNCHER_BEHAVIOR_KEYBOARD_LAUNCHER_PRO ->
                openKeyboardLauncherPro(context) || openPastieraQuickLauncher(context)
            else -> openPastieraQuickLauncher(context)
        }
    }

    private fun openPastieraQuickLauncher(context: Context): Boolean {
        return try {
            val intent = QuickLauncherActivity.createToggleIntent(context)
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Error opening Pastiera QuickLauncher", error)
            false
        }
    }

    private fun openNiagaraSearch(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, NIAGARA_SEARCH_URI).apply {
                setPackage(NIAGARA_PACKAGE)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Error opening Niagara search; falling back to Pastiera QuickLauncher", error)
            false
        }
    }

    // No search link is known for Keyboard Launcher Pro the way niagara:// is,
    // but a keyboard-first launcher opens with its search box ready, so opening
    // the app stands in for the search. A home launcher may hide its LAUNCHER
    // entry, hence the HOME fallback.
    private fun openKeyboardLauncherPro(context: Context): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(KEYBOARD_LAUNCHER_PRO_PACKAGE)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                setPackage(KEYBOARD_LAUNCHER_PRO_PACKAGE)
            }.takeIf { it.resolveActivity(pm) != null }
            ?: return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Error opening Keyboard Launcher Pro; falling back to Pastiera QuickLauncher", error)
            false
        }
    }
}
