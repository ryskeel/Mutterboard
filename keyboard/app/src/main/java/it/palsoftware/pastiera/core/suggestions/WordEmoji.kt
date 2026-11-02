package it.palsoftware.pastiera.core.suggestions

import android.content.res.AssetManager
import it.palsoftware.pastiera.data.emoji.EmojiAvailability
import java.io.Reader
import java.util.Locale

/**
 * Mutterboard's emoji for the word being typed (English): "party" puts 🎉 in
 * the bar's right slot, and taking it replaces the word like any suggestion.
 *
 * It answers one exact word with one emoji, from a table built by
 * scripts/build-emoji-words.py rather than from the picker's search, because
 * search ranks forty results for someone hunting for an emoji and this fires
 * on every word of ordinary typing. Why the table is shaped the way it is
 * lives in that script.
 */
class WordEmoji private constructor(private val emojiByWord: Map<String, String>) {

    fun forWord(word: String): String? = emojiByWord[word.lowercase(Locale.ROOT)]

    companion object {
        private const val ASSET = "common/dictionaries/en_emoji.tsv"

        /**
         * The words shown with [emoji] on the right, which is index 1 of the
         * list [FullSuggestionsBar] lays out. The second word moves to index 2
         * rather than dropping off, so it stays on the bar, on the left, and
         * the third word is the one given up.
         *
         * The bar puts a lone suggestion in the centre, so with no words to
         * show the typed word holds that slot, unless it is waiting to be
         * added: the bar drops the add offer for a word it already shows.
         */
        fun placeInBar(words: List<String>, typed: String, emoji: String, typedIsAddCandidate: Boolean): List<String> {
            if (words.isEmpty() && typedIsAddCandidate) return listOf(emoji)
            return listOfNotNull(words.firstOrNull() ?: typed, emoji, words.getOrNull(1))
        }

        fun parse(reader: Reader, isAvailable: (String) -> Boolean): WordEmoji {
            val table = HashMap<String, String>()
            reader.buffered().forEachLine { line ->
                val fields = line.split('\t', limit = 2)
                if (fields.size == 2) {
                    fields[1].split(' ').firstOrNull(isAvailable)?.let { table[fields[0]] = it }
                }
            }
            return WordEmoji(table)
        }

        private fun load(assets: AssetManager): WordEmoji {
            val availability = EmojiAvailability.fromAssets(assets)
            return assets.open(ASSET).reader().use { parse(it, availability::isAvailable) }
        }

        @Volatile private var shared: WordEmoji? = null
        @Volatile private var loading = false

        /** The app-wide table, or null while it loads; see [BigramModel.shared]. */
        fun shared(assets: AssetManager): WordEmoji? {
            shared?.let { return it }
            synchronized(this) {
                if (!loading) {
                    loading = true
                    Thread({ shared = load(assets) }, "WordEmoji-load").start()
                }
            }
            return null
        }
    }
}
