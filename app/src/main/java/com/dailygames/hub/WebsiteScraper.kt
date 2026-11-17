package com.dailygames.hub

import org.json.JSONArray
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
    val category: String,
    /** The page states its reset time / time zone (1.8.5): otherwise those are the defaults. */
    val timeFound: Boolean = false,
    val zoneFound: Boolean = false
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
        put("timeFound", timeFound)
        put("zoneFound", zoneFound)
    }
}

/**
 * What else is known about a page besides its raw HTML (1.8.3): a copy rendered with its scripts
 * run ([PageRenderer]), its web-app manifest and a time-zone clue from its own script.
 */
data class PageExtras(
    /** document.title after the page's scripts ran. */
    val renderedTitle: String = "",
    /** The rendered page's text, including hidden "How to play" / rules sections. */
    val renderedText: String = "",
    /** Meta tags of the rendered page (name or property, lower-cased -> content). */
    val renderedMeta: Map<String, String> = emptyMap(),
    /** Raw JSON-LD blocks of the rendered page. */
    val jsonLd: List<String> = emptyList(),
    /** The web-app manifest, if it could be read. */
    val manifest: JSONObject? = null,
    /** A reset zone read from the site's own script ("utc" or an IANA id), or null. */
    val scriptZone: String? = null,
    /** Big frames on the rendered page (a game shown inside another site), as absolute links. */
    val frames: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = renderedTitle.isBlank() && renderedText.isBlank() && renderedMeta.isEmpty() && jsonLd.isEmpty() && manifest == null && scriptZone == null

    companion object {
        /** From [PageRenderer]'s JSON ({title, text, meta: {...}, ld: [...]}). */
        fun fromRendered(json: JSONObject?): PageExtras {
            if (json == null) return PageExtras()
            val meta = HashMap<String, String>()
            json.optJSONObject("meta")?.let { m -> m.keys().forEach { k -> meta[k.lowercase(Locale.US)] = m.optString(k) } }
            val ld = ArrayList<String>()
            json.optJSONArray("ld")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { ld += it } }
            return PageExtras(
                frames = json.optJSONArray("frames")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { u -> u.startsWith("http") } } }.orEmpty().take(8),
                renderedTitle = json.optString("title").take(300),
                renderedText = json.optString("text").take(40_000),
                renderedMeta = meta,
                jsonLd = ld
            )
        }
    }
}

/**
 * Scrapes and parses HTML metadata from daily web game URLs (title, description, favicon,
 * daily reset schedule, score metric format, par target, and category).
 */
object WebsiteScraper {
    private const val MAX_HTML_CHARS = 131_072
    /** Time for the manifest and script reads after the page itself (1.8.3). */
    private const val EXTRAS_BUDGET_MS = 16_000L
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
    private val GUESSES_REGEX = Regex("""\b([1-9]|1\d)\s+(?:tries|guesses|attempts|chances|lives|mistakes)\b""", RegexOption.IGNORE_CASE)
    private val STOPWATCH_REGEX = Regex("""\b(stopwatch|timer|timed|speedrun|speed\s+run|fastest(?:\s+time)?|beat\s+the\s+clock|how\s+fast|against\s+the\s+clock|seconds)\b""", RegexOption.IGNORE_CASE)
    private val POINTS_REGEX = Regex("""\b(high\s+score|points|max(?:imum)?\s+score|score\s+as\s+(?:many|high)|best\s+score)\b""", RegexOption.IGNORE_CASE)

