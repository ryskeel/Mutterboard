package it.palsoftware.pastiera.core.suggestions

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * Words spelled wrong on purpose, as opposed to keys hit wrong: "nutrician",
 * "definately", "seperate". AutocorrectScorecardTest generates finger slips;
 * these are real people's misspellings, from Wikipedia's list of the common
 * ones, and they are mostly spelling by sound, which keyboard distance knows
 * nothing about.
 *
 *   ./gradlew :keyboard:testDebugUnitTest --tests '*SpellingScorecardTest*'
 *
 * Report: keyboard/app/build/spelling-scorecard.txt
 *
 * The list is split in two: tune on "tune", judge on "held out", so a cost
 * table cannot be fitted to the very words it is scored on. Run
 * AutocorrectScorecardTest too: its names-and-slang count is what a looser
 * corrector pays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpellingScorecardTest {

    private data class Case(val typed: String, val intended: String)

    @Test
    fun scorecard() {
        val repository = EnglishFixture.repository(RuntimeEnvironment.getApplication())
        val bigrams = EnglishFixture.bigrams()
        val known = EnglishFixture.knownWords()
        val model = TypoModel(repository, Locale.ENGLISH, knownWords = { known }) { bigrams }.also { it.buildSoundAlikes() }

        val cases = misspellings().filter { c ->
            c.intended.all { it in 'a'..'z' } && c.typed.all { it in 'a'..'z' } &&
                repository.isKnownWord(c.intended) && !repository.isKnownWord(c.typed) &&
                bigrams.everydayWordCount(c.typed) < 3
        }
        val tune = cases.filterIndexed { i, _ -> i % 2 == 0 }
        val heldOut = cases.filterIndexed { i, _ -> i % 2 == 1 }

        val report = StringBuilder("=== Spelling scorecard (${cases.size} misspellings) ===\n")
        for ((name, subset) in listOf("tune" to tune, "held out" to heldOut)) {
            var fixed = 0; var wrong = 0
            val wrongs = mutableListOf<String>(); val misses = mutableListOf<String>()
            for (c in subset) {
                val out = model.correct(c.typed)?.word
                when {
                    out == null -> { if (misses.size < 25) misses += "${c.typed}->${c.intended}" }
                    out.equals(c.intended, ignoreCase = true) -> fixed++
                    else -> { wrong++; if (wrongs.size < 25) wrongs += "${c.typed}->$out (${c.intended})" }
                }
            }
            report.append("\n[$name] fixed ${pct(fixed, subset.size)}  wrong ${pct(wrong, subset.size)}  left ${pct(subset.size - fixed - wrong, subset.size)}\n")
            report.append("  wrong: ${wrongs.joinToString("  ")}\n")
            report.append("  missed: ${misses.joinToString("  ")}\n")
        }
        // Ry's own, from the phone.
        report.append("\n[Ry's]\n")
        for ((typed, before) in listOf("nuutriciaan" to "a", "nutrician" to "a", "sandpoint" to "nutrition")) {
            report.append("  $before $typed -> ${model.correct(typed, before)?.word}\n")
        }
        println(report)
        File("build/spelling-scorecard.txt").writeText(report.toString())
    }

    private fun pct(n: Int, total: Int) = if (total == 0) "  -  " else "%3d%%".format(n * 100 / total)

    private fun misspellings(): List<Case> = EnglishFixture.misspellings().map { Case(it.first, it.second) }
}
