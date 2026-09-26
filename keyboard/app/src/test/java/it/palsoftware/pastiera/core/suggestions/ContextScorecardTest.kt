package it.palsoftware.pastiera.core.suggestions

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Autocorrect and next-word prediction with the previous word in view, on
 * held-out Tatoeba sentences the bigram table was not built from.
 *
 *   ./gradlew :keyboard:testDebugUnitTest --tests '*ContextScorecardTest*'
 *
 * Report: keyboard/app/build/context-scorecard.txt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContextScorecardTest {

    private data class Case(val previous: String?, val typed: String, val intended: String)

    @Test
    fun scorecard() {
        val repository = EnglishFixture.repository(RuntimeEnvironment.getApplication())
        val bigrams = EnglishFixture.bigrams()
        val sentences = EnglishFixture.heldOutSentences()
        val random = Random(20260925)

        val cases = sentences.mapNotNull { sentence ->
            val tokens = EnglishFixture.tokens(sentence)
            val candidates = tokens.indices.filter { i ->
                val w = tokens[i]
                w.length >= 3 && w.all { it in 'a'..'z' } && repository.isKnownWord(w)
            }
            if (candidates.isEmpty()) return@mapNotNull null
            val i = candidates[random.nextInt(candidates.size)]
            val (typo, _) = TypoGenerator.any(tokens[i], random) ?: return@mapNotNull null
            if (repository.isKnownWord(typo)) return@mapNotNull null
            Case(if (i == 0) null else tokens[i - 1], typo, tokens[i])
        }

        val report = StringBuilder("\n=== Context scorecard (${cases.size} typos in held-out sentences) ===\n")
        val weights = if (System.getenv("SCORECARD_SWEEP") == "1") listOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0) else listOf(0.0, TypoModel.Tuning().contextWeight)
        for (weight in weights.distinct()) {
            val model = TypoModel(repository, Locale.ENGLISH, TypoModel.Tuning(contextWeight = weight)) { bigrams }
            var fixed = 0; var wrong = 0
            val wrongExamples = mutableListOf<String>()
            for (c in cases) {
                val out = model.correct(c.typed, c.previous)?.word
                when (out) {
                    c.intended -> fixed++
                    null -> {}
                    else -> {
                        wrong++
                        if (wrongExamples.size < 10) wrongExamples += "${c.previous ?: "^"} ${c.typed}->$out (${c.intended})"
                    }
                }
            }
            report.append("context weight %.2f: fixed %d%%  wrong word %d%%\n".format(
                weight, fixed * 100 / cases.size, wrong * 100 / cases.size))
            if (weight == weights.last()) report.append("  wrong: ${wrongExamples.joinToString("  ")}\n")
        }

        // Next-word prediction: is the word the writer actually used among the
        // three the bar would offer, given only the word before it?
        var positions = 0; var top1 = 0; var top3 = 0
        for (sentence in sentences) {
            val tokens = EnglishFixture.tokens(sentence)
            for (i in tokens.indices) {
                val previous = if (i == 0) null else tokens[i - 1]
                val offered = bigrams.continuations(previous).take(3).map { it.first.lowercase(Locale.ROOT) }
                val actual = tokens[i].lowercase(Locale.ROOT)
                positions++
                if (offered.firstOrNull() == actual) top1++
                if (actual in offered) top3++
            }
        }
        report.append("next word: in the bar's first slot %d%%, anywhere in its three %d%% (%d positions)\n".format(
            top1 * 100 / positions, top3 * 100 / positions, positions))

        println(report)
        File("build/context-scorecard.txt").writeText(report.toString())
    }
}
