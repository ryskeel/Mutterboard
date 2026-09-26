package it.palsoftware.pastiera.core.suggestions

import android.content.Context
import java.io.File
import java.util.Locale

/** The English dictionary exactly as the phone loads it, for the scorecards. */
object EnglishFixture {
    fun assetFile(path: String): File = listOf(
        File("src/main/assets/common/$path"),
        File("keyboard/app/src/main/assets/common/$path")
    ).first { it.exists() }

    fun repository(context: Context): AndroidDictionaryRepository {
        val repository = AndroidDictionaryRepository(
            context = context,
            assets = context.assets,
            userDictionaryStore = UserDictionaryStore(),
            baseLocale = Locale.ENGLISH
        )
        // The compiled .dict is what the phone loads; the JSON beside it is only
        // read for the list of common words to misspell.
        kotlinx.coroutines.runBlocking { repository.loadSerializedFromFile(assetFile("dictionaries_serialized/en_base.dict")) }
        val extras = repository.loadLocaleExtras()
        check(extras.isNotEmpty()) { "en_extra.json did not load" }
        repository.index(extras, keepExisting = true)
        repository.addToSymSpell(extras)
        repository.isReady = true
        return repository
    }

    fun bigrams(): BigramModel =
        assetFile("dictionaries/en_bigrams.tsv").bufferedReader().use { BigramModel.parse(it) }

    /** Held-out Tatoeba sentences the bigram table never saw. */
    fun heldOutSentences(): List<String> = listOf(
        File("src/test/resources/tatoeba_en_heldout.txt"),
        File("keyboard/app/src/test/resources/tatoeba_en_heldout.txt")
    ).first { it.exists() }.readLines().filter { it.isNotBlank() }

    private val token = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
    fun tokens(sentence: String): List<String> = token.findAll(sentence.replace('\u2019', '\'')).map { it.value }.toList()
}
