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
        val typo = TypoModel(repository, Locale.ENGLISH, knownWords = { EnglishFixture.knownWords() }) { bigrams }.also { it.buildSoundAlikes() }
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

    /**
     * The same typos judged again once the next word is typed. "only" is a
     * correction taken back or changed; "also" lets the late look fix a word
     * the space bar left alone.
     */
    private fun nextWord(repository: DictionaryRepository, bigrams: BigramModel, known: KnownWords, cases: List<Case>): String {
        val out = StringBuilder("judged again with the next word (${cases.count { it.next != null }} typos with one):\n")
        val shipped = TypoModel.Tuning()
        val weights = if (System.getenv("SCORECARD_SWEEP") == "1") listOf(0.25, 0.5, 0.75, 1.0) else listOf(shipped.nextWordWeight)
        val space = TypoModel(repository, Locale.ENGLISH, shipped, { known }) { bigrams }.also { it.buildSoundAlikes() }
        val withNext = cases.filter { it.next != null }
        val atSpace = withNext.map { space.correct(it.typed, it.previous, it.twoBack)?.word }
        fun line(label: String, results: List<String?>) {
            val n = withNext.size.toDouble()
            val f = withNext.indices.count { results[it] == withNext[it].intended }
            val w = withNext.indices.count { results[it] != null && results[it] != withNext[it].intended }
            out.append("  %-28s fixed %.1f%%  wrong %.1f%%\n".format(label, f * 100 / n, w * 100 / n))
        }
        line("space bar only", atSpace)
        for (weight in weights) {
            val late = TypoModel(repository, Locale.ENGLISH, shipped.copy(nextWordWeight = weight), { known }) { bigrams }.also { it.buildSoundAlikes() }
            val again = withNext.map { late.correct(it.typed, it.previous, it.twoBack, it.next)?.word }
            line("weight $weight, only", atSpace.indices.map { if (atSpace[it] != null) again[it] ?: atSpace[it] else null })
            line("weight $weight, also", atSpace.indices.map { again[it] ?: atSpace[it] })
            if (weight == shipped.nextWordWeight) {
                val saved = withNext.indices.filter { atSpace[it] != null && atSpace[it] != withNext[it].intended && again[it] == withNext[it].intended }
                val broke = withNext.indices.filter { atSpace[it] == withNext[it].intended && again[it] != null && again[it] != withNext[it].intended }
                fun show(i: Int) = withNext[i].let { "${it.previous ?: "^"} ${it.typed} ${it.next}: ${atSpace[i]}->${again[i]}" }
                out.append("    saved ${saved.size}: ${saved.take(8).joinToString("  ") { show(it) }}\n")
                out.append("    broke ${broke.size}: ${broke.take(8).joinToString("  ") { show(it) }}\n")
            }
        }
        return out.toString()
    }

    /**
     * The user's own pairs, simulated: 2,000 held-out sentences the cases do
     * not come from stand in for a few weeks of their typing. Tatoeba's
     * habits are the table's habits, so this shows the weight does no harm
     * more than how much a real person's typing helps; CorrectionAudit is
     * where that shows.
     */
    private fun personal(repository: DictionaryRepository, bigrams: BigramModel, known: KnownWords, cases: List<Case>): String {
        val pairs = HashMap<String, HashMap<String, Int>>()
        for (sentence in EnglishFixture.heldOutSentences().drop(4000).take(2000)) {
            val tokens = EnglishFixture.tokens(sentence).map { it.lowercase(Locale.ROOT) }
            tokens.forEachIndexed { i, w ->
                val key = if (i == 0) "<s>" else tokens[i - 1]
                pairs.getOrPut(key) { HashMap() }.merge(w, 1, Int::plus)
            }
        }
        val lookup: (String?) -> Map<String, Int> = { pairs[it?.lowercase(Locale.ROOT) ?: "<s>"].orEmpty() }
        // A person whose habits are not Tatoeba's: the slip's intended word is
        // one they have typed after that previous word a few times before.
        val habits = HashMap<String, HashMap<String, Int>>()
        cases.filterIndexed { i, _ -> i % 2 == 0 }.forEach { c ->
            habits.getOrPut(c.previous?.lowercase(Locale.ROOT) ?: "<s>") { HashMap() }.merge(c.intended.lowercase(Locale.ROOT), 3, Int::plus)
        }
        val habitLookup: (String?) -> Map<String, Int> = { habits[it?.lowercase(Locale.ROOT) ?: "<s>"].orEmpty() }
        val out = StringBuilder("with the user's own pairs (simulated):\n")
        val weights = if (System.getenv("SCORECARD_SWEEP") == "1") listOf(0.0, 0.25, 0.5, 1.0) else listOf(0.0, TypoModel.Tuning().personalWeight)
        for (weight in weights.distinct()) {
            val model = TypoModel(repository, Locale.ENGLISH, TypoModel.Tuning(personalWeight = weight), { known }, lookup) { bigrams }
                .also { it.buildSoundAlikes() }
            val results = cases.map { model.correct(it.typed, it.previous, it.twoBack)?.word }
            val n = cases.size.toDouble()
            val f = cases.indices.count { results[it] == cases[it].intended }
            val w = cases.indices.count { results[it] != null && results[it] != cases[it].intended }
            out.append("  weight %.2f: fixed %.1f%%  wrong %.1f%%\n".format(weight, f * 100 / n, w * 100 / n))
            // Only the even cases have a habit; the odd ones check it does not leak.
            val habitual = TypoModel(repository, Locale.ENGLISH, TypoModel.Tuning(personalWeight = weight), { known }, habitLookup) { bigrams }
                .also { it.buildSoundAlikes() }
            for (parity in 0..1) {
                val subset = cases.filterIndexed { i, _ -> i % 2 == parity }
                val r = subset.map { habitual.correct(it.typed, it.previous, it.twoBack)?.word }
                val m = subset.size.toDouble()
                out.append("    %s: fixed %.1f%%  wrong %.1f%%\n".format(if (parity == 0) "words they type there" else "other words        ",
                    subset.indices.count { r[it] == subset[it].intended } * 100 / m,
                    subset.indices.count { r[it] != null && r[it] != subset[it].intended } * 100 / m))
            }
        }
        return out.toString()
    }

    private data class Case(val previous: String?, val typed: String, val intended: String, val twoBack: String? = null, val next: String? = null)

    @Test
    fun scorecard() {
        val repository = EnglishFixture.repository(RuntimeEnvironment.getApplication())
        val bigrams = EnglishFixture.bigrams()
        val known = EnglishFixture.knownWords()
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
            Case(if (i == 0) null else tokens[i - 1], typo, tokens[i], if (i < 2) null else tokens[i - 2], tokens.getOrNull(i + 1))
        }

        val report = StringBuilder("\n=== Context scorecard (${cases.size} typos in held-out sentences) ===\n")
        val shipped = TypoModel.Tuning()
        val tunings = if (System.getenv("SCORECARD_SWEEP") == "1") {
            listOf(0.0, 0.25, 0.5).flatMap { t -> listOf(0.5, 0.75, 1.0).map { c -> shipped.copy(twoWordWeight = t, contextWeight = c) } }
        } else {
            listOf(shipped.copy(contextWeight = 0.0), shipped)
        }
        val firstDropped = cases.filter { it.typed == it.intended.drop(1) }
        val twoLetter = cases.filter { it.typed.length == 2 }
        for (tuning in tunings) {
            val weight = tuning.contextWeight
            val model = TypoModel(repository, Locale.ENGLISH, tuning, { known }) { bigrams }.also { it.buildSoundAlikes() }
            fun rate(subset: List<Case>): String {
                var f = 0; var w = 0
                for (c in subset) {
                    val out = model.correct(c.typed, c.previous, c.twoBack)?.word
                    if (out == c.intended) f++ else if (out != null) w++
                }
                val n = subset.size.coerceAtLeast(1).toDouble()
                return "%.1f%%/%.1f%%".format(f * 100 / n, w * 100 / n)
            }
            report.append("everyday %.2f context %.2f two-word %.2f: all %s, first-letter drops %s, two-letter %s\n".format(
                tuning.everydayWeight, tuning.contextWeight, tuning.twoWordWeight, rate(cases), rate(firstDropped), rate(twoLetter)))
            var fixed = 0; var wrong = 0
            val wrongExamples = mutableListOf<String>()
            for (c in cases) {
                val out = model.correct(c.typed, c.previous, c.twoBack)?.word
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
        report.append(nextWord(repository, bigrams, known, cases))
        report.append(personal(repository, bigrams, known, cases))

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