    // ---- 1.8.3: JSON-LD, manifest, script clues and short descriptions ----
    private val JSON_LD_REGEX = Regex("""<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    private val SCRIPT_SRC_REGEX = Regex("""<script\b[^>]*?\bsrc\s*=\s*$ATTR_VALUE""", RegexOption.IGNORE_CASE)
    /** An explicit Intl time zone in the site's code ({ timeZone: "America/New_York" }): the zone its days follow. */
    private val SCRIPT_TZ_OPTION = Regex("""timeZone["']?\s*:\s*["']((?:America|Europe|Asia|Australia|Pacific|Africa|Atlantic|Indian)/[A-Za-z_]+(?:/[A-Za-z_]+)?|UTC|Etc/UTC|GMT)["']""")
    /** The start of a UTC day being computed (setUTCHours(0,0,…)): days that turn at midnight UTC. */
    private val SCRIPT_UTC_DAY = Regex("""setUTCHours\(\s*0\s*,\s*0""")
    private val AD_SCRIPT_HOST = Regex("""(googletagmanager|google-analytics|googlesyndication|doubleclick|adservice|facebook|cloudflareinsights|hotjar|clarity\.ms|cdn\.jsdelivr|unpkg|cdnjs|gstatic|googleapis|amazon-adsystem|pubmatic|criteo|taboola|outbrain|sentry|segment|mixpanel|amplitude|newrelic|nr-data)""", RegexOption.IGNORE_CASE)
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?])\s+(?=[A-Z0-9"'“(])""")
    private val GAMEPLAY_VERB = Regex("""\b(guess|guessing|find|solve|connect|group|identify|name|spell|match|sort|unscramble|arrange|discover|figure\s+out|uncover|place|build|fill|deduce|locate|pick|rank|order|link|clear|reveal|draw|trace|decode|crack)\b""", RegexOption.IGNORE_CASE)
    private val DESCRIPTION_NOISE = Regex("""\b(cookie|cookies|privacy|sign\s*in|log\s*in|subscribe|newsletter|download\s+the\s+app|javascript|enable\s+js|browser|advertis|terms\s+of|copyright|all\s+rights)""", RegexOption.IGNORE_CASE)
    private val MARKETING_WORDS = Regex("""\b(?:for\s+free|free|online|unlimited|no\s+download|no\s+sign[- ]?up|addictive|viral|the\s+best|ultimate|official)\b[ ,]*""", RegexOption.IGNORE_CASE)
    private const val SHORT_DESCRIPTION_MAX = 90

    private val CAT_GEO_REGEX = Regex("""\b(geography|country|countries|map|world|globle|worldle|flag|city)\b""", RegexOption.IGNORE_CASE)
    private val CAT_MATH_REGEX = Regex("""\b(math|number|equation|sudoku|nerdle|calc|logic|chess)\b""", RegexOption.IGNORE_CASE)
    private val CAT_MEDIA_REGEX = Regex("""\b(song|music|movie|film|actor|album|framed|heardle|bandle)\b""", RegexOption.IGNORE_CASE)
    private val CAT_TRIVIA_REGEX = Regex("""\b(trivia|quiz|history|wiki|movie\s+grid|immaculate)\b""", RegexOption.IGNORE_CASE)

    /**
     * Fetches the normalized URL and returns only the parsed metadata JSON without the 128 KB raw HTML (P9, K10).
     *
     * 1.8.3: also reads the site's web-app manifest and up to three of its own script files (for a
     * time zone its code names), and [rendered] (when given) waits for a copy of the page rendered with
     * its scripts run ([PageRenderer]). A page whose plain HTML is blocked or empty (a bot check, a
     * single-page app) is still read from the rendered copy.
     */
    fun fetchAndScrape(rawUrl: String, rendered: (() -> JSONObject?)? = null): JSONObject {
        val started = System.currentTimeMillis()
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
        var html = ""
        var finalUrl = normalizedUrl
        var error: String? = null
        when {
            resp == null -> error = "fetch_failed"
            resp.code !in 200..299 -> {
                // Error pages (403/404/5xx) would otherwise be parsed as the game's own title.
                finalUrl = resp.finalUrl
                error = "http_${resp.code}"
            }
            else -> {
                finalUrl = resp.finalUrl
                val body = String(resp.body, charsetFromContentType(resp.contentType)).take(MAX_HTML_CHARS)
                // Cloudflare / bot-check interstitials carry no game metadata ("Just a moment...").
                if (looksLikeBotChallenge(body)) error = "bot_challenge" else html = body
            }
        }
        if (resp != null) result.put("finalUrl", finalUrl)

        // The manifest and the site's own code, within what's left of the time budget.
        fun left() = EXTRAS_BUDGET_MS - (System.currentTimeMillis() - started)
        var manifest: JSONObject? = null
        manifestUrl(html, finalUrl)?.let { mu ->
            if (left() > 1_000) {
                val r = SafeHttp.get(mu, headers = mapOf("User-Agent" to USER_AGENT), maxBytes = 64 * 1024, deadlineMs = minOf(4_000L, left()))
                if (r != null && r.code in 200..299) manifest = runCatching { JSONObject(String(r.body, Charsets.UTF_8)) }.getOrNull()
            }
        }
        var scriptZone: String? = null
        for (src in ownScriptUrls(html, finalUrl)) {
            if (scriptZone != null || left() < 1_500) break
            val r = SafeHttp.get(src, headers = mapOf("User-Agent" to USER_AGENT), maxBytes = 2 * 1024 * 1024, deadlineMs = minOf(5_000L, left()))
            if (r != null && r.code in 200..299) scriptZone = scriptZoneHint(String(r.body, Charsets.UTF_8))
        }
        val renderedJson = rendered?.invoke()
        val extras = PageExtras.fromRendered(renderedJson).copy(manifest = manifest, scriptZone = scriptZone)
        // Where the rendered page ended up (a redirect the plain fetch didn't get to follow).
        if (resp == null) renderedJson?.optString("url")?.takeIf { it.startsWith("http") }?.let { finalUrl = it; result.put("finalUrl", it) }

        val renderedUseful = extras.renderedText.length >= 40 || extras.renderedTitle.isNotBlank()
        result.put("parsed", parseHtmlMetadata(html, finalUrl, extras).toJson())
        // Games shown inside this page (1.8.13): the hub asks before adding one it already has.
        val embeds = embeddedGameLinks(html, finalUrl, extras.frames)
        if (embeds.isNotEmpty()) result.put("embeds", JSONArray(embeds))
        result.put("rendered", renderedUseful)
        if (error != null && !renderedUseful) {
            result.put("ok", false)
            result.put("error", error)
        } else {
            result.put("ok", true)
        }
        return result
    }

    private val IFRAME_SRC = Regex("""<iframe\b[^>]*?\bsrc\s*=\s*["']([^"'<>]+)["']""", RegexOption.IGNORE_CASE)
    private val NOT_A_GAME_FRAME = Regex("""(youtube|youtu\.be|vimeo|google\.com/maps|recaptcha|disqus|facebook|twitter|x\.com/|instagram|tiktok|spotify|soundcloud|consent|cookie)""", RegexOption.IGNORE_CASE)

    /**
     * Frames on a page that could be a game shown inside it (1.8.13): from its HTML and, when it was
     * rendered, the big frames its scripts added. Ads, videos, maps and sign-in frames are left out.
     */
    fun embeddedGameLinks(html: String, pageUrl: String, rendered: List<String> = emptyList()): List<String> {
        val base = runCatching { URI(pageUrl) }.getOrNull()
        val out = LinkedHashSet<String>()
        (IFRAME_SRC.findAll(html).map { it.groupValues[1].trim() } + rendered.asSequence()).forEach { raw ->
            val abs = runCatching { if (base != null) base.resolve(raw.replace(" ", "%20")).toString() else raw }.getOrNull() ?: return@forEach
            val u = runCatching { URI(abs) }.getOrNull() ?: return@forEach
            val scheme = u.scheme?.lowercase(Locale.US)
            if ((scheme != "http" && scheme != "https") || u.host.isNullOrBlank()) return@forEach
            if (AD_SCRIPT_HOST.containsMatchIn(u.host) || NOT_A_GAME_FRAME.containsMatchIn(abs)) return@forEach
            out += abs.take(500)
        }
        return out.take(8)
    }

    /**
     * Pure-JVM parser that extracts game parameters from raw HTML and page URL.
     */
    fun parseHtmlMetadata(html: String, pageUrl: String, extras: PageExtras = PageExtras()): ScrapedSiteMetadata {
        val safeUrl = normalizeUrl(pageUrl)
        val host = extractHost(safeUrl)
        val fallbackName = deriveNameFromHost(host)
        // A tag from the raw HTML, else from the rendered page (single-page apps add theirs in script).
        fun meta(key: String) = extractMetaContent(html, key).ifBlank { extras.renderedMeta[key].orEmpty().trim() }

        val ogSiteName = meta("og:site_name")
        val appName = meta("application-name")
        val appleTitle = meta("apple-mobile-web-app-title")
        val ogTitle = meta("og:title")
        val rawTitle = extractTitleText(html).ifBlank { extras.renderedTitle }
        val ld = jsonLdInfo(extractJsonLdBlocks(html) + extras.jsonLd)
        val manifest = extras.manifest

        val rawBestTitle = listOf(
            ogSiteName, appName, appleTitle,
            manifest?.optString("short_name").orEmpty(), manifest?.optString("name").orEmpty(),
            ld.name, ogTitle, rawTitle
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val cleanedName = cleanGameTitle(rawBestTitle).ifBlank { fallbackName }

        val descCandidates = listOf(
            meta("og:description"), meta("description"), meta("twitter:description"),
            ld.description, manifest?.optString("description").orEmpty()
        ).map { decodeHtmlEntities(it) } + howToPlaySentences(extras.renderedText)
        val description = pickShortDescription(descCandidates, cleanedName)

        val faviconHref = extractLinkHref(
            html,
            listOf("apple-touch-icon", "shortcut icon", "icon", "fluid-icon")
        )
        val faviconUrl = resolveFaviconUrl(faviconHref, safeUrl, host)

        val visibleBodyText = stripScriptsStylesAndTags(html.take(25_000))
        val allDescriptions = descCandidates.joinToString(" ")
        val combinedText = "$cleanedName $allDescriptions $rawTitle $visibleBodyText ${extras.renderedText}"
        val schedule = detectResetScheduleDetail(combinedText)
        // No zone written on the page: one the site's own script names (its days follow it).
        val resetZone = if (!schedule.zoneFound && extras.scriptZone != null) extras.scriptZone else schedule.zone
        val resetTime = schedule.time
        // No clue about how it's scored: say nothing (the hub keeps "Daily Puzzle" and learns it from
        // the first results) instead of guessing "6 guesses".
        val (metricType, par) = detectMetric(combinedText) ?: ("" to 0)
        val category = detectCategory("$cleanedName $allDescriptions $rawTitle ${ld.genre}", host)

        return ScrapedSiteMetadata(
            name = cleanedName,
            description = description,
            faviconUrl = faviconUrl,
            resetTime = resetTime,
            resetZone = resetZone,
            metricType = metricType,
            par = par,
            category = category,
            timeFound = schedule.timeFound,
            zoneFound = schedule.zoneFound || extras.scriptZone != null
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
    fun detectResetSchedule(text: String): Pair<String, String> = detectResetScheduleDetail(text).let { it.time to it.zone }

    /** A reset schedule and whether the page actually said the time / the zone (vs. the defaults). */
    data class ScheduleGuess(val time: String, val zone: String, val timeFound: Boolean, val zoneFound: Boolean)

    fun detectResetScheduleDetail(text: String): ScheduleGuess {
        // A zone named anywhere ("refreshes at midnight UTC" in a description) is the fallback.
        val utcAnywhere = UTC_GMT_REGEX.containsMatchIn(text)
        val globalZone = if (utcAnywhere) "utc" else "local"
        fun guess(time: String, after: Int): ScheduleGuess {
            val z = zoneAfter(text, after)
            return ScheduleGuess(time, z ?: globalZone, true, z != null || utcAnywhere)
        }

        HHMM_RESET_REGEX.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val ampm = m.groupValues[3].lowercase(Locale.US)
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return guess(String.format(Locale.US, "%02d:%02d", hour, minute), m.range.last + 1)
        }
        HOUR_AMPM_RESET_REGEX.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val pm = m.groupValues[2].lowercase(Locale.US).startsWith("p")
            if (pm && hour < 12) hour += 12
            if (!pm && hour == 12) hour = 0
            return guess(String.format(Locale.US, "%02d:00", hour), m.range.last + 1)
        }
        MIDNIGHT_OR_NOON_REGEX.find(text)?.let { m ->
            val time = if (m.groupValues[1].equals("noon", ignoreCase = true)) "12:00" else "00:00"
            return guess(time, m.range.last + 1)
        }
        return ScheduleGuess("00:00", globalZone, false, utcAnywhere)
    }

    /** How a game is scored, or "tries" / 6 when the text gives no clue (see [detectMetric]). */
    fun detectMetricAndPar(text: String): Pair<String, Int> = detectMetric(text) ?: ("tries" to 6)

    /** "tries" with the number of guesses, "time" or "points" when the text says so; null when it doesn't. */
    fun detectMetric(text: String): Pair<String, Int>? {
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
        return null
    }

    // ---- 1.8.3: JSON-LD, manifest, script clues, short descriptions ----

    /** The JSON-LD blocks in raw HTML. */
    fun extractJsonLdBlocks(html: String): List<String> =
        if (html.isEmpty()) emptyList() else JSON_LD_REGEX.findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.take(5).toList()

    data class JsonLdInfo(val name: String = "", val description: String = "", val genre: String = "")

    /**
     * Name, description and genre from JSON-LD, preferring a Game / VideoGame / WebApplication entry
     * over a WebSite / WebPage one. Malformed blocks are skipped.
     */
    fun jsonLdInfo(blocks: List<String>): JsonLdInfo {
        val found = ArrayList<JSONObject>()
        fun collect(v: Any?, depth: Int) {
            if (depth > 4 || found.size > 40) return
            when (v) {
                is JSONObject -> {
                    found += v
                    v.optJSONArray("@graph")?.let { collect(it, depth + 1) }
                    v.optJSONObject("mainEntity")?.let { collect(it, depth + 1) }
                }
                is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), depth + 1)
            }
        }
        blocks.forEach { b -> runCatching { collect(org.json.JSONTokener(b).nextValue(), 0) } }
        fun types(o: JSONObject): String = when (val t = o.opt("@type")) {
            is JSONArray -> (0 until t.length()).joinToString(" ") { t.optString(it) }
            null -> ""
            else -> t.toString()
        }
        val rank = { o: JSONObject ->
            val t = types(o)
            when {
                Regex("(?i)\\b(VideoGame|Game)\\b").containsMatchIn(t) -> 0
                Regex("(?i)(WebApplication|SoftwareApplication|MobileApplication)").containsMatchIn(t) -> 1
                Regex("(?i)(WebSite|WebPage)").containsMatchIn(t) -> 2
                else -> 3
            }
        }
        val best = found.filter { it.optString("name").isNotBlank() || it.optString("description").isNotBlank() }.minByOrNull(rank)
            ?: return JsonLdInfo()
        val genre = when (val g = best.opt("genre") ?: best.opt("applicationCategory")) {
            is JSONArray -> (0 until g.length()).joinToString(" ") { g.optString(it) }
            null -> ""
            else -> g.toString()
        }
        return JsonLdInfo(decodeHtmlEntities(best.optString("name")), decodeHtmlEntities(best.optString("description")), genre)
    }

