package com.acazen.app

/**
 * Recognises the spoken command "Open Acazen" (and natural variants of it) on the listening
 * screen, so Acazen can open straight away instead of sending the words to the chat as a question.
 *
 * Only a whole utterance counts: "open Acazen", "please open the Acazen app", "launch acazen now"
 * match, while "open Acazen and explain photosynthesis" does not — that is a real question and is
 * sent as one. Speech recognizers often split or misspell the brand name ("a cazen", "akazen",
 * "a cousin"), so the name is checked loosely; a wrong guess only ever opens the app.
 *
 * Also understands the common Hinglish form ("Acazen kholo", "Acazen khol do").
 */
object OpenCommand {

    /**
     * A matching live transcript must stay unchanged this long before we act on it. Long enough
     * that "open Acazen and explain…" isn't cut off after the first two words, short enough to
     * feel instant.
     */
    const val SETTLE_MS = 450L

    private val LEAD = """(?:(?:hey|hi|ok|okay|please|can you|could you|would you|will you|just)\s+)*"""
    private val VERB = """(?:open|launch|start|run)"""
    private val TAIL = """(?:\s+(?:please|now|right now|instantly|quickly|fast|for me))*"""

    private val ENGLISH = Regex("""$LEAD$VERB\s+(?:up\s+)?(?:the\s+|my\s+)?(?:app\s+)?(.+?)(?:\s+app)?$TAIL""")
    private val HINDI = Regex(
        """$LEAD(.+?)\s+(?:kholo|khol do|khol dijiye|khol de|open karo|start karo|chalu karo|chalao)(?:\s+please)?"""
    )

    // "acazen", "akazen", "acasen", "acazens", "ocazen", "acazine", "a cousin" … (spaces removed)
    private val NAME = Regex("""[ao](?:c|k|ck|q)[aeou]u?(?:z|s|zz|zh|x)(?:e|a|i|o|u|ee|ou|ei)?n(?:s|e)?""")

    private val NON_WORD = Regex("[^a-z0-9 ]")
    private val SPACES = Regex("\\s+")

    /** True if [spoken] is, as a whole, a request to open Acazen. */
    fun matches(spoken: String?): Boolean {
        if (spoken.isNullOrBlank()) return false
        val text = spoken.lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()
        val m = ENGLISH.matchEntire(text) ?: HINDI.matchEntire(text) ?: return false
        val name = m.groupValues[1]
        if (name.split(' ').size > 4) return false // "a ca zen" is fine, a sentence is not
        return NAME.matches(name.replace(" ", ""))
    }
}
