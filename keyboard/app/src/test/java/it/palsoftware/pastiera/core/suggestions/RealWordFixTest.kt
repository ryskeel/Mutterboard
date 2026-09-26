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
class RealWordFixTest {

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
            realWordFixer = RealWordFixer(repository, Locale.ENGLISH, { bigrams }, typo)
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
    fun slipIsFixedOnceTheNextWordIsTypedAndBackspaceUndoesIt() {
        val controller = controller()
        val connection = space(controller, "we where going", "going")
        assertEquals("we were going ", connection.text)
        assertTrue(controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, connection))
        assertEquals("we where going ", connection.text)
    }

    @Test
    fun correctTextIsLeftAlone() {
        val controller = controller()
        for (sentence in listOf("I want to go", "the show must go", "we are in the", "I feel ill today")) {
            val words = sentence.split(' ')
            assertEquals(sentence + " ", space(controller, sentence, words.last()).text)
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
