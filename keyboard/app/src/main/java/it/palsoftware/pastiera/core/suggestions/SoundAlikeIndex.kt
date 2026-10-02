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

        private const val VOWELS = "aeiou"

        /**
         * One pass per rule, in order: each sees the last one's output, so
         * "tch" is read before "ch" and "sh" after "shi" + vowel. Hand-written
         * because the same rules as regexes took four seconds over the
         * dictionary on the Titan II, at the moment the keyboard starts.
         */
        fun key(word: String): String {
            var k = collapseDoubles(word.lowercase())
            k = shSound(k)                                 // nation, musician, mansion, fashion
            k = k.replace("sh", "X")
            k = k.replace("tch", "C")
            k = k.replace("ch", "C")
            k = k.replace("ph", "f")
            k = dropGh(k)                                  // night, though
            k = k.replace("ck", "k")
            k = soften(k, "c", 's')
            k = k.replace('c', 'k').replace('q', 'k')
            k = k.replace("x", "ks")
            k = k.replace('z', 's')
            k = soften(k, "dg", 'j')
            if (k.startsWith("wr")) k = "r" + k.substring(2)
            if (k.startsWith("kn")) k = "n" + k.substring(2)
            k = vowelY(k)                                  // "day", "key": y as a vowel
            if (k.endsWith("e")) k = k.dropLast(1)         // a silent final e
            k = vowelRuns(k)
            return collapseDoubles(k)
        }

        private fun collapseDoubles(s: String): String {
            val out = StringBuilder(s.length)
            for (c in s) if (out.isEmpty() || out.last() != c) out.append(c)
            return out.toString()
        }

        // t, c or s, an optional h, then i before a vowel: the "sh" sound.
        private fun shSound(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] in "tcs") {
                    var j = i + 1
                    if (j < s.length && s[j] == 'h') j++
                    if (j + 1 < s.length && s[j] == 'i' && s[j + 1] in VOWELS) {
                        out.append('X')
                        i = j + 1
                        continue
                    }
                }
                out.append(s[i])
                i++
            }
            return out.toString()
        }

        private fun dropGh(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == 'g' && i + 1 < s.length && s[i + 1] == 'h' && (i + 2 >= s.length || s[i + 2] !in VOWELS)) {
                    i += 2
                    continue
                }
                out.append(s[i])
                i++
            }
            return out.toString()
        }

        // [from] before e, i or y becomes [to]: soft c, the j in "judge".
        private fun soften(s: String, from: String, to: Char): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s.startsWith(from, i) && i + from.length < s.length && s[i + from.length] in "eiy") {
                    out.append(to)
                    i += from.length
                    continue
                }
                out.append(s[i])
                i++
            }
            return out.toString()
        }

        private fun vowelY(s: String): String {
            val out = StringBuilder(s)
            for (i in 1 until s.length) if (s[i] == 'y' && s[i - 1] in VOWELS) out.setCharAt(i, 'a')
            return out.toString()
        }

        private fun vowelRuns(s: String): String {
            val out = StringBuilder(s.length)
            for (c in s) {
                if (c in "aeiouy") {
                    if (out.isEmpty() || out.last() != '*') out.append('*')
                } else {
                    out.append(c)
                }
            }
            return out.toString()
        }
    }
}
