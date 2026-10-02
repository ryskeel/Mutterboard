package it.palsoftware.pastiera.core.suggestions

import android.content.res.AssetManager
import android.util.Log
import java.io.InputStream

/**
 * English words the bundled dictionary does not have, from SCOWL
 * (scripts/build-english-wordlist.py). The dictionary's 50,000 words left
 * autocorrect taking "sprig", "swaddle" and "glisten" for typos. These are
 * words to leave alone, never words to correct to: there are no frequencies
 * to say how likely any of them is.
 *
 * Held as one sorted string and searched in place: about 80,000 words as a
 * HashSet would be several megabytes of objects in the keyboard's process.
 */
class KnownWords private constructor(private val text: String, private val starts: IntArray) {

    operator fun contains(word: String): Boolean {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(mid, word)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    val size: Int get() = starts.size

    private fun compareAt(index: Int, word: String): Int {
        val start = starts[index]
        val end = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
        val n = minOf(end - start, word.length)
        for (i in 0 until n) {
            val d = text[start + i] - word[i]
            if (d != 0) return d
        }
        return (end - start) - word.length
    }

    companion object {
        private const val TAG = "KnownWords"
        private const val ASSET = "common/dictionaries/en_known_words.txt"

        /** [input] must be sorted, one lowercase word per line, as the build script writes it. */
        fun parse(input: InputStream): KnownWords {
            val text = input.bufferedReader(Charsets.UTF_8).readText().trimEnd('\n')
            val starts = ArrayList<Int>(100_000)
            starts.add(0)
            text.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
            return KnownWords(text, starts.toIntArray())
        }

        @Volatile private var shared: KnownWords? = null
        @Volatile private var loading = false

        /** The app-wide list, or null while it loads on a background thread. */
        fun shared(assets: AssetManager): KnownWords? {
            shared?.let { return it }
            synchronized(this) {
                if (!loading) {
                    loading = true
                    Thread({
                        shared = try {
                            assets.open(ASSET).use { parse(it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "No bundled known words: ${e.message}")
                            null
                        }
                    }, "KnownWords-load").start()
                }
            }
            return null
        }
    }
}
