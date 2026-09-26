package it.palsoftware.pastiera.core.suggestions

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.ln

/**
 * Which words follow which, in everyday English, bundled with the app.
 *
 * Pastiera only knew the pairs the user had typed themselves, so a fresh
 * install predicted nothing and autocorrect had no way to see that "tem" after
 * "the" is "team" while after "for" it is "them". The table is built from
 * Tatoeba's sentences by scripts/build-english-bigrams.py; the format is
 * documented there.
 */
class BigramModel private constructor(
    private val vocabulary: Array<String>,
    private val ids: Map<String, Int>,
    private val unigrams: IntArray,
    private val contexts: Map<String, Context>,
    private val totalTokens: Long
) {
    /**
     * One previous word's continuations as word ids, twice over: by frequency
     * for the bar, and sorted by id for lookup. Kept as int arrays because the
     * table holds a couple of hundred thousand pairs and lives in the keyboard's
     * process for as long as it is up.
     */
    internal class Context(
        val total: Int,
        val byFrequency: IntArray,
        val frequencyCounts: IntArray,
        val sortedIds: IntArray,
        val sortedCounts: IntArray
    ) {
        fun count(id: Int): Int {
            val at = sortedIds.binarySearch(id)
            return if (at >= 0) sortedCounts[at] else 0
        }
    }

    /** Continuations of [previous] in descending frequency, display-cased ("I", "don't"). */
    fun continuations(previous: String?, limit: Int = 16): List<Pair<String, Int>> {
        val context = contexts[key(previous)] ?: return emptyList()
        val n = minOf(limit, context.byFrequency.size)
        return (0 until n).map { vocabulary[context.byFrequency[it]] to context.frequencyCounts[it] }
    }

    /**
     * How much likelier [candidate] is after [previous] than anywhere, in
     * natural-log units: positive for "team" after "the", negative for a word
     * that does not follow it, zero when the table knows nothing either way.
     */
    fun contextScore(previous: String?, candidate: String): Double {
        val context = contexts[key(previous)] ?: return 0.0
        val id = ids[candidate.lowercase(Locale.ROOT)]
        val pair = id?.let { context.count(it) } ?: 0
        val unigram = id?.let { unigrams[it] } ?: 0
        val expected = context.total.toDouble() * unigram / totalTokens
        // Pairs under three sightings were pruned at build time, so a missing
        // pair means "rare", not "never": the +1 keeps that from reading as
        // impossible.
        return ln((pair + 1.0) / (expected + 1.0))
    }

    /**
     * Estimated probability of [word] right after [previous], smoothed toward
     * how common the word is overall. For choosing between two spellings of
     * the same keystrokes ("ill" or "I'll"), where only the ratio matters.
     */
    fun likelihood(previous: String?, word: String): Double {
        val id = ids[WordNormalization.normalizeApostrophes(word).lowercase(Locale.ROOT)]
        val overall = ((id?.let { unigrams[it] } ?: 0) + 0.5) / totalTokens
        val context = contexts[key(previous)] ?: return overall
        val pair = id?.let { context.count(it) } ?: 0
        return (pair + SMOOTHING * overall) / (context.total + SMOOTHING)
    }

    companion object {
        private const val SMOOTHING = 100.0
        const val SENTENCE_START = "<s>"
        private const val TAG = "BigramModel"
        private const val ASSET = "common/dictionaries/en_bigrams.tsv"

        private fun key(previous: String?): String =
            previous?.let { WordNormalization.normalizeApostrophes(it).lowercase(Locale.ROOT) } ?: SENTENCE_START

        fun load(assets: AssetManager): BigramModel? = try {
            assets.open(ASSET).use { parse(BufferedReader(InputStreamReader(it, Charsets.UTF_8))) }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled bigrams: ${e.message}")
            null
        }

        internal fun parse(reader: BufferedReader): BigramModel {
            var total = 0L
            val vocabulary = ArrayList<String>(50000)
            val ids = HashMap<String, Int>(65536)
            val unigramCounts = ArrayList<Int>(50000)
            fun idOf(display: String): Int {
                val low = display.lowercase(Locale.ROOT)
                return ids.getOrPut(low) {
                    vocabulary.add(display)
                    unigramCounts.add(0)
                    vocabulary.size - 1
                }
            }
            val contexts = HashMap<String, Context>(16384)
            var sawUnigrams = false
            reader.lineSequence().forEach { line ->
                if (line.startsWith("#") || line.isEmpty()) return@forEach
                if (total == 0L) {
                    total = line.trim().toLong()
                    return@forEach
                }
                val fields = line.split('\t')
                if (fields.size < 3) return@forEach
                val pairs = fields[2].split(' ')
                val wordIds = IntArray(pairs.size)
                val counts = IntArray(pairs.size)
                var n = 0
                for (pair in pairs) {
                    val colon = pair.lastIndexOf(':')
                    if (colon <= 0) continue
                    wordIds[n] = idOf(pair.substring(0, colon))
                    counts[n] = pair.substring(colon + 1).toInt()
                    n++
                }
                if (!sawUnigrams && fields[0].isEmpty()) {
                    sawUnigrams = true
                    for (i in 0 until n) unigramCounts[wordIds[i]] = counts[i]
                    return@forEach
                }
                val order = (0 until n).sortedBy { wordIds[it] }
                contexts[fields[0]] = Context(
                    total = fields[1].toInt(),
                    byFrequency = wordIds.copyOf(n),
                    frequencyCounts = counts.copyOf(n),
                    sortedIds = IntArray(n) { wordIds[order[it]] },
                    sortedCounts = IntArray(n) { counts[order[it]] }
                )
            }
            return BigramModel(
                vocabulary.toTypedArray(), ids, unigramCounts.toIntArray(), contexts, total.coerceAtLeast(1)
            )
        }

        @Volatile private var shared: BigramModel? = null
        @Volatile private var loading = false

        /**
         * The app-wide table, or null while it is still loading. It loads on a
         * background thread the first time anyone asks, because the space key
         * that asks must never wait for a 1 MB parse.
         */
        fun shared(assets: AssetManager): BigramModel? {
            shared?.let { return it }
            synchronized(this) {
                if (!loading) {
                    loading = true
                    Thread({ shared = load(assets) }, "BigramModel-load").start()
                }
            }
            return null
        }
    }
}
