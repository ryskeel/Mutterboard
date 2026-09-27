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
     * Slips that land on another real word ("an there"), judged once the next
     * word is typed. The number that matters is the last one: correct words
     * changed per thousand, in clean text.
     */
    private fun realWords(repository: DictionaryRepository, bigrams: BigramModel): String {
        val typo = TypoModel(repository, Locale.ENGLISH) { bigrams }
        val random = Random(20260926)
        val sentences = EnglishFixture.heldOutSentences().take(4000)
        data class Slip(val before: String?, val typed: String, val after: String, val intended: String)
        val slips = sentences.mapNotNull { sentence ->
            val tokens = EnglishFixture.tokens(sentence)
            if (tokens.size < 3) return@mapNotNull null
            val i = 1 + random.nextInt(tokens.size - 2)
            val word = tokens[i]
            if (word.length < 2 || !word.all { it in 'a'..'z' }) return@mapNotNull null
            repeat(12) {
                val (t, _) = TypoGenerator.any(word, random) ?: return@repeat
                if (t != word && repository.isKnownWord(t)) return@mapNotNull Slip(tokens[i - 1], t, tokens[i + 1], word)
            }
            null
        }
        val clean = sentences.drop(2000).flatMap { sentence ->
            val tokens = EnglishFixture.tokens(sentence)
            (1 until tokens.size - 1).map { i -> Triple(tokens[i - 1], tokens[i], tokens[i + 1]) }
        }
        val out = StringBuilder("real-word slips (${slips.size}), fixed/wrong, and correct words changed per 1000 (${clean.size} words):\n")
        val grid = if (System.getenv("SCORECARD_SWEEP") == "1") {
            listOf(1.0, 1.5, 2.0).flatMap { w -> listOf(3.0, 4.0, 5.0, 6.0, 8.0).map { w to it } }
        } else listOf(2.0 to 8.0)
        for ((weight, margin) in grid) {
            val fixer = RealWordFixer(repository, Locale.ENGLISH, { bigrams }, TypoModel(repository, Locale.ENGLISH, RealWordFixer.SLIP_TUNING) { bigrams }, weight, margin)
            var fixed = 0; var wrong = 0
            for (s in slips) {
                val out2 = fixer.fix(s.before, s.typed, s.after)
                if (out2?.equals(s.intended, ignoreCase = true) == true) fixed++ else if (out2 != null) wrong++
            }
            val changed = clean.filter { (b, w, a) -> fixer.fix(b, w, a) != null }
            out.append("  slip weight %.1f margin %.0f: fixed %d%% wrong %d%%, clean words changed %.1f/1000  e.g. %s\n".format(
                weight, margin, fixed * 100 / slips.size, wrong * 100 / slips.size, changed.size * 1000.0 / clean.size,
                changed.take(4).joinToString(" ") { (b, w, a) -> "[$b $w $a]" }))
        }
        return out.toString()
    }

    /**
     * The bar while a word is being typed: after how many letters does the
     * word the writer used show among its three suggestions? "Saved" is the
     * share of letters a tap on it would have spared.
     */
    private fun completions(repository: DictionaryRepository, bigrams: BigramModel): String {
        val pastiera = SuggestionEngine(repository, Locale.ENGLISH)
        val ranker = WordBarRanker(repository, Locale.ENGLISH, { bigrams }, TypoModel(repository, Locale.ENGLISH) { bigrams })
        val engines = linkedMapOf<String, (String, String?) -> List<String>>(
            "Pastiera" to { prefix, _ -> pastiera.suggest(prefix, 3, true, false, false).map { it.candidate } },
            "Mutterboard" to { prefix, previous -> ranker.suggest(prefix, previous, 3).map { it.candidate } },
        )
        val out = StringBuilder("while typing (top 3 in the bar):\n")
        val sentences = EnglishFixture.heldOutSentences().take(1500)
        for ((name, suggest) in engines) {
            var words = 0; var letters = 0; var saved = 0
            val foundBy = IntArray(4)
            for (sentence in sentences) {
                val tokens = EnglishFixture.tokens(sentence)
                for (i in tokens.indices) {
                    val word = tokens[i]
                    if (word.length < 3) continue
                    val previous = if (i == 0) null else tokens[i - 1]
                    words++; letters += word.length
                    for (k in 1 until word.length) {
                        val offered = suggest(word.substring(0, k), previous).map { it.lowercase(Locale.ROOT) }
                        if (word.lowercase(Locale.ROOT) in offered) {
                            saved += word.length - k
                            if (k <= 3) for (j in k..3) foundBy[j]++
                            break
                        }
                    }
                }
            }
            out.append("  %-11s by 1 letter %2d%%, by 2 %2d%%, by 3 %2d%%; letters saved %2d%% (%d words)\n".format(
                name, foundBy[1] * 100 / words, foundBy[2] * 100 / words, foundBy[3] * 100 / words, saved * 100 / letters, words))
        }
        return out.toString()
    }

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
            listOf(0.0, 0.25, 0.5, 0.75, 0.9).map { shipped.copy(everydayWeight = it) }
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
            report.append("everyday weight %.2f: all %s, first-letter drops %s, two-letter %s\n".format(
                tuning.everydayWeight, rate(cases), rate(firstDropped), rate(twoLetter)))
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
        for (twoWords in listOf(false, true)) {
            var positions = 0; var top1 = 0; var top3 = 0
            for (sentence in sentences) {
                val tokens = EnglishFixture.tokens(sentence)
                for (i in tokens.indices) {
                    val previous = if (i == 0) null else tokens[i - 1]
                    val twoBack = if (i < 2) null else tokens[i - 2]
                    val offered = (if (twoWords) bigrams.continuations(twoBack, previous, 3) else bigrams.continuations(previous, 3))
                        .map { it.first.lowercase(Locale.ROOT) }
                    val actual = tokens[i].lowercase(Locale.ROOT)
                    positions++
                    if (offered.firstOrNull() == actual) top1++
                    if (actual in offered) top3++
                }
            }
            report.append("next word from %s: in the bar's first slot %d%%, anywhere in its three %d%% (%d positions)\n".format(
                if (twoWords) "two words" else "one word", top1 * 100 / positions, top3 * 100 / positions, positions))
        }

        report.append(contractions(bigrams))
        report.append(completions(repository, bigrams))
        report.append(realWords(repository, bigrams))
        println(report)
        File("build/context-scorecard.txt").writeText(report.toString())
    }
}
