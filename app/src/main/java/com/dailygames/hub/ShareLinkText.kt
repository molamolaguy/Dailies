package com.dailygames.hub

import java.net.URI
import java.net.URLDecoder
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
        Regex("""^(twitter\.com|x\.com|mobile\.twitter\.com)/(intent/(tweet|post)|share|compose/(tweet|post))""") to listOf("text", "url"),
        Regex("""^(wa\.me|api\.whatsapp\.com|web\.whatsapp\.com)/""") to listOf("text"),
        Regex("""^t\.me/share""") to listOf("text", "url"),
        Regex("""^(facebook\.com|m\.facebook\.com)/(sharer|share|dialog/share)""") to listOf("quote", "text"),
        Regex("""^(reddit\.com|old\.reddit\.com)/submit""") to listOf("title", "text"),
        Regex("""^bsky\.app/intent/compose""") to listOf("text"),
        Regex("""^(threads\.net|threads\.com)/intent/post""") to listOf("text"),
        Regex("""^(mastodonshare\.com|toot\.kytta\.dev)/""") to listOf("text")
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
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return null
        val where = host + (uri.rawPath ?: "")
        val keys = WEB_SHARES.firstOrNull { (re, _) -> re.containsMatchIn(where) }?.second ?: return null
        val q = uri.rawQuery ?: return null
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
        query.split('&').forEach { kv ->
            if (kv.substringBefore('=').equals(key, ignoreCase = true) && kv.contains('=')) {
                return decode(kv.substringAfter('='), plusIsSpace).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** Percent-decoding; "+" is a space only where [plusIsSpace] (form-style query strings). */
    private fun decode(s: String, plusIsSpace: Boolean = true): String {
        val input = if (plusIsSpace) s else s.replace("+", "%2B")
        return runCatching { URLDecoder.decode(input, "UTF-8") }.getOrDefault(s)
    }
}
