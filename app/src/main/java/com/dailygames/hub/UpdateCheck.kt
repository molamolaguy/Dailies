package com.dailygames.hub

import org.json.JSONObject

/**
 * Opt-in check for a newer release on GitHub (the app isn't in an app store, so nothing else would
 * tell the user). One anonymous request to GitHub's public API; nothing about the user is sent.
 */
object UpdateCheck {
    const val RELEASES_API = "https://api.github.com/repos/molamolaguy/Dailies/releases/latest"
    const val RELEASES_PAGE = "https://github.com/molamolaguy/Dailies/releases/latest"
    /** Only APKs attached to this repository's releases are ever downloaded. */
    const val ASSET_PREFIX = "https://github.com/molamolaguy/Dailies/releases/download/"

    /** "v1.2.0" / "1.2" -> [1, 2, 0]; null if it isn't a plain version. */
    fun parse(version: String?): List<Int>? {
        val v = version?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('-')?.substringBefore('+') ?: return null
        if (v.isEmpty()) return null
        val parts = v.split('.')
        if (parts.size > 4 || parts.any { it.isEmpty() || it.length > 6 || !it.all(Char::isDigit) }) return null
        return parts.map { it.toInt() }
    }

    fun isNewer(latest: String?, current: String?): Boolean {
        val a = parse(latest) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Result JSON for the hub: {ok, latest, current, newer, canInstall}. Drafts and pre-releases never count. */
    fun resultJson(body: String?, code: Int, current: String): String {
        val out = JSONObject().put("current", current)
        val release = if (code in 200..299) runCatching { JSONObject(body ?: "") }.getOrNull() else null
        if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease")) {
            return out.put("ok", false).put("newer", false).toString()
        }
        val tag = release.optString("tag_name")
        return out.put("ok", parse(tag) != null).put("latest", tag.removePrefix("v")).put("newer", isNewer(tag, current))
            .put("canInstall", apkAsset(release) != null).toString()
    }

    /** The release's APK: (download URL, size in bytes), or null when it has none from this repository. */
    fun apkAsset(release: JSONObject): Pair<String, Long>? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url")
            if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
            if (!url.startsWith(ASSET_PREFIX) || url.contains("..")) continue
            return url to a.optLong("size", -1L)
        }
        return null
    }

    /** The latest release's JSON (fetched fresh, so nothing the page says decides what is downloaded). */
    fun latestRelease(): JSONObject? {
        val r = SafeHttp.get(
            RELEASES_API,
            headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Dailies-Android"),
            maxBytes = 256 * 1024
        ) ?: return null
        if (r.code !in 200..299) return null
        val release = runCatching { JSONObject(r.body.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        return release
    }

    fun check(current: String): String {
        val r = SafeHttp.get(
            RELEASES_API,
            headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Dailies-Android"),
            maxBytes = 256 * 1024
        )
        return resultJson(r?.body?.toString(Charsets.UTF_8), r?.code ?: 0, current)
    }
}
