package com.dailygames.hub

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale

/**
 * The text a game shares through a link instead of the clipboard or share sheet (1.8.3): "Post on X",
 * WhatsApp, Telegram, Facebook, Reddit, Bluesky, Threads, text message, email, or an Android intent
 * link. The result is in the link itself (?text=, ?body=, ?quote=, …), so it can be read without
 * opening anything. Pure, so it is unit tested; share-hook.js has the same rules for links a page opens.
 */
object ShareLinkText {
    private val WEB_SHARES: List<Pair<Regex, List<String>>> = listOf(
        // host (no www.) + path -> the query parameters that hold the text, in order of preference
        Regex("""^(twitter\.com|x\.com|mobile\.twitter\.com)/(intent/(tweet|post)|share|compose/(tweet|post))""", RegexOption.IGNORE_CASE) to listOf("text", "url"),
        Regex("""^(wa\.me|api\.whatsapp\.com|web\.whatsapp\.com)/""", RegexOption.IGNORE_CASE) to listOf("text"),
        Regex("""^t\.me/share""", RegexOption.IGNORE_CASE) to listOf("text", "url"),
        Regex("""^(facebook\.com|m\.facebook\.com)/(sharer|share|dialog/share)""", RegexOption.IGNORE_CASE) to listOf("quote", "text"),
        Regex("""^(reddit\.com|old\.reddit\.com)/submit""", RegexOption.IGNORE_CASE) to listOf("title", "text"),
        Regex("""^bsky\.app/intent/compose""", RegexOption.IGNORE_CASE) to listOf("text"),
        Regex("""^(threads\.net|threads\.com)/intent/post""", RegexOption.IGNORE_CASE) to listOf("text"),
        Regex("""^(mastodonshare\.com|toot\.kytta\.dev)/""", RegexOption.IGNORE_CASE) to listOf("text")
    )

    private val WEB_URL_PARTS = Regex(
        """^https?://(?:[^/?#@\\]*@)?([^/?#:\\]+)(?::[0-9]+)?([^?#]*)(?:\?(.*))?$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** The shared text in [link], or null when it isn't a share link (or carries no text). */
    fun extract(link: String?): String? {
        val raw = link?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 20_000) return null
        val scheme = raw.substringBefore(':', "").lowercase(Locale.US)
        val text = when (scheme) {
            "http", "https" -> fromWeb(raw)
            // In sms: / mailto: links a "+" is a plus sign; in web and app share links it's a space.
            "sms", "smsto", "mms", "mmsto" -> param(query(raw), "body", plusIsSpace = false)
            "mailto" -> param(query(raw), "body", plusIsSpace = false)
            "whatsapp" -> param(query(raw), "text")
            "tg" -> param(query(raw), "text")
            "intent" -> fromIntent(raw)
            else -> null
        }
        return text?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun fromWeb(link: String): String? {
        var host: String? = null
        var path = ""
        var q: String? = null
        val uri = runCatching { URI(link) }.getOrNull()
        if (uri != null && !uri.host.isNullOrBlank()) {
            host = uri.host.lowercase(Locale.US).removePrefix("www.")
            path = uri.rawPath ?: ""
            q = uri.rawQuery?.let { rq ->
                if (uri.rawFragment != null) "$rq#${uri.rawFragment}" else rq
            }
        } else {
            val m = WEB_URL_PARTS.find(link) ?: return null
            host = m.groupValues[1].lowercase(Locale.US).removePrefix("www.")
            path = m.groupValues[2]
            q = m.groupValues[3].ifEmpty { null }
        }
        if (host.isNullOrBlank() || q.isNullOrEmpty()) return null
        val where = host + path
        val keys = WEB_SHARES.firstOrNull { (re, _) -> re.containsMatchIn(where) }?.second ?: return null
        // X shares often put the link in its own parameter: text first, then the link.
        val parts = keys.mapNotNull { param(q, it) }.distinct()
        return parts.joinToString("\n").ifEmpty { null }
    }

    /** intent://…#Intent;…;S.android.intent.extra.TEXT=…;end */
    private fun fromIntent(link: String): String? {
        val frag = link.substringAfter("#Intent;", "")
        if (frag.isEmpty()) return null
        frag.split(';').forEach { kv ->
            val key = kv.substringBefore('=')
            if (key == "S.android.intent.extra.TEXT") return decode(kv.substringAfter('='), plusIsSpace = false)
        }
        // A share link wrapped in an intent: intent://send?text=…#Intent;scheme=whatsapp;…
        return param(query(link.substringBefore('#')), "text")
    }

    private fun query(link: String): String = link.substringAfter('?', "").substringBefore('#')

    private fun param(query: String, key: String, plusIsSpace: Boolean = true): String? {
        if (query.isEmpty()) return null
        for (kv in query.split('&')) {
            val eq = kv.indexOf('=')
            if (eq > 0 && kv.substring(0, eq).equals(key, ignoreCase = true)) {
                val decoded = decode(kv.substring(eq + 1), plusIsSpace)
                if (decoded.isNotBlank()) return decoded
            }
        }
        return null
    }

    /** Percent-decoding that tolerates stray '%' without failing valid '%XX' / '+' decoding. */
    private fun decode(s: String, plusIsSpace: Boolean = true): String {
        val out = ByteArrayOutputStream(s.length)
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.write(plain.toString().toByteArray(Charsets.UTF_8))
                plain.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    flushPlain()
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            plain.append(if (c == '+' && plusIsSpace) ' ' else c)
            i++
        }
        flushPlain()
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

