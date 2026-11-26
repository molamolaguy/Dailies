package com.dailygames.hub

import com.dailygames.hub.GamePlayerPolicy.PopupAction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GamePlayerPolicyTest {
    private val b64: (String) -> ByteArray = { Base64.getMimeDecoder().decode(it) }

    @Test
    fun popupsStayInAppOnlyForTheGameSiteAndSignInPages() {
        PublicSuffix.load(sequenceOf("com", "uk", "co.uk", "github.io", "app"))
        assertEquals(PopupAction.DIALOG, GamePlayerPolicy.popupAction("help.nytimes.com", "/x", "www.nytimes.com", false))
        assertEquals(PopupAction.DIALOG, GamePlayerPolicy.popupAction("accounts.google.com", "/o/oauth2/v2/auth", "game.com", true))
        assertEquals(PopupAction.DIALOG, GamePlayerPolicy.popupAction("www.facebook.com", "/v19.0/dialog/oauth", "game.com", true))
        assertEquals(PopupAction.DIALOG, GamePlayerPolicy.popupAction("mygame.firebaseapp.com", "/__/auth/handler", "game.com", true))
        assertEquals(PopupAction.DIALOG, GamePlayerPolicy.popupAction("github.com", "/login/oauth/authorize", "game.com", true))
        assertEquals(PopupAction.EXTERNAL, GamePlayerPolicy.popupAction("x.com", "/intent/tweet", "game.com", true))
        assertEquals(PopupAction.EXTERNAL, GamePlayerPolicy.popupAction("ads.example.net", "/click", "game.com", true))
        assertEquals(PopupAction.BLOCK, GamePlayerPolicy.popupAction("ads.example.net", "/popunder", "game.com", false))
        assertEquals(PopupAction.BLOCK, GamePlayerPolicy.popupAction(null, null, "game.com", true))
        // Another user's github.io site is a different site.
        assertEquals(PopupAction.EXTERNAL, GamePlayerPolicy.popupAction("evil.github.io", "/", "me.github.io", true))
        assertFalse(GamePlayerPolicy.isAuthPage("www.authority-news.com", "/authors/x"))
    }

    @Test
    fun originsOfPagesAndBlobs() {
        assertEquals("https://game.com", GamePlayerPolicy.originOf("https://game.com"))
        assertEquals("https://game.com:8443", GamePlayerPolicy.originOf("HTTPS://Game.com:8443/"))
        assertNull(GamePlayerPolicy.originOf("file:///x"))
        assertEquals("https://game.com", GamePlayerPolicy.blobOrigin("blob:https://game.com/5f1c-uuid"))
        assertEquals("https://cdn.game.net", GamePlayerPolicy.blobOrigin("blob:https://cdn.game.net/abc"))
        assertNull(GamePlayerPolicy.blobOrigin("blob:null/abc"))
        assertNull(GamePlayerPolicy.blobOrigin("https://game.com/x"))
    }

    @Test
    fun contentDispositionFileNames() {
        assertEquals("my file.png", GamePlayerPolicy.filenameFromContentDisposition("attachment; filename*=UTF-8''my%20file.png"))
        assertEquals("résumé.txt", GamePlayerPolicy.filenameFromContentDisposition("attachment; filename=\"fallback.txt\"; filename*=utf-8''r%C3%A9sum%C3%A9.txt"))
        assertEquals("plain name.pdf", GamePlayerPolicy.filenameFromContentDisposition("attachment; filename=\"plain name.pdf\""))
        assertEquals("score.csv", GamePlayerPolicy.filenameFromContentDisposition("inline; filename=score.csv"))
        assertEquals("evil.sh", GamePlayerPolicy.filenameFromContentDisposition("attachment; filename=\"..\\\\..\\\\evil.sh\""))
        assertEquals("passwd", GamePlayerPolicy.filenameFromContentDisposition("attachment; filename=\"../../etc/passwd\""))
        assertNull(GamePlayerPolicy.filenameFromContentDisposition("attachment; filename=\"..\""))
        assertNull(GamePlayerPolicy.filenameFromContentDisposition("attachment"))
        assertNull(GamePlayerPolicy.filenameFromContentDisposition(null))
    }

    @Test
    fun dataUrlsDecodeToExactBytes() {
        val bin = GamePlayerPolicy.decodeDataUrl("data:application/octet-stream,%FF%00%80abc", b64)!!
        assertEquals("application/octet-stream", bin.mime)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0, 0x80.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()), bin.bytes)

        val text = GamePlayerPolicy.decodeDataUrl("data:text/plain;charset=utf-8,Wordle%201%2C234%20%F0%9F%9F%A9 é", b64)!!
        assertEquals("text/plain", text.mime)
        assertEquals("Wordle 1,234 🟩 é", String(text.bytes, Charsets.UTF_8))

        val png = GamePlayerPolicy.decodeDataUrl("data:image/png;base64,iVBO%2Bw0K\nGgo=", b64)!!
        assertEquals("image/png", png.mime)
        assertArrayEquals(Base64.getDecoder().decode("iVBO+w0KGgo="), png.bytes)

        val plainB64 = GamePlayerPolicy.decodeDataUrl("data:image/png;base64,iVBO+w0K\nGgo=", b64)!!
        assertEquals("image/png", plainB64.mime)
        assertArrayEquals(Base64.getDecoder().decode("iVBO+w0KGgo="), plainB64.bytes)

        val noMime = GamePlayerPolicy.decodeDataUrl("data:,hi", b64)!!
        assertEquals("", noMime.mime)
        assertEquals("hi", String(noMime.bytes))

        assertNull(GamePlayerPolicy.decodeDataUrl("data:text/plain", b64))
        assertNull(GamePlayerPolicy.decodeDataUrl("https://x/y", b64))
        // A lone or broken escape is kept as text.
        assertEquals("100%", String(GamePlayerPolicy.decodeDataUrl("data:,100%", b64)!!.bytes))
        assertEquals("%zz", String(GamePlayerPolicy.decodeDataUrl("data:,%zz", b64)!!.bytes))
        assertTrue(GamePlayerPolicy.decodeDataUrl("data:,", b64)!!.bytes.isEmpty())
    }

    @Test
    fun intentLaunchPolicyRejectsUnsafeSchemesAndSelfPackage() {
        val self = "com.dailygames.hub"
        assertTrue(GamePlayerPolicy.isSafeIntentLaunch("https", "com.android.chrome", self))
        assertTrue(GamePlayerPolicy.isSafeIntentLaunch("HTTPS", null, self))
        assertTrue(GamePlayerPolicy.isSafeIntentLaunch("geo", "com.google.android.apps.maps", self))
        assertFalse(GamePlayerPolicy.isSafeIntentLaunch(null, null, self))
        assertFalse(GamePlayerPolicy.isSafeIntentLaunch("", null, self))
        for (bad in listOf("file", "content", "javascript", "data", "blob", "dailies", "intent", "FILE")) {
            assertFalse("scheme $bad must be rejected", GamePlayerPolicy.isSafeIntentLaunch(bad, "com.other.app", self))
        }
        assertFalse(GamePlayerPolicy.isSafeIntentLaunch("https", self, self))
    }

    @Test
    fun mergeEarlierResultCapsLengthCapsShareAndUpdatesOpenedAtOnNewerShare() {
        var arr = org.json.JSONArray()
        // First visit to game A on Day 1 without share.
        arr = GamePlayerPolicy.mergeEarlierResult(arr, "a", 10_000L, 1_000L, null, confirmed = false)
        assertEquals(1, arr.length())
        assertEquals(1_000L, arr.getJSONObject(0).getLong("openedAt"))
        assertEquals("", arr.getJSONObject(0).getString("share"))

        // Second visit to game A on Day 1 with share updates elapsedMs and share while preserving openedAt=1000.
        arr = GamePlayerPolicy.mergeEarlierResult(arr, "a", 5_000L, 1_500L, "Game A #1 3/6", confirmed = false)
        assertEquals(15_000L, arr.getJSONObject(0).getLong("elapsedMs"))
        assertEquals(1_500L, arr.getJSONObject(0).getLong("openedAt"))
        assertEquals("Game A #1 3/6", arr.getJSONObject(0).getString("share"))

        // Revisit game A on Day 2 after reset: newer non-empty share stamps Day 2's openedAt, not Day 1's!
        arr = GamePlayerPolicy.mergeEarlierResult(arr, "a", 7_000L, 90_000L, "Game A #2 2/6", confirmed = true)
        assertEquals(22_000L, arr.getJSONObject(0).getLong("elapsedMs"))
        assertEquals(90_000L, arr.getJSONObject(0).getLong("openedAt"))
        assertEquals("Game A #2 2/6", arr.getJSONObject(0).getString("share"))
        assertTrue(arr.getJSONObject(0).getBoolean("confirmed"))

        // Returning without a new share preserves existing share and does not regress openedAt.
        arr = GamePlayerPolicy.mergeEarlierResult(arr, "a", 2_000L, 500L, null, confirmed = false)
        assertEquals(24_000L, arr.getJSONObject(0).getLong("elapsedMs"))
        assertEquals(500L, arr.getJSONObject(0).getLong("openedAt"))
        assertEquals("Game A #2 2/6", arr.getJSONObject(0).getString("share"))

        // Individual share is capped to 4000 chars and array length is capped to maxEntries.
        val oversized = "x".repeat(5000)
        arr = GamePlayerPolicy.mergeEarlierResult(arr, "b", 1_000L, 2_000L, oversized, confirmed = false)
        assertEquals(GamePlayerPolicy.MAX_EARLIER_SHARE_CHARS, arr.getJSONObject(1).getString("share").length)

        var many = org.json.JSONArray()
        for (i in 1..205) {
            many = GamePlayerPolicy.mergeEarlierResult(many, "g$i", 100L, i.toLong(), null, maxEntries = 200)
        }
        assertEquals(200, many.length())
        assertEquals("g6", many.getJSONObject(0).getString("id"))
        assertEquals("g205", many.getJSONObject(199).getString("id"))
    }

    @Test
    fun serviceWorkerFirstPartyHostPrefersOriginThenRefererThenFallback() {
        assertEquals(
            "cdn.example.com",
            GamePlayerPolicy.serviceWorkerFirstPartyHost(
                mapOf("origin" to "https://cdn.example.com", "referer" to "https://game.com/play"),
                "fallback.com"
            )
        )
        assertEquals(
            "game.com",
            GamePlayerPolicy.serviceWorkerFirstPartyHost(
                mapOf("Referer" to "https://game.com/play?day=1"),
                "fallback.com"
            )
        )
        assertEquals(
            "fallback.com",
            GamePlayerPolicy.serviceWorkerFirstPartyHost(
                mapOf("Origin" to "null", "Referer" to "about:blank"),
                "fallback.com"
            )
        )
        assertEquals("fallback.com", GamePlayerPolicy.serviceWorkerFirstPartyHost(null, "fallback.com"))
    }

    @Test
    fun sanitizeDownloadFilenameRemovesInvalidFilesystemCharacters() {
        assertEquals("a_b_c_d_e_f_g_h_i_.png", GamePlayerPolicy.sanitizeDownloadFilename("a\\b/c:d*e?f\"g<h>i|.png"))
        assertEquals("download", GamePlayerPolicy.sanitizeDownloadFilename(".."))
        assertEquals("download", GamePlayerPolicy.sanitizeDownloadFilename("   "))
        assertEquals("wordle-score.txt", GamePlayerPolicy.sanitizeDownloadFilename("wordle-score.txt"))
    }
}
