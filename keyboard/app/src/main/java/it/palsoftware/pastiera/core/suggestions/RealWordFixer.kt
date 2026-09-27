package it.palsoftware.pastiera.core.suggestions

import java.util.Locale
import kotlin.math.ln

/**
 * Typos that land on another real word ("an there" for "and there"). The
 * space-bar corrector never sees these, because the typed word is in the
 * dictionary. Only the word after gives them away, so this judges a word once
 * the next one is typed: does a one-slip neighbour fit between its two
 * neighbours so much better that the slip is the likelier story?
 *
 * Changing a word the user has already moved past is the most intrusive thing
 * a keyboard does, so the bar is set by counting how many correct words it
 * would change in clean text (ContextScorecardTest), not by how many slips it
 * catches.
 */
class RealWordFixer(
    private val repository: DictionaryRepository,
    private val locale: Locale,
    private val bigrams: () -> BigramModel?,
    private val typoModel: TypoModel,
    // Set on ContextScorecardTest's clean text: 0.6 correct words changed per
    // thousand for 42% of slips caught. Looser settings caught more and
    // changed up to fifteen.
    private val slipWeight: Double = 2.0,
    private val margin: Double = 8.0
) {
    companion object {
        /**
         * The fixer's slip costs. It changes words the user has already moved
         * past, so it keeps the strict first-letter costs the space bar gave up
         * for long words: the cheaper ones raised clean words changed from 0.6
         * to 0.8 per thousand.
         */
        val SLIP_TUNING = TypoModel.Tuning().let { it.copy(firstLetterLong = it.firstLetter, firstLetterExtra = it.firstLetter) }

        private val CONTRACTION = Regex("^(\\p{L}+)['’](\\p{L}+)$")
        private val ENDINGS = listOf("s", "d", "ll", "re", "ve", "m")
        // BigramModel leaves 'd out of its families because "would" is too
        // loose a stand-in; the contractions alone still say "'d gonna" is not a thing.
        private val D_FAMILY = listOf("I'd", "you'd", "he'd", "she'd", "it'd", "we'd", "they'd", "that'd", "there'd")
    }

    /** The word [typed] should have been, or null to leave it. */
    fun fix(before: String?, typed: String, after: String): String? {
        val table = bigrams() ?: return null
        CONTRACTION.matchEntire(typed)?.let { return fixEnding(table, before, it.groupValues[1], it.groupValues[2], after) }
        if (typed.length < 2 || typed.any { !it.isLetter() }) return null
        val input = WordNormalization.normalizeForSuggestion(typed, locale)
        fun fit(word: String) = ln(table.likelihood(before, word)) + ln(table.likelihood(word, after))
        val asTyped = fit(typed)
        val best = repository.symSpellLookup(input, maxSuggestions = 32)
            .asSequence()
            .filter { it.distance == 1 }
            .mapNotNull { item ->
                val spellings = repository.topByNormalized(item.term, limit = 4)
                spellings.firstOrNull { it.word.first().isLowerCase() }?.word ?: spellings.firstOrNull()?.word
            }
            .filter { table.unigramProbability(it) > 0.0 }
            .map { it to fit(it) - slipWeight * typoModel.channelCost(input, it.lowercase(locale)) }
            .maxByOrNull { it.second }
            ?: return null
        return if (best.second - asTyped >= margin) best.first else null
    }

    /**
     * "or there'd gonna be": the 'd was a slip for 's, and only the word after
     * says so. Most contractions are not in the prose dictionary, so the
     * candidates are the same word with the other endings, judged by the
     * everyday table under the same bar as any other real-word slip. A
     * contraction's own pairs are thin ("there'd" was seen 16 times), so the
     * word after is judged against its family's.
     */
    private fun fixEnding(table: BigramModel, before: String?, stem: String, ending: String, after: String): String? {
        val typed = "$stem'$ending".lowercase(locale)
        if (ending.lowercase(locale) !in ENDINGS) return null
        fun following(word: String): Double {
            val family = if (word.endsWith("'d")) D_FAMILY else BigramModel.contractionFamily(word)
            return family?.let { table.pooledLikelihood(it, after) } ?: table.likelihood(word, after)
        }
        fun fit(word: String) = ln(table.likelihood(before, word)) + ln(following(word))
        val asTyped = fit(typed)
        val best = ENDINGS.asSequence()
            .filter { it != ending.lowercase(locale) }
            .map { "${stem.lowercase(locale)}'$it" }
            .filter { table.unigramProbability(it) > 0.0 }
            .map { it to fit(it) - slipWeight * typoModel.channelCost(typed, it) }
            .maxByOrNull { it.second }
            ?: return null
        return if (best.second - asTyped >= margin) best.first else null
    }
}
