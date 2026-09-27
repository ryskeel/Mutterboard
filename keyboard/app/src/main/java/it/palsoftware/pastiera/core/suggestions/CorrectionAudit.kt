package it.palsoftware.pastiera.core.suggestions

import android.content.Context
import it.palsoftware.pastiera.BuildConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Mutterboard: every autocorrection the keyboard makes, and every one Ry takes
 * back with an immediate backspace, appended to a file on the phone. The
 * scorecards are all measured on Tatoeba, so gaps in his own typing only
 * surfaced when he happened to notice one; the undone lines are that list.
 *
 * Debug builds only, because the lines are his words. Releases are debug
 * builds, so this is his phone. scripts/autocorrect-audit.sh pulls and sums it.
 */
object CorrectionAudit {
    private const val FILE = "autocorrect-audit.tsv"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        if (BuildConfig.DEBUG) file = File(context.filesDir, FILE)
    }

    /** [source] names the path that made it; [previous]/[next] are the words either side, when known. */
    fun corrected(source: String, previous: String?, typed: String, fixed: String, next: String? = null) =
        append("corrected", source, previous, typed, fixed, next)

    fun undone(source: String, typed: String, fixed: String) =
        append("undone", source, null, typed, fixed, null)

    private fun append(event: String, source: String, previous: String?, typed: String, fixed: String, next: String?) {
        val target = file ?: return
        val line = listOf(System.currentTimeMillis().toString(), event, source, previous.orEmpty(), typed, fixed, next.orEmpty())
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n"
        writer.execute {
            runCatching {
                // Keep the newer half once it grows, so it never needs clearing.
                if (target.length() > MAX_BYTES) {
                    val lines = target.readLines()
                    target.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
                }
                target.appendText(line)
            }
        }
    }
}
