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

    /**
     * Words that are also apostrophe-less contractions. Every use of either
     * form in the held-out text is typed without its apostrophe, and the
     * keyboard has to put back exactly the ones the writer meant.
     */
    private fun contractions(bigrams: BigramModel): String {
        val pairs = mapOf("ill" to "i'll", "its" to "it's", "lets" to "let's", "cant" to "can't",
            "wed" to "we'd", "shed" to "she'd", "shell" to "she'll", "id" to "i'd")
        val right = mutableMapOf<String, IntArray>() // [rule right, context right, total]
        val misses = mutableListOf<String>()
        for (sentence in EnglishFixture.heldOutSentences()) {
            val tokens = EnglishFixture.tokens(sentence)
            for (i in tokens.indices) {
                val actual = tokens[i].lowercase(Locale.ROOT)
                val plain = pairs.keys.firstOrNull { it == actual || pairs[it] == actual } ?: continue
                val contraction = pairs.getValue(plain)
                val previous = if (i == 0) null else tokens[i - 1]
                val chosen = if (bigrams.likelihood(previous, plain) > bigrams.likelihood(previous, contraction)) plain else contraction
                val tally = right.getOrPut(plain) { IntArray(3) }
                if (actual == contraction) tally[0]++
                if (chosen == actual) tally[1]++ else if (misses.size < 12) misses += "${previous ?: "^"} $plain->$chosen ($actual)"
                tally[2]++
            }
        }
        val out = StringBuilder("apostrophe words (typed without it; fixed rule vs previous word):\n")
        right.forEach { (plain, t) ->
            out.append("  %-6s rule %3d%%  context %3d%%  (%d uses)\n".format(plain, t[0] * 100 / t[2], t[1] * 100 / t[2], t[2]))
        }
        out.append("  misses: ${misses.joinToString("  ")}\n")
        return out.toString()
    }

    private data class Case(val previous: String?, val typed: String, val intended: String)

    @Test
    fun scorecard() {
        val repository = EnglishFixture.repository(RuntimeEnvironment.getApplication())
        val bigrams = EnglishFixture.bigrams()
        val sentences = EnglishFixture.heldOutSentences().take(4000)
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
        val shipped = TypoModel.Tuning()
        val tunings = if (System.getenv("SCORECARD_SWEEP") == "1") {
            listOf(0.0, 0.5, 1.0, 2.0).flatMap { dropped ->
                listOf(3.0, 4.0, 5.0, 99.0).map { two -> shipped.copy(firstLetterDropped = dropped, twoLetterThreshold = two) }
            }
        } else {
            listOf(shipped.copy(contextWeight = 0.0), shipped)
        }
        val firstDropped = cases.filter { it.typed == it.intended.drop(1) }
        val twoLetter = cases.filter { it.typed.length == 2 }
        for (tuning in tunings) {
            val weight = tuning.contextWeight
            val model = TypoModel(repository, Locale.ENGLISH, tuning) { bigrams }
            fun rate(subset: List<Case>): String {
                var f = 0; var w = 0
                for (c in subset) {
                    val out = model.correct(c.typed, c.previous)?.word
                    if (out == c.intended) f++ else if (out != null) w++
                }
                return "%d%%/%d%%".format(f * 100 / subset.size.coerceAtLeast(1), w * 100 / subset.size.coerceAtLeast(1))
            }
            report.append("dropped-first %.1f, two-letter %.0f: first-letter drops %s (%d), two-letter %s (%d)\n".format(
                tuning.firstLetterDropped, tuning.twoLetterThreshold, rate(firstDropped), firstDropped.size, rate(twoLetter), twoLetter.size))
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
            if (tuning == tunings.last()) report.append("  wrong: ${wrongExamples.joinToString("  ")}\n")
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

        report.append(contractions(bigrams))
        println(report)
        File("build/context-scorecard.txt").writeText(report.toString())
    }
}
