package com.dailygames.hub

import org.json.JSONObject
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Structured metadata extracted from a daily game website's HTML and URL.
 */
data class ScrapedSiteMetadata(
    val name: String,
    val description: String,
    val faviconUrl: String,
    val resetTime: String,
    val resetZone: String,
    val metricType: String,
    val par: Int,
    val category: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("faviconUrl", faviconUrl)
        put("resetTime", resetTime)
        put("resetZone", resetZone)
        put("metricType", metricType)
        put("par", par)
        put("category", category)
    }
}

/**
 * Scrapes and parses HTML metadata from daily web game URLs (title, description, favicon,
 * daily reset schedule, score metric format, par target, and category).
 */
object WebsiteScraper {
    private const val MAX_HTML_CHARS = 131_072
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    // Precompiled regular expressions (K11)
    private val SCRIPT_STYLE_REGEX = Regex("""<(script|style|noscript)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val TITLE_SPLIT_REGEX = Regex("""(?:\s+[|\-\u2013\u2014]\s+|:\s+)""")
    private val TITLE_TAG_REGEX = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val NUMERIC_ENTITY_REGEX = Regex("""&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});""")
    private val CHARSET_REGEX = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
    private val CHALLENGE_TITLE_REGEX = Regex(
        """<title[^>]*>\s*(?:just a moment|attention required|access denied|please wait|checking your browser|ddos-guard)""",
        RegexOption.IGNORE_CASE
    )
    private val CHALLENGE_MARKER_REGEX = Regex(
        """(?:/cdn-cgi/challenge-platform/|cf-chl-|cf_chl_opt|id=["']challenge-form["']|captcha-delivery\.com)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * An HTML attribute value in either quote style. Double-quoted values may contain apostrophes and
     * single-quoted values may contain double quotes ("Guess today's word" used to be cut at the ').
     */
    private const val ATTR_VALUE = """(?:"([^"]*)"|'([^']*)')"""
    private val metaPatternCache = ConcurrentHashMap<String, List<Regex>>()
    private val linkPatternCache = ConcurrentHashMap<String, List<Regex>>()

    private val NAMED_ENTITIES = linkedMapOf(
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">",
        "&nbsp;" to " ", "&rsquo;" to "\u2019", "&lsquo;" to "\u2018", "&rdquo;" to "\u201D",
        "&ldquo;" to "\u201C", "&ndash;" to "\u2013", "&mdash;" to "\u2014", "&hellip;" to "\u2026",
        "&middot;" to "\u00B7", "&bull;" to "\u2022", "&copy;" to "\u00A9", "&reg;" to "\u00AE",
        "&trade;" to "\u2122", "&eacute;" to "\u00E9"
    )

    private val UTC_GMT_REGEX = Regex("""\b(utc|gmt)\b""", RegexOption.IGNORE_CASE)
    private val HHMM_RESET_REGEX = Regex(
        """(?:reset|refresh|new\s+(?:puzzle|game|daily))[^0-9]{0,25}\b([01]?\d|2[0-3]):([0-5]\d)\s*(am|pm)?\b""",
        RegexOption.IGNORE_CASE
    )
    /** "new puzzle every day at 9 AM ET" (hour only, with am/pm). */
    private val HOUR_AMPM_RESET_REGEX = Regex(
        """(?:reset|refresh|new\s+(?:puzzle|game|daily)|releas\w*|available)[^0-9]{0,25}\b(1[0-2]|0?[1-9])\s*(a\.?m\.?|p\.?m\.?)(?![a-z])""",
        RegexOption.IGNORE_CASE
    )
    private val MIDNIGHT_OR_NOON_REGEX = Regex("""\b(midnight|noon)\b""", RegexOption.IGNORE_CASE)

    /**
     * Time-zone words that may follow a reset time, mapped to the zone they were published in.
     * Short abbreviations (ET, PT…) only count right after the time, and in capitals, so ordinary
     * words aren't mistaken for zones.
     */
    private val ZONE_TOKENS: List<Pair<Regex, String>> = listOf(
        Regex("""^\W{0,3}(?:utc|gmt|zulu)\b""", RegexOption.IGNORE_CASE) to "utc",
        Regex("""^\W{0,3}(?:(?:your|my)\s+)?local(?:\s+time)?\b""", RegexOption.IGNORE_CASE) to "local",
        Regex("""^\W{0,3}(?:ET|EST|EDT)\b""") to "America/New_York",
        Regex("""^\W{0,3}(?:eastern|new\s+york)(?:\s+(?:standard|daylight))?(?:\s+time)?\b""", RegexOption.IGNORE_CASE) to "America/New_York",
        Regex("""^\W{0,3}(?:CT|CST|CDT)\b""") to "America/Chicago",
        Regex("""^\W{0,3}central(?:\s+(?:standard|daylight))?\s+time\b""", RegexOption.IGNORE_CASE) to "America/Chicago",
        Regex("""^\W{0,3}(?:MT|MST|MDT)\b""") to "America/Denver",
        Regex("""^\W{0,3}mountain(?:\s+(?:standard|daylight))?\s+time\b""", RegexOption.IGNORE_CASE) to "America/Denver",
        Regex("""^\W{0,3}(?:PT|PST|PDT)\b""") to "America/Los_Angeles",
        Regex("""^\W{0,3}pacific(?:\s+(?:standard|daylight))?(?:\s+time)?\b""", RegexOption.IGNORE_CASE) to "America/Los_Angeles",
        Regex("""^\W{0,3}(?:BST|UK\s+time)\b""") to "Europe/London",
        Regex("""^\W{0,3}(?:british|london|uk)\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/London",
        Regex("""^\W{0,3}(?:CET|CEST)\b""") to "Europe/Paris",
        Regex("""^\W{0,3}(?:central\s+european|paris|berlin)(?:\s+(?:summer|standard))?\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/Paris",
        Regex("""^\W{0,3}IST\b""") to "Asia/Kolkata",
        Regex("""^\W{0,3}JST\b""") to "Asia/Tokyo",
        Regex("""^\W{0,3}(?:japan|tokyo)(?:\s+standard)?\s+time\b""", RegexOption.IGNORE_CASE) to "Asia/Tokyo",
        Regex("""^\W{0,3}(?:AEST|AEDT)\b""") to "Australia/Sydney",
        Regex("""^\W{0,3}(?:NZST|NZDT)\b""") to "Pacific/Auckland"
    )

    /** Zone written immediately after a matched reset time (e.g. "9:00 PM ET"), or null. */
    private fun zoneAfter(text: String, endIndex: Int): String? {
        if (endIndex >= text.length) return null
        val tail = text.substring(endIndex, minOf(text.length, endIndex + 40))
        return ZONE_TOKENS.firstOrNull { (re, _) -> re.containsMatchIn(tail) }?.second
    }

    private val IN_GUESSES_REGEX = Regex("""\bin\s+([1-9]|1\d)\s+(?:tries|guesses|attempts|chances|mistakes)\b""", RegexOption.IGNORE_CASE)
    private val GUESSES_REGEX = Regex("""\b([1-9]|1\d)\s+(?:tries|guesses|attempts|chances)\b""", RegexOption.IGNORE_CASE)
    private val STOPWATCH_REGEX = Regex("""\b(stopwatch|timer|speedrun|fastest\s+time|seconds)\b""", RegexOption.IGNORE_CASE)
    private val POINTS_REGEX = Regex("""\b(high\s+score|points|max\s+score)\b""", RegexOption.IGNORE_CASE)

    private val CAT_GEO_REGEX = Regex("""\b(geography|country|countries|map|world|globle|worldle|flag|city)\b""", RegexOption.IGNORE_CASE)
    private val CAT_MATH_REGEX = Regex("""\b(math|number|equation|sudoku|nerdle|calc|logic|chess)\b""", RegexOption.IGNORE_CASE)
    private val CAT_MEDIA_REGEX = Regex("""\b(song|music|movie|film|actor|album|framed|heardle|bandle)\b""", RegexOption.IGNORE_CASE)
    private val CAT_TRIVIA_REGEX = Regex("""\b(trivia|quiz|history|wiki|movie\s+grid|immaculate)\b""", RegexOption.IGNORE_CASE)

    /**
     * Fetches the normalized URL and returns only the parsed metadata JSON without the 128 KB raw HTML (P9, K10).
     */
    fun fetchAndScrape(rawUrl: String): JSONObject {
        val normalizedUrl = normalizeUrl(rawUrl)
        val result = JSONObject()
        result.put("url", normalizedUrl)

        val uri = try {
            URI(normalizedUrl)
        } catch (e: Exception) {
            null
        }
        val scheme = uri?.scheme?.lowercase(Locale.US)
        if (uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            val fallback = parseHtmlMetadata("", normalizedUrl)
            result.put("parsed", fallback.toJson())
            result.put("ok", false)
            result.put("error", "invalid_url")
            return result
        }

        // SafeHttp: public hosts only, every redirect hop checked, 10 s overall deadline, size cap.
        val resp = SafeHttp.get(
            normalizedUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml"),
            maxBytes = MAX_HTML_CHARS * 2,
            deadlineMs = 10_000
        )
        if (resp == null) {
            result.put("parsed", parseHtmlMetadata("", normalizedUrl).toJson())
            result.put("ok", false)
            result.put("error", "fetch_failed")
            return result
        }
        val finalUrl = resp.finalUrl
        result.put("finalUrl", finalUrl)
        if (resp.code !in 200..299) {
            // Error pages (403/404/5xx) would otherwise be parsed as the game's own title.
            result.put("parsed", parseHtmlMetadata("", finalUrl).toJson())
            result.put("ok", false)
            result.put("error", "http_${resp.code}")
            return result
        }
        val html = String(resp.body, charsetFromContentType(resp.contentType)).take(MAX_HTML_CHARS)
        if (looksLikeBotChallenge(html)) {
            // Cloudflare / bot-check interstitials carry no game metadata ("Just a moment...").
            result.put("parsed", parseHtmlMetadata("", finalUrl).toJson())
            result.put("ok", false)
            result.put("error", "bot_challenge")
            return result
        }
        result.put("parsed", parseHtmlMetadata(html, finalUrl).toJson())
        result.put("ok", true)
        return result
    }

    /**
     * Pure-JVM parser that extracts game parameters from raw HTML and page URL.
     */
    fun parseHtmlMetadata(html: String, pageUrl: String): ScrapedSiteMetadata {
        val safeUrl = normalizeUrl(pageUrl)
        val host = extractHost(safeUrl)
        val fallbackName = deriveNameFromHost(host)

        val ogSiteName = extractMetaContent(html, "og:site_name")
        val appName = extractMetaContent(html, "application-name")
        val appleTitle = extractMetaContent(html, "apple-mobile-web-app-title")
        val ogTitle = extractMetaContent(html, "og:title")
        val rawTitle = extractTitleText(html)

        val rawBestTitle = listOf(ogSiteName, appName, appleTitle, ogTitle, rawTitle)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val cleanedName = cleanGameTitle(rawBestTitle).ifBlank { fallbackName }

        val rawDesc = listOf(
            extractMetaContent(html, "og:description"),
            extractMetaContent(html, "description"),
            extractMetaContent(html, "twitter:description")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val description = decodeHtmlEntities(rawDesc).take(140)

        val faviconHref = extractLinkHref(
            html,
            listOf("apple-touch-icon", "shortcut icon", "icon", "fluid-icon")
        )
        val faviconUrl = resolveFaviconUrl(faviconHref, safeUrl, host)

        val visibleBodyText = stripScriptsStylesAndTags(html.take(25_000))
        val combinedText = "$cleanedName $description $rawTitle $visibleBodyText"
        val (resetTime, resetZone) = detectResetSchedule(combinedText)
        val (metricType, par) = detectMetricAndPar(combinedText)
        val category = detectCategory("$cleanedName $description $rawTitle", host)

        return ScrapedSiteMetadata(
            name = cleanedName,
            description = description,
            faviconUrl = faviconUrl,
            resetTime = resetTime,
            resetZone = resetZone,
            metricType = metricType,
            par = par,
            category = category
        )
    }

    /** Strips `<script>`, `<style>`, and HTML tags so embedded JS code never triggers false keyword matches (K11). */
    fun stripScriptsStylesAndTags(rawHtml: String): String {
        if (rawHtml.isEmpty()) return ""
        val withoutScripts = SCRIPT_STYLE_REGEX.replace(rawHtml, " ")
        val withoutTags = HTML_TAG_REGEX.replace(withoutScripts, " ")
        return decodeHtmlEntities(WHITESPACE_REGEX.replace(withoutTags, " "))
    }

    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return "https://example.com"
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun extractHost(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.")?.lowercase(Locale.US).orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    fun deriveNameFromHost(host: String): String {
        val base = host.substringBefore('.').replace('-', ' ').replace('_', ' ').trim()
        if (base.isEmpty()) return "Daily Game"
        return base.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }
    }

    fun cleanGameTitle(raw: String): String {
        val decoded = decodeHtmlEntities(raw).trim()
        if (decoded.isEmpty()) return ""
        val firstSegment = decoded.split(TITLE_SPLIT_REGEX).firstOrNull()?.trim().orEmpty()
        return (if (firstSegment.length >= 2) firstSegment else decoded).take(40)
    }

    /** True for bot-protection interstitials (Cloudflare "Just a moment...", DataDome, etc.). */
    fun looksLikeBotChallenge(html: String): Boolean {
        if (html.isEmpty()) return false
        val head = html.take(20_000)
        return CHALLENGE_TITLE_REGEX.containsMatchIn(head) || CHALLENGE_MARKER_REGEX.containsMatchIn(head)
    }

    fun charsetFromContentType(contentType: String?): Charset {
        val name = contentType?.let { CHARSET_REGEX.find(it)?.groupValues?.get(1) }
        return try {
            if (name.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    /**
     * Decodes named and numeric (&#8217; / &#x27;) entities. `&amp;` is decoded last so text such as
     * "&amp;lt;" correctly becomes "&lt;" instead of being double-decoded to "<".
     */
    fun decodeHtmlEntities(input: String): String {
        var out = NUMERIC_ENTITY_REGEX.replace(input) { m ->
            val raw = m.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) raw.substring(1).toIntOrNull(16) else raw.toIntOrNull()
            if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) {
                String(Character.toChars(code))
            } else {
                m.value
            }
        }
        for ((entity, value) in NAMED_ENTITIES) {
            if (out.contains(entity, ignoreCase = true)) out = out.replace(entity, value, ignoreCase = true)
        }
        return out.replace("&amp;", "&", ignoreCase = true).trim()
    }

    private fun extractMetaContent(html: String, key: String): String {
        if (html.isEmpty()) return ""
        val patterns = metaPatternCache.getOrPut(key) {
            val escaped = Regex.escape(key)
            listOf(
                Regex("""<meta\b[^>]*?\b(?:property|name)\s*=\s*["']$escaped["'][^>]*?\bcontent\s*=\s*$ATTR_VALUE""", RegexOption.IGNORE_CASE),
                Regex("""<meta\b[^>]*?\bcontent\s*=\s*$ATTR_VALUE[^>]*?\b(?:property|name)\s*=\s*["']$escaped["']""", RegexOption.IGNORE_CASE)
            )
        }
        for (p in patterns) {
            val match = p.find(html) ?: continue
            val value = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun extractTitleText(html: String): String {
        if (html.isEmpty()) return ""
        return TITLE_TAG_REGEX.find(html)?.groupValues?.get(1)?.replace(WHITESPACE_REGEX, " ")?.trim().orEmpty()
    }

    private fun extractLinkHref(html: String, relValues: List<String>): String {
        if (html.isEmpty()) return ""
        for (rel in relValues) {
            val patterns = linkPatternCache.getOrPut(rel) {
                val escaped = Regex.escape(rel)
                listOf(
                    Regex("""<link\b[^>]*?\brel\s*=\s*["'][^"']*\b$escaped\b[^"']*["'][^>]*?\bhref\s*=\s*$ATTR_VALUE""", RegexOption.IGNORE_CASE),
                    Regex("""<link\b[^>]*?\bhref\s*=\s*$ATTR_VALUE[^>]*?\brel\s*=\s*["'][^"']*\b$escaped\b[^"']*["']""", RegexOption.IGNORE_CASE)
                )
            }
            for (p in patterns) {
                val m = p.find(html) ?: continue
                val href = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
                if (href.isNotBlank()) return decodeHtmlEntities(href)
            }
        }
        return ""
    }

    fun resolveFaviconUrl(href: String, pageUrl: String, host: String): String {
        if (href.isNotBlank()) {
            return try {
                URI(pageUrl).resolve(href).toString()
            } catch (e: Exception) {
                href
            }
        }
        return if (host.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=$host&sz=128"
        } else {
            ""
        }
    }

    /**
     * Finds the daily reset time and the time zone it is published in. Returns the time as HH:MM
     * plus "local", "utc" or an IANA zone id; the hub converts it to the user's local time for display.
     */
    fun detectResetSchedule(text: String): Pair<String, String> {
        // A zone named anywhere ("refreshes at midnight UTC" in a description) is the fallback.
        val globalZone = if (UTC_GMT_REGEX.containsMatchIn(text)) "utc" else "local"

        HHMM_RESET_REGEX.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val ampm = m.groupValues[3].lowercase(Locale.US)
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            val zone = zoneAfter(text, m.range.last + 1) ?: globalZone
            return String.format(Locale.US, "%02d:%02d", hour, minute) to zone
        }
        HOUR_AMPM_RESET_REGEX.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val pm = m.groupValues[2].lowercase(Locale.US).startsWith("p")
            if (pm && hour < 12) hour += 12
            if (!pm && hour == 12) hour = 0
            val zone = zoneAfter(text, m.range.last + 1) ?: globalZone
            return String.format(Locale.US, "%02d:00", hour) to zone
        }
        MIDNIGHT_OR_NOON_REGEX.find(text)?.let { m ->
            val time = if (m.groupValues[1].equals("noon", ignoreCase = true)) "12:00" else "00:00"
            val zone = zoneAfter(text, m.range.last + 1) ?: globalZone
            return time to zone
        }
        return "00:00" to globalZone
    }

    fun detectMetricAndPar(text: String): Pair<String, Int> {
        val lower = text.lowercase(Locale.US)
        val guessesMatch = IN_GUESSES_REGEX.find(lower) ?: GUESSES_REGEX.find(lower)
        if (guessesMatch != null) {
            val n = guessesMatch.groupValues[1].toIntOrNull() ?: 6
            return "tries" to n.coerceIn(1, 20)
        }
        if (STOPWATCH_REGEX.containsMatchIn(lower)) {
            return "time" to 60
        }
        if (POINTS_REGEX.containsMatchIn(lower)) {
            return "points" to 100
        }
        return "tries" to 6
    }

    fun detectCategory(text: String, host: String): String {
        val combined = "${text.lowercase(Locale.US)} ${host.lowercase(Locale.US)}"
        return when {
            CAT_GEO_REGEX.containsMatchIn(combined) -> "Geography"
            CAT_MATH_REGEX.containsMatchIn(combined) -> "Math & Logic"
            CAT_MEDIA_REGEX.containsMatchIn(combined) -> "Music & Media"
            CAT_TRIVIA_REGEX.containsMatchIn(combined) -> "Trivia"
            else -> "Word"
        }
    }
}
