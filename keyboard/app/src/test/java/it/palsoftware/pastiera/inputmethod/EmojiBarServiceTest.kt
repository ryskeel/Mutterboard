package it.palsoftware.pastiera.inputmethod

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.EditText
import it.palsoftware.pastiera.SettingsManager
import it.palsoftware.pastiera.core.InputContextState
import it.palsoftware.pastiera.core.suggestions.SuggestionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The emoji in the bar as the real service puts it there, swipe included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmojiBarServiceTest {

    private lateinit var service: PhysicalKeyboardInputMethodService
    private lateinit var field: EditText
    private lateinit var editorInfo: EditorInfo

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
        // Without a subtype the service falls back to Italian.
        shadowOf(context.getSystemService(InputMethodManager::class.java)).setCurrentInputMethodSubtype(
            InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeLocale("en_US").setSubtypeMode("keyboard").build()
        )
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        field = EditText(RuntimeEnvironment.getApplication())
        editorInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val connection = field.onCreateInputConnection(editorInfo)
        setField("mInputConnection", connection)
        setField("mStartedInputConnection", connection)
        setField("mInputEditorInfo", editorInfo)
        setField("isInputViewActive", true)
        setField("inputContextState", InputContextState.fromEditorInfo(editorInfo))
    }

    @Test
    fun `typing a word with an emoji puts it on the right`() {
        type("party")
        assertEquals(listOf(PARTY), visibleOnceLoaded(PARTY).drop(1).take(1))
    }

    @Test
    fun `a word without one leaves the bar alone`() {
        type("party")
        assertTrue(PARTY in visibleOnceLoaded(PARTY))
        field.setText("")
        controller().onContextReset()
        type("because")
        assertFalse(visible().any { it == PARTY })
    }

    @Test
    fun `a password field gets no emoji`() {
        type("party")
        assertTrue(PARTY in visibleOnceLoaded(PARTY))
        setField("inputContextState", InputContextState.fromEditorInfo(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }))
        assertTrue(visible().none { it == PARTY })
    }

    @Test
    fun `a swipe on the right takes the emoji`() {
        type("party")
        visibleOnceLoaded(PARTY)
        service.javaClass.getDeclaredMethod("acceptSuggestionAtIndex", Int::class.java).run {
            isAccessible = true
            invoke(service, 2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("$PARTY ", field.text.toString())
    }

    private companion object {
        // Robolectric's Paint has no glyphs, so 🎉 counts as undrawable and "party"
        // gives way to its second emoji, which minApi.txt vouches for.
        const val PARTY = "🥳"
    }

    private fun type(word: String) {
        field.setText(word)
        field.setSelection(word.length)
        word.forEach { controller().onCharacterCommitted(it.toString(), field.onCreateInputConnection(editorInfo)) }
    }

    /** The table loads on its own thread, so the first asks come back without it. */
    private fun visibleOnceLoaded(emoji: String): List<String> {
        repeat(200) {
            val shown = visible()
            if (emoji in shown) return shown
            Thread.sleep(25)
        }
        return visible()
    }

    @Suppress("UNCHECKED_CAST")
    private fun visible(): List<String> =
        service.javaClass.getDeclaredMethod("visibleSuggestionStrings").run {
            isAccessible = true
            invoke(service) as List<String>
        }

    private fun controller(): SuggestionController =
        service.javaClass.getDeclaredField("suggestionController").run {
            isAccessible = true
            get(service) as SuggestionController
        }

    private fun setField(name: String, value: Any?) {
        var type: Class<*>? = service.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(service, value)
                return
            }
            type = type.superclass
        }
        error("no field $name")
    }
}
