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
}
