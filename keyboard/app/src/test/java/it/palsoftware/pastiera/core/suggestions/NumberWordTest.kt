package it.palsoftware.pastiera.core.suggestions

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
// "7th", "4oz": the lookups normalize digits away, which made these "th" and
// "oz" and let autocorrect turn "4th" into "the".
class NumberWordTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val repository by lazy { EnglishFixture.repository(context) }
    private val bigrams by lazy { EnglishFixture.bigrams() }

    private fun controller(): AutoReplaceController {
        val typo = TypoModel(repository, Locale.ENGLISH) { bigrams }
        return AutoReplaceController(
            repository = repository,
            suggestionEngine = SuggestionEngine(repository, Locale.ENGLISH),
            settingsProvider = { SuggestionSettings(autoReplaceOnSpaceEnter = true) },
            typoModel = typo,
            bigrams = { bigrams },
            exactReplacementProvider = { word, b ->
                it.palsoftware.pastiera.inputmethod.AutoCorrector.processText(word + (b ?: ' '), "en", context) { repository.isKnownWord(it) }
                    ?.takeIf { (o, r) -> o == word && r != word }?.second
            },
            realWordFixer = RealWordFixer(repository, Locale.ENGLISH, { bigrams }, TypoModel(repository, Locale.ENGLISH, RealWordFixer.SLIP_TUNING) { bigrams })
        )
    }

    private fun space(controller: AutoReplaceController, text: String, word: String): FakeInputConnection {
        val connection = FakeInputConnection(context, text)
        val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
        tracker.setWord(word)
        controller.handleBoundary(KeyEvent.KEYCODE_SPACE, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE), tracker, connection)
        return connection
    }

    @Test
    fun numbersAreLeftAlone() {
        it.palsoftware.pastiera.inputmethod.AutoCorrector.loadCorrections(context.assets, context)
        val controller = controller()
        val out = listOf("4th", "7th", "4oz", "2pm", "1st", "3rd", "10k").map { w ->
            val t = "the weekend of Nov $w"
            w to space(controller, t, w).text.removePrefix("the weekend of Nov ")
        }
        out.forEach { (w, got) -> assertEquals(w + " ", got) }
    }

    @Test
    fun everydayWordsMissingFromTheDictionaryAreLeftAlone() {
        val controller = controller()
        assertEquals("be poop water ", space(controller, "be poop water", "water").text)
        for (w in listOf("poop", "grandma", "faucet")) assertEquals("the $w ", space(controller, "the $w", w).text)
    }

    @Test
    fun barOffersNothingForNumbers() {
        for (w in listOf("4th", "7th", "4oz")) {
            assertTrue(SuggestionEngine(repository, Locale.ENGLISH).suggest(w, 3).isEmpty())
            assertTrue(WordBarRanker(repository, Locale.ENGLISH, { bigrams }, null).suggest(w, "Nov").isEmpty())
        }
    }

    private class FakeInputConnection(context: Context, initialText: String) :
        BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder(initialText)
        val text: String get() = buffer.toString()
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            buffer.delete((buffer.length - beforeLength).coerceAtLeast(0), buffer.length)
            return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
    }
}
