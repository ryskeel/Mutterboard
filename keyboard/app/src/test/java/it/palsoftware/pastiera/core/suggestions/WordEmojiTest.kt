package it.palsoftware.pastiera.core.suggestions

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.children
import it.palsoftware.pastiera.inputmethod.suggestions.ui.FullSuggestionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The emoji in the suggestion bar, on the real table and the real bar. The
 * table's job is as much staying quiet as answering, so the held-out sentences
 * measure how often it speaks up during ordinary typing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WordEmojiTest {

    private val table = EnglishFixture.assetFile("dictionaries/en_emoji.tsv").reader().use { reader ->
        WordEmoji.parse(reader) { true }
    }

    @Test
    fun `the words the feature was asked for`() {
        assertEquals("😊", table.forWord("smile"))
        assertEquals("🎉", table.forWord("party"))
    }

    @Test
    fun `everyday words get the obvious emoji, whatever the keywords say`() {
        mapOf(
            "love" to "❤️", "happy" to "😊", "home" to "🏠", "sad" to "😢", "lol" to "😂", "thanks" to "🙏",
            "dog" to "🐶", "pizza" to "🍕"
        ).forEach { (word, emoji) -> assertEquals(word, emoji, table.forWord(word)) }
    }

    @Test
    fun `case does not matter`() {
        assertEquals("🎉", table.forWord("Party"))
        assertEquals("🎉", table.forWord("PARTY"))
    }

    @Test
    fun `a partial word gets nothing`() {
        assertNull(table.forWord("part"))
        assertNull(table.forWord("smil"))
    }

    @Test
    fun `the words the keywords misfire on stay quiet`() {
        listOf("the", "of", "one", "two", "you", "me", "not", "no", "at", "can", "like", "first", "way", "oh", "de")
            .forEach { assertNull(it, table.forWord(it)) }
    }

    @Test
    fun `an emoji the phone cannot draw gives way to the next`() {
        val parsed = WordEmoji.parse("party\t🎉 🥳\nsmile\t😊\n".reader()) { it != "🎉" && it != "😊" }
        assertEquals("🥳", parsed.forWord("party"))
        assertNull(parsed.forWord("smile"))
    }

    @Test
    fun `ordinary typing mostly leaves the bar alone`() {
        val words = EnglishFixture.heldOutSentences().flatMap { sentence ->
            EnglishFixture.tokens(sentence)
        }
        val hits = words.groupingBy { it }.eachCount().filterKeys { table.forWord(it) != null }
        val rate = hits.values.sum().toDouble() / words.size
        println(
            "emoji on %.1f%% of %d held-out words; most often: %s".format(
                rate * 100,
                words.size,
                hits.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key} ${it.value}" }
            )
        )
        assertTrue("emoji on ${rate * 100}% of words", rate < 0.08)
    }

    @Test
    fun `the emoji takes the right slot and the second word moves left`() {
        assertEquals(
            listOf("smiling", "smiled", "😊"),
            barSlots(WordEmoji.placeInBar(listOf("smiled", "smiling", "smiles"), "smile", "😊", false))
        )
    }

    @Test
    fun `with no other words the emoji is still on the right`() {
        assertEquals(listOf("", "sushi", "🍣"), barSlots(WordEmoji.placeInBar(emptyList(), "sushi", "🍣", false)))
    }

    @Test
    fun `a word waiting to be added keeps its offer`() {
        assertEquals(
            listOf("boba", "🧋", ""),
            barSlots(WordEmoji.placeInBar(emptyList(), "boba", "🧋", true), addWord = "boba")
        )
    }

    @Test
    fun `tapping the emoji replaces the word and leaves a space`() {
        val field = EditText(RuntimeEnvironment.getApplication()).apply {
            setText("lets party")
            setSelection(text.length)
        }
        val bar = showBar(WordEmoji.placeInBar(listOf("parts"), "party", "🎉", false), field)
        (bar.wordsView!!.children.last() as TextView).performClick()
        assertEquals("lets 🎉 ", field.text.toString())
    }

    private fun barSlots(suggestions: List<String>, addWord: String? = null): List<String> =
        showBar(suggestions, null, addWord).wordsView!!.children.map { (it as TextView).text.toString() }.toList()

    private fun showBar(suggestions: List<String>, field: EditText?, addWord: String? = null): FullSuggestionsBar {
        val bar = FullSuggestionsBar(RuntimeEnvironment.getApplication())
        bar.ensureView()
        bar.requireDictionaryForSuggestions = false
        bar.update(
            suggestions = suggestions,
            shouldShow = true,
            inputConnection = field?.onCreateInputConnection(EditorInfo()),
            listener = null,
            shouldDisableSuggestions = false,
            addWordCandidate = addWord,
            onAddUserWord = null,
            onAddUserWordSubstitutionRequested = null,
            onSuggestionCommitted = null,
            onHideSuggestion = null,
            onDeleteUserSuggestion = null,
            canDeleteUserSuggestion = null
        )
        return bar
    }
}
