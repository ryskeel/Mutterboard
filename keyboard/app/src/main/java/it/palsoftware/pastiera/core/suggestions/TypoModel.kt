package it.palsoftware.pastiera.core.suggestions

import java.util.Locale
import java.util.Collections
import java.util.WeakHashMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

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
    internal val tuning: Tuning = Tuning(),
    // Null while the bundled table loads, and for languages without one.
    private val bigrams: () -> BigramModel? = { null }
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
        // For typed words of four letters or more. At 2.0 "jsve" could never reach
        // "have" (two neighbour keys, one of them the first). Short words keep
        // the full cost: cheaper, two-letter words went wrong more often.
        val firstLetterLong: Double = 1.0,
        // An extra key before the first ("opage" for "page"), typed words of
        // four letters or more: the alignment already charged for the key.
        val firstLetterExtra: Double = 0.0,
        // A missed first key ("ight" for "right") is its own slip: the finger
        // rolling off the space bar. It is far commoner than a wrong first key.
        val firstLetterDropped: Double = 0.0,
        // Two-letter words ("cn" for "can") need more confidence than three.
        val twoLetterThreshold: Double = 5.0,
        val properNounPenalty: Double = 2.0,
        val maxCost: Double = 7.0,
        // Was 3.25. With firstLetterLong, 3.0 fixes 46% of two-slip words
        // ("jsve", "opsge"; was 35%) and 87% of first-key slips (was 68%),
        // with no new wrong fixes among the hand-written cases. 2.75 fixed a
        // little more and started getting hand cases wrong ("wierd" -> wired).
        val threshold: Double = 3.0,
        val shortWordThreshold: Double = 3.9,
        // Taken off the threshold per letter past four: a long string the
        // dictionary does not know is almost always a typo ("typong").
        val perLetterRelief: Double = 0.5,
        // Was 1.0, which left "sandpoint" alone: "standpoint" is one missed
        // key away and nothing else is close, but it is rare enough that its
        // score never cleared a fixed bar. Spelling scorecard 70% -> 77%.
        val minThreshold: Double = 0.0,
        // Weight on how well a candidate follows the previous word. Settles the
        // close calls frequency cannot: "the tem" is team, "for tem" is them.
        val contextWeight: Double = 0.5,
        // Share of a word's commonness taken from the everyday table rather
        // than the prose dictionary, where "wired" outranks "weird".
        val everydayWeight: Double = 0.75,
        // Seen this many times in the everyday table, a word is a word: 3 costs
        // a point of fixes on held-out text and saves a point of wrong ones.
        val everydayKnownCount: Int = 3,
        // One vowel written for another ("seperate", "definately"): a
        // spelling mistake, not a finger slip, and far commoner than a key
        // across the board.
        // From seven letters: shorter, it turned more slips into the wrong
        // word. Spelling scorecard 61% -> 71%, slips unchanged.
        val vowelForVowel: Double = 2.5,
        val vowelMinLength: Int = 7,
        // A known word plus a regular ending is a word ("payphones",
        // "fizzing"): the dictionary lists few inflections. Stems rarer than
        // this frequency do not count, so a slip that happens to end in "ing"
        // on top of an obscure stem is still fixed. 0 turns the check off.
        val inflectionStemFrequency: Int = 60,
        // How much surer the corrector must be to rewrite one. Not a veto: a
        // veto lost "comming" (com + ing) and "caled" (cal + ed).
        val inflectionThreshold: Double = 2.0,
        // A capital in the middle of a sentence is a name the user chose to
        // capitalise ("Higgs", "Thebes"), not a slip of a common word.
        val midSentenceCapitalThreshold: Double = 4.0,
        // A long word that sounds like the typed one but is too many edits
        // away for the lookup ("nutrician" for nutrition) costs this, plus a
        // share of its spelling distance so the closer of two sound-alikes
        // wins. Negative turns sound-alikes off.
        val soundAlike: Double = 2.0,
        val soundAlikeSlope: Double = 0.25,
    )

    data class Correction(val word: String, val score: Double, val runnerUpScore: Double?)

    // The previous word picks between candidates, and a good fit can make a
    // correction confident enough; a poor fit never vetoes one. Letting it veto
    // cost a fifth of all fixes on the context scorecard, because a pair the
    // table has not seen reads as a poor fit.
    private fun soundAlikes(input: String): List<String> {
        if (tuning.soundAlike < 0 || locale.language != "en" || input.length < SoundAlikeIndex.MIN_LENGTH) return emptyList()
        return soundIndexFor(repository)?.lookup(input).orEmpty()
    }

    /** Builds the sound-alike index on the calling thread. Tests call it so a run is not timing-dependent. */
    internal fun buildSoundAlikes() {
        val index = SoundAlikeIndex(repository.normalizedTerms())
        synchronized(soundIndexes) { soundIndexes[repository] = index }
    }

    private class Scored(val word: String, val rank: Double, val confidence: Double)

    /**
     * [previousWord] is the word before this one, or null at the start of a
     * sentence; see [BigramModel.SENTENCE_START].
     */
    fun correct(typed: String, previousWord: String? = null): Correction? {
        if (!repository.isReady) return null
        // Normalizing drops digits, so "4th" would be judged as "th" and fixed
        // to "the". A word with a number in it is never a typo of a dictionary word.
        if (typed.any { it.isDigit() }) return null
        val input = WordNormalization.normalizeForSuggestion(typed, locale)
        if (input.length < MIN_LENGTH || input.any { !it.isLetter() && it != '\'' }) return null

        // The dictionary is written prose and lacks thousands of everyday words
        // ("poop" became "pop", "grandma", "faucet"); the everyday table has them.
        if (tuning.everydayKnownCount > 0 &&
            (bigrams()?.everydayWordCount(input) ?: 0) >= tuning.everydayKnownCount
        ) return null
        val threshold = if (input.length == 2) {
            tuning.twoLetterThreshold
        } else if (input.length <= 3) {
            tuning.shortWordThreshold
        } else {
            (tuning.threshold - tuning.perLetterRelief * (input.length - 4)).coerceAtLeast(tuning.minThreshold)
        }
        var bar = threshold
        if (isInflection(input)) bar += tuning.inflectionThreshold
        if (previousWord != null && typed.first().isUpperCase()) bar += tuning.midSentenceCapitalThreshold

        val nearby = repository.symSpellLookup(input, maxSuggestions = 64)
            .filter { it.distance > 0 }
            .map { it.term }
        best(typed, input, previousWord, nearby, soundAlike = false, bar)?.let { return it }
        // Only when nothing a slip away will do: a sound-alike beat the slip
        // the user actually made ("placee" became something other than place).
        val alike = soundAlikes(input).filter { it != input }
        return best(typed, input, previousWord, alike, soundAlike = true, bar)
    }

    private fun best(
        typed: String,
        input: String,
        previousWord: String?,
        terms: List<String>,
        soundAlike: Boolean,
        bar: Double
    ): Correction? {
        if (terms.isEmpty()) return null
        val context = if (tuning.contextWeight > 0.0) bigrams() else null
        val scored = terms.asSequence()
            .mapNotNull { term ->
                val spellings = repository.topByNormalized(term, limit = 4)
                // "born" and "Born" share a key; a lowercase slip means the lowercase word.
                val entry = spellings.firstOrNull { it.word.firstOrNull()?.isLowerCase() == true }
                    ?: spellings.firstOrNull()
                    ?: return@mapNotNull null
                val spelled = channelCost(input, term)
                val cost = if (soundAlike) {
                    minOf(spelled, tuning.soundAlike + tuning.soundAlikeSlope * minOf(spelled, SPELLING_CAP))
                } else {
                    spelled
                }
                if (cost >= tuning.maxCost) return@mapNotNull null
                var score = logPrior(entry, context) - cost
                val fit = if (context != null) tuning.contextWeight * context.contextScore(previousWord, entry.word) else 0.0
                // A lowercase word turning into a capitalised one is usually a
                // name the user was not typing.
                if (typed.firstOrNull()?.isLowerCase() == true && entry.word.firstOrNull()?.isUpperCase() == true) {
                    score -= tuning.properNounPenalty
                }
                Scored(entry.word, rank = score + fit, confidence = score + maxOf(0.0, fit))
            }
            .sortedByDescending { it.rank }
            .distinctBy { it.word.lowercase(locale) }
            .take(2)
            .toList()

        val best = scored.firstOrNull() ?: return null
        if (best.confidence < bar) return null
        return Correction(best.word, best.rank, scored.getOrNull(1)?.rank)
    }

    private fun isInflection(input: String): Boolean {
        if (tuning.inflectionStemFrequency <= 0 || locale.language != "en" || input.length < 5) return false
        fun known(stem: String) = stem.length >= 3 &&
            (repository.bestEntryForNormalized(stem)?.frequency ?: 0) >= tuning.inflectionStemFrequency
        for (ending in INFLECTIONS) {
            if (!input.endsWith(ending)) continue
            val stem = input.dropLast(ending.length)
            val ok = when (ending) {
                // "moved", "braver", "bravest": only after a silent e.
                "d", "r", "st" -> stem.endsWith("e") && known(stem)
                // "dayss" and "laterr" are a key held too long.
                "s" -> !stem.endsWith("s") && known(stem)
                "ies", "ied", "ier", "iest", "ily" -> known(stem + "y")
                // "relegated", "fizzing", "stopped": the e drops and a final
                // consonant may double; "receiveed" keeps an e it cannot.
                else -> !stem.endsWith("e") && (known(stem) || known(stem + "e") ||
                    (stem.length >= 4 && stem.last() == stem[stem.length - 2] && stem.last() !in VOWELS && known(stem.dropLast(1))))
            }
            if (ok) return true
        }
        return false
    }

    // The bundled dictionaries store frequency on a log scale: across English,
    // one step of natural-log frequency is about thirteen units (rank 100 sits
    // at 165, rank 10,000 at 105).
    private fun logPrior(entry: DictionaryEntry, table: BigramModel?): Double {
        val raw = when (entry.source) {
            SuggestionSource.MAIN -> entry.frequency
            // The user's own words are words they actually type.
            SuggestionSource.USER, SuggestionSource.DEFAULT_USER -> maxOf(entry.frequency, USER_WORD_FREQUENCY)
        }
        val prose = raw / tuning.frequencyPerLogUnit
        if (table == null || tuning.everydayWeight <= 0.0 || entry.source != SuggestionSource.MAIN) return prose
        // Same scale as the prose score: the blend moves it by however much
        // likelier (or rarer) the word is in everyday text.
        val proseProbability = THE_PROBABILITY * exp((raw - THE_FREQUENCY) / NATURAL_FREQUENCY_PER_LOG_UNIT)
        val blended = tuning.everydayWeight * table.unigramProbability(entry.word) +
            (1 - tuning.everydayWeight) * proseProbability
        return prose + NATURAL_FREQUENCY_PER_LOG_UNIT / tuning.frequencyPerLogUnit * ln(blended / proseProbability)
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
                var best = d[i - 1][j - 1] + if (a == b) 0.0 else substituteCost(a, b, spelling = n >= tuning.vowelMinLength)
                best = minOf(best, d[i - 1][j] + extraKeyCost(typed, i - 1))
                best = minOf(best, d[i][j - 1] + missedKeyCost(intended, j - 1))
                if (i > 1 && j > 1 && a == intended[j - 2] && typed[i - 2] == b) {
                    best = minOf(best, d[i - 2][j - 2] + tuning.swap)
                }
                d[i][j] = best
            }
        }
        var cost = d[n][m]
        if (typed.first() != intended.first()) {
            cost += when {
                typed.first() == intended.getOrNull(1) -> tuning.firstLetterDropped
                // Charging an extra first key again as a wrong first letter
                // counted one slip twice. Not below four letters, where it
                // made two-letter words go wrong more often.
                typed.length >= 4 && typed[1] == intended.first() -> tuning.firstLetterExtra
                else -> if (typed.length >= 4) tuning.firstLetterLong else tuning.firstLetter
            }
        }
        return cost
    }

    private fun substituteCost(typed: Char, intended: Char, spelling: Boolean): Double = when {
        isNeighbour(typed, intended) -> tuning.neighbourKey
        spelling && typed in VOWELS && intended in VOWELS -> minOf(tuning.vowelForVowel, tuning.farKey)
        else -> tuning.farKey
    }

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
        private const val MIN_LENGTH = 2
        private const val USER_WORD_FREQUENCY = 150
        private const val THE_PROBABILITY = 0.05
        private const val THE_FREQUENCY = 222.0
        private const val NATURAL_FREQUENCY_PER_LOG_UNIT = 13.0
        private const val VOWELS = "aeiou"
        private const val SPELLING_CAP = 16.0

        // One per dictionary, not per model: the word bar makes a TypoModel on
        // every keystroke. Building reads all ~50k words, too slow for the space
        // press that first needs it, so it happens off-thread and there are no
        // sound-alikes until it is done. A repository is replaced, not
        // reloaded, when the language changes, so the weak key retires it.
        private val soundIndexes = WeakHashMap<DictionaryRepository, SoundAlikeIndex>()
        private val soundIndexBuilds = Collections.newSetFromMap(WeakHashMap<DictionaryRepository, Boolean>())

        private fun soundIndexFor(repository: DictionaryRepository): SoundAlikeIndex? {
            synchronized(soundIndexes) {
                soundIndexes[repository]?.let { return it }
                if (!soundIndexBuilds.add(repository)) return null
            }
            thread(isDaemon = true, name = "SoundAlikeIndex") {
                try {
                    val index = SoundAlikeIndex(repository.normalizedTerms())
                    synchronized(soundIndexes) { soundIndexes[repository] = index }
                } catch (e: ConcurrentModificationException) {
                    // A user word landed mid-read; the next lookup tries again.
                    synchronized(soundIndexes) { soundIndexBuilds.remove(repository) }
                }
            }
            return null
        }
        // Longest first, so "ies" is tried before "s".
        private val INFLECTIONS = listOf("iest", "ing", "ies", "ied", "ier", "ily", "est", "es", "ed", "er", "ly", "st", "s", "d", "r")



        // Staggered QWERTY, as on the Titan's hardware keys: (row, column).
        private val KEY_POSITIONS: Map<Char, Pair<Double, Double>> = buildMap {
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, keys ->
                keys.forEachIndexed { col, ch -> put(ch, row.toDouble() to col + row * 0.5) }
            }
        }
    }
}
