package com.dailygames.hub

/** Parses links shared into Dailies from other apps (Chrome → Share → Dailies). */
object SharedLinkParser {
    private val URL_REGEX = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCT = setOf('.', ',', ';', '!', '?', ')', '\'', '>', '"', ']')

    /**
     * Extracts the first HTTP/HTTPS URL from a shared `text/plain` intent payload,
     * stripping trailing sentence punctuation while keeping balanced `)` when `(` appears in the URL,
     * or returns the trimmed text if no explicit URL scheme is embedded.
     */
    fun extractSharedUrl(action: String?, mimeType: String?, rawText: String?): String? {
        val baseMime = mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.US)
        if (action != "android.intent.action.SEND" || baseMime != "text/plain") return null
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null
        val found = URL_REGEX.find(text)?.value ?: return text
        return trimUrlPunctuation(found)
    }

    private fun trimUrlPunctuation(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            if (c !in TRAILING_PUNCT) break
            if (c == ')') {
                val sub = url.substring(0, end)
                val opens = sub.count { it == '(' }
                val closes = sub.count { it == ')' }
                if (opens >= closes) break
            }
            end--
        }
        return url.substring(0, end)
    }

    /**
     * The full shared text, handed to the hub so a game's share result can be logged.
     * Capped at [MAX_SHARED_TEXT] (8000 characters) to guard bridge/intent memory while allowing
     * multi-line share messages containing results plus extra commentary (the hub's strict result
     * check itself enforces a 4000-character limit on the matched share result).
     */
    fun sharedTextForLogging(rawText: String?): String {
        val text = rawText?.trim().orEmpty()
        return if (text.length > MAX_SHARED_TEXT) "" else text
    }

    private const val MAX_SHARED_TEXT = 8000
}

