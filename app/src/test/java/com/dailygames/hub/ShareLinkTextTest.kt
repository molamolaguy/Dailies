package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.8.3: results shared through a link (X, WhatsApp, text message, email, intent links). */
class ShareLinkTextTest {
    private val grid = "Wordle 1,234 3/6\n\n⬛🟨⬛⬛⬛\n🟩🟩🟩🟩🟩"
    private val enc = java.net.URLEncoder.encode(grid, "UTF-8") // spaces as +

    @Test
    fun webShareLinks() {
        assertEquals(grid, ShareLinkText.extract("https://twitter.com/intent/tweet?text=$enc"))
        assertEquals("$grid\nhttps://wordle.example/", ShareLinkText.extract("https://x.com/intent/post?text=$enc&url=https%3A%2F%2Fwordle.example%2F"))
        assertEquals(grid, ShareLinkText.extract("https://wa.me/?text=$enc"))
        assertEquals(grid, ShareLinkText.extract("https://api.whatsapp.com/send?text=$enc"))
        assertEquals(grid, ShareLinkText.extract("https://t.me/share/url?url=&text=$enc"))
        assertEquals(grid, ShareLinkText.extract("https://www.facebook.com/sharer/sharer.php?u=https%3A%2F%2Fg.example&quote=$enc"))
        assertEquals(grid, ShareLinkText.extract("https://bsky.app/intent/compose?text=$enc"))
        assertEquals(grid, ShareLinkText.extract("https://www.reddit.com/submit?title=$enc&url=x"))
    }

    @Test
    fun appAndMessageLinks() {
        val pct = enc.replace("+", "%20")
        assertEquals(grid, ShareLinkText.extract("sms:?body=$pct"))
        assertEquals(grid, ShareLinkText.extract("sms:+15551234567?body=$pct"))
        assertEquals(grid, ShareLinkText.extract("mailto:?subject=Wordle&body=$pct"))
        assertEquals(grid, ShareLinkText.extract("whatsapp://send?text=$enc"))
        assertEquals(grid, ShareLinkText.extract("intent://send?text=$enc#Intent;scheme=whatsapp;package=com.whatsapp;end"))
        assertEquals("Nerdle 3/6 +1", ShareLinkText.extract("intent:#Intent;action=android.intent.action.SEND;type=text/plain;S.android.intent.extra.TEXT=Nerdle%203%2F6%20+1;end"))
        // A literal plus in a text message stays a plus.
        assertEquals("Score +12", ShareLinkText.extract("sms:?body=Score%20+12"))
    }

    @Test
    fun notShareLinks() {
        assertNull(ShareLinkText.extract("https://www.nytimes.com/games/wordle?text=hello"))
        assertNull(ShareLinkText.extract("https://twitter.com/home"))
        assertNull(ShareLinkText.extract("https://x.com/intent/post"))
        assertNull(ShareLinkText.extract("tel:+15551234567"))
        assertNull(ShareLinkText.extract("market://details?id=com.example"))
        assertNull(ShareLinkText.extract(null))
        assertNull(ShareLinkText.extract("sms:?body="))
    }
}
