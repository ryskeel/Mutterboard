package it.palsoftware.pastiera.core.suggestions

import org.junit.Test
import java.io.File

/**
 * Contractions typed without the apostrophe, where the bare form is also a
 * word: "were" or "we're", "well" or "we'll", "its" or "it's". Gboard gets
 * these right from context without the apostrophe key ever being pressed.
 *
 * Every occurrence of either form in the held-out sentences is typed bare, and
 * each way of deciding is scored on whether it lands on what was written.
 *
 *   ./gradlew :keyboard:testDebugUnitTest --tests '*ContractionScorecardTest*'
 *
 * Report: keyboard/app/build/contraction-scorecard.txt
 */
class ContractionScorecardTest {

    private data class Case(val twoBack: String?, val before: String?, val bare: String, val after: String?, val written: String)

    @Test
    fun scorecard() {
        val bigrams = EnglishFixture.bigrams()
        val cases = EnglishFixture.heldOutSentences().flatMap { sentence ->
            val tokens = EnglishFixture.tokens(sentence)
            tokens.indices.mapNotNull { i ->
                val lower = tokens[i].lowercase()
                val bare = lower.replace("'", "")
                val contraction = AMBIGUOUS[bare] ?: return@mapNotNull null
                if (lower != bare && lower != contraction.lowercase()) return@mapNotNull null
                Case(tokens.getOrNull(i - 2), tokens.getOrNull(i - 1), bare, tokens.getOrNull(i + 1), if (lower == bare) bare else contraction)
            }
        }

        fun l(prev: String?, w: String) = bigrams.likelihood(prev, w)
        val deciders = linkedMapOf<String, (Case) -> String>(
            // Before 2026-09-27: a fixed rule, overridden when the previous
            // word prefers the bare word, and no rule at all for "were",
            // "well" or "hell".
            "before (no were/well rules)" to { c ->
                val contraction = AMBIGUOUS.getValue(c.bare)
                if (c.bare in NO_RULE_TODAY) c.bare
                else if (l(c.before, c.bare) > l(c.before, contraction)) c.bare else contraction
            },
            "space bar (word before)" to { c ->
                val contraction = AMBIGUOUS.getValue(c.bare)
                if (l(c.before, c.bare) > l(c.before, contraction)) c.bare else contraction
            },
            // What ships for were/well/its/...: the space bar's call, taken
            // again once the next word is typed (AutoReplaceController.
            // reconsiderContraction). At the end of a sentence there is no
            // next word and the first call stands.
            "then one word late" to { c ->
                val contraction = AMBIGUOUS.getValue(c.bare)
                fun fit(w: String) = l(c.before, w) * (c.after?.let { l(w, it) } ?: 1.0)
                if (fit(c.bare) >= fit(contraction)) c.bare else contraction
            },
        )

        val out = StringBuilder("Contractions typed without the apostrophe (${cases.size} cases in held-out text)\n\n")
        for ((name, decide) in deciders) {
            val right = cases.count { decide(it).equals(it.written, ignoreCase = true) }
            out.append("%-28s %5.1f%% right\n".format(name, right * 100.0 / cases.size))
            for ((bare, group) in cases.groupBy { it.bare }.toSortedMap()) {
                val contraction = AMBIGUOUS.getValue(bare)
                val asBare = group.filter { it.written == bare }
                val asContraction = group.filter { it.written != bare }
                fun pct(g: List<Case>) = if (g.isEmpty()) "  -  " else "%3d%%".format(g.count { decide(it).equals(it.written, ignoreCase = true) } * 100 / g.size)
                out.append("    %-6s meant %-6s %s of %-4d  meant %-7s %s of %d\n".format(
                    bare, bare, pct(asBare), asBare.size, contraction, pct(asContraction), asContraction.size))
            }
            out.append("\n")
        }
        val report = listOf(File("build"), File("keyboard/app/build")).first { it.exists() }.resolve("contraction-scorecard.txt")
        report.writeText(out.toString())
        println(out)
    }

    companion object {
        /** Bare form -> contraction, where the bare form is also a word. */
        val AMBIGUOUS = mapOf(
            "were" to "we're", "well" to "we'll", "its" to "it's", "ill" to "I'll",
            "lets" to "let's", "shell" to "she'll", "wed" to "we'd", "shed" to "she'd",
            "id" to "I'd", "hell" to "he'll", "cant" to "can't", "wont" to "won't",
        )
        val NO_RULE_TODAY = setOf("were", "well", "hell")
    }
}
