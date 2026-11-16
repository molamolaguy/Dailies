package com.dailygames.hub

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Frame by frame, in the real WebView with real touch input: quick and slow swipes each way between the
 * pages. Each swipe's frames (interval, where the page is), the touch handlers' time and input delay, and
 * whether the swipe was set up at touchdown, are written to logcat (tag SwipeTiming) for CI to print. It
 * fails only if a swipe doesn't reach its page.
 */
@RunWith(AndroidJUnit4::class)
class SwipeTimingDeviceTest {
    private fun findWebView(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun ActivityScenario<HubActivity>.js(script: String): String {
        var result: String? = null
        val latch = CountDownLatch(1)
        onActivity { a -> findWebView(a.window.decorView)!!.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JS timed out: $script", latch.await(10, TimeUnit.SECONDS))
        return result ?: "null"
    }

    private fun ActivityScenario<HubActivity>.waitForJs(script: String, expected: String, timeoutMs: Long = 20_000) {
        val end = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < end) {
            last = js(script)
            if (last == expected) return
            Thread.sleep(250)
        }
        throw AssertionError("$script was $last, expected $expected")
    }

    private fun ActivityScenario<HubActivity>.swipe(fromX: Float, toX: Float, atY: Float, steps: Int, stepMs: Long) {
        var x0 = 0f
        var x1 = 0f
        var y = 0f
        onActivity { a ->
            val wv = findWebView(a.window.decorView)!!
            val loc = IntArray(2)
            wv.getLocationOnScreen(loc)
            x0 = loc[0] + wv.width * fromX
            x1 = loc[0] + wv.width * toX
            y = loc[1] + wv.height * atY
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val t = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x0, y, 0))
        for (i in 1..steps) {
            val now = SystemClock.uptimeMillis()
            inst.sendPointerSync(MotionEvent.obtain(t, now, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y, 0))
            Thread.sleep(stepMs)
        }
        inst.sendPointerSync(MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1, y, 0))
    }

    // Records every frame from now: [ms since start, interval, current page's left, scrollY], plus each
    // touch event's handler time and how late it arrived, and whether the swipe was set up at touchdown.
    private val recordJs = """(function(){
        window.__rec = { f: [], ev: [], primed: null, from: activeTab };
        const R = window.__rec, t0 = performance.now(); let last = t0;
        if (!window.__recHooked) {
          window.__recHooked = true;
          for (const type of ['touchstart', 'touchmove', 'touchend']) {
            document.addEventListener(type, (e) => { const r = window.__rec; if (!r) return;
              r.ev.push([type[5], Math.round(performance.now() - r.t0), +(performance.now() - e.timeStamp).toFixed(1)]); }, { passive: true, capture: true });
          }
          document.addEventListener('touchstart', () => { const r = window.__rec; if (r) setTimeout(() => { if (r.primed === null) r.primed = !!Pager.primed; }, 0); }, { passive: true });
          const oa = Pager.attach;
          Pager.attach = function () { const s = performance.now(); const v = oa.apply(this, arguments); const r = window.__rec; if (r) r.ev.push(['A', Math.round(s - r.t0), +(performance.now() - s).toFixed(1)]); return v; };
        }
        R.t0 = t0;
        const step = () => { const n = performance.now();
          const el = document.getElementById(TAB_VIEW_IDS[activeTab]);
          R.f.push([Math.round(n - t0), Math.round(n - last), Math.round(el.getBoundingClientRect().left), Math.round(scrollY)]);
          last = n; if (n - t0 < 1800) requestAnimationFrame(step); };
        requestAnimationFrame(step);
        return 'ok'; })()"""

    private fun ActivityScenario<HubActivity>.measure(label: String, fromX: Float, toX: Float, steps: Int, stepMs: Long, expectTab: String, after: Long = 2_000) {
        js(recordJs)
        swipe(fromX, toX, 0.1f, steps, stepMs)
        Thread.sleep(after)
        val out = js(
            """(function(){ const r = window.__rec; window.__rec = null;
            const xs = r.f.map((f) => f[2]); const moved = r.f.filter((f, i) => i > 0 && f[2] !== r.f[i - 1][2]);
            const long = r.f.filter((f) => f[1] > 24).map((f) => f[0] + ':' + f[1]);
            const pr = (window.__probe || []).filter((x) => x[0] >= r.t0 - 50 && x[0] <= r.t0 + 1800).map((x) => Math.round(x[0] - r.t0) + ' ' + x[1] + 'ms ' + x[2]);
            const worst = Math.max(0, ...r.f.slice(1).map((f) => f[1])); const lost = r.f.slice(1).reduce((a, f) => a + Math.max(0, f[1] - 17), 0);
            return 'WORST ' + worst + 'ms LOST ' + lost + 'ms | ' + r.from + '->' + activeTab + ' primed=' + r.primed + ' | long frames(t:ms) ' + (long.join(' ') || 'none') + ' | slow tasks: ' + (pr.join('; ') || 'none') +
              ' | events(type t delay) ' + r.ev.map((e) => e.join(' ')).join(', ') +
              ' | frames(t interval x scrollY) ' + r.f.filter((f, i) => i === 0 || f[2] !== r.f[i - 1][2] || f[1] > 24).map((f) => f.join(' ')).join(', '); })()"""
        ).trim('"')
        // Logcat lines are cut at about 4 KB: in pieces.
        out.chunked(3000).forEachIndexed { i, part -> Log.i("SwipeTiming", "$label ${if (i > 0) "(cont.) " else ""}$part") }
        assertEquals("$label: $out", "\"$expectTab\"", js("activeTab"))
    }

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
        Thread.sleep(300)
    }

    @Test
    fun swipeTimingsEachWay() {
        // CI turns animations off, which the WebView reports as prefers-reduced-motion (pages then switch
        // without sliding): on for this test, off again after.
        shell("settings put global animator_duration_scale 1")
        shell("settings put global transition_animation_scale 1")
        shell("settings put global window_animation_scale 1")
        try { measureAll() } finally {
            shell("settings put global animator_duration_scale 0")
            shell("settings put global transition_animation_scale 0")
            shell("settings put global window_animation_scale 0")
        }
    }

    private fun measureAll() {
        ActivityScenario.launch(HubActivity::class.java).use { s ->
            s.waitForJs("String(typeof renderGamesGrid === 'function' && typeof Pager === 'object')", "\"true\"")
            Log.i("SwipeTiming", "reduced motion at launch: " + s.js("String(matchMedia('(prefers-reduced-motion: reduce)').matches)"))
            // The way pages wait beside the screen now (A), and waiting in place, nearly invisible (B), twice each.
            for ((round, park) in listOf("A1" to "", "B1" to "onscreen", "A2" to "", "B2" to "onscreen")) s.round(round, park)
            s.js("state.games=[]; saveState(); flushSyncNativeNow(); localStorage.removeItem('dailies_test_motion'); localStorage.removeItem('dailies_test_park'); 'ok'")
        }
    }

    private fun ActivityScenario<HubActivity>.round(round: String, park: String) {
        // Animations on, whatever the WebView thinks of the system's setting (see REDUCED_MOTION), and a fresh page.
        js("localStorage.setItem('dailies_test_motion', '1'); localStorage.setItem('dailies_test_park', '$park'); location.reload(); 'ok'")
        Thread.sleep(1_500)
        waitForJs("String(typeof renderGamesGrid === 'function' && typeof Pager === 'object' && !REDUCED_MOTION)", "\"true\"")
        // A few games, so each page has something on it.
        js(
            "const mk=(id)=>{const g={id,name:'Game '+id,url:'https://example.com/'+id,resetTime:'00:00',resetZone:'utc',status:'ready'," +
                "streak:3,notifyEnabled:false,dayHistory:{}}; g.lastCycleKey=getCycleDateKey(g.resetTime,g.resetZone); return g;};" +
                "state.games=['a','b','c','d','e','f','g','h'].map(mk); saveState(); switchNavigation('dashboard', { settled: true }); renderGamesGrid(); 'ok'"
        )
        Thread.sleep(4_000) // the pages beside the screen are drawn ahead
        Log.i("SwipeTiming", "== round $round park='$park': page width " + js("String(innerWidth)") + ", parked: " +
            js("[...document.querySelectorAll('.tab-page.parked')].map(e => e.id + ' ' + e.style.transform + ' ' + e.style.opacity).join('; ')"))
        val quick = 6
        val slow = 30
        measure("$round quick left  hub->streaks", 0.85f, 0.15f, quick, 16, "stats"); Thread.sleep(1_500)
        measure("$round quick left  streaks->settings", 0.85f, 0.15f, quick, 16, "settings"); Thread.sleep(1_500)
        measure("$round quick right settings->streaks", 0.15f, 0.85f, quick, 16, "stats"); Thread.sleep(1_500)
        measure("$round quick right streaks->hub", 0.15f, 0.85f, quick, 16, "dashboard"); Thread.sleep(1_500)
        measure("$round slow  left  hub->streaks", 0.85f, 0.15f, slow, 16, "stats"); Thread.sleep(1_500)
        measure("$round slow  right streaks->hub", 0.15f, 0.85f, slow, 16, "dashboard"); Thread.sleep(1_500)
    }
}