    /** The web-app manifest's address, if the page links one. */
    fun manifestUrl(html: String, pageUrl: String): String? {
        val href = extractLinkHref(html, listOf("manifest")).takeIf { it.isNotBlank() } ?: return null
        return runCatching { URI(pageUrl).resolve(href).toString() }.getOrNull()
    }

    /** Up to [max] of the page's own script files (same site, not ad / analytics / CDN libraries). */
    fun ownScriptUrls(html: String, pageUrl: String, max: Int = 3): List<String> {
        if (html.isEmpty()) return emptyList()
        val pageHost = extractHost(pageUrl)
        val site = pageHost.split('.').takeLast(2).joinToString(".")
        return SCRIPT_SRC_REGEX.findAll(html).mapNotNull { m ->
            val src = decodeHtmlEntities(m.groupValues[1].ifEmpty { m.groupValues[2] })
            val abs = runCatching { URI(pageUrl).resolve(src).toString() }.getOrNull() ?: return@mapNotNull null
            val h = extractHost(abs)
            if (h.isEmpty() || AD_SCRIPT_HOST.containsMatchIn(abs)) return@mapNotNull null
            if (h != pageHost && !h.endsWith(".$site") && h != site) return@mapNotNull null
            abs
        }.distinct().take(max).toList()
    }

