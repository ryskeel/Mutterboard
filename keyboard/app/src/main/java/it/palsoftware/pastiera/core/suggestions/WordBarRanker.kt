package it.palsoftware.pastiera.core.suggestions

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * Mutterboard's suggestions while a word is being typed (English).
 *
 * Pastiera ranked completions by dictionary frequency alone, from written
 * prose, so after "I want" the letters "t" offered whatever t-word Wikipedia
 * uses most rather than "to". This scores each completion by how likely it is
 * after the previous word ([BigramModel]), falling back to the prose
 * frequency for words the everyday table has not seen, and puts the word the
 * space bar is about to correct to in the first slot, so the bar shows what
 * space will do.
 *
 * Measured by ContextScorecardTest's completion section.
 */
class WordBarRanker(
    private val repository: DictionaryRepository,
    private val locale: Locale,
    private val bigrams: () -> BigramModel?,
    private val typoModel: TypoModel?
) {
    fun suggest(typed: String, previousWord: String?, limit: Int = 3): List<SuggestionResult> {
        if (typed.isBlank() || !repository.isReady) return emptyList()
        val table = bigrams()
        val prefix = WordNormalization.normalizeForSuggestion(typed, locale)
        if (prefix.isEmpty()) return emptyList()
        val typedLower = typed.lowercase(locale)
        val lowercaseInput = typed.first().isLowerCase()

        // Keyed by lowercase so the table's "I'm" and the dictionary's "I'm" meet.
        val pool = LinkedHashMap<String, Pair<String, Double>>()
        fun offer(word: String, score: Double) {
            val key = word.lowercase(locale)
            if (key == typedLower) return
            val existing = pool[key]
            if (existing == null || existing.second < score) pool[key] = word to score
        }

        table?.continuations(previousWord, CONTINUATION_POOL)?.forEach { (word, _) ->
            if (WordNormalization.normalizeForSuggestion(word, locale).startsWith(prefix) && word.length > typed.length) {
                offer(word, score(table, previousWord, word, null))
            }
        }
        repository.lookupByPrefixMerged(prefix, maxSize = DICTIONARY_POOL).forEach { entry ->
            if (entry.word.length <= typed.length) return@forEach
            if (!WordNormalization.normalizeForSuggestion(entry.word, locale).startsWith(prefix)) return@forEach
            val isUser = entry.source == SuggestionSource.USER
            // "hard" should not offer "Hardy": a capital on a lowercase input is a name.
            if (lowercaseInput && entry.word.first().isUpperCase() && !isUser) return@forEach
            val s = score(table, previousWord, entry.word, entry) + if (isUser) USER_BONUS else 0.0
            offer(entry.word, s)
        }

        val ranked = pool.values.sortedByDescending { it.second }.map { it.first }
        val correction = if (!repository.isKnownWord(typed)) typoModel?.correct(typed, previousWord)?.word else null
        val words = (listOfNotNull(correction) + ranked)
            .distinctBy { it.lowercase(locale) }
            .take(limit)
            .map { CasingHelper.applyCasing(it, typed, forceLeadingCapital = false) }
        return words.mapIndexed { i, word ->
            SuggestionResult(
                candidate = word,
                distance = if (word.equals(correction, ignoreCase = true)) 1 else 0,
                score = (limit - i).toDouble(),
                source = SuggestionSource.MAIN
            )
        }
    }

    /**
     * ln P(word | previous). The everyday table's estimate where it knows the
     * word, blended with the prose dictionary's so a word Tatoeba never used
     * still ranks by how common it is.
     */
    private fun score(table: BigramModel?, previous: String?, word: String, entry: DictionaryEntry?): Double {
        val prose = proseProbability(entry ?: repository.bestEntryForNormalized(
            WordNormalization.normalizeForDictionary(word, locale)
        ))
        val everyday = table?.likelihood(previous, word) ?: 0.0
        return ln(EVERYDAY_WEIGHT * everyday + (1 - EVERYDAY_WEIGHT) * prose + 1e-12)
    }

    // The bundled lists put "the" at 222 on a log scale of about thirteen per
    // natural-log step (see TypoModel); "the" is about 5% of English words.
    private fun proseProbability(entry: DictionaryEntry?): Double {
        val f = entry?.frequency ?: return 0.0
        return THE_PROBABILITY * exp((f - THE_FREQUENCY) / FREQUENCY_PER_LOG_UNIT)
    }

    companion object {
        private const val CONTINUATION_POOL = 256
        private const val DICTIONARY_POOL = 300
        private const val EVERYDAY_WEIGHT = 0.8
        private const val USER_BONUS = 2.0
        private const val THE_PROBABILITY = 0.05
        private const val THE_FREQUENCY = 222.0
        private const val FREQUENCY_PER_LOG_UNIT = 13.0
    }
}
