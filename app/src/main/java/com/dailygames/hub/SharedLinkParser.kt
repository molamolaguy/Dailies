package com.dailygames.hub

/** Parses links shared into Dailies from other apps (Chrome → Share → Dailies). */
object SharedLinkParser {
    private val URL_REGEX = Regex("(https?://[^\\s]+)", RegexOption.IGNORE_CASE)

    /**
     * Extracts the first HTTP/HTTPS URL from a shared `text/plain` intent payload,
     * or returns the trimmed text if no explicit URL scheme is embedded.
     */
    fun extractSharedUrl(action: String?, mimeType: String?, rawText: String?): String? {
        if (action != "android.intent.action.SEND" || mimeType != "text/plain") return null
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null
        return URL_REGEX.find(text)?.value ?: text
    }

    /**
     * The full shared text, handed to the hub so a game's share result can be logged. Bounded: share
     * results are short (the hub's own check rejects anything over 4000 characters).
     */
    fun sharedTextForLogging(rawText: String?): String {
        val text = rawText?.trim().orEmpty()
        return if (text.length > MAX_SHARED_TEXT) "" else text
    }

    private const val MAX_SHARED_TEXT = 8000
}
