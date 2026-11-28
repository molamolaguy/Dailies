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

    /** The page's text views, joined, to look for the "result on screen" card. */
    private fun allText(v: View): String = when (v) {
        is TextView -> v.text?.toString().orEmpty() + "\n"
        is ViewGroup -> (0 until v.childCount).joinToString("") { allText(v.getChildAt(it)) }
        else -> ""
    }

    private fun launchWithPage(page: String, block: (ActivityScenario<GameActivity>) -> Unit) {
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "a")
            .putExtra(GameActivity.EXTRA_NAME, "Game A")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/a")
        ActivityScenario.launchActivityForResult<GameActivity>(intent).use { s ->
            Thread.sleep(1_500)
            s.onActivity { a -> findWebView(a.window.decorView)!!.loadDataWithBaseURL("https://example.com/a", page, "text/html", "utf-8", null) }
            block(s)
            s.onActivity { a -> a.finish() }
        }
    }

    /**
     * 1.8.12: a countdown to the next puzzle and the game's own running clock (noillirk.com has both at
     * the bottom of its page) are never offered as the result of a game that hasn't been played.
     */
    @Test
    fun aCountdownAndARunningClockAreNotOfferedAsAResult() {
        val page = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:24px;font:16px sans-serif"><h1>Game A</h1><div id="grid">grid</div>
            <footer><div>Time</div><div id="tm">0:00</div><div id="cd">Next puzzle in 05:12:44</div></footer>
            <script>var t=0,c=18764;setInterval(function(){t++;c--;document.getElementById('tm').textContent=Math.floor(t/60)+':'+('0'+t%60).slice(-2);
            document.getElementById('cd').textContent='Next puzzle in 05:12:'+('0'+c%60).slice(-2);},1000);</script></body></html>"""
        launchWithPage(page) { s ->
            Thread.sleep(14_000)
            s.onActivity { a ->
                val shown = allText(a.window.decorView)
                assertTrue("no result card for an unplayed game; shown: $shown", !shown.contains("result on screen"))
            }
        }
    }

    /**
     * 1.8.12: an end screen that comes up a while after the last move (here 11 s, after the checks that
     * follow a move) is still found. The page watcher used to fail to start (it ran before the page had
     * an <html> element), so only the checks at 1.5, 4 and 8 s after a move could see one.
     */
    @Test
    fun aLateEndScreenOffersItsResult() {
        val page = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:24px;font:16px sans-serif"><h1>Game A</h1>
            <button id="go" style="font-size:18px;padding:12px 24px">Guess</button>
            <div>Time <span id="tm">0:00</span></div>
            <script>var t=0,iv=setInterval(function(){t++;document.getElementById('tm').textContent='0:'+('0'+t).slice(-2);},1000);
            document.getElementById('go').addEventListener('click',function(){setTimeout(function(){clearInterval(iv);
            var d=document.createElement('div');d.innerHTML='<p>You solved it!</p><p>Solved in 3 guesses</p>';document.body.appendChild(d);},11000);});</script></body></html>"""
        launchWithPage(page) { s ->
            s.waitForJs("String(!!document.getElementById('go'))", "\"true\"")
            pageCssWidth = s.js("String(document.documentElement.clientWidth)").trim('"').toFloat()
            s.tap("#go")
            val end = System.currentTimeMillis() + 25_000
            var shown = ""
            while (System.currentTimeMillis() < end) {
                s.onActivity { a -> shown = allText(a.window.decorView) }
                if (shown.contains("result on screen")) break
                Thread.sleep(500)
            }
            assertTrue("the result card is shown; shown: $shown", shown.contains("Game A result on screen"))
            assertTrue("it offers the solve, not the clock; shown: $shown", shown.contains("Solved in 3 guesses"))
        }
    }

    @Test
    fun inGameProgressAndRulesProseAreNotOfferedAsAResultUntilFinished() {
        val page = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>.pill{display:inline-flex;gap:6px;padding:6px 12px}</style></head>
            <body style="margin:0;padding:20px;font:16px sans-serif">
            <main>
              <div><div class="pill"><span>12</span><span>Guesses</span></div>
              <div class="pill"><span id="prog">0/9</span><span>Fields</span></div></div>
              <div>Score <span>0</span></div>
              <button id="cell" style="font-size:18px;padding:12px 24px">Cell 1</button>
              <p>Click a cell and enter a game that fits both categories. You have 12 guesses left.</p>
            </main>
            <div id="doneWrap" class="daily-done-card" style="display:none;padding:16px">
              <div>9/9 Fields filled</div>
              <button id="copyBtn" aria-label="Copy result" style="padding:10px 18px">Copy</button>
              <div>Next puzzle in 05:42:10</div>
            </div>
            <section style="margin-top:24px">
              <p>Nine cells add up to 900. A flawless grid leaves three of them over for 1,200 points.</p>
              <p>Sudoku is a grid puzzle solved by following rules.</p>
            </section>
            <script>
              document.getElementById('cell').addEventListener('click', function () {
                document.getElementById('prog').textContent = '3/9';
              });
              document.getElementById('copyBtn').addEventListener('click', function () {
                navigator.clipboard.writeText('Game A #52 8/9\n🟩🟩🟩\n🟩🟩🟩\n🟩⬜🟩');
              });
            </script></body></html>"""
        launchWithPage(page) { s ->
            s.waitForJs("String(!!document.getElementById('cell'))", "\"true\"")
            pageCssWidth = s.js("String(document.documentElement.clientWidth)").trim('"').toFloat()
            s.tap("#cell")
            Thread.sleep(10_500)
            s.onActivity { a ->
                val shown = allText(a.window.decorView)
                assertTrue("in-game 0/9 or 3/9 progress must not trigger result card; shown: $shown",
                    !shown.contains("result on screen") && !shown.contains("result saved"))
            }
            s.js("document.getElementById('doneWrap').style.display='block'; 'ok'")
            val end = System.currentTimeMillis() + 10_000
            var shown = ""
            while (System.currentTimeMillis() < end) {
                s.onActivity { a -> shown = allText(a.window.decorView) }
                if (shown.contains("result saved")) break
                Thread.sleep(300)
            }
            assertTrue("revealing the finished card captures Copy result; shown: $shown", shown.contains("Game A result saved"))
        }
    }

    @Test
    fun aShareButtonAfterALongFinishAnimationIsCapturedAutomatically() {
        val page = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:24px;font:16px sans-serif">
            <div id="stage"><button id="pin" style="font-size:18px;padding:12px 24px">Drop pin</button></div>
            <div id="camp"></div>
            <script>
              var __offset = 0, __origNow = Date.now.bind(Date);
              Date.now = function () { return __origNow() + __offset; };
              window.__showCamp = function () {
                __offset = 35000;
                document.getElementById('stage').style.display = 'none';
                document.getElementById('camp').innerHTML =
                  '<section class="camp-sheet"><div>265 km</div>' +
                  '<button id="sh" aria-label="Share" style="padding:12px 24px;font-size:16px">Share</button>' +
                  '<div>Expedition #52 opens in <span>9 h 12 m</span></div></section>';
                document.getElementById('sh').addEventListener('click', function () {
                  navigator.clipboard.writeText('Game A #52 · 265/500 km\nRare · Uncommon\nhttps://example.com/a');
                });
              };
            </script></body></html>"""
        launchWithPage(page) { s ->
            s.waitForJs("String(!!document.getElementById('pin'))", "\"true\"")
            pageCssWidth = s.js("String(document.documentElement.clientWidth)").trim('"').toFloat()
            s.tap("#pin")
            Thread.sleep(500)
            s.js("window.__showCamp(); 'ok'")
            val end = System.currentTimeMillis() + 10_000
            var shown = ""
            while (System.currentTimeMillis() < end) {
                s.onActivity { a -> shown = allText(a.window.decorView) }
                if (shown.contains("result saved")) break
                Thread.sleep(300)
            }
            assertTrue("Share button after ~35s animation is auto-captured; shown: $shown", shown.contains("Game A result saved"))
        }
    }

    @Test
    fun completedGameWithLoggedResultHidesLogButtonAndSkipsAutoLogger() {
        val lineup = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Game A").put("url", "https://example.com/a").put("done", true).put("loggedResult", true))
            .put(JSONObject().put("id", "b").put("name", "Game B").put("url", "https://example.com/b").put("done", false).put("loggedResult", false))
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, "a")
            .putExtra(GameActivity.EXTRA_NAME, "Game A")
            .putExtra(GameActivity.EXTRA_URL, "https://example.com/a")
            .putExtra(GameActivity.EXTRA_LOGGED_RESULT, true)
            .putExtra(GameActivity.EXTRA_LINEUP, lineup.toString())
        ActivityScenario.launchActivityForResult<GameActivity>(intent).use { s ->
            s.onActivity { a ->
                val logBtn = findByDescription(a.window.decorView, "Log today's result")
                assertEquals(View.GONE, logBtn?.visibility)
                offer(a, "Game A #12 3/6\n🟩🟩🟩")
                assertEquals(null, findByDescription(a.window.decorView, "Copy result"))
                // Switching to unlogged Game B restores the Log button.
                findByDescription(a.window.decorView, "Next game")!!.performClick()
                assertEquals(View.VISIBLE, logBtn?.visibility)
                a.finish()
            }
            val data = s.result.resultData
            assertEquals("b", data.getStringExtra(GameActivity.EXTRA_ID))
            val earlier = JSONArray(data.getStringExtra(GameActivity.EXTRA_EARLIER) ?: "[]")
            assertEquals("", earlier.getJSONObject(0).getString("share"))
        }
    }
}
