package it.palsoftware.pastiera.core.suggestions

import java.util.Locale
import kotlin.math.abs

/**
 * Mutterboard's autocorrect decision: which dictionary word did the user most
 * likely mean to type, and is that likely enough to change what they typed?
 *
 * It scores each candidate as (how common the word is) minus (how unlikely this
 * particular slip of the fingers is), both in natural-log units, which is the
 * shape every serious keyboard's corrector has. Pastiera's own path ranked
 * candidates for the suggestion bar and then vetoed any correction whose length
 * differed from the typed word, so an extra key ("quyick") or a dropped letter
 * ("tomorow") was never fixed at all.
 *
 * The costs are what a thumb on a small QWERTY does: a neighbouring key is a
 * cheap slip, a key across the board is not, a doubled letter is easy to gain
 * or lose, and almost nobody gets the first letter wrong.
 * AutocorrectScorecardTest is how these numbers are judged; change them there.
 */
class TypoModel(
    private val repository: DictionaryRepository,
    private val locale: Locale,
    internal val tuning: Tuning = Tuning()
) {

    data class Tuning(
        val frequencyPerLogUnit: Double = 20.0,
        val neighbourKey: Double = 2.5,
        val farKey: Double = 5.0,
        val extraNeighbour: Double = 2.5,
        val extraFar: Double = 4.5,
        val missedKey: Double = 3.5,
        val doubledKey: Double = 1.5,
        val swap: Double = 2.5,
        val firstLetter: Double = 2.0,
        val properNounPenalty: Double = 2.0,
        val maxCost: Double = 7.0,
        val threshold: Double = 3.25,
        val shortWordThreshold: Double = 3.9,
        // Taken off the threshold per letter past four: a long string the
        // dictionary does not know is almost always a typo ("typong").
        val perLetterRelief: Double = 0.5,
        val minThreshold: Double = 1.0,
    )

    data class Correction(val word: String, val score: Double, val runnerUpScore: Double?)

    fun correct(typed: String): Correction? {
        if (!repository.isReady) return null
        val input = WordNormalization.normalizeForSuggestion(typed, locale)
        if (input.length < MIN_LENGTH || input.any { !it.isLetter() && it != '\'' }) return null

        val scored = repository.symSpellLookup(input, maxSuggestions = 64)
            .asSequence()
            .filter { it.distance > 0 }
            .mapNotNull { item ->
                val spellings = repository.topByNormalized(item.term, limit = 4)
                // "born" and "Born" share a key; a lowercase slip means the lowercase word.
                val entry = spellings.firstOrNull { it.word.firstOrNull()?.isLowerCase() == true }
                    ?: spellings.firstOrNull()
                    ?: return@mapNotNull null
                val cost = channelCost(input, item.term)
                if (cost >= tuning.maxCost) return@mapNotNull null
                var score = logPrior(entry) - cost
                // A lowercase word turning into a capitalised one is usually a
                // name the user was not typing.
                if (typed.firstOrNull()?.isLowerCase() == true && entry.word.firstOrNull()?.isUpperCase() == true) {
                    score -= tuning.properNounPenalty
                }
                entry.word to score
            }
            .sortedByDescending { it.second }
            .distinctBy { it.first.lowercase(locale) }
            .take(2)
            .toList()

        val best = scored.firstOrNull() ?: return null
        val threshold = if (input.length <= 3) {
            tuning.shortWordThreshold
        } else {
            (tuning.threshold - tuning.perLetterRelief * (input.length - 4)).coerceAtLeast(tuning.minThreshold)
        }
        if (best.second < threshold) return null
        return Correction(best.first, best.second, scored.getOrNull(1)?.second)
    }

    // The bundled dictionaries store frequency on a log scale: across English,
    // one step of natural-log frequency is about thirteen units (rank 100 sits
    // at 165, rank 10,000 at 105).
    private fun logPrior(entry: DictionaryEntry): Double {
        val raw = when (entry.source) {
            SuggestionSource.MAIN -> entry.frequency
            // The user's own words are words they actually type.
            SuggestionSource.USER, SuggestionSource.DEFAULT_USER -> maxOf(entry.frequency, USER_WORD_FREQUENCY)
        }
        return raw / tuning.frequencyPerLogUnit
    }

    /**
     * Cheapest way to get from what the user meant ([intended]) to what they
     * typed ([typed]), as a weighted Damerau alignment.
     */
    internal fun channelCost(typed: String, intended: String): Double {
        val n = typed.length
        val m = intended.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) d[i][0] = d[i - 1][0] + extraKeyCost(typed, i - 1)
        for (j in 1..m) d[0][j] = d[0][j - 1] + missedKeyCost(intended, j - 1)
        for (i in 1..n) {
            for (j in 1..m) {
                val a = typed[i - 1]
                val b = intended[j - 1]
                var best = d[i - 1][j - 1] + if (a == b) 0.0 else substituteCost(a, b)
                best = minOf(best, d[i - 1][j] + extraKeyCost(typed, i - 1))
                best = minOf(best, d[i][j - 1] + missedKeyCost(intended, j - 1))
                if (i > 1 && j > 1 && a == intended[j - 2] && typed[i - 2] == b) {
                    best = minOf(best, d[i - 2][j - 2] + tuning.swap)
                }
                d[i][j] = best
            }
        }
        var cost = d[n][m]
        if (typed.first() != intended.first()) cost += tuning.firstLetter
        return cost
    }

    private fun substituteCost(typed: Char, intended: Char): Double =
        if (isNeighbour(typed, intended)) tuning.neighbourKey else tuning.farKey

    // A key pressed that the word does not have: cheap when it sits beside or
    // repeats a key the finger was pressing anyway.
    private fun extraKeyCost(typed: String, index: Int): Double {
        val c = typed[index]
        val prev = typed.getOrNull(index - 1)
        val next = typed.getOrNull(index + 1)
        return when {
            c == prev || c == next -> tuning.doubledKey
            (prev != null && isNeighbour(c, prev)) || (next != null && isNeighbour(c, next)) -> tuning.extraNeighbour
            else -> tuning.extraFar
        }
    }

    private fun missedKeyCost(intended: String, index: Int): Double {
        val c = intended[index]
        return if (c == intended.getOrNull(index - 1)) tuning.doubledKey else tuning.missedKey
    }

    private fun isNeighbour(a: Char, b: Char): Boolean {
        val p = KEY_POSITIONS[a] ?: return false
        val q = KEY_POSITIONS[b] ?: return false
        return abs(p.first - q.first) <= 1.0 && abs(p.second - q.second) <= 1.0
    }

    companion object {
        private const val MIN_LENGTH = 3
        private const val USER_WORD_FREQUENCY = 150



        // Staggered QWERTY, as on the Titan's hardware keys: (row, column).
        private val KEY_POSITIONS: Map<Char, Pair<Double, Double>> = buildMap {
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, keys ->
                keys.forEachIndexed { col, ch -> put(ch, row.toDouble() to col + row * 0.5) }
            }
        }
    }
}
