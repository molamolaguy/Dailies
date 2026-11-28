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
    val zoneFound: Boolean = false,
    /** Human-readable Target Format / Par (e.g. "6 guesses", "4 mistakes", "Fastest time", "50,000 points"). */
    val targetFormat: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("faviconUrl", faviconUrl)
        put("resetTime", resetTime)
        put("resetZone", resetZone)
        put("metricType", metricType)
        put("par", par)
        put("targetFormat", targetFormat)
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
    val frames: List<String> = emptyList(),
    /** Icon href discovered in the rendered DOM, if any. */
    val renderedIcon: String = ""
) {
    val isEmpty: Boolean get() = renderedTitle.isBlank() && renderedText.isBlank() && renderedMeta.isEmpty() && jsonLd.isEmpty() && manifest == null && scriptZone == null && renderedIcon.isBlank()

    companion object {
        /** From [PageRenderer]'s JSON ({title, text, icon, meta: {...}, ld: [...]}). */
        fun fromRendered(json: JSONObject?): PageExtras {
            if (json == null) return PageExtras()
            val meta = HashMap<String, String>()
            json.optJSONObject("meta")?.let { m -> m.keys().forEach { k -> meta[k.lowercase(Locale.US)] = m.optString(k) } }
            val ld = ArrayList<String>()
            json.optJSONArray("ld")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { ld += it } }
            return PageExtras(
                frames = json.optJSONArray("frames")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { u -> u.startsWith("http") } } }.orEmpty().take(8),
                renderedTitle = json.optString("title").take(300),
                renderedText = json.optString("text").take(45_000),
                renderedMeta = meta,
                jsonLd = ld,
                renderedIcon = json.optString("icon").take(500)
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
    private val ENTITY_REGEX = Regex("""&(?:#(x[0-9a-fA-F]{1,6}|[0-9]{1,7})|([A-Za-z][A-Za-z0-9]{1,10}));""")
    private val CHARSET_REGEX = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
    private val META_CHARSET_REGEX = Regex("""<meta\b[^>]*?(?:\bcharset\s*=\s*["']?([A-Za-z0-9._-]+)|\bhttp-equiv\s*=\s*["']content-type["'][^>]*?\bcharset\s*=\s*([A-Za-z0-9._-]+))""", RegexOption.IGNORE_CASE)
    private val CHALLENGE_TITLE_REGEX = Regex(
        """(?:<title[^>]*>\s*|^\s*)(?:just a moment|attention required|access denied|please wait|checking your browser|ddos-guard)""",
        RegexOption.IGNORE_CASE
    )
    private val CHALLENGE_MARKER_REGEX = Regex(
        """(?:/cdn-cgi/challenge-platform/(?!scripts/jsd/)|cf-chl-|_?cf_chl_opt|id=["']challenge-form["']|captcha-delivery\.com|verify you are human|checking if the site connection is secure)""",
        RegexOption.IGNORE_CASE
    )
    private val REAL_PAGE_META_REGEX = Regex(
        """(?:<meta\b[^>]*\b(?:name\s*=\s*["']description["']|property\s*=\s*["']og:(?:title|description|site_name)["'])\b[^>]*\bcontent\s*=\s*["'][^"']{4,}|<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'])""",
        RegexOption.IGNORE_CASE
    )

    /**
     * An HTML attribute value in either quote style. Double-quoted values may contain apostrophes and
     * single-quoted values may contain double quotes ("Guess today's word" used to be cut at the ').
     */
    private const val ATTR_VALUE = """(?:"([^"]*)"|'([^']*)')"""
    private val metaPatternCache = ConcurrentHashMap<String, List<Regex>>()
    private val linkPatternCache = ConcurrentHashMap<String, List<Regex>>()

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "nbsp" to " ", "rsquo" to "\u2019", "lsquo" to "\u2018", "rdquo" to "\u201D",
        "ldquo" to "\u201C", "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026",
        "middot" to "\u00B7", "bull" to "\u2022", "copy" to "\u00A9", "reg" to "\u00AE",
        "trade" to "\u2122", "eacute" to "\u00E9", "Eacute" to "\u00C9"
    )

    private const val RESET_NOUN = """(?:puzzle|game|word\w*|daily|challenge|one|round|grid|board|hole|level|expedition|mystery|case|song|movie|film|photo|question|article|quiz|clue|map|episode|card|track)"""
    private const val RESET_TRIGGER = """(?:reset\w*|refresh\w*|roll\w*\s+over|turn\w*\s+over|new\s+(?:[a-z0-9-]+\s+){0,2}?$RESET_NOUN|next\s+$RESET_NOUN|$RESET_NOUN\s+(?:opens?|unlocks?|appears?|drops?|starts?|begins?|goes\s+live|comes?\s+out)|every\s+(?:day|morning|night|evening)|each\s+(?:day|morning|night)|daily|releas\w*|available|updates?|publish\w*|opens?|unlocks?)"""

    private val BLOCK_TAG_REGEX = Regex("""<(?:br|hr|/p|/div|/li|/tr|/td|/th|/h[1-6]|/section|/article|/header|/footer|/dt|/dd|/summary|/blockquote|/details|/dialog)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HORIZ_SPACE_REGEX = Regex("""[ \t\r\f\u00A0]+""")
    private val MULTI_NEWLINE_REGEX = Regex("""\n(?:\s*\n)+""")

    private val RESET_NEAR_UTC_REGEX = Regex(
        """$RESET_TRIGGER(?!\s+in\s+\d)[^.\n]{0,65}\b(utc|gmt)\b(?![+-]\s*\d)|\b(utc|gmt)\b(?![+-]\s*\d)[^.\n]{0,45}$RESET_TRIGGER""",
        RegexOption.IGNORE_CASE
    )
    private val HHMM_RESET_REGEX = Regex(
        """$RESET_TRIGGER([^0-9.\n]{0,45})(?<!\b(?:in|within|for|under|after)\s)\b([01]?\d|2[0-3]):([0-5]\d)(?!\s*:\s*\d)\s*(a\.?m\.?|p\.?m\.?|midnight|noon)?(?![a-z])""",
        RegexOption.IGNORE_CASE
    )
    private val EU_HHMM_RESET_REGEX = Regex(
        """$RESET_TRIGGER[^0-9.\n]{0,45}(?<!\b(?:in|within|for|under|after)\s)\b([01]?\d|2[0-3])h([0-5]\d)\b""",
        RegexOption.IGNORE_CASE
    )
    /** "new puzzle every day at 9 AM ET" or "at 12 midnight" (hour only, with am/pm/midnight/noon). */
    private val HOUR_AMPM_RESET_REGEX = Regex(
        """$RESET_TRIGGER[^0-9.\n]{0,45}(?<!\b(?:in|within|for|under|after)\s)\b(1[0-2]|0?[1-9])\s*(a\.?m\.?|p\.?m\.?|midnight|noon)(?![a-z])""",
        RegexOption.IGNORE_CASE
    )
    private val MIDNIGHT_OR_NOON_REGEX = Regex(
        """(?:$RESET_TRIGGER[^.\n]{0,45}\b(midnight|noon)\b|\bat\s+(midnight|noon)\b[^.\n]{0,40}(?:every\s+day|each\s+day|daily|a\s+new\b|new\s+$RESET_NOUN|reset\w*|refresh\w*|opens?|unlocks?))""",
        RegexOption.IGNORE_CASE
    )
    private val AT_PREP_END_REGEX = Regex("""\b(?:at|around|by)\s+$|@\s*$""", RegexOption.IGNORE_CASE)

    /** Fixed numeric UTC/GMT offset right after a reset time, e.g. "08:00 UTC+8" or "19:30 GMT-5:30". */
    private val UTC_OFFSET_AFTER_REGEX = Regex(
        """^(?:[,;:\-\u2013\u2014()\s]|(?:\bin|\bon|\bat|\bof)\b\s+){0,4}(?:utc|gmt)\s*([+-])\s*(1[0-4]|0?\d)(?::?([0-5]\d))?\b""",
        RegexOption.IGNORE_CASE
    )

    private const val ZONE_PREFIX = """^(?:[,;:\-\u2013\u2014()\s]|(?:\bin|\bon|\bat|\bof|\buhr)\b\s+){0,4}"""
    private val IANA_AFTER_REGEX = Regex("""$ZONE_PREFIX((?:America|Europe|Asia|Australia|Pacific|Africa|Atlantic|Indian)/[A-Za-z_]+(?:/[A-Za-z_]+)?)\b""")

    /**
     * Time-zone words that may follow a reset time, mapped to the zone they were published in.
     * Short abbreviations (ET, PT…) only count right after the time, and in capitals, so ordinary
     * words ("et cetera") aren't mistaken for zones.
     */
    private val ZONE_TOKENS: List<Pair<Regex, String>> = listOf(
        Regex("""$ZONE_PREFIX(?:(?:utc|gmt|zulu)(?![+-]\s*\d)|coordinated\s+universal\s+time)\b""", RegexOption.IGNORE_CASE) to "utc",
        Regex("""$ZONE_PREFIX(?:(?:(?:your|my|user'?s?|player'?s?|device|browser)\s+)?local(?:\s+time(?:\s*zone)?)?|(?:your|my)\s+time\s*zone|wherever\s+you\s+are)\b""", RegexOption.IGNORE_CASE) to "local",
        Regex("""^\W{0,4}(?:ET|EST|EDT)\b""") to "America/New_York",
        Regex("""$ZONE_PREFIX(?:(?:us\s+)?eastern|new\s+york|nyc)(?:\s+(?:standard|daylight))?(?:\s+time)?\b""", RegexOption.IGNORE_CASE) to "America/New_York",
        Regex("""$ZONE_PREFIX(?:toronto)(?:\s+time)?\b""", RegexOption.IGNORE_CASE) to "America/Toronto",
        Regex("""^\W{0,4}(?:CT|CST|CDT)\b""") to "America/Chicago",
        Regex("""$ZONE_PREFIX(?:(?:us\s+|north\s+american\s+)?central(?:\s+(?:standard|daylight))?\s+time|chicago(?:\s+time)?)\b""", RegexOption.IGNORE_CASE) to "America/Chicago",
        Regex("""^\W{0,4}(?:MT|MST|MDT)\b""") to "America/Denver",
        Regex("""$ZONE_PREFIX(?:(?:us\s+)?mountain(?:\s+(?:standard|daylight))?\s+time|denver(?:\s+time)?)\b""", RegexOption.IGNORE_CASE) to "America/Denver",
        Regex("""^\W{0,4}(?:PT|PST|PDT)\b""") to "America/Los_Angeles",
        Regex("""$ZONE_PREFIX(?:(?:us\s+)?pacific(?:\s+(?:standard|daylight))?(?:\s+time)?|(?:los\s+angeles|seattle|san\s+francisco)\s+time)\b""", RegexOption.IGNORE_CASE) to "America/Los_Angeles",
        Regex("""^\W{0,4}(?:BRT|BRST)\b""") to "America/Sao_Paulo",
        Regex("""$ZONE_PREFIX(?:brazil(?:ian)?|s[a\u00e3]o\s+paulo|brasilia)(?:\s+time)?\b""", RegexOption.IGNORE_CASE) to "America/Sao_Paulo",
        Regex("""^\W{0,4}BST\b""") to "Europe/London",
        Regex("""$ZONE_PREFIX(?:british|london|uk|u\.k\.)\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/London",
        Regex("""^\W{0,4}(?:MEZ|MESZ)\b""") to "Europe/Berlin",
        Regex("""$ZONE_PREFIX(?:(?:german|berlin)(?:\s+(?:summer|standard))?\s+time|deutsch\w*\s+zeit)\b""", RegexOption.IGNORE_CASE) to "Europe/Berlin",
        Regex("""^\W{0,4}(?:CET|CEST)\b""") to "Europe/Paris",
        Regex("""$ZONE_PREFIX(?:central\s+european|paris|french)(?:\s+(?:summer|standard))?(?:\s+time)?\b""", RegexOption.IGNORE_CASE) to "Europe/Paris",
        Regex("""$ZONE_PREFIX(?:madrid|spanish)\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/Madrid",
        Regex("""$ZONE_PREFIX(?:amsterdam|dutch)\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/Amsterdam",
        Regex("""$ZONE_PREFIX(?:rome|italian)\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/Rome",
        Regex("""^\W{0,4}(?:EET|EEST)\b""") to "Europe/Athens",
        Regex("""$ZONE_PREFIX(?:eastern\s+european)(?:\s+(?:summer|standard))?\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/Athens",
        Regex("""^\W{0,4}MSK\b""") to "Europe/Moscow",
        Regex("""$ZONE_PREFIX(?:moscow)\s+time\b""", RegexOption.IGNORE_CASE) to "Europe/Moscow",
        Regex("""^\W{0,4}IST\b""") to "Asia/Kolkata",
        Regex("""$ZONE_PREFIX(?:india(?:n)?(?:\s+standard)?|kolkata|mumbai)\s+time\b""", RegexOption.IGNORE_CASE) to "Asia/Kolkata",
        Regex("""$ZONE_PREFIX(?:china(?:\s+standard)?|beijing|shanghai)\s+time\b""", RegexOption.IGNORE_CASE) to "Asia/Shanghai",
        Regex("""^\W{0,4}HKT\b""") to "Asia/Hong_Kong",
        Regex("""$ZONE_PREFIX(?:hong\s+kong)\s+time\b""", RegexOption.IGNORE_CASE) to "Asia/Hong_Kong",
        Regex("""^\W{0,4}SGT\b""") to "Asia/Singapore",
        Regex("""$ZONE_PREFIX(?:singapore)\s+time\b""", RegexOption.IGNORE_CASE) to "Asia/Singapore",
        Regex("""^\W{0,4}JST\b""") to "Asia/Tokyo",
        Regex("""$ZONE_PREFIX(?:japan(?:ese)?|tokyo)(?:\s+standard)?\s+time\b""", RegexOption.IGNORE_CASE) to "Asia/Tokyo",
        Regex("""^\W{0,4}KST\b""") to "Asia/Seoul",
        Regex("""$ZONE_PREFIX(?:korea(?:n)?|seoul)(?:\s+standard)?\s+time\b""", RegexOption.IGNORE_CASE) to "Asia/Seoul",
        Regex("""^\W{0,4}(?:AEST|AEDT)\b""") to "Australia/Sydney",
        Regex("""$ZONE_PREFIX(?:australian\s+eastern|sydney|melbourne)(?:\s+(?:standard|daylight))?\s+time\b""", RegexOption.IGNORE_CASE) to "Australia/Sydney",
        Regex("""^\W{0,4}(?:NZST|NZDT)\b""") to "Pacific/Auckland",
        Regex("""$ZONE_PREFIX(?:new\s+zealand|auckland)(?:\s+(?:standard|daylight))?\s+time\b""", RegexOption.IGNORE_CASE) to "Pacific/Auckland"
    )

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "twenty-one" to 21, "thirty-two" to 32, "thirty-seven" to 37
    )
    private const val COUNT_TOKEN = """([1-9]|[1-5]\d|6[0-4]|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty(?:-one)?|thirty-(?:two|seven))"""

    private val IN_GUESSES_REGEX = Regex("""\b(?:in|up\s+to|within|with\s+up\s+to)\s+$COUNT_TOKEN\s+(tries|guesses|attempts|chances|lives|mistakes|strikes|swaps|frames|equations|shots)\b""", RegexOption.IGNORE_CASE)
    private val GUESSES_REGEX = Regex("""(?<!\b(?:no|zero|without|few|fewest)\s)\b$COUNT_TOKEN\s+(tries|guesses|attempts|chances|lives|mistakes|strikes|swaps|frames|equations|shots)\b""", RegexOption.IGNORE_CASE)
    private val ROUNDS_FORMAT_REGEX = Regex("""\b(?:each\s+of\s+|across\s+|all\s+|answer\s+|solve\s+)?$COUNT_TOKEN\s+(?:daily\s+|interesting\s+|mystery\s+)?(prompts|rounds|questions)\b""", RegexOption.IGNORE_CASE)
    private val FEWEST_FORMAT_REGEX = Regex("""(?:\b(?:in\s+)?as\s+few\s+(guesses|tries|moves|strokes|steps|swaps|words|clues|hints|clicks|turns)\s+as\s+possible|\bfewest\s+(guesses|tries|moves|strokes|steps|swaps|words|clues|hints|clicks|turns))\b""", RegexOption.IGNORE_CASE)
    private val UNLIMITED_GUESSES_REGEX = Regex("""\bunlimited\s+(?:guesses|tries|attempts)\b""", RegexOption.IGNORE_CASE)
    private val GRID_3X3_REGEX = Regex("""\b(?:fill\s+(?:the\s+|all\s+)?(?:3\s*[x\u00d7]\s*3\s+grid|9\s+cells)|3\s*[x\u00d7]\s*3\s+(?:[\w-]+\s+){0,2}?grid)\b""", RegexOption.IGNORE_CASE)
    private val MINIGOLF_REGEX = Regex("""\bmini\s*-?\s*golf\b""", RegexOption.IGNORE_CASE)
    private val NO_MISTAKES_REGEX = Regex("""\b(?:no\s+mistakes|without\s+(?:any\s+)?mistakes|zero\s+mistakes)\b""", RegexOption.IGNORE_CASE)
    private val FIND_ALL_WORDS_REGEX = Regex("""\bfind\s+(?:all|every)\s+(?:the\s+)?(theme\s+words|words)\b""", RegexOption.IGNORE_CASE)
    private val SHORTEST_PATH_REGEX = Regex("""\bshortest\s+(?:path|route)\b""", RegexOption.IGNORE_CASE)
    private val EXPLICIT_SECONDS_REGEX = Regex("""(?:\b(?:in|under|within)\s+(\d{2,3})\s+seconds?\b|\b(\d{2,3})\s*(?:-\s*)?seconds?\s+(?:timer|clock|round|limit|challenge)\b)""", RegexOption.IGNORE_CASE)
    private val STOPWATCH_REGEX = Regex("""\b(stopwatch|timer|timed|speedrun|speed\s+run|fastest(?:\s+time)?|beat\s+the\s+clock|how\s+fast|against\s+the\s+clock)\b""", RegexOption.IGNORE_CASE)
    private val MAX_POINTS_REGEX = Regex("""(?:\b(?:up\s+to|out\s+of|max(?:imum)?(?:\s+score)?(?:\s+of)?|earn\s+up\s+to|score\s+up\s+to)\s+([1-9]\d{1,5}|[1-9]\d{0,2}(?:,\d{3})+)\s*(?:points|pts)\b|\b([1-9]\d{1,5}|[1-9]\d{0,2}(?:,\d{3})+)\s*(?:points|pts)\s+max(?:imum)?\b)""", RegexOption.IGNORE_CASE)
    private val MAX_KM_REGEX = Regex("""(?:\b(?:up\s+to|out\s+of|max(?:imum)?(?:\s+of)?)\s+([1-9]\d{1,5}|[1-9]\d{0,2}(?:,\d{3})+)\s*km\b|\b([1-9]\d{1,5}|[1-9]\d{0,2}(?:,\d{3})+)\s*km\s+max(?:imum)?\b)""", RegexOption.IGNORE_CASE)
    private val MOST_KM_REGEX = Regex("""\b(?:most\s+km|furthest\s+distance|travel\s+further)\b""", RegexOption.IGNORE_CASE)
    private val POINTS_REGEX = Regex("""\b(high\s+score|points|max(?:imum)?\s+score|score\s+as\s+(?:many|high)|best\s+score)\b""", RegexOption.IGNORE_CASE)

    // ---- 1.8.3: JSON-LD, manifest, script clues and short descriptions ----
    private val JSON_LD_REGEX = Regex("""<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    private val SCRIPT_SRC_REGEX = Regex("""<script\b[^>]*?\bsrc\s*=\s*$ATTR_VALUE""", RegexOption.IGNORE_CASE)
    private const val IANA_OR_UTC_PATTERN = """(?:America|Europe|Asia|Australia|Pacific|Africa|Atlantic|Indian)/[A-Za-z_]+(?:/[A-Za-z_]+)?|UTC|Etc/UTC|GMT"""
    /** An explicit Intl / Luxon / Day.js / Moment time zone in the site's code: the zone its days follow. */
    private val SCRIPT_TZ_OPTION = Regex("""timeZone["']?\s*:\s*["']($IANA_OR_UTC_PATTERN)["']""")
    private val SCRIPT_TZ_CALL = Regex("""(?:\.(?:setZone|tz)\(\s*["']($IANA_OR_UTC_PATTERN)["']\s*\)|(?:\bdayjs\.tz|\bmoment\.tz)\([^,()\n]{1,60},\s*["']($IANA_OR_UTC_PATTERN)["']\s*\))""")
    /** The start of a UTC day being computed (setUTCHours(0,0,…) or Date.UTC(d.getUTCFullYear(),…)). */
    private val SCRIPT_UTC_DAY = Regex("""(?:setUTCHours\(\s*0\s*,\s*0|Date\.UTC\(\s*[A-Za-z_$][\w$]*\.getUTCFullYear\(\)\s*,\s*[A-Za-z_$][\w$]*\.getUTCMonth\(\)\s*,\s*[A-Za-z_$][\w$]*\.getUTCDate\(\)\s*\))""")
    private val AD_SCRIPT_HOST = Regex("""(googletagmanager|google-analytics|googlesyndication|doubleclick|adservice|facebook|cloudflareinsights|hotjar|clarity\.ms|cdn\.jsdelivr|unpkg|cdnjs|gstatic|googleapis|amazon-adsystem|pubmatic|criteo|taboola|outbrain|sentry|segment|mixpanel|amplitude|newrelic|nr-data|btloader|html-load)""", RegexOption.IGNORE_CASE)
    private val VENDOR_SCRIPT_PATH = Regex("""(?:^|/)(?:jquery|bootstrap|modernizr|polyfill|polyfills|webpack|turbopack|framework[-.]|chunk-vendors|vendors?[-.]|lite-yt-embed|fontawesome|twemoji|confetti|lottie)(?:[-._min0-9a-z]*)\.js(?:$|\?)""", RegexOption.IGNORE_CASE)
    private val APP_BUNDLE_SCRIPT_PATH = Regex("""(?:^|/)(?:index|app|main|game|daily|puzzle|bundle|client|page)(?:[-._][A-Za-z0-9_-]+)?\.m?js(?:$|\?)""", RegexOption.IGNORE_CASE)
    private val DOMAIN_TITLE_SUFFIX = Regex("""\.(?:app|com|net|org|io|gg|wtf|me|xyz|co|games?|online|play)$""", RegexOption.IGNORE_CASE)
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?])\s+(?=[A-Z0-9"'“(])|\s+(?:[|\u00b7\u2022]|\u2014)\s+""")
    private val GAMEPLAY_VERB = Regex("""\b(guess|guessing|find|solve|connect|group|identify|name|spell|match|sort|unscramble|arrange|rearrange|discover|figure\s+out|uncover|place|build|fill|deduce|locate|pick|rank|order|link|clear|reveal|draw|trace|decode|crack|pin|swipe|tap|drag|drop|spot|put|get\s+between|test\s+your|aim|putt|sink|climb|reach|navigate|rotate|slide|swap|flip|merge|combine|complete|answer|listen)\b""", RegexOption.IGNORE_CASE)
    private val DESCRIPTION_NOISE = Regex("""\b(cookie|cookies|privacy|sign\s*in|log\s*in|subscribe|newsletter|download\s+the\s+app|javascript|enable\s+js|browser|advertis|terms\s+of|copyright|all\s+rights|discord|ko-fi|patreon|merch|leaderboard)""", RegexOption.IGNORE_CASE)
    private val PROMO_SENTENCE = Regex("""^(?:share\s+(?:your|with)|play\s+(?:the\s+)?(?:daily|archive|unlimited|party)|free\s*[,!]?\s*no\s+|no\s+(?:download|sign|login|account)|[\d,]+\+?\s+(?:titles|levels|puzzles|words|players)\b)""", RegexOption.IGNORE_CASE)
    private val MARKETING_WORDS = Regex("""\b(?:for\s+free|free\s+online|free(?!\s+to\b)|online|no\s+download|no\s+sign[- ]?up|addictive|viral|the\s+best|ultimate|official)\b[ ,]*""", RegexOption.IGNORE_CASE)
    private const val SHORT_DESCRIPTION_MAX = 90

    private data class CategoryRule(val category: String, val strong: Regex, val medium: Regex? = null)
    private val CATEGORY_RULES = listOf(
        CategoryRule(
            "Gaming & RPG",
            Regex("""\b(video\s*games?|rpg|mmorpg|jrpg|nintendo|pokemon|pok[e\u00e9]mon|pok[e\u00e9]dex|zelda|hyrule|mario|smash\s+bros|fire\s+emblem|playstation|xbox|steam|league\s+of\s+legends|valorant|overwatch|minecraft|fortnite|genshin|elden\s+ring|dark\s+souls|warcraft|hearthstone|runescape|final\s+fantasy|baldur|cyberpunk|halo|metroid|kirby|splatoon|gamedle|pokedle|pokedoku|loldle|nintendoku|valdle)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(gaming|esports|boss\s+fight|game\s+cover|cover\s+art|nes|snes|gamecube|game\s*boy|switch\s*2)\b""", RegexOption.IGNORE_CASE)
        ),
        CategoryRule(
            "Geography",
            Regex("""\b(geography|geographical|geoguessr|globle|worldle|travle|countryle|flagle|timeguessr|foodguessr|geogrid|farflung|world\s+map|street\s*view|countries|country|capitals?|capital\s+cit\w*|continents?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(map\s+quiz|map\s+game|on\s+the\s+map|world\s+atlas|atlas|globe|flags?|borders?|latitude|longitude|territor\w+|provinces?|us\s+states?)\b|\bmap\b""", RegexOption.IGNORE_CASE)
        ),
        CategoryRule(
            "Math & Logic",
            Regex("""\b(math|maths|mathematics|arithmetic|equations?|sudoku|nonogram|picross|minesweeper|nerdle|mathler|kenken|kakuro|futoshiki|queens\s+puzzle|chess|checkmate|logic\s+puzzle|logic\s+grid|deduce|deduction|murdle)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?<!puzzle\s)\bnumbers?\b(?!\s+of\b)|\b(numeric|calculat\w*|algebra|prime\s+number|domino\w*|logic)\b""", RegexOption.IGNORE_CASE)
        ),
        CategoryRule(
            "Music & Media",
            Regex("""\b(movies?|films?|cinema|filmography|box\s+office|actors?|actress\w*|directors?|imdb|tmdb|letterboxd|tv\s+shows?|television|episodes?|sitcom|anime|manga|songs?|music\w*|albums?|artists?|singers?|bands?|lyrics?|melody|spotify|soundcloud|billboard|heardle|bandle|spotle|framed|moviedle|actorle|cine2nerdle|chronophoto)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(soundtrack|instrument\w*|audio\s+clip|still\s+frames?|pop\s+culture)\b""", RegexOption.IGNORE_CASE)
        ),
        CategoryRule(
            "Word",
            Regex("""\b(wordle|quordle|octordle|sedecordle|duotrigordle|semantle|contexto|strands|waffle|squaredle|phrazle|redactle|spelling\s+bee|crosswords?|crossclimb|anagrams?|pangrams?|spangram|word\s+search|word\s+ladder|word\s+game|word\s+puzzle|word\s+building|word\s+association|secret\s+word|hidden\s+word|mystery\s+word|five-letter\s+word|letter\s+grid)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(words|word|letters?|spell|spelling|vocabulary|synonyms?|antonyms?|rhymes?|syllables?|vowels?|consonants?|phrases?|unscramble|jumble|lexicon|dictionary|cipher|cryptogram|cryptic)\b""", RegexOption.IGNORE_CASE)
        ),
        CategoryRule(
            "Trivia",
            Regex("""\b(trivia|history|historical|wikipedia|wiki|chronolog\w*|timeline|general\s+knowledge|jeopardy|catfishing|movie\s+grid|immaculate)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(quiz|quizzes)\b""", RegexOption.IGNORE_CASE)
        )
    )

    /**
     * Fetches the normalized URL and returns only the parsed metadata JSON without the 128 KB raw HTML (P9, K10).
     *
     * 1.8.3: also reads the site's web-app manifest and up to three of its own script files (for a
     * time zone its code names), and [rendered] (when given) waits for a copy of the page rendered with
     * its scripts run ([PageRenderer]). A page whose plain HTML is blocked or empty (a bot check, a
     * single-page app) is still read from the rendered copy.
     */
    fun fetchAndScrape(
        rawUrl: String,
        fetchExtras: Boolean = true,
        maxScriptUrls: Int = 3,
        maxScriptBytes: Int = 2 * 1024 * 1024,
        rendered: (() -> JSONObject?)? = null
    ): JSONObject {
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
                val body = String(resp.body, charsetFromResponse(resp.contentType, resp.body)).take(MAX_HTML_CHARS)
                // Cloudflare / bot-check interstitials carry no game metadata ("Just a moment...").
                if (looksLikeBotChallenge(body)) error = "bot_challenge" else html = body
            }
        }
        if (resp != null) result.put("finalUrl", finalUrl)

        // The manifest and the site's own code, within what's left of the time budget.
        fun left() = EXTRAS_BUDGET_MS - (System.currentTimeMillis() - started)
        var manifest: JSONObject? = null
        var scriptZone: String? = null
        if (fetchExtras) {
            manifestUrl(html, finalUrl)?.let { mu ->
                if (left() > 1_000) {
                    val r = SafeHttp.get(mu, headers = mapOf("User-Agent" to USER_AGENT), maxBytes = 64 * 1024, deadlineMs = minOf(4_000L, left()))
                    if (r != null && r.code in 200..299) manifest = runCatching { JSONObject(String(r.body, Charsets.UTF_8)) }.getOrNull()
                }
            }
            if (maxScriptUrls > 0 && maxScriptBytes > 0) {
                for (src in ownScriptUrls(html, finalUrl, max = maxScriptUrls)) {
                    if (scriptZone != null || left() < 1_500) break
                    val r = SafeHttp.get(src, headers = mapOf("User-Agent" to USER_AGENT), maxBytes = maxScriptBytes, deadlineMs = minOf(5_000L, left()))
                    if (r != null && r.code in 200..299) scriptZone = scriptZoneHint(String(r.body, Charsets.UTF_8))
                }
            }
        }
        val renderedJson = rendered?.invoke()
        val rawExtras = PageExtras.fromRendered(renderedJson).copy(manifest = manifest, scriptZone = scriptZone)
        val renderedIsChallenge = looksLikeBotChallenge(rawExtras.renderedTitle) || looksLikeBotChallenge(rawExtras.renderedText)
        val extras = if (renderedIsChallenge) {
            PageExtras(manifest = manifest, scriptZone = scriptZone)
        } else {
            rawExtras
        }
        // Where the rendered page ended up (a redirect the plain fetch didn't get to follow).
        if (!renderedIsChallenge && (resp == null || error == "bot_challenge" || error == "http_403")) {
            renderedJson?.optString("url")?.takeIf { it.startsWith("http") }?.let { finalUrl = it; result.put("finalUrl", it) }
        }

        val renderedUseful = !renderedIsChallenge && (extras.renderedText.length >= 40 || extras.renderedTitle.isNotBlank())
        result.put("parsed", parseHtmlMetadata(html, finalUrl, extras).toJson())
        // Games shown inside this page (1.8.13): the hub asks before adding one it already has.
        val embeds = embeddedGameLinks(html, finalUrl, extras.frames)
        if (embeds.isNotEmpty()) result.put("embeds", JSONArray(embeds))
        result.put("rendered", renderedUseful)
        val recoverableError = error == "bot_challenge" || error == "fetch_failed" || error == "http_403"
        if (error != null && !(renderedUseful && recoverableError)) {
            result.put("ok", false)
            result.put("error", error)
        } else {
            result.put("ok", true)
        }
        return result
    }

    private val IFRAME_SRC = Regex("""<iframe\b[^>]*?\bsrc\s*=\s*["']([^"'<>]+)["']""", RegexOption.IGNORE_CASE)
    private val NOT_A_GAME_FRAME_HOST = Regex("""(youtube|youtu\.be|vimeo|recaptcha|disqus|facebook|twitter|x\.com|instagram|tiktok|spotify|soundcloud|consent|cookiebot|onetrust|usercentrics|didomi|quantcast|trustarc)""", RegexOption.IGNORE_CASE)
    private val NOT_A_GAME_FRAME_URL = Regex("""(google\.com/maps|youtube\.com/embed|player\.vimeo\.com|platform\.twitter\.com|connect\.facebook\.net|consent|cookie[-_]?banner|cookie[-_]?consent)""", RegexOption.IGNORE_CASE)

    /**
     * Frames on a page that could be a game shown inside it (1.8.13): from its HTML and, when it was
     * rendered, the big frames its scripts added. Ads, videos, maps and sign-in frames are left out.
     */
    fun resolveUrl(pageUrl: String, ref: String): String? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("data:", true) || trimmed.startsWith("javascript:", true) || trimmed.startsWith("mailto:", true) || trimmed.startsWith("blob:", true)) {
            return null
        }
        val safeRef = trimmed.replace(" ", "%20")
        return runCatching {
            val base = URI(pageUrl)
            val normalizedBase = if (base.path.isNullOrEmpty() && !base.host.isNullOrEmpty()) {
                URI(base.scheme, base.userInfo, base.host, base.port, "/", base.query, base.fragment)
            } else {
                base
            }
            val resolved = normalizedBase.resolve(safeRef)
            val clampedPath = resolved.path?.replace(Regex("""^/(?:\.\./)+"""), "/")
            if (clampedPath != resolved.path && resolved.host != null) {
                URI(resolved.scheme, resolved.userInfo, resolved.host, resolved.port, clampedPath, resolved.query, resolved.fragment).toString()
            } else {
                resolved.toString()
            }
        }.getOrNull()
    }

    fun embeddedGameLinks(html: String, pageUrl: String, rendered: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        (IFRAME_SRC.findAll(html).map { it.groupValues[1].trim() } + rendered.asSequence()).forEach { raw ->
            val abs = resolveUrl(pageUrl, raw) ?: raw
            val u = runCatching { URI(abs) }.getOrNull() ?: return@forEach
            val scheme = u.scheme?.lowercase(Locale.US)
            val host = u.host?.lowercase(Locale.US)
            if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) return@forEach
            if (AD_SCRIPT_HOST.containsMatchIn(host) || NOT_A_GAME_FRAME_HOST.containsMatchIn(host) || NOT_A_GAME_FRAME_URL.containsMatchIn(abs)) return@forEach
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
        val keywords = decodeHtmlEntities(meta("keywords"))
        val rawTitle = extractTitleText(html).ifBlank { extras.renderedTitle }
        val ld = jsonLdInfo(extractJsonLdBlocks(html) + extras.jsonLd)
        val manifest = extras.manifest

        val rawBestTitle = listOf(
            ogSiteName, appName, appleTitle,
            manifest?.optString("short_name").orEmpty(), manifest?.optString("name").orEmpty(),
            ld.name, ogTitle, rawTitle
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val pageHeadingTitle = cleanGameTitle(ogTitle.ifBlank { rawTitle })
        val cleanedName = cleanGameTitle(rawBestTitle, pageHeadingTitle).ifBlank { fallbackName }

        val visibleBodyText = stripScriptsStylesAndTags(html).take(30_000)
        val rulesSentences = (howToPlaySentences(extras.renderedText) + howToPlaySentences(visibleBodyText)).distinct()
        val metaDescriptions = listOf(
            meta("og:description"), meta("description"), meta("twitter:description"),
            ld.description, manifest?.optString("description").orEmpty()
        ).map { decodeHtmlEntities(it) }
        val descCandidates = metaDescriptions + rulesSentences
        val description = pickShortDescription(descCandidates, cleanedName)

        val faviconHref = extractLinkHref(
            html,
            listOf("apple-touch-icon", "shortcut icon", "icon", "fluid-icon")
        ).ifBlank { extras.renderedIcon }.ifBlank { manifestIconHref(manifest) }
        val faviconUrl = resolveFaviconUrl(faviconHref, safeUrl, host)

        val allDescriptions = descCandidates.joinToString("\n")
        val priorityText = "$allDescriptions\n${ld.description}\n${rulesSentences.joinToString("\n")}"
        val combinedText = "$cleanedName\n$allDescriptions\n$rawTitle\n$visibleBodyText\n${extras.renderedText}"
        val schedule = detectResetScheduleDetail(combinedText)
        // No zone written on the page: one the site's own script names (its days follow it).
        val resetZone = if (!schedule.zoneFound && extras.scriptZone != null) extras.scriptZone else schedule.zone
        val resetTime = schedule.time
        // No clue about how it's scored: say nothing (the hub keeps "Daily Puzzle" and learns it from
        // the first results) instead of guessing "6 guesses".
        val (metricType, par) = detectMetric(priorityText) ?: detectMetric(combinedText) ?: ("" to 0)
        val targetFormat = detectTargetFormat(priorityText, combinedText)
        val category = detectCategory("$cleanedName $allDescriptions $rawTitle ${ld.genre} $keywords", host)

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
            zoneFound = schedule.zoneFound || extras.scriptZone != null,
            targetFormat = targetFormat
        )
    }

    /** Strips `<script>`, `<style>`, and HTML tags while keeping block breaks as `\n` so unrelated UI blocks don't bleed together (K11). */
    fun stripScriptsStylesAndTags(rawHtml: String): String {
        if (rawHtml.isEmpty()) return ""
        val withoutScripts = SCRIPT_STYLE_REGEX.replace(rawHtml, " ")
        val withBreaks = BLOCK_TAG_REGEX.replace(withoutScripts, "\n")
        val withoutTags = HTML_TAG_REGEX.replace(withBreaks, " ")
        val cleanLines = HORIZ_SPACE_REGEX.replace(withoutTags, " ")
            .let { MULTI_NEWLINE_REGEX.replace(it, "\n") }
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return decodeHtmlEntities(cleanLines)
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

    fun cleanGameTitle(raw: String, cleanFallback: String = ""): String {
        val decoded = decodeHtmlEntities(raw).trim()
        if (decoded.isEmpty()) return ""
        val firstSegment = decoded.split(TITLE_SPLIT_REGEX).firstOrNull()?.trim().orEmpty()
        var candidate = (if (firstSegment.length >= 2) firstSegment else decoded).take(40)
        // Prefer "Squaredle" over "Squaredle.app" when <title> confirms the clean brand name.
        if (DOMAIN_TITLE_SUFFIX.containsMatchIn(candidate)) {
            val stem = DOMAIN_TITLE_SUFFIX.replace(candidate, "").trim()
            if (stem.length >= 2 && (cleanFallback.equals(stem, ignoreCase = true) || stem[0].isUpperCase())) {
                candidate = if (cleanFallback.equals(stem, ignoreCase = true)) cleanFallback else stem
            }
        }
        return candidate
    }

    /** True for bot-protection interstitials (Cloudflare "Just a moment...", DataDome, etc.). */
    fun looksLikeBotChallenge(html: String): Boolean {
        if (html.isEmpty()) return false
        val head = html.take(20_000)
        if (CHALLENGE_TITLE_REGEX.containsMatchIn(head)) return true
        if (!CHALLENGE_MARKER_REGEX.containsMatchIn(head)) return false
        return !REAL_PAGE_META_REGEX.containsMatchIn(head)
    }

    fun charsetFromContentType(contentType: String?): Charset {
        val name = contentType?.let { CHARSET_REGEX.find(it)?.groupValues?.get(1) }
        return try {
            if (name.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    fun charsetFromResponse(contentType: String?, body: ByteArray): Charset {
        val headerName = contentType?.let { CHARSET_REGEX.find(it)?.groupValues?.get(1) }
        if (!headerName.isNullOrBlank()) {
            return runCatching { Charset.forName(headerName) }.getOrDefault(Charsets.UTF_8)
        }
        val sniff = String(body, 0, minOf(body.size, 2048), Charsets.ISO_8859_1)
        val m = META_CHARSET_REGEX.find(sniff)
        val metaName = m?.groupValues?.get(1)?.ifEmpty { m.groupValues[2] }
        return if (!metaName.isNullOrBlank()) {
            runCatching { Charset.forName(metaName) }.getOrDefault(Charsets.UTF_8)
        } else {
            Charsets.UTF_8
        }
    }

    /**
     * Single-pass decoder for named and numeric (`&#8217;` / `&#x27;`) HTML entities.
     * Single-pass prevents double-decoding (e.g. `&amp;lt;` -> `&lt;` and `&#38;lt;` -> `&lt;`)
     * while preserving entity case (`&Eacute;` vs `&eacute;`).
     */
    fun decodeHtmlEntities(input: String): String {
        if (input.isEmpty()) return ""
        return ENTITY_REGEX.replace(input) { m ->
            val num = m.groupValues[1]
            if (num.isNotEmpty()) {
                val code = if (num.startsWith("x", ignoreCase = true)) num.substring(1).toIntOrNull(16) else num.toIntOrNull()
                if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) {
                    String(Character.toChars(code))
                } else {
                    m.value
                }
            } else {
                val name = m.groupValues[2]
                NAMED_ENTITIES[name] ?: NAMED_ENTITIES[name.lowercase(Locale.US)] ?: m.value
            }
        }.trim()
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
                    Regex("""<link\b[^>]*?\brel\s*=\s*["']([^"']*\b$escaped\b[^"']*)["'][^>]*?\bhref\s*=\s*$ATTR_VALUE""", RegexOption.IGNORE_CASE),
                    Regex("""<link\b[^>]*?\bhref\s*=\s*$ATTR_VALUE[^>]*?\brel\s*=\s*["']([^"']*\b$escaped\b[^"']*)["']""", RegexOption.IGNORE_CASE)
                )
            }
            for ((idx, p) in patterns.withIndex()) {
                for (m in p.findAll(html)) {
                    val relAttr = if (idx == 0) m.groupValues[1] else m.groupValues[3]
                    if (relAttr.contains("mask-icon", ignoreCase = true)) continue
                    val href = if (idx == 0) m.groupValues[2].ifEmpty { m.groupValues[3] }.trim() else m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
                    if (href.isNotBlank()) return decodeHtmlEntities(href)
                }
            }
        }
        return ""
    }

    private fun manifestIconHref(manifest: JSONObject?): String {
        val icons = manifest?.optJSONArray("icons") ?: return ""
        var bestSrc = ""
        var bestRank = Int.MAX_VALUE
        for (i in 0 until icons.length()) {
            val icon = icons.optJSONObject(i) ?: continue
            val src = icon.optString("src").trim()
            if (src.isEmpty() || src.startsWith("data:", true)) continue
            val sizes = icon.optString("sizes").lowercase(Locale.US)
            val rank = when {
                "192x192" in sizes || "180x180" in sizes -> 0
                "512x512" in sizes || "256x256" in sizes -> 1
                "128x128" in sizes || "96x96" in sizes -> 2
                else -> 3
            }
            if (rank < bestRank) {
                bestRank = rank
                bestSrc = src
            }
        }
        return bestSrc
    }

    fun resolveFaviconUrl(href: String, pageUrl: String, host: String): String {
        if (href.isNotBlank()) {
            val resolved = resolveUrl(pageUrl, href)
            if (!resolved.isNullOrBlank()) return resolved
            if (href.startsWith("http://", true) || href.startsWith("https://", true)) return href
        }
        return if (host.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=$host&sz=128"
        } else {
            ""
        }
    }

    /** Zone written immediately after a matched reset time (e.g. "9:00 PM ET", "midnight in your local time"), or null. */
    private fun zoneAfter(text: String, endIndex: Int): String? {
        if (endIndex >= text.length) return null
        val tail = text.substring(endIndex, minOf(text.length, endIndex + 55))
        IANA_AFTER_REGEX.find(tail)?.let { return it.groupValues[1] }
        return ZONE_TOKENS.firstOrNull { (re, _) -> re.containsMatchIn(tail) }?.second
    }

    /** Fixed numeric UTC/GMT offset after a reset time ("08:00 UTC+8" -> shifted UTC HH:MM), or null. */
    private fun utcOffsetSchedule(hour: Int, minute: Int, text: String, endIndex: Int): ScheduleGuess? {
        if (endIndex >= text.length) return null
        val tail = text.substring(endIndex, minOf(text.length, endIndex + 40))
        val m = UTC_OFFSET_AFTER_REGEX.find(tail) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val offHours = m.groupValues[2].toIntOrNull() ?: return null
        val offMins = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val totalUtcMinutes = ((hour * 60 + minute) - sign * (offHours * 60 + offMins)).mod(24 * 60)
        val utcTime = String.format(Locale.US, "%02d:%02d", totalUtcMinutes / 60, totalUtcMinutes % 60)
        return ScheduleGuess(utcTime, "utc", timeFound = true, zoneFound = true)
    }

    /**
     * Finds the daily reset time and the time zone it is published in. Returns the time as HH:MM
     * plus "local", "utc" or an IANA zone id; the hub converts it to the user's local time for display.
     */
    fun detectResetSchedule(text: String): Pair<String, String> = detectResetScheduleDetail(text).let { it.time to it.zone }

    /** A reset schedule and whether the page actually said the time / the zone (vs. the defaults). */
    data class ScheduleGuess(val time: String, val zone: String, val timeFound: Boolean, val zoneFound: Boolean)

    fun detectResetScheduleDetail(text: String): ScheduleGuess {
        // Only treat UTC/GMT as an explicit reset zone if it appears near a reset phrase (or directly after a reset time).
        val utcNearReset = RESET_NEAR_UTC_REGEX.containsMatchIn(text)
        val globalZone = if (utcNearReset) "utc" else "local"
        fun guess(hour: Int, minute: Int, after: Int): ScheduleGuess {
            utcOffsetSchedule(hour, minute, text, after)?.let { return it }
            val z = zoneAfter(text, after)
            val time = String.format(Locale.US, "%02d:%02d", hour, minute)
            return ScheduleGuess(time, z ?: globalZone, true, z != null || utcNearReset)
        }

        for (m in HHMM_RESET_REGEX.findAll(text)) {
            val bridge = m.groupValues[1]
            val hourRaw = m.groupValues[2]
            var hour = hourRaw.toInt()
            val minute = m.groupValues[3].toInt()
            val suffix = m.groupValues[4].lowercase(Locale.US).replace(".", "")
            val after = m.range.last + 1
            // Single-digit "H:MM" without "at/by/@", am/pm, or a trailing timezone is often a ratio ("scale 1:50").
            if (hourRaw.length == 1 && suffix.isEmpty() && !AT_PREP_END_REGEX.containsMatchIn(bridge) &&
                zoneAfter(text, after) == null && utcOffsetSchedule(hour, minute, text, after) == null
            ) {
                continue
            }
            when (suffix) {
                "midnight" -> if (hour == 12 && minute == 0) hour = 0
                "noon" -> if (hour == 12 && minute == 0) hour = 12
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            return guess(hour, minute, after)
        }
        EU_HHMM_RESET_REGEX.find(text)?.let { m ->
            return guess(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.range.last + 1)
        }
        HOUR_AMPM_RESET_REGEX.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val suffix = m.groupValues[2].lowercase(Locale.US).replace(".", "")
            when {
                suffix == "midnight" -> hour = 0
                suffix == "noon" -> hour = 12
                suffix.startsWith("p") -> if (hour < 12) hour += 12
                else -> if (hour == 12) hour = 0
            }
            return guess(hour, 0, m.range.last + 1)
        }
        MIDNIGHT_OR_NOON_REGEX.find(text)?.let { m ->
            val word = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val hour = if (word.equals("noon", ignoreCase = true)) 12 else 0
            // When matched via "At midnight UTC, a new puzzle...", the zone immediately follows midnight/noon.
            val wordEnd = if (m.groupValues[1].isNotEmpty()) m.range.last + 1 else m.groups[2]!!.range.last + 1
            return guess(hour, 0, wordEnd)
        }
        return ScheduleGuess("00:00", globalZone, false, utcNearReset)
    }

    private fun parseCountToken(raw: String): Int? {
        val clean = raw.trim().lowercase(Locale.US)
        return clean.toIntOrNull() ?: NUMBER_WORDS[clean]
    }

    /** How a game is scored, or "tries" / 6 when the text gives no clue (see [detectMetric]). */
    fun detectMetricAndPar(text: String): Pair<String, Int> = detectMetric(text) ?: ("tries" to 6)

    /** "tries" with the number of guesses, "time" or "points" when the text says so; null when it doesn't. */
    fun detectMetric(text: String): Pair<String, Int>? {
        val lower = text.lowercase(Locale.US)
        val guessesMatch = IN_GUESSES_REGEX.find(lower) ?: GUESSES_REGEX.find(lower)
        if (guessesMatch != null) {
            val n = parseCountToken(guessesMatch.groupValues[1]) ?: 6
            return "tries" to n.coerceIn(1, 64)
        }
        EXPLICIT_SECONDS_REGEX.find(lower)?.let { m ->
            val secs = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: 60
            return "time" to secs.coerceIn(10, 600)
        }
        if (STOPWATCH_REGEX.containsMatchIn(lower)) {
            return "time" to 60
        }
        MAX_POINTS_REGEX.find(lower)?.let { m ->
            val pts = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "").toIntOrNull()
            if (pts != null && pts > 0) return "points" to pts
        }
        if (POINTS_REGEX.containsMatchIn(lower)) {
            return "points" to 100
        }
        return null
    }

    /**
     * Extracts a human-readable Target Format / Par (e.g. "6 guesses", "4 mistakes", "5 prompts",
     * "Fewest guesses", "Fewest strokes", "50,000 points", "Fastest time", "Most points"), or ""
     * when the page gives no clear target format.
     */
    fun detectTargetFormat(priorityText: String, fullText: String = priorityText): String {
        for (source in listOf(priorityText, fullText)) {
            if (source.isBlank()) continue
            formatFromSource(source)?.let { return it }
        }
        // Broader optimization / timer / points fallbacks across the full text when no explicit count was stated.
        val combined = "$priorityText\n$fullText"
        if (MINIGOLF_REGEX.containsMatchIn(combined)) return "Fewest strokes"
        if (MOST_KM_REGEX.containsMatchIn(combined)) return "Most km"
        EXPLICIT_SECONDS_REGEX.find(combined)?.let { m ->
            val secs = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
            if (secs != null) return "${secs}s target"
        }
        if (STOPWATCH_REGEX.containsMatchIn(combined)) return "Fastest time"
        if (POINTS_REGEX.containsMatchIn(combined)) return "Most points"
        return ""
    }

    private fun formatFromSource(source: String): String? {
        FEWEST_FORMAT_REGEX.find(source)?.let { m ->
            val rawUnit = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase(Locale.US)
            val unit = if (rawUnit == "tries") "guesses" else rawUnit
            return "Fewest $unit"
        }
        if (UNLIMITED_GUESSES_REGEX.containsMatchIn(source)) return "Fewest guesses"
        if (SHORTEST_PATH_REGEX.containsMatchIn(source)) return "Shortest path"
        if (NO_MISTAKES_REGEX.containsMatchIn(source)) return "No mistakes"
        FIND_ALL_WORDS_REGEX.find(source)?.let { m ->
            val kind = m.groupValues[1].lowercase(Locale.US)
            return if (kind.contains("theme")) "Find all theme words" else "Find all words"
        }
        (IN_GUESSES_REGEX.find(source) ?: GUESSES_REGEX.find(source))?.let { m ->
            val n = parseCountToken(m.groupValues[1]) ?: return@let
            val rawUnit = m.groupValues[2].lowercase(Locale.US)
            val unit = when (rawUnit) {
                "tries", "guesses", "attempts", "chances" -> "guesses"
                else -> rawUnit
            }
            return "$n $unit"
        }
        MAX_KM_REGEX.find(source)?.let { m ->
            val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "")
            val n = raw.toIntOrNull()
            if (n != null) return "${String.format(Locale.US, "%,d", n)} km"
        }
        MAX_POINTS_REGEX.find(source)?.let { m ->
            val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "")
            val n = raw.toIntOrNull()
            if (n != null) return "${String.format(Locale.US, "%,d", n)} points"
        }
        ROUNDS_FORMAT_REGEX.find(source)?.let { m ->
            val n = parseCountToken(m.groupValues[1]) ?: return@let
            val unit = m.groupValues[2].lowercase(Locale.US)
            return "$n $unit"
        }
        if (GRID_3X3_REGEX.containsMatchIn(source)) return "9 answers (3x3 grid)"
        return null
    }

    // ---- 1.8.3: JSON-LD, manifest, script clues, short descriptions ----

    /** The JSON-LD blocks in raw HTML. */
    fun extractJsonLdBlocks(html: String): List<String> =
        if (html.isEmpty()) emptyList() else JSON_LD_REGEX.findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.take(6).toList()

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
        fun strField(o: JSONObject, key: String): String = (o.opt(key) as? String).orEmpty()
        val best = found.filter { strField(it, "name").isNotBlank() || strField(it, "description").isNotBlank() }.minByOrNull(rank)
            ?: return JsonLdInfo()
        val genre = when (val g = best.opt("genre") ?: best.opt("applicationCategory") ?: best.opt("keywords")) {
            is JSONArray -> (0 until g.length()).joinToString(" ") { g.optString(it) }
            null -> ""
            else -> g.toString()
        }
        return JsonLdInfo(decodeHtmlEntities(strField(best, "name")), decodeHtmlEntities(strField(best, "description")), genre)
    }

    /** The web-app manifest's address, if the page links one. */
    fun manifestUrl(html: String, pageUrl: String): String? {
        val href = extractLinkHref(html, listOf("manifest")).takeIf { it.isNotBlank() } ?: return null
        return resolveUrl(pageUrl, href)
    }

    /** Up to [max] of the page's own script files (same site, not ad / analytics / vendor libraries). */
    fun ownScriptUrls(html: String, pageUrl: String, max: Int = 3): List<String> {
        if (html.isEmpty()) return emptyList()
        val pageHost = extractHost(pageUrl)
        val site = AdBlockEngine.siteOf(pageHost)
        val candidates = SCRIPT_SRC_REGEX.findAll(html).mapNotNull { m ->
            val src = decodeHtmlEntities(m.groupValues[1].ifEmpty { m.groupValues[2] })
            val abs = resolveUrl(pageUrl, src) ?: return@mapNotNull null
            val h = extractHost(abs)
            if (h.isEmpty() || AD_SCRIPT_HOST.containsMatchIn(h)) return@mapNotNull null
            if (h != pageHost && !h.endsWith(".$site") && h != site) return@mapNotNull null
            val path = runCatching { URI(abs).path.orEmpty() }.getOrDefault("")
            if (VENDOR_SCRIPT_PATH.containsMatchIn(path)) return@mapNotNull null
            abs
        }.distinct().toList()
        // Keep document order, except promote main app/game bundles ahead of anonymous chunk hashes.
        val appBundles = candidates.filter { APP_BUNDLE_SCRIPT_PATH.containsMatchIn(it) }
        val others = candidates.filterNot { APP_BUNDLE_SCRIPT_PATH.containsMatchIn(it) }
        return (appBundles + others).take(max)
    }

    /** The zone a site's days follow according to its own code, or null when the code doesn't say. */
    fun scriptZoneHint(js: String): String? {
        fun normZone(z: String) = if (z.equals("UTC", true) || z.equals("Etc/UTC", true) || z.equals("GMT", true)) "utc" else z
        SCRIPT_TZ_OPTION.find(js)?.let { return normZone(it.groupValues[1]) }
        SCRIPT_TZ_CALL.find(js)?.let { m ->
            val z = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (z.isNotEmpty()) return normZone(z)
        }
        return if (SCRIPT_UTC_DAY.containsMatchIn(js)) "utc" else null
    }

    /** Sentences from a page's "How to play" / rules / about text (where games explain themselves). */
    fun howToPlaySentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val at = Regex("(?i)\\b(?:how\\s+to\\s+play|the\\s+rules|game\\s+rules|instructions)\\b").find(text)?.range?.last ?: return emptyList()
        return text.substring(at + 1, minOf(text.length, at + 1800)).split('\n')
            .map { it.trim() }.filter { it.length in 15..220 }.take(8)
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
                if (PROMO_SENTENCE.containsMatchIn(s)) score -= 3
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
            // Strip repeated leading "<Name> - " / "Play <Name> free: " / "<Name> is a daily game where you …"
            repeat(2) {
                s = s.replace(Regex("(?i)^(?:play\\s+)?$n(?:\\s+(?:for\\s+free|free|online|daily|today|now))*\\s*(?::|-|–|—|\\||!)\\s*"), "")
            }
            s = s.replace(Regex("(?i)^in\\s+$n\\s*,\\s*(?:you\\s+)?"), "")
            s = s.replace(Regex("(?i)^$n\\s+is\\s+(?:an?\\s+|the\\s+)?(?:[\\w-]+\\s+){0,5}?(?:game|puzzle|quiz|challenge)\\s+(?:where|in\\s+which)\\s+you\\s+"), "")
        }
        s = s.replace(Regex("(?i)^(?:an?\\s+|the\\s+)?(?:free\\s+|official\\s+|online\\s+)*daily\\s+(?:[\\w-]+\\s+){0,2}?(?:game|puzzle|quiz|challenge)\\s+(?:where|in\\s+which)\\s+you\\s+"), "")
        s = s.replace(Regex("(?i)^(?:an?\\s+)?daily\\s+(?:[\\w-]+\\s+){0,2}?(?:game|puzzle|quiz|challenge)\\s*[,:\\-–—]\\s*"), "")
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
        var bestCategory = ""
        var bestScore = 0
        for (rule in CATEGORY_RULES) {
            val strongHits = rule.strong.findAll(combined).take(4).count()
            val mediumHits = rule.medium?.findAll(combined)?.take(4)?.count() ?: 0
            val score = strongHits * 4 + mediumHits * 2
            if (score > bestScore) {
                bestScore = score
                bestCategory = rule.category
            }
        }
        return if (bestScore > 0) bestCategory else "Custom Daily"
    }
}
