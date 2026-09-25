package it.palsoftware.pastiera.inputmethod.mutterboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Mutterboard fix: launchers that keep a search box focused on the home screen
 * (Niagara's "Search apps", so a hardware keyboard can type-to-search) made the
 * auto-show setting put the full bar up every time you went home. Pastierina
 * mode only hid it by being small.
 *
 * The home screen never auto-shows. Typing on the keys still reaches the search
 * box, and the bar comes up with the first keypress like anywhere else.
 */
object HomeScreen {
    fun isHomeApp(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }
}
