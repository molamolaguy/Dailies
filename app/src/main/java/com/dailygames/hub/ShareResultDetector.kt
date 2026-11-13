package com.dailygames.hub

/**
 * Strict check used for *automatic* logging: is this copied/shared text a daily-game result?
 * Deliberately stricter than the hub's "+ Score" paste check, so copying a game's link or name, or
 * ordinary text (a shopping list with ✅, a date, an address), never logs anything. Mirrors
 * isStrictShareResult() in assets/hub-core.js; both run the fixtures in
 * app/src/test/resources/share_cases.json.
 *
 * Everything is matched against an ASCII-lower-cased copy with case-sensitive patterns and explicit
 * ASCII classes (no \s, \b, \d or IGNORE_CASE, which Kotlin makes Unicode-aware), so the JVM tests,
 * Android's ICU engine and the hub's JS agree exactly. `\z` here is JS's `$` (end of input).
 */
object ShareResultDetector {
    private const val CELL = """(?:[\x{2B1B}\x{2B1C}\x{26AB}\x{26AA}\x{2705}\x{274C}\x{1F7E0}-\x{1F7EB}\x{1F534}\x{1F535}]|[0-9]\x{FE0F}?\x{20E3})"""
    private val TRIM = Regex("""^[ \t\n\r\f\x{0B}\x{00A0}\x{FEFF}]+|[ \t\n\r\f\x{0B}\x{00A0}\x{FEFF}]+\z""")
    // Two result squares / circles / keycaps side by side: a share grid ("🟩🟩⬛", "5️⃣7️⃣", "🟥 🟩").
    private val GRID = Regex("""$CELL\x{FE0F}?[ ]?$CELL""")
    // A single one only counts with the game's name or a puzzle number ("✅ milk" is a shopping list).
    private val GLYPH = Regex(CELL)
    private val SINGLE_URL = Regex("""^https?://[^ \t\n\r\f\x{0B}\x{00A0}]+\z""")
    private val FRACTION = Regex("""(?<![0-9/.])([0-9]{1,2}|x)[ \t\x{00A0}]*/[ \t\x{00A0}]*([0-9]{1,2})(?![0-9/])""")
    private val CONTEXT = Regex("""#[ \x{00A0}]?[0-9]|(?<![a-z0-9_])(?:puzzle|guess|guesses|guessed|tries|attempts?|mistakes?|streak)(?![a-z0-9_])""")
    private val COUNTED = Regex("""(?<![a-z0-9_])[0-9]+[ \t\x{00A0}]*(?:guess|guesses|tries|attempts?|mistakes?|points?|pts|moves?|swaps?|seconds?|secs?|minutes?|mins?|words?|clues?|hints?|strokes?|shots?|steps?)(?![a-z0-9_])|(?<![a-z0-9_])(?:guessed|solved|got it)[ \t\x{00A0}]+in[ \t\x{00A0}]+[0-9]|(?<![a-z0-9_])(?:score|combo)[ \t\x{00A0}]*:[ \t\x{00A0}]*-?[0-9]|(?<![a-z0-9_])[0-9]+[ \t\x{00A0}]+(?:under|over)[ \t\x{00A0}]+par(?![a-z0-9_])|(?<![0-9.,])[0-9][0-9,]{2,}[ \t\x{00A0}]*/[ \t\x{00A0}]*[0-9][0-9,]{2,}(?![0-9])|(?<![0-9.])[0-9]{1,2}\.[0-9][ \t\x{00A0}]*/[ \t\x{00A0}]*[0-9]{1,2}(?![0-9])|\n[ \t\x{00A0}]*[0-9][0-9,]*[ \t\x{00A0}]*(?:\n|\z)""")
    // A solve time like "0:42" or "1:05:12" (not a clock time such as "9:14 am").
    private val TIME = Regex("""(?<![0-9:/])[0-9]{1,2}:[0-5][0-9](?::[0-5][0-9])?(?![0-9:])(?![ \t\x{00A0}]*[ap]\.?m(?![a-z]))""")
    // Rules / help text ("Guess the Wordle in 6 tries") names the game and a count but isn't a result;
    // it is rejected unless it carries a puzzle number ("#512"), which help text doesn't.
    private val HELP = Regex("""(?<![a-z0-9_])(?:how to play|you have[ \t\x{00A0}]+[0-9]+|you get[ \t\x{00A0}]+[0-9]+|in[ \t\x{00A0}]+[0-9]+[ \t\x{00A0}]+(?:tries|guesses|attempts|chances)|guess the|find the|can you|each guess|tap to|rules|allowed)(?![a-z0-9_])""")
    private val PUZZLE_NO = Regex("""#[ \x{00A0}]?[0-9]{2}""")
    // Results scored in other units ("4,250m", "87%", "1,200 xp").
    private val MEASURED = Regex("""(?<![a-z0-9_])[0-9][0-9,.]*[ \t\x{00A0}]*(?:m|meters?|metres?|km|ft|feet|%|xp|pts|points?|depth)(?![a-z0-9_])""")
    // A rank instead of a number ("I got to Genius in today's Spelling Bee!", "Rank: Mayor").
    private val RANK = Regex("""(?<![a-z0-9_])(?:i got to|i reached|rank[ \t\x{00A0}]*:)[ \t\x{00A0}]*[a-z]|\(perfect\)""")
    private val SOLVED = Regex("""(?<![a-z0-9_])(?:solved|completed|finished|beat|scored)(?![a-z0-9_])""")
    private val EMOJI = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B50}]""")
    private val NAME_PREFIX = Regex("""^(?:the|nyt|daily)[ ]+""")
    private val LINE_START = Regex("""^(?:\z|[0-9]+(?![0-9]*[:./])|[ \t\x{00A0}]*#|[ \t\x{00A0}]+[0-9][0-9,]{2,}(?![0-9,]*[:./]|[0-9,]*[ \t\x{00A0}]*m(?![a-z]))|[ \t\x{00A0}]+[-\x{2013}\x{2014}][ \t\x{00A0}]+)""")
    private val NAME_SPLIT = Regex("""[·:|–—]| - """)
    private val GENERIC_WORDS = setOf("the", "daily", "game", "games", "puzzle", "puzzles", "play", "today", "www", "html", "index")

    private fun strictTrim(s: String?): String = TRIM.replace(s.orEmpty(), "")
    private fun asciiLower(s: String): String = buildString(s.length) { s.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }
    private fun word(lower: String, key: String, after: String) =
        Regex("(?<![a-z0-9])" + Regex.escape(key) + "(?!$after)").containsMatchIn(lower)

    fun isStrictShareResult(text: String?, gameName: String?, gameUrl: String?): Boolean {
        val t = strictTrim(text)
        if (t.length < 3 || t.length > 4000) return false
        val lower = asciiLower(t)
        if (SINGLE_URL.containsMatchIn(lower)) return false
        if (GRID.containsMatchIn(t)) return true
        val mention = gameMention(lower, gameName, gameUrl)
        val puzzleNo = PUZZLE_NO.containsMatchIn(lower)
        if (GLYPH.containsMatchIn(t) && (mention > 0 || puzzleNo)) return true
        if (HELP.containsMatchIn(lower) && !puzzleNo) return false
        if (hasFraction(lower) && (mention > 0 || CONTEXT.containsMatchIn(lower))) return true
        if (mention == 0) return false
        val scored = COUNTED.containsMatchIn(lower) || MEASURED.containsMatchIn(lower) || TIME.containsMatchIn(lower) ||
            RANK.containsMatchIn(lower) || (EMOJI.containsMatchIn(t) && puzzleNo)
        if (!scored) return false
        // Named only in passing ("My zip code is 90210, 5 minutes away"): also needs a puzzle number or a "solved".
        return mention == 2 || puzzleNo || SOLVED.containsMatchIn(lower)
    }

    /** Whether a text names the game at all, or has a puzzle number (for clipboard text found after the fact). */
    fun mentionsGame(text: String?, gameName: String?, gameUrl: String?): Boolean {
        val lower = asciiLower(strictTrim(text))
        return gameMention(lower, gameName, gameUrl) > 0 || PUZZLE_NO.containsMatchIn(lower)
    }

    /** A guess fraction that could be a result: "3/6", "x/6" (not "24/7", "1/2 cup" or a date like "11/13/2026"). */
    private fun hasFraction(lower: String): Boolean = FRACTION.findAll(lower).any { m ->
        val max = m.groupValues[2].toInt()
        max >= 3 && (m.groupValues[1] == "x" || m.groupValues[1].toInt() <= max)
    }

    /** 2 = names the game clearly, 1 = in passing, 0 = not at all. See gameMention() in hub-core.js. */
    fun gameMention(lower: String, gameName: String?, gameUrl: String?): Int {
        val raw = asciiLower(strictTrim(gameName)).replace(Regex("[ \\t\\x{00A0}]+"), " ")
        val keys = mutableListOf<String>()
        fun add(k: String) { val s = strictTrim(k); if (s.length >= 3 && s !in keys) keys.add(s) }
        add(raw)
        add(NAME_PREFIX.replace(raw, ""))
        val head = raw.split(NAME_SPLIT)[0]
        add(head)
        add(NAME_PREFIX.replace(head, ""))
        val first = NAME_PREFIX.replace(raw, "").split(' ')[0]
        if (first.length >= 5 && first.all { it in 'a'..'z' || it in '0'..'9' }) add(first)
        var level = 0
        for (k in keys) {
            if (!word(lower, k, "[a-z]")) continue
            level = maxOf(level, if (k == raw && raw.contains(' ') && raw.length >= 6) 2 else 1)
        }
        if (level == 1) {
            val lineStart = lower.split('\n').any { line ->
                val l = NAME_PREFIX.replace(strictTrim(line).removePrefix("#"), "")
                keys.any { k -> l.startsWith(k) && LINE_START.containsMatchIn(l.substring(k.length)) }
            }
            if (lineStart) level = 2
        }
        val (host, path) = hostPath(gameUrl)
        val words = raw.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in GENERIC_WORDS }
        val shared = path.split('/').any { seg -> words.any { seg.contains(it) } }
        if (host.isNotEmpty()) {
            if (!shared && lower.contains(host)) level = 2
            else if (shared && lower.contains(host + path)) level = maxOf(level, 1)
            val label = if (shared) "" else siteLabel(host)
            if (label.length >= 4 && word(lower, label, "[a-z0-9]")) level = maxOf(level, 1)
        }
        return level
    }

    /** The site's own name from its host: "krillion" for krillion.io, "bbc" for www.bbc.co.uk. */
    fun siteLabel(host: String): String {
        val parts = asciiLower(host).split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return ""
        var label = parts[parts.size - 2]
        if (parts.size >= 3 && label in setOf("co", "com", "org", "net", "ac", "gov", "edu")) label = parts[parts.size - 3]
        return if (label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) label else ""
    }

    /**
     * Host (no "www.") and path (no trailing "/") of an http(s) link, ASCII-lower-cased, read the way
     * a browser's URL parser reads it (as hub-core.js does with `new URL`): "https:host" and extra
     * slashes are fine, a backslash ends the host, and %-escapes in the host are decoded.
     */
    private fun hostPath(url: String?): Pair<String, String> {
        val u = url?.trim().orEmpty()
        val m = URL_PARTS.find(u) ?: return "" to ""
        val rawHost = m.groupValues[2]
        val host = runCatching { java.net.URLDecoder.decode(rawHost.replace("+", "%2B"), "UTF-8") }.getOrDefault(rawHost)
        // No IP-literal hosts ("[::1]"): no game lives there, and the two parsers read them differently.
        if (host.isEmpty() || host.startsWith("[") || host.any { it == ' ' || it == '%' }) return "" to ""
        val path = m.groupValues[3].replace('\\', '/').ifEmpty { "/" }
        return asciiLower(host).removePrefix("www.") to asciiLower(path).trimEnd('/')
    }

    private val URL_PARTS = Regex("""^([hH][tT][tT][pP][sS]?):[/\\]*(?:[^/?#@\\]*@)?([^/?#:\\]+)(?::[0-9]*)?([^?#]*)""")
}
