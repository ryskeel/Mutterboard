package it.palsoftware.pastiera.core.suggestions

import kotlin.random.Random

/** The slips a thumb makes on a small hardware QWERTY, shared by the scorecards. */
object TypoGenerator {
    // Standard QWERTY grid, staggered like the Titan's hardware keys.
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val neighbours: Map<Char, List<Char>> by lazy {
        val pos = mutableMapOf<Char, Pair<Double, Double>>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> pos[ch] = r.toDouble() to c + r * 0.5 } }
        pos.mapValues { (ch, p) ->
            pos.filter { (other, q) ->
                other != ch && kotlin.math.abs(p.first - q.first) <= 1.0 && kotlin.math.abs(p.second - q.second) <= 1.0
            }.keys.toList()
        }
    }

    fun neighbourSwap(word: String, random: Random): String? {
        val i = 1 + random.nextInt(word.length - 1)
        val n = neighbours[word[i]] ?: return null
        return word.substring(0, i) + n.random(random) + word.substring(i + 1)
    }

    fun extraNeighbour(word: String, random: Random): String? {
        val i = random.nextInt(word.length)
        val n = neighbours[word[i]] ?: return null
        val at = i + 1
        return word.substring(0, at) + n.random(random) + word.substring(at)
    }

    fun droppedLetter(word: String, random: Random): String? {
        val i = 1 + random.nextInt(word.length - 1)
        return word.removeRange(i, i + 1)
    }

    fun droppedFirstLetter(word: String, random: Random): String? = word.substring(1)

    fun swappedPair(word: String, random: Random): String? {
        if (word.length < 3) return null
        val i = 1 + random.nextInt(word.length - 2)
        if (word[i] == word[i + 1]) return null
        return word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
    }

    fun doubleLetter(word: String, random: Random): String? {
        val doubled = (0 until word.length - 1).firstOrNull { word[it] == word[it + 1] }
        if (doubled != null) return word.removeRange(doubled, doubled + 1)
        val i = 1 + random.nextInt(word.length - 1)
        return word.substring(0, i + 1) + word[i] + word.substring(i + 1)
    }


    /** One typo of [word] of a random kind, or null. */
    fun any(word: String, random: Random): Pair<String, String>? {
        val kinds = listOf(
            "neighbour key" to ::neighbourSwap,
            "extra key" to ::extraNeighbour,
            "dropped letter" to ::droppedLetter,
            "swapped pair" to ::swappedPair,
            "double letter" to ::doubleLetter,
            "dropped first" to ::droppedFirstLetter,
        )
        val (kind, make) = kinds[random.nextInt(kinds.size)]
        return make(word, random)?.let { it to kind }
    }
}
