package com.dailygames.hub

import java.net.URI
import java.util.Locale

/**
 * Strict check used for *automatic* logging: is this copied/shared text a daily-game result?
 * Deliberately stricter than the hub's "+ Score" paste check, so copying a game's link or name never
 * logs anything. Mirrors isStrictShareResult() in assets/hub-core.js; both run the fixtures in
 * app/src/test/resources/share_cases.json.
 */
object ShareResultDetector {
    // ⬛ ⬜ ⚫ ⚪, coloured squares/circles 🟠-🟫, 🔴 🔵, ✅ ❌ 🟥 etc.
    private val GLYPH = Regex("[\\x{2B1B}\\x{2B1C}\\x{26AB}\\x{26AA}\\x{2705}\\x{274C}\\x{1F7E0}-\\x{1F7EB}\\x{1F534}\\x{1F535}]")
    // Explicit ASCII classes instead of \\s / \\b / \\d: Android's regex engine (ICU) makes those
    // Unicode-aware while the JVM tests and the hub's JS treat them as ASCII. NBSP counts as a space.
    private val SINGLE_URL = Regex("^https?://[^ \\t\\n\\r\\f\\x{0B}\\x{A0}]+$", RegexOption.IGNORE_CASE)
    private val FRACTION = Regex("(?<![0-9/])(?:[0-9]{1,2}|x)[ \\t\\x{A0}]*/[ \\t\\x{A0}]*[0-9]{1,2}(?![0-9/])", RegexOption.IGNORE_CASE)
    private val CONTEXT = Regex("\\n|#[ \\x{A0}]?[0-9]|(?<![A-Za-z0-9_])(?:day|puzzle|guess|guesses|tries|try|attempts?|mistakes?|score|points?|streak)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val COUNTED = Regex("#[ \\x{A0}]?[0-9]+|(?<![A-Za-z0-9_])[0-9]+[ \\t\\x{A0}]*(?:guess|guesses|tries|attempts?|mistakes?|points?|pts|moves?|swaps?|seconds?|secs?|minutes?|mins?)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    // Rules / help text ("Guess the Wordle in 6 tries") names the game and a count but isn't a result;
    // it is rejected unless it carries a puzzle number ("#512"), which help text doesn't.
    private val HELP = Regex("(?<![A-Za-z0-9_])(?:how to play|you have[ \\t\\x{A0}]+[0-9]+|you get[ \\t\\x{A0}]+[0-9]+|in[ \\t\\x{A0}]+[0-9]+[ \\t\\x{A0}]+(?:tries|guesses|attempts|chances)|guess the|find the|can you|each guess|tap to|rules)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val PUZZLE_NO = Regex("#[ \\x{A0}]?[0-9]")

    fun isStrictShareResult(text: String?, gameName: String?, gameUrl: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.length < 3 || t.length > 4000) return false
        if (SINGLE_URL.matches(t)) return false
        if (GLYPH.containsMatchIn(t)) return true
        val lower = t.lowercase(Locale.US)
        val name = gameName?.trim()?.lowercase(Locale.US).orEmpty()
        val host = hostOf(gameUrl)
        val mentionsGame = (name.length >= 3 && lower.contains(name)) || (host.isNotEmpty() && lower.contains(host))
        if (HELP.containsMatchIn(t) && !PUZZLE_NO.containsMatchIn(t)) return false
        if (FRACTION.containsMatchIn(t) && (CONTEXT.containsMatchIn(t) || mentionsGame)) return true
        return mentionsGame && COUNTED.containsMatchIn(t)
    }

    /** Lower-cased host without "www.", tolerant of URLs java.net.URI rejects (e.g. underscores). */
    private fun hostOf(url: String?): String {
        val u = url?.trim().orEmpty()
        val viaUri = runCatching { URI(u).host }.getOrNull()
        val raw = viaUri ?: Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:@]+@)?([^/?#:]+)").find(u)?.groupValues?.get(2)
        return raw?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
    }
}
