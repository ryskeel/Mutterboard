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

    private fun englishRepository(context: Context): AndroidDictionaryRepository {
        val repository = AndroidDictionaryRepository(
            context = context,
            assets = context.assets,
            userDictionaryStore = UserDictionaryStore(),
            baseLocale = Locale.ENGLISH
        )
        // The compiled .dict is what the phone loads; the JSON beside it is only
        // read here for the list of common words to misspell.
        kotlinx.coroutines.runBlocking { repository.loadSerializedFromFile(assetFile("dictionaries_serialized/en_base.dict")) }
        val extras = repository.loadLocaleExtras()
        check(extras.isNotEmpty()) { "en_extra.json did not load" }
        repository.index(extras, keepExisting = true)
        repository.addToSymSpell(extras)
        repository.isReady = true
        return repository
    }

    /** Grid over the tuning knobs; prints one line per combination. Opt-in: SCORECARD_SWEEP=1 */
    @Test
    fun sweep() {
        if (System.getenv("SCORECARD_SWEEP") != "1") return
        val context = RuntimeEnvironment.getApplication()
        val repository = englishRepository(context)
        val cases = handWritten + generateTypos(loadEnglish(), repository)
        val lines = StringBuilder("fpl  thr  short maxc | fixed wrong changed\n")
        for (fpl in listOf(13.0, 16.0, 20.0, 26.0))
        for (thr in listOf(3.0, 4.0, 5.0, 6.0))
        for (short in listOf(6.0, 7.0, 8.0, 9.0))
        for (maxCost in listOf(7.0, 9.0)) {
            val model = TypoModel(repository, Locale.ENGLISH, TypoModel.Tuning(
                frequencyPerLogUnit = fpl, threshold = thr * 13.0 / fpl, shortWordThreshold = short * 13.0 / fpl, maxCost = maxCost))
            var fixed = 0; var wrong = 0
            for (c in cases) {
                val out = model.correct(c.typed)?.word
                if (out == c.intended) fixed++ else if (out != null) wrong++
            }
            val changed = leaveAlone.count { w ->
                !repository.isKnownWord(w) && model.correct(w) != null
            }
            lines.append("%4.0f %4.1f %5.1f %4.0f | %4d%% %4d%% %3d\n".format(
                fpl, thr, short, maxCost, fixed * 100 / cases.size, wrong * 100 / cases.size, changed))
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

    private fun assetFile(path: String): File = listOf(
        File("src/main/assets/common/$path"),
        File("keyboard/app/src/main/assets/common/$path")
    ).first { it.exists() }

    private fun generateTypos(entries: List<DictionaryEntry>, repository: DictionaryRepository): List<Case> {
        val random = Random(20260925)
        // Common everyday words, not the function words at the very top.
        val words = entries.asSequence()
            .map { it.word }
            .filter { w -> w.length >= 4 && w.all { it in 'a'..'z' } }
            .drop(50)
            .take(400)
            .toList()
        val out = mutableListOf<Case>()
        for (word in words) {
            val candidates = listOfNotNull(
                neighbourSwap(word, random)?.let { Case(it, word, "neighbour key") },
                extraNeighbour(word, random)?.let { Case(it, word, "extra key") },
                droppedLetter(word, random)?.let { Case(it, word, "dropped letter") },
                swappedPair(word, random)?.let { Case(it, word, "swapped pair") },
                doubleLetter(word, random)?.let { Case(it, word, "double letter") },
            )
            out += candidates.filter { !repository.isKnownWord(it.typed) }
        }
        return out
    }

    // Standard QWERTY grid, staggered like the Titan's hardware keys.
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val neighbours: Map<Char, List<Char>> by lazy {
        val pos = mutableMapOf<Char, Pair<Double, Double>>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> pos[ch] = r.toDouble() to c + r * 0.5 } }
        pos.mapValues { (ch, p) ->
            pos.filter { (other, q) ->
                other != ch && kotlin.math.abs(p.first - q.first) <= 1.0 && kotlin.math.abs(p.second - q.second) <= 1.0
            }.keys.toList()
        }
    }

    private fun neighbourSwap(word: String, random: Random): String? {
        val i = 1 + random.nextInt(word.length - 1)
        val n = neighbours[word[i]] ?: return null
        return word.substring(0, i) + n.random(random) + word.substring(i + 1)
    }

    private fun extraNeighbour(word: String, random: Random): String? {
        val i = random.nextInt(word.length)
        val n = neighbours[word[i]] ?: return null
        val at = i + 1
        return word.substring(0, at) + n.random(random) + word.substring(at)
    }

    private fun droppedLetter(word: String, random: Random): String? {
        val i = 1 + random.nextInt(word.length - 1)
        return word.removeRange(i, i + 1)
    }

    private fun swappedPair(word: String, random: Random): String? {
        val i = 1 + random.nextInt(word.length - 2)
        if (word[i] == word[i + 1]) return null
        return word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
    }

    private fun doubleLetter(word: String, random: Random): String? {
        val doubled = (0 until word.length - 1).firstOrNull { word[it] == word[it + 1] }
        if (doubled != null) return word.removeRange(doubled, doubled + 1)
        val i = 1 + random.nextInt(word.length - 1)
        return word.substring(0, i + 1) + word[i] + word.substring(i + 1)
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
    )

    private val leaveAlone = listOf(
        "Ry", "Mutterboard", "Gboard", "Groq", "Qwen", "Checkr", "Unihertz", "Kika",
        "lol", "lmao", "tbh", "idk", "ngl", "omw", "brb", "imo", "btw", "rn", "fwiw",
        "gonna", "wanna", "gotta", "kinda", "sorta", "dunno", "yeah", "nah", "yep", "nope",
        "haha", "hahaha", "hmm", "ugh", "tho", "bruh", "cuz", "ya", "yall", "ok",
        "Venmo", "Spotify", "Airbnb", "TikTok", "iPhone", "Pixel", "WhatsApp", "Reddit",
        "adb", "apk", "repo", "json", "regex", "localhost", "async",
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
