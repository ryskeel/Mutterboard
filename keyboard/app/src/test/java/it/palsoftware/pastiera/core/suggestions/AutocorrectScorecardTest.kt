package it.palsoftware.pastiera.core.suggestions

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Mutterboard's measure of "as good as Gboard", run against the real English
 * dictionary rather than a handful of fakes. It prints a scorecard instead of
 * asserting one, because the point is to compare settings and changes side by
 * side; the floors at the bottom only catch a change that makes things worse.
 *
 *   ./gradlew :keyboard:testDebugUnitTest --tests '*AutocorrectScorecardTest*' -i
 *
 * Typos are generated from the kinds of slips a thumb makes on a small hardware
 * keyboard (a neighbouring key, an extra neighbouring key, a dropped letter, two
 * letters swapped, a double letter gained or lost), seeded so runs compare. A
 * typo that happens to be a real word is dropped: that is a different problem.
 *
 * The other half is words the keyboard must leave alone: names, slang and
 * brands that are not in the dictionary. Fixing every typo is easy if you are
 * allowed to rewrite everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutocorrectScorecardTest {

    private data class Case(val typed: String, val intended: String, val kind: String)

    private data class Tally(var fixed: Int = 0, var wrong: Int = 0, var missed: Int = 0) {
        val total get() = fixed + wrong + missed
    }

    private fun englishRepository(context: Context) = EnglishFixture.repository(context)

    /** Grid over the tuning knobs; prints one line per combination. Opt-in: SCORECARD_SWEEP=1 */
    @Test
    fun sweep() {
        if (System.getenv("SCORECARD_SWEEP") != "1") return
        val context = RuntimeEnvironment.getApplication()
        val repository = englishRepository(context)
        val cases = handWritten + generateTypos(loadEnglish(), repository)
        val lines = StringBuilder("fixed%/wrong%\n")
        for (fpl in listOf(20.0))
        for (thr in listOf(5.0))
        for (dropped in listOf(0.0, 0.5, 1.0, 1.5, 2.0))
        for (two in listOf(4.0, 5.0, 6.0, 99.0)) {
            val model = TypoModel(repository, Locale.ENGLISH, TypoModel.Tuning(
                firstLetterDropped = dropped, twoLetterThreshold = two))
            fun rate(subset: List<Case>): String {
                var fixed = 0; var wrong = 0
                for (c in subset) {
                    val out = model.correct(c.typed)?.word
                    if (out == c.intended) fixed++ else if (out != null) wrong++
                }
                return "%3d%%/%2d%%".format(fixed * 100 / subset.size.coerceAtLeast(1), wrong * 100 / subset.size.coerceAtLeast(1))
            }
            val changed = leaveAlone.filter { w -> !repository.isKnownWord(w) && model.correct(w) != null }
            lines.append("dropped %.1f twoThr %4.1f | all %s  first %s  2-letter %s  changed %d %s\n".format(
                dropped, two, rate(cases), rate(cases.filter { it.kind == "dropped first" }),
                rate(cases.filter { it.typed.length == 2 }), changed.size, changed.joinToString(",")))
        }
        File("build/autocorrect-sweep.txt").writeText(lines.toString())
    }

    @Test
    fun scorecard() {
        val context = RuntimeEnvironment.getApplication()
        val entries = loadEnglish()
        val repository = englishRepository(context)

        val cases = handWritten + generateTypos(entries, repository)
        val scenarios = linkedMapOf(
            "shipped defaults" to SuggestionSettings(),
            "fix on space, distance 1" to SuggestionSettings(autoReplaceOnSpaceEnter = true, maxAutoReplaceDistance = 1),
        )
        val typoModel = TypoModel(repository, Locale.ENGLISH)

        val report = StringBuilder("\n=== Autocorrect scorecard (${cases.size} typos, ${leaveAlone.size} words to leave alone) ===\n")
        val runs = scenarios.map { (name, settings) -> Triple(name, settings, null as TypoModel?) } +
            Triple("Mutterboard typo model", SuggestionSettings(autoReplaceOnSpaceEnter = true), typoModel)
        for ((name, settings, model) in runs) {
            val byKind = linkedMapOf<String, Tally>()
            val wrongExamples = mutableListOf<String>()
            val missExamples = mutableListOf<String>()
            for (case in cases) {
                val tally = byKind.getOrPut(case.kind) { Tally() }
                val out = typeAndSpace(context, repository, settings, model, case.typed)
                when (out) {
                    case.intended -> tally.fixed++
                    case.typed -> {
                        tally.missed++
                        if (missExamples.size < 12) missExamples += "${case.typed}->${case.intended}"
                    }
                    else -> {
                        tally.wrong++
                        if (wrongExamples.size < 12) wrongExamples += "${case.typed}->$out (meant ${case.intended})"
                    }
                }
            }
            val changed = leaveAlone.mapNotNull { word ->
                val out = typeAndSpace(context, repository, settings, model, word)
                if (out != word) "$word->$out" else null
            }
            val all = byKind.values.fold(Tally()) { acc, t ->
                acc.fixed += t.fixed; acc.wrong += t.wrong; acc.missed += t.missed; acc
            }
            report.append("\n[$name]\n")
            report.append("  fixed ${pct(all.fixed, all.total)}  wrong word ${pct(all.wrong, all.total)}  left alone ${pct(all.missed, all.total)}\n")
            byKind.forEach { (kind, t) ->
                report.append("    %-14s fixed %s  wrong %s\n".format(kind, pct(t.fixed, t.total), pct(t.wrong, t.total)))
            }
            report.append("  wrongly changed ${changed.size}/${leaveAlone.size} words it should leave alone\n")
            if (changed.isNotEmpty()) report.append("    ${changed.joinToString("  ")}\n")
            if (wrongExamples.isNotEmpty()) report.append("  wrong: ${wrongExamples.joinToString("  ")}\n")
            if (missExamples.isNotEmpty()) report.append("  missed: ${missExamples.joinToString("  ")}\n")
        }
        println(report)
        File("build/autocorrect-scorecard.txt").writeText(report.toString())
    }

    private fun pct(n: Int, total: Int) = if (total == 0) "  -  " else "%3d%%".format(n * 100 / total)

    private fun typeAndSpace(
        context: Context,
        repository: DictionaryRepository,
        settings: SuggestionSettings,
        typoModel: TypoModel?,
        typed: String
    ): String {
        val controller = AutoReplaceController(
            repository = repository,
            suggestionEngine = SuggestionEngine(repository, Locale.ENGLISH),
            settingsProvider = { settings },
            typoModel = typoModel
        )
        val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
        tracker.setWord(typed)
        val connection = FakeInputConnection(context, typed)
        controller.handleBoundary(
            keyCode = KeyEvent.KEYCODE_SPACE,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE),
            tracker = tracker,
            inputConnection = connection
        )
        return connection.text.trimEnd()
    }

    private fun loadEnglish(): List<DictionaryEntry> {
        val file = assetFile("dictionaries/en_base.json")
        val array = JSONArray(file.readText())
        return (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            DictionaryEntry(obj.getString("w"), obj.optInt("f", 1), SuggestionSource.MAIN)
        }
    }

    private fun assetFile(path: String) = EnglishFixture.assetFile(path)

    private fun generateTypos(entries: List<DictionaryEntry>, repository: DictionaryRepository): List<Case> {
        val random = Random(20260925)
        // Common everyday words, not the function words at the very top.
        val words = entries.asSequence()
            .map { it.word }
            .filter { w -> w.length >= 3 && w.all { it in 'a'..'z' } }
            .drop(50)
            .toList()
            .let { it.take(400) + it.drop(400).take(4000).filterIndexed { i, _ -> i % 10 == 0 } }
        val out = mutableListOf<Case>()
        for (word in words) {
            val candidates = listOfNotNull(
                TypoGenerator.neighbourSwap(word, random)?.let { Case(it, word, "neighbour key") },
                TypoGenerator.extraNeighbour(word, random)?.let { Case(it, word, "extra key") },
                TypoGenerator.droppedLetter(word, random)?.let { Case(it, word, "dropped letter") },
                TypoGenerator.swappedPair(word, random)?.let { Case(it, word, "swapped pair") },
                TypoGenerator.doubleLetter(word, random)?.let { Case(it, word, "double letter") },
                TypoGenerator.droppedFirstLetter(word, random)?.let { Case(it, word, "dropped first") },
            )
            out += candidates.filter { !repository.isKnownWord(it.typed) }
        }
        return out
    }

    private val handWritten = listOf(
        Case("quyick", "quick", "hand"),
        Case("teh", "the", "hand"),
        Case("becuase", "because", "hand"),
        Case("recieve", "receive", "hand"),
        Case("definately", "definitely", "hand"),
        Case("tommorow", "tomorrow", "hand"),
        Case("wierd", "weird", "hand"),
        Case("thnaks", "thanks", "hand"),
        Case("somethign", "something", "hand"),
        Case("realy", "really", "hand"),
        Case("jsut", "just", "hand"),
        Case("waht", "what", "hand"),
        Case("abotu", "about", "hand"),
        Case("pepole", "people", "hand"),
        Case("comming", "coming", "hand"),
        // Typed on the Titan, 2026-09-25.
        Case("rsndomly", "randomly", "hand"),
        Case("typong", "typing", "hand"),
        Case("ight", "right", "hand"),
        Case("imes", "times", "hand"),
        Case("cn", "can", "hand"),
    )

    private val leaveAlone = listOf(
        "Ry", "Mutterboard", "Gboard", "Groq", "Qwen", "Checkr", "Unihertz", "Kika",
        "lol", "lmao", "tbh", "idk", "ngl", "omw", "brb", "imo", "btw", "rn", "fwiw",
        "gonna", "wanna", "gotta", "kinda", "sorta", "dunno", "yeah", "nah", "yep", "nope",
        "haha", "hahaha", "hmm", "ugh", "tho", "bruh", "cuz", "ya", "yall", "ok",
        "Venmo", "Spotify", "Airbnb", "TikTok", "iPhone", "Pixel", "WhatsApp", "Reddit",
        "adb", "apk", "repo", "json", "regex", "localhost", "async",
        "hmu", "wyd", "lmk", "gg", "tf", "af", "irl", "pov", "fomo", "yolo", "smh",
        "Ry", "Titan", "Niagara", "Pastiera", "Plektra", "Tatoeba", "Groq's",
    )

    private class FakeInputConnection(context: Context, initialText: String) :
        BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder(initialText)
        val text: String get() = buffer.toString()
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            buffer.delete((buffer.length - beforeLength).coerceAtLeast(0), buffer.length)
            return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
    }
}
