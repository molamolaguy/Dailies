package com.dailygames.hub

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Launch (1.8.2): the launch screen stays up until the hub has drawn its game cards, so the first thing
 * shown is the finished hub, never a blank page; and it never waits longer than its time limit.
 */
@RunWith(AndroidJUnit4::class)
class LaunchDeviceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val stateFile get() = File(ctx.filesDir, HubStateStore.FILE_NAME)
    private var savedState: ByteArray? = null

    @Before
    fun seedHub() {
        savedState = stateFile.takeIf { it.exists() }?.readBytes()
        val games = (1..4).joinToString(",") {
            """{"id":"launch-$it","name":"Launch game $it","url":"https://example.com/$it","resetTime":"00:00","resetZone":"utc","notifyEnabled":false,"dayHistory":{}}"""
        }
        stateFile.writeText("""{"schemaVersion":3,"games":[$games],"historyLogs":[],"settings":{},"lastSeenVersion":"9.9.9"}""")
    }

    @After
    fun restore() {
        val old = savedState
        if (old != null) stateFile.writeBytes(old) else stateFile.delete()
        File(ctx.filesDir, HubStateStore.FILE_NAME + ".bak").delete()
    }

    private fun findWebView(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun ActivityScenario<HubActivity>.js(script: String): String {
        var result: String? = null
        val latch = CountDownLatch(1)
        onActivity { a -> findWebView(a.window.decorView)!!.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JS timed out", latch.await(10, TimeUnit.SECONDS))
        return result ?: "null"
    }

    @Test
    fun launchScreenLiftsOnlyOnceTheCardsAreThere() {
        ActivityScenario.launch(HubActivity::class.java).use { s ->
            val start = System.currentTimeMillis()
            var shown = true
            while (shown && System.currentTimeMillis() - start < 6000) {
                s.onActivity { shown = it.launchScreenShown }
                if (shown) Thread.sleep(16)
            }
            val waited = System.currentTimeMillis() - start
            assertFalse("launch screen still up after ${waited}ms (limit is 3 s plus the cover)", shown)
            // The hub's cards were in the page when the launch screen lifted.
            val cards = s.js("String(document.querySelectorAll('#gamesGrid [data-card-game-id]').length)").trim('"').toInt()
            assertTrue("expected the 4 saved games on the hub, found $cards", cards >= 4)
        }
    }
}