    /** The zone a site's days follow according to its own code, or null when the code doesn't say. */
    fun scriptZoneHint(js: String): String? {
        SCRIPT_TZ_OPTION.find(js)?.let { m ->
            val z = m.groupValues[1]
            return if (z.equals("UTC", true) || z.equals("Etc/UTC", true) || z.equals("GMT", true)) "utc" else z
        }
        return if (SCRIPT_UTC_DAY.containsMatchIn(js)) "utc" else null
    }

    /** Sentences from a rendered page's "How to play" / rules text (where games explain themselves). */
    fun howToPlaySentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val at = Regex("(?i)how\\s+to\\s+play|the\\s+rules|instructions").find(text)?.range?.last ?: return emptyList()
        return text.substring(at + 1, minOf(text.length, at + 1500)).split('\n')
            .map { it.trim() }.filter { it.length in 15..200 }.take(6)
    }

    /**
     * A short description: the first sentence that says how the game is played ("Guess the country in
     * six tries."), without the game's name in front or "free online" filler, cut at a sentence end
     * or a word, at most [SHORT_DESCRIPTION_MAX] characters.
     */
    fun pickShortDescription(candidates: List<String>, name: String): String {
        data class Option(val text: String, val score: Int, val order: Int)
        val options = ArrayList<Option>()
        var order = 0
        candidates.map { WHITESPACE_REGEX.replace(it, " ").trim() }.filter { it.isNotEmpty() }.forEach { cand ->
            cand.split(SENTENCE_SPLIT).forEach { raw ->
                val s = cleanSentence(raw, name)
                if (s.length < 12) return@forEach
                var score = 0
                if (GAMEPLAY_VERB.containsMatchIn(s)) score += 3
                if (Regex("(?i)\\b(daily|every\\s+day|each\\s+day|today'?s)\\b").containsMatchIn(s)) score += 1
                if (DESCRIPTION_NOISE.containsMatchIn(s)) score -= 4
                if (s.length > 140) score -= 2
                options += Option(s, score, order++)
            }
        }
        val best = options.filter { it.score > 0 }.maxWithOrNull(compareBy<Option> { it.score }.thenByDescending { it.order })
            ?: options.firstOrNull { it.score >= 0 } ?: return ""
        return shorten(best.text)
    }

    private fun cleanSentence(raw: String, name: String): String {
        var s = raw.trim().trim('"', '“', '”', '\'')
        if (name.isNotBlank()) {
            val n = Regex.escape(name.trim())
            // "Play Wordle: guess…" / "Wordle - guess…" / "Wordle is a daily game where you guess…"
            s = s.replace(Regex("(?i)^(?:play\\s+)?$n\\s*(?::|-|–|—|\\|)\\s*"), "")
            s = s.replace(Regex("(?i)^$n\\s+is\\s+an?\\s+(?:\\w+\\s+){0,4}?(?:game|puzzle|challenge)\\s+(?:where|in\\s+which)\\s+you\\s+"), "")
        }
        s = MARKETING_WORDS.replace(s, "").replace(Regex("\\s{2,}"), " ").replace(Regex("\\s+([,.!?])"), "$1").trim()
        return s.replaceFirstChar { it.uppercaseChar() }
    }

    private fun shorten(s: String): String {
        if (s.length <= SHORT_DESCRIPTION_MAX) return s
        val cut = s.take(SHORT_DESCRIPTION_MAX)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
        if (end >= 30) return cut.substring(0, end + 1)
        val word = cut.lastIndexOf(' ')
        return (if (word >= 30) cut.substring(0, word) else cut).trimEnd(',', ';', ':', ' ', '-') + "…"
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
