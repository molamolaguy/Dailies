package com.dailygames.hub

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Game lists shared between friends, as a `dailies://add?v=1&d=…` link (also shown as a QR code).
 * The payload is the list's name, link, reset time and zone per game, deflated and base64url-encoded,
 * so a list of 20 games fits a QR code a phone camera reads easily. Nothing goes through a server.
 */
object GameListCodec {
    const val SCHEME = "dailies"
    private const val PREFIX = "dailies://add?v=1&d="
    private const val MAX_GAMES = 100
    private const val MAX_JSON = 64 * 1024
    private val LINK_RE = Regex("""dailies://add\?v=1&d=([A-Za-z0-9_-]{8,40000})""")

    /** [gamesJson]: [{name,url,resetTime,resetZone}] → link, or null if nothing usable. */
    fun encode(gamesJson: String): String? {
        val src = runCatching { JSONArray(gamesJson) }.getOrNull() ?: return null
        val out = JSONArray()
        for (i in 0 until minOf(src.length(), MAX_GAMES)) {
            val g = src.optJSONObject(i) ?: continue
            val url = g.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            out.put(JSONArray().put(g.optString("name").take(80)).put(url.take(500))
                .put(g.optString("resetTime", "00:00")).put(g.optString("resetZone", "local")))
        }
        if (out.length() == 0) return null
        val raw = out.toString().toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(raw.size + 64)
        val n = deflater.deflate(buf)
        deflater.end()
        return PREFIX + b64UrlEncode(buf.copyOf(n))
    }

    /** Finds a list link in [text] (a link, or a message containing one) → [{name,url,resetTime,resetZone}] JSON. */
    fun decode(text: String?): String? {
        val m = LINK_RE.find(text ?: return null) ?: return null
        val bytes = b64UrlDecode(m.groupValues[1]) ?: return null
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val k = inflater.inflate(buf)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, k)
                if (out.size() > MAX_JSON) return null
            }
        } catch (e: Exception) {
            return null
        } finally {
            inflater.end()
        }
        val arr = runCatching { JSONArray(out.toString(Charsets.UTF_8.name())) }.getOrNull() ?: return null
        val games = JSONArray()
        for (i in 0 until minOf(arr.length(), MAX_GAMES)) {
            val row = arr.optJSONArray(i) ?: continue
            val url = row.optString(1)
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            games.put(JSONObject().put("name", row.optString(0).take(80)).put("url", url.take(500))
                .put("resetTime", row.optString(2, "00:00")).put("resetZone", row.optString(3, "local")))
        }
        return if (games.length() > 0) games.toString() else null
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Unpadded base64url (java.util.Base64 needs Android 8, and android.util.Base64 isn't in unit tests). */
    fun b64UrlEncode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(B64[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(B64[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    fun b64UrlDecode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val out = ByteArrayOutputStream(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in text) {
            val v = B64.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    /** A QR code for [text] as a PNG data: URL (black on white, with a quiet zone). */
    fun qrDataUrl(text: String, sizePx: Int = 720): String? = runCatching {
        val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 2)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val bmp = android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }.getOrNull()
}
