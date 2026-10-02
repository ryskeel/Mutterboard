package it.palsoftware.pastiera.core.suggestions

/**
 * English words grouped by roughly how they sound, for misspellings that are
 * spelled by ear rather than mistyped: "nutrician" and "nuutriciaan" are
 * three and four edits from "nutrition", past anything the edit-distance
 * lookup reaches, but all three sound the same.
 *
 * The key is deliberately coarse. It keeps the consonants in order, folds the
 * spellings English uses for one sound into one ("ph"/"f", "tion"/"cian"/
 * "sion", soft "c"/"s"), collapses doubled letters and reduces every run of
 * vowels to a single marker, since vowels are where people guess. A coarse
 * key finds more candidates; TypoModel still has to like one enough to use it.
 */
class SoundAlikeIndex(terms: Collection<String>) {

    private val byKey: Map<String, List<String>> = terms.asSequence()
        .filter { it.length >= MIN_LENGTH && it.all { c -> c in 'a'..'z' } }
        .groupBy { key(it) }

    fun lookup(word: String): List<String> =
        if (word.length < MIN_LENGTH) emptyList() else byKey[key(word)].orEmpty()

    companion object {
        const val MIN_LENGTH = 6

        // Order matters: longer spellings first, so "tch" is not read as "t" + "ch".
        private val RULES: List<Pair<Regex, String>> = listOf(
            Regex("[tcs]h?i(?=[aeiou])") to "X",   // nation, musician, mansion, fashion
            Regex("sh") to "X",
            Regex("tch") to "C",
            Regex("ch") to "C",
            Regex("ph") to "f",
            Regex("gh(?![aeiou])") to "",          // night, though
            Regex("ck") to "k",
            Regex("c(?=[eiy])") to "s",
            Regex("[cq]") to "k",
            Regex("x") to "ks",
            Regex("z") to "s",
            Regex("dg(?=[eiy])") to "j",
            Regex("^wr") to "r",
            Regex("^kn") to "n",
            Regex("(?<=[aeiou])y") to "a",         // "day", "key": y as a vowel
            Regex("e$") to "",                     // a silent final e
        )
        private val VOWEL_RUN = Regex("[aeiouy]+")
        private val DOUBLE = Regex("(.)\\1+")

        fun key(word: String): String {
            var k = DOUBLE.replace(word.lowercase(), "$1")
            for ((rule, sound) in RULES) k = rule.replace(k, sound)
            k = VOWEL_RUN.replace(k, "*")
            return DOUBLE.replace(k, "$1")
        }
    }
}
