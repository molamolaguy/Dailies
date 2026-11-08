package com.dailygames.hub

import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Network ad / tracker blocker for the game player.
 *
 * Data is compiled by tools/build_adblock.py from EasyList, EasyPrivacy, AdGuard Base, HaGeZi Pro and
 * StevenBlack hosts (see assets/adblock/sources.json):
 *  - [blockedHosts]: ~300k hostnames as sorted 64-bit FNV-1a hashes (a host blocks its subdomains too)
 *  - [importantHosts]: host rules marked `$important` (only `@@…$important` exceptions can undo them)
 *  - URL rules in a subset of Adblock Plus syntax: anchors `||` `|`, wildcards `*`, separator `^`,
 *    options third-party / 1p / resource types (incl. uBO aliases) / domain= / important / match-case,
 *    `@@` exceptions.
 *  - [documentAllowedSites]: sites the lists exempt from blocking entirely (`@@||site^$document`).
 *
 * Precedence (as in ABP/uBO): `$important` blocks beat normal exceptions; `@@…$important` exceptions
 * beat everything; otherwise exceptions beat blocks. Requests to the page's own site are never
 * blocked by the big host lists, so a game can't be broken by its own domain being listed.
 */
class AdBlockEngine(
    private val blockedHosts: LongArray,
    private val blockedThirdPartyHosts: LongArray,
    private val allowedHosts: Set<String>,
    urlRuleLines: Sequence<String>,
    private val importantHosts: LongArray = LongArray(0),
    private val documentAllowedSites: Set<String> = emptySet()
) {
    /** PING / WEBSOCKET / OBJECT are never inferred from a WebView request, so such rules never fire. */
    enum class RequestType { DOCUMENT, SUBDOCUMENT, SCRIPT, STYLESHEET, IMAGE, MEDIA, FONT, XHR, PING, WEBSOCKET, OBJECT, OTHER }

    private class UrlRule(
        val allow: Boolean,
        val pattern: String,          // lower-cased unless matchCase
        val hostAnchor: Boolean,
        val startAnchor: Boolean,
        val endAnchor: Boolean,
        val thirdParty: Boolean?,     // true = 3p only, false = 1p only, null = any
        val types: Set<RequestType>?, // null = any type
        val excludedTypes: Set<RequestType>,
        val includeDomains: List<String>,
        val excludeDomains: List<String>,
        val important: Boolean,
        val matchCase: Boolean
    )

    private class RuleSet {
        val index = HashMap<String, MutableList<UrlRule>>()
        val untokened = ArrayList<UrlRule>()
        fun add(r: UrlRule, token: String?) {
            if (token == null) untokened.add(r) else index.getOrPut(token) { ArrayList(2) }.add(r)
        }
    }

    private val blocks = RuleSet()
    private val importantBlocks = RuleSet()
    private val allows = RuleSet()
    private val importantAllows = RuleSet()
    var ruleCount = 0
        private set

    init {
        for (line in urlRuleLines) {
            val rule = parseRule(line.trim()) ?: continue
            ruleCount++
            val set = when {
                rule.allow && rule.important -> importantAllows
                rule.allow -> allows
                rule.important -> importantBlocks
                else -> blocks
            }
            set.add(rule, bestToken(rule))
        }
    }

    /**
     * @param url full request URL
     * @param pageHost host of the document that made the request (frame or page; null if unknown)
     */
    fun shouldBlock(url: String, pageHost: String?, type: RequestType): Boolean {
        if (type == RequestType.DOCUMENT) return false // never blank the game page itself
        val lowerUrl = url.lowercase(Locale.US)
        val host = hostOf(lowerUrl) ?: return false
        if (pageHost != null && isDocumentAllowed(pageHost)) return false
        if (isAllowedHost(host)) return false
        val thirdParty = pageHost != null && !sameSite(host, pageHost)
        val tokens = tokenize(lowerUrl)
        val ctx = Ctx(url, lowerUrl, host, pageHost, thirdParty, type, tokens)

        // 1) $important blocks: only an @@…$important exception can lift them.
        val importantHit = hostHashHit(host, importantHosts) || findMatch(ctx, importantBlocks) != null
        if (importantHit) return findMatch(ctx, importantAllows) == null

        // 2) Normal blocks (host lists, then URL rules), lifted by any exception.
        val hostListHit = (thirdParty || pageHost == null) &&
            (hostHashHit(host, blockedHosts) || (thirdParty && hostHashHit(host, blockedThirdPartyHosts)))
        if (!hostListHit && findMatch(ctx, blocks) == null) return false
        return findMatch(ctx, importantAllows) == null && findMatch(ctx, allows) == null
    }

    /** Sites the filter lists deliberately exempt from all blocking (`@@||site^$document`). */
    fun isDocumentAllowed(pageHost: String): Boolean =
        documentAllowedSites.isNotEmpty() && anySuffix(pageHost) { it in documentAllowedSites }

    private class Ctx(
        val url: String, val lowerUrl: String, val host: String, val pageHost: String?,
        val thirdParty: Boolean, val type: RequestType, val tokens: Set<String>
    )

    // ------------------------------------------------------------------ hosts
    private fun isAllowedHost(host: String): Boolean = anySuffix(host) { it in allowedHosts }

    /** Checks the host and each parent domain (a.b.example.com, b.example.com, example.com). */
    private fun hostHashHit(host: String, table: LongArray): Boolean =
        table.isNotEmpty() && anySuffix(host) { table.binarySearch(fnv1a64(it)) >= 0 }

    private inline fun anySuffix(host: String, test: (String) -> Boolean): Boolean {
        var h = host
        while (true) {
            if (test(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    // ------------------------------------------------------------------ URL rules
    private fun findMatch(c: Ctx, set: RuleSet): UrlRule? {
        if (set.index.isNotEmpty()) {
            for (t in c.tokens) {
                val rules = set.index[t] ?: continue
                for (r in rules) if (applies(r, c)) return r
            }
        }
        for (r in set.untokened) if (applies(r, c)) return r
        return null
    }

    private fun applies(r: UrlRule, c: Ctx): Boolean {
        if (r.thirdParty != null) {
            if (c.pageHost == null) return false
            if (r.thirdParty != c.thirdParty) return false
        }
        if (r.types != null && !typeMatches(r.types, c.type, c.thirdParty)) return false
        if (c.type in r.excludedTypes) return false
        // An unknown-type request might be an XHR: `$~xmlhttprequest` rules must not hit it.
        if (c.type == RequestType.OTHER && RequestType.XHR in r.excludedTypes) return false
        if (r.includeDomains.isNotEmpty() || r.excludeDomains.isNotEmpty()) {
            val ph = c.pageHost ?: return false
            if (r.excludeDomains.any { domainMatches(ph, it) }) return false
            if (r.includeDomains.isNotEmpty() && r.includeDomains.none { domainMatches(ph, it) }) return false
        }
        val subject = if (r.matchCase) c.url else c.lowerUrl
        return matchesPattern(r, subject, c.host)
    }

    /**
     * Requests whose type can't be inferred (OTHER: fetch/XHR without hints) only match rules for
     * xhr/other, plus `$script` rules when the request is third-party. That keeps script-only rules
     * from breaking a game's own API calls.
     */
    private fun typeMatches(types: Set<RequestType>, type: RequestType, thirdParty: Boolean): Boolean {
        if (type in types) return true
        if (type != RequestType.OTHER) return false
        return RequestType.XHR in types || RequestType.OTHER in types || (thirdParty && RequestType.SCRIPT in types)
    }

    private fun matchesPattern(r: UrlRule, s: String, host: String): Boolean {
        val p = r.pattern
        if (r.hostAnchor) {
            // Must start at the beginning of the host or right after a dot inside it.
            val hostStart = hostStartIndex(s)
            val hostEnd = hostStart + host.length
            var i = hostStart
            while (i <= hostEnd) {
                if ((i == hostStart || s[i - 1] == '.') && glob(p, 0, s, i, r.endAnchor)) return true
                val next = s.indexOf('.', i)
                if (next < 0 || next >= hostEnd) break
                i = next + 1
            }
            return false
        }
        if (r.startAnchor) return glob(p, 0, s, 0, r.endAnchor)
        // Unanchored: try every start position (first char narrows it quickly).
        val first = p.firstOrNull() ?: return true
        if (first == '*') return glob(p, 0, s, 0, r.endAnchor)
        var i = 0
        while (i < s.length) {
            if (first == '^' || s[i] == first) {
                if (glob(p, 0, s, i, r.endAnchor)) return true
            }
            i++
        }
        return first == '^' && glob(p, 0, s, s.length, r.endAnchor)
    }

    /** ABP glob: `*` any run, `^` separator (non [A-Za-z0-9_.%-]) or end of URL. */
    private fun glob(p: String, pi0: Int, s: String, si0: Int, endAnchor: Boolean): Boolean {
        var pi = pi0
        var si = si0
        var starP = -1
        var starS = -1
        while (true) {
            if (pi == p.length) {
                if (!endAnchor || si == s.length) return true
            } else if (p[pi] == '*') {
                starP = pi; starS = si; pi++; continue
            } else if (p[pi] == '^') {
                if (si == s.length) { pi++; continue }
                if (isSeparator(s[si])) { pi++; si++; continue }
            } else if (si < s.length && p[pi] == s[si]) {
                pi++; si++; continue
            }
            if (starP < 0 || starS >= s.length) return false
            starS++
            si = starS
            pi = starP + 1
        }
    }

    private fun isSeparator(c: Char): Boolean =
        !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

    private fun parseRule(line: String): UrlRule? {
        if (line.isEmpty() || line.startsWith("!")) return null
        var body = line
        val allow = body.startsWith("@@")
        if (allow) body = body.substring(2)
        var opts = ""
        val dollar = body.lastIndexOf('$')
        if (dollar > 0 && OPTS_RE.matches(body.substring(dollar + 1))) {
            opts = body.substring(dollar + 1)
            body = body.substring(0, dollar)
        }
        var thirdParty: Boolean? = null
        var types: MutableSet<RequestType>? = null
        val excludedTypes = HashSet<RequestType>()
        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
        var important = false
        var matchCase = false
        if (opts.isNotEmpty()) {
            for (raw in opts.split(',')) {
                val o = raw.trim()
                if (o.isEmpty()) continue // "$script," — same as the build tool
                val neg = o.startsWith("~")
                val name = o.removePrefix("~").substringBefore('=').lowercase(Locale.US)
                when (name) {
                    "third-party", "3p" -> thirdParty = !neg
                    "first-party", "1p" -> thirdParty = neg
                    "important" -> important = true
                    "match-case" -> matchCase = true
                    "all" -> types = null
                    "domain", "from" -> o.substringAfter('=').split('|').forEach { d ->
                        val dd = d.trim().lowercase(Locale.US)
                        if (dd.startsWith("~")) exclude.add(dd.substring(1)) else if (dd.isNotEmpty()) include.add(dd)
                    }
                    else -> {
                        val t = TYPE_NAMES[name] ?: return null
                        if (neg) excludedTypes.add(t) else (types ?: HashSet<RequestType>().also { types = it }).add(t)
                    }
                }
            }
        }
        // ABP: a pattern that starts and ends with "/" is a regular expression. Not supported (too slow
        // per request on a phone); the build tool drops them as well.
        if (body.length > 2 && body.startsWith("/") && body.endsWith("/")) return null
        if (body.contains('$')) return null // unparsed options such as $replace=/…/
        var hostAnchor = false
        var startAnchor = false
        var endAnchor = false
        if (body.startsWith("||")) { hostAnchor = true; body = body.substring(2) }
        else if (body.startsWith("|")) { startAnchor = true; body = body.substring(1) }
        if (body.endsWith("|")) { endAnchor = true; body = body.dropLast(1) }
        if (body.isEmpty() || body == "*") return null
        val pattern = if (matchCase) body else body.lowercase(Locale.US)
        return UrlRule(allow, pattern, hostAnchor, startAnchor, endAnchor, thirdParty, types, excludedTypes,
            include, exclude, important, matchCase)
    }

    /** Longest [a-z0-9%] run of >= 3 chars that can't be extended by a neighbouring wildcard. */
    private fun bestToken(r: UrlRule): String? {
        val p = r.pattern.lowercase(Locale.US)
        var best: String? = null
        var i = 0
        while (i < p.length) {
            if (!isTokenChar(p[i])) { i++; continue }
            val start = i
            while (i < p.length && isTokenChar(p[i])) i++
            val before = if (start > 0) p[start - 1] else if (r.hostAnchor || r.startAnchor) '|' else '*'
            val after = if (i < p.length) p[i] else if (r.endAnchor) '|' else '*'
            if (before != '*' && after != '*' && i - start >= 3) {
                val tok = p.substring(start, i)
                if (best == null || tok.length > best.length) best = tok
            }
        }
        return best
    }

    private fun tokenize(s: String): Set<String> {
        val out = HashSet<String>(16)
        var i = 0
        while (i < s.length) {
            if (!isTokenChar(s[i])) { i++; continue }
            val start = i
            while (i < s.length && isTokenChar(s[i])) i++
            if (i - start >= 3) out.add(s.substring(start, i))
        }
        return out
    }

    private fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

    companion object {
        private val OPTS_RE = Regex("""[~a-zA-Z0-9_=|.,\-*]*""")
        private val TYPE_NAMES = mapOf(
            "script" to RequestType.SCRIPT, "image" to RequestType.IMAGE, "stylesheet" to RequestType.STYLESHEET,
            "css" to RequestType.STYLESHEET, "xmlhttprequest" to RequestType.XHR, "xhr" to RequestType.XHR,
            "subdocument" to RequestType.SUBDOCUMENT, "frame" to RequestType.SUBDOCUMENT, "media" to RequestType.MEDIA,
            "font" to RequestType.FONT, "object" to RequestType.OBJECT, "object-subrequest" to RequestType.OBJECT,
            "ping" to RequestType.PING, "beacon" to RequestType.PING, "websocket" to RequestType.WEBSOCKET,
            "other" to RequestType.OTHER
        )

        fun fnv1a64(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (ch in s) {
                h = h xor (ch.code.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }

        /** Reads a hosts.bin file: uint32 count + sorted uint64 hashes (compared as signed longs). */
        fun readHashes(input: InputStream): LongArray = DataInputStream(input.buffered()).use { din ->
            val n = din.readInt()
            val arr = LongArray(n) { din.readLong() }
            arr.sort() // the file is sorted unsigned; binarySearch needs signed order
            arr
        }

        /** Index just after "scheme://" and any "user:pass@" in [url]. */
        fun hostStartIndex(url: String): Int {
            val schemeEnd = url.indexOf("://")
            val start = if (schemeEnd < 0) 0 else schemeEnd + 3
            var end = start
            while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
            val at = url.lastIndexOf('@', end - 1)
            return if (at >= start) at + 1 else start
        }

        /** Lower-cased host of [url] (userinfo and port removed; IPv6 brackets kept off). */
        fun hostOf(url: String): String? {
            if (url.indexOf("://") < 0) return null
            val start = hostStartIndex(url)
            if (start < url.length && url[start] == '[') {
                val close = url.indexOf(']', start)
                return if (close > start) url.substring(start + 1, close).lowercase(Locale.US).ifEmpty { null } else null
            }
            var end = start
            while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#' && url[end] != ':') end++
            return url.substring(start, end).lowercase(Locale.US).trimEnd('.').ifEmpty { null }
        }

        fun domainMatches(host: String, domain: String): Boolean {
            if (domain.endsWith(".*")) {
                val base = domain.dropLast(2)
                return host == base || host.startsWith("$base.") || host.contains(".$base.")
            }
            return host == domain || host.endsWith(".$domain")
        }

        /** Same site: same registrable domain (Public Suffix List). */
        fun sameSite(a: String, b: String): Boolean = a == b || siteOf(a) == siteOf(b)

        /** Registrable domain ("eTLD+1") of [host], e.g. a.b.example.co.uk -> example.co.uk. */
        fun siteOf(host: String): String = PublicSuffix.registrableDomain(host)

        /** Best-effort resource type from the URL extension and request headers. */
        fun inferType(url: String, accept: String?, isMainFrame: Boolean, requestedWith: String? = null): RequestType {
            if (isMainFrame) return RequestType.DOCUMENT
            val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
            val a = accept?.lowercase(Locale.US).orEmpty()
            return when {
                path.endsWith(".js") || path.endsWith(".mjs") -> RequestType.SCRIPT
                path.endsWith(".css") || a.startsWith("text/css") -> RequestType.STYLESHEET
                IMAGE_EXT.any { path.endsWith(it) } || a.startsWith("image/") -> RequestType.IMAGE
                MEDIA_EXT.any { path.endsWith(it) } -> RequestType.MEDIA
                FONT_EXT.any { path.endsWith(it) } -> RequestType.FONT
                a.startsWith("text/html") -> RequestType.SUBDOCUMENT
                requestedWith.equals("XMLHttpRequest", ignoreCase = true) || a.startsWith("application/json") -> RequestType.XHR
                else -> RequestType.OTHER
            }
        }

        private val IMAGE_EXT = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif", ".bmp")
        private val MEDIA_EXT = listOf(".mp4", ".webm", ".m3u8", ".mp3", ".ogg", ".m4a", ".ts", ".mpd")
        private val FONT_EXT = listOf(".woff", ".woff2", ".ttf", ".otf", ".eot")
    }
}

/**
 * Public Suffix List lookup ("which part of a host is the registrable site?").
 * Loaded from assets/adblock/psl.txt (compiled by tools/build_adblock.py); until loaded, a small
 * built-in list of common multi-part suffixes is used.
 */
object PublicSuffix {
    @Volatile private var rules: Set<String> = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp", "or.jp",
        "com.br", "com.mx", "com.ar", "co.in", "co.za", "com.tr", "com.cn", "com.hk", "com.sg", "co.kr", "github.io"
    )
    @Volatile private var wildcards: Set<String> = emptySet()
    @Volatile private var exceptions: Set<String> = emptySet()
    @Volatile var loaded = false
        private set

    /** Loads assets/adblock/psl.txt once (about 10 ms); safe to call from any thread. */
    fun ensureLoaded(ctx: android.content.Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { ctx.applicationContext.assets.open("adblock/psl.txt").bufferedReader().useLines { load(it) } }
            loaded = true
        }
    }

    /** PSL rule lines ("com", "co.uk", "*.ck", "!www.ck"); comments and blanks ignored. */
    fun load(lines: Sequence<String>) {
        val r = HashSet<String>(16_384)
        val w = HashSet<String>()
        val e = HashSet<String>()
        for (raw in lines) {
            val l = raw.trim().lowercase(Locale.US)
            if (l.isEmpty() || l.startsWith("//")) continue
            when {
                l.startsWith("!") -> e.add(l.substring(1))
                l.startsWith("*.") -> w.add(l.substring(2))
                else -> r.add(l)
            }
        }
        rules = r; wildcards = w; exceptions = e
        loaded = true
    }

    /** Length (in labels) of the public suffix of [labels]. */
    private fun suffixLabels(labels: List<String>): Int {
        var best = 1 // "*" default rule: the last label is a public suffix
        for (i in labels.indices) {
            val cand = labels.subList(i, labels.size).joinToString(".")
            val n = labels.size - i
            if (cand in exceptions) return n - 1
            if (cand in rules && n > best) best = n
            if (i + 1 < labels.size) {
                val parent = labels.subList(i + 1, labels.size).joinToString(".")
                if (parent in wildcards && n > best) best = n
            }
        }
        return best
    }

    fun registrableDomain(host: String): String {
        val h = host.lowercase(Locale.US).trimEnd('.')
        if (h.isEmpty() || h.all { it.isDigit() || it == '.' } || h.contains(':')) return h // IPs
        val labels = h.split('.')
        val n = suffixLabels(labels)
        return if (labels.size <= n) h else labels.takeLast(n + 1).joinToString(".")
    }
}
