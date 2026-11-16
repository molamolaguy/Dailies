package com.dailygames.hub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The game player: Next and switching games without going back to the hub (1.4.0). */
@RunWith(AndroidJUnit4::class)
class GamePlayerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun findByDescription(v: View, desc: String): View? {
        if (v.contentDescription?.toString() == desc) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findByDescription(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    private fun findText(v: View, prefix: String): TextView? {
        if (v is TextView && v.text?.toString()?.startsWith(prefix) == true) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), prefix)?.let { return it }
        return null
    }

    @Test
    fun nextSwitchesToTheNextUnplayedGameAndReportsTheEarlierOne() {
        val lineup = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Game A").put("url", "https://example.com/a").put("done", false))
            .put(JSONObject().put("id", "b").put("name", "Game B").put("url", "https://example.com/b").put("done", true))
            .put(JSONObject().put("id", "c").put("name", "Game C").put("url", "https://example.com/c").put("done", false))
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "a")
            .putExtra(GameActivity.EXTRA_NAME, "Game A")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/a")
            .putExtra(GameActivity.EXTRA_LINEUP, lineup.toString())
        ActivityScenario.launchActivityForResult<GameActivity>(intent).use { s ->
            s.onActivity { a ->
                val next = findByDescription(a.window.decorView, "Next game")
                assertNotNull("Next button shown when there are other games", next)
                next!!.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                // B is already done, so Next skips to C.
                assertNotNull("title shows the new game", findText(a.window.decorView, "Game C"))
                a.finish()
            }
            val result = s.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            val data = result.resultData
            assertEquals("c", data.getStringExtra(GameActivity.EXTRA_ID))
            val earlier = JSONArray(data.getStringExtra(GameActivity.EXTRA_EARLIER) ?: "[]")
            assertEquals(1, earlier.length())
            assertEquals("a", earlier.getJSONObject(0).getString("id"))
            assertTrue(earlier.getJSONObject(0).getLong("openedAt") > 0)
        }
    }

    @Test
    fun aCapturedResultOffersPlayNextOrBackToHub() {
        val lineup = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Game A").put("url", "https://example.com/a").put("done", false))
            .put(JSONObject().put("id", "b").put("name", "Game B").put("url", "https://example.com/b").put("done", false))
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "a")
            .putExtra(GameActivity.EXTRA_NAME, "Game A")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/a")
            .putExtra(GameActivity.EXTRA_LINEUP, lineup.toString())
        ActivityScenario.launchActivityForResult<GameActivity>(intent).use { s ->
            s.onActivity { a ->
                // The same path a copied / shared / found result takes.
                val offer = GameActivity::class.java.getDeclaredMethod("offerShare", String::class.java, String::class.java)
                offer.isAccessible = true
                offer.invoke(a, "Game A #12 3/6\n🟩🟩🟩", "page")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                val play = findByDescription(a.window.decorView, "Play Game B")
                assertNotNull("play-next choice names the next game", play)
                // "Back to hub" sits next to it on the card (the toolbar has one too).
                assertNotNull("back-to-hub choice", findByDescription(play!!.parent as View, "Back to hub"))
                assertNotNull("copy choice", findByDescription(a.window.decorView, "Copy result"))
                play!!.performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                assertNotNull(findText(a.window.decorView, "Game B"))
                a.finish()
            }
            val data = s.result.resultData
            assertEquals("b", data.getStringExtra(GameActivity.EXTRA_ID))
            val earlier = JSONArray(data.getStringExtra(GameActivity.EXTRA_EARLIER) ?: "[]")
            assertEquals("Game A #12 3/6\n🟩🟩🟩", earlier.getJSONObject(0).getString("share"))
        }
    }

    private fun playAllIntent(): Intent {
        val lineup = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Game A").put("url", "https://example.com/a").put("done", false))
            .put(JSONObject().put("id", "b").put("name", "Game B").put("url", "https://example.com/b").put("done", false))
        return Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "a")
            .putExtra(GameActivity.EXTRA_NAME, "Game A")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/a")
            .putExtra(GameActivity.EXTRA_LINEUP, lineup.toString())
            .putExtra(GameActivity.EXTRA_PLAY_ALL, true)
    }

    private fun offer(a: GameActivity, text: String) {
        val m = GameActivity::class.java.getDeclaredMethod("offerShare", String::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(a, text, "page")
    }

    @Test
    fun playAllMovesOnToTheNextGameByItself() {
        ActivityScenario.launchActivityForResult<GameActivity>(playAllIntent()).use { s ->
            s.onActivity { a -> offer(a, "Game A #12 3/6\n🟩🟩🟩") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                val play = findByDescription(a.window.decorView, "Play Game B") as TextView?
                assertNotNull(play)
                assertTrue("counts down: ${play!!.text}", play.text.toString().startsWith("Play Game B in "))
            }
            Thread.sleep(5_500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                assertNotNull("moved on to Game B", findText(a.window.decorView, "Game B"))
                assertTrue(findByDescription(a.window.decorView, "Play Game B") == null)
                a.finish()
            }
            assertEquals("b", s.result.resultData.getStringExtra(GameActivity.EXTRA_ID))
        }
    }

    @Test
    fun playAllStaysWhenYouKeepPlaying() {
        ActivityScenario.launchActivityForResult<GameActivity>(playAllIntent()).use { s ->
            s.onActivity { a -> offer(a, "Game A #12 3/6\n🟩🟩🟩") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a -> findByDescription(a.window.decorView, "Copy result")!!.performClick() }
            Thread.sleep(5_500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                val play = findByDescription(a.window.decorView, "Play Game B") as TextView?
                assertNotNull("still on Game A's card", play)
                assertEquals("Play Game B", play!!.text.toString())
                a.finish()
            }
            assertEquals("a", s.result.resultData.getStringExtra(GameActivity.EXTRA_ID))
        }
    }

    @Test
    fun theOldPageCantReportAResultForTheNewGame() {
        val lineup = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Game A").put("url", "https://example.com/a").put("done", false))
            .put(JSONObject().put("id", "b").put("name", "Game B").put("url", "https://example.com/b").put("done", false))
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "a")
            .putExtra(GameActivity.EXTRA_NAME, "Game A")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/a")
            .putExtra(GameActivity.EXTRA_LINEUP, lineup.toString())
        ActivityScenario.launchActivityForResult<GameActivity>(intent).use { s ->
            s.onActivity { a ->
                findByDescription(a.window.decorView, "Next game")!!.performClick()
                // Game A's page is still live until B's is on screen: a late result from it is ignored.
                val offer = GameActivity::class.java.getDeclaredMethod("offerShare", String::class.java, String::class.java)
                offer.isAccessible = true
                offer.invoke(a, "Game B #12 3/6\n🟩🟩🟩", "page")
                a.finish()
            }
            val data = s.result.resultData
            assertEquals("b", data.getStringExtra(GameActivity.EXTRA_ID))
            assertEquals(null, data.getStringExtra(GameActivity.EXTRA_SHARE_TEXT))
        }
    }

    @Test
    fun aSingleGameHasNoNextButton() {
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "solo")
            .putExtra(GameActivity.EXTRA_NAME, "Solo")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/solo")
        ActivityScenario.launch<GameActivity>(intent).use { s ->
            s.onActivity { a -> assertEquals(null, findByDescription(a.window.decorView, "Next game")) }
        }
    }

    @Test
    fun zoomIsRememberedPerSite() {
        PublicSuffix.ensureLoaded(ctx)
        GamePrefs.setSiteZoom(ctx, "www.example.com", 1.3f)
        assertEquals(1.3f, GamePrefs.siteZoom(ctx, "example.com"), 0.001f)
        GamePrefs.setSiteZoom(ctx, "example.com", 9f)
        assertEquals(GamePrefs.MAX_ZOOM, GamePrefs.siteZoom(ctx, "example.com"), 0.001f)
        GamePrefs.setSiteZoom(ctx, "example.com", 1f)
        assertEquals(1f, GamePrefs.siteZoom(ctx, "example.com"), 0.001f)
    }

    // ---- Drop-down lists on game pages open the app's own menu (1.8.7 / 1.8.8) ----

    private fun findWebView(v: View): WebView? {
        if (v is WebView && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun ActivityScenario<GameActivity>.js(script: String): String {
        var result: String? = null
        val latch = CountDownLatch(1)
        onActivity { a -> findWebView(a.window.decorView)!!.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JS timed out: $script", latch.await(10, TimeUnit.SECONDS))
        return result ?: "null"
    }

    private fun ActivityScenario<GameActivity>.waitForJs(script: String, expected: String, timeoutMs: Long = 20_000) {
        val end = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < end) {
            last = js(script)
            if (last == expected) return
            Thread.sleep(250)
        }
        throw AssertionError("$script was $last, expected $expected")
    }

    /** A real tap (finger down and up) on the page element [selector]. */
    private fun ActivityScenario<GameActivity>.tap(selector: String) {
        val at = js("(function(){const r=document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect(); return (r.left+r.width/2)+','+(r.top+r.height/2);})()").trim('"')
        val (cx, cy) = at.split(',').map { it.toFloat() }
        var sx = 0f
        var sy = 0f
        onActivity { a ->
            val wv = findWebView(a.window.decorView)!!
            val loc = IntArray(2)
            wv.getLocationOnScreen(loc)
            val scale = wv.width.toFloat() / js0Width(wv, a.resources.displayMetrics.density)
            sx = loc[0] + cx * scale
            sy = loc[1] + cy * scale
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val t = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx, sy, 0))
        inst.sendPointerSync(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, sx, sy, 0))
        inst.waitForIdleSync()
    }

    /** The page's width in CSS pixels (the player may zoom a page, so it isn't always width / density). */
    private var pageCssWidth = 0f
    private fun js0Width(wv: WebView, density: Float): Float = if (pageCssWidth > 0f) pageCssWidth else wv.width / density

    @Test
    fun aDropDownOnAGamePageOpensTheAppsOwnMenu() {
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "a")
            .putExtra(GameActivity.EXTRA_NAME, "Game A")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/a")
        ActivityScenario.launchActivityForResult<GameActivity>(intent).use { s ->
            Thread.sleep(1_500)
            val page = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:24px;font:16px sans-serif">
                <select id="diff" style="font-size:18px;padding:10px;width:240px"><option>Easy</option><option selected>Medium</option><option>Hard</option></select>
                <p id="log">none</p>
                <script>document.getElementById('diff').addEventListener('change', function (e) { document.getElementById('log').textContent = e.target.value; });</script>
                </body></html>"""
            s.onActivity { a -> findWebView(a.window.decorView)!!.loadDataWithBaseURL("https://example.com/a", page, "text/html", "utf-8", null) }
            s.waitForJs("String(window.__dailiesSelectMenu === true && !!document.getElementById('diff'))", "\"true\"")
            pageCssWidth = s.js("String(document.documentElement.clientWidth)").trim('"').toFloat()
            s.tap("#diff")
            // The app's menu is showing (Android's own picker would have left it hidden).
            s.waitForJs("String((document.querySelector('dailies-select-menu') || { style: {} }).style.display)", "\"block\"", 5_000)
            // The page itself is left as it was until something is chosen.
            assertEquals("\"none\"", s.js("document.getElementById('log').textContent"))
            assertEquals("\"Medium\"", s.js("document.getElementById('diff').value"))
            s.onActivity { a -> a.finish() }
        }
    }
}
