package com.dailygames.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on a real Android (emulator in CI): the hub page, its bridge and the Android-only parts. */
@RunWith(AndroidJUnit4::class)
class HubSmokeTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun findWebView(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun ActivityScenario<HubActivity>.js(script: String): String {
        var result: String? = null
        val latch = CountDownLatch(1)
        onActivity { a ->
            val wv = findWebView(a.window.decorView) ?: error("no WebView")
            wv.evaluateJavascript(script) { result = it; latch.countDown() }
        }
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

    @Test
    fun hubLoadsAndSyncsGamesToNative() {
        ActivityScenario.launch(HubActivity::class.java).use { s ->
            s.waitForJs("String(typeof HubCore === 'object' && !!document.getElementById('gamesGrid'))", "\"true\"")
            // Add a game through the hub's own state and let it sync over the bridge.
            s.js(
                "state.games.push({id:'smoke',name:'Smoke Test',url:'https://example.com',resetTime:'00:00',resetZone:'utc'," +
                    "status:'ready',streak:0,notifyEnabled:true,dayHistory:{}}); saveState(); flushSyncNativeNow(); 'ok'"
            )
            val end = System.currentTimeMillis() + 10_000
            var found = false
            while (!found && System.currentTimeMillis() < end) {
                found = GameStore.load(ctx).toString().contains("\"smoke\"")
                if (!found) Thread.sleep(200)
            }
            assertTrue("game reached the native store", found)
            // Clean up so other runs start empty.
            s.js("state.games = state.games.filter(g => g.id !== 'smoke'); saveState(); flushSyncNativeNow(); 'ok'")
        }
    }

    @Test
    fun lightThemeAppliesFromSettings() {
        ActivityScenario.launch(HubActivity::class.java).use { s ->
            s.waitForJs("String(typeof applyThemeAndSettings)", "\"function\"")
            s.js("state.settings.themeMode='light'; applyThemeAndSettings(); 'ok'")
            assertEquals("\"light\"", s.js("document.documentElement.getAttribute('data-theme')"))
            s.js("state.settings.themeMode='dark'; applyThemeAndSettings(); saveState(); 'ok'")
        }
    }

    @Test
    fun silentLegacyAlertChannelIsReplacedByOneWithSound() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ctx.getSharedPreferences("dailes_notifier", Context.MODE_PRIVATE).edit().remove("channel_id").commit()
        nm.deleteNotificationChannel(Notifier.SOUND_CHANNEL_ID)
        nm.createNotificationChannel(NotificationChannel(Notifier.LEGACY_CHANNEL_ID, "Reset Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })

        assertEquals(Notifier.SOUND_CHANNEL_ID, Notifier.channelId(ctx))
        assertNull(nm.getNotificationChannel(Notifier.LEGACY_CHANNEL_ID))
        val channel = nm.getNotificationChannel(Notifier.SOUND_CHANNEL_ID)
        assertNotNull(channel)
        assertNotNull("new channel has the default sound", channel.sound)
        assertNotNull(nm.getNotificationChannel(ReminderChannel.ID))
    }

    @Test
    fun streakReminderSettingIsStoredAndValidated() {
        StreakReminders.setLeadMinutes(ctx, 60)
        assertEquals(60, StreakReminders.leadMinutes(ctx))
        StreakReminders.setLeadMinutes(ctx, 45) // not an offered choice
        assertEquals(0, StreakReminders.leadMinutes(ctx))
    }

    /** Taps the element matched by [selector] with real touch events, like a finger on the screen. */
    private fun ActivityScenario<HubActivity>.tapElement(selector: String) {
        val rect = js("(function(){const e=document.querySelector(${org.json.JSONObject.quote(selector)}); if(!e) return ''; e.scrollIntoView({block:'center'}); const r=e.getBoundingClientRect(); return (r.left+r.width/2)+','+(r.top+r.height/2);})()")
            .trim('"')
        assertTrue("element $selector not found", rect.contains(','))
        Thread.sleep(300)
        val again = js("(function(){const r=document.querySelector(${org.json.JSONObject.quote(selector)}).getBoundingClientRect(); return (r.left+r.width/2)+','+(r.top+r.height/2);})()").trim('"')
        val (cx, cy) = again.split(',').map { it.toFloat() }
        var sx = 0f
        var sy = 0f
        onActivity { a ->
            val wv = findWebView(a.window.decorView)!!
            val loc = IntArray(2)
            wv.getLocationOnScreen(loc)
            val density = a.resources.displayMetrics.density
            sx = loc[0] + cx * density
            sy = loc[1] + cy * density
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val t = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx, sy, 0))
        inst.sendPointerSync(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, sx, sy, 0))
        inst.waitForIdleSync()
        Thread.sleep(600)
    }

    @Test
    fun doneTodaySectionFoldsWithARealTap() {
        ActivityScenario.launch(HubActivity::class.java).use { s ->
            s.waitForJs("String(typeof renderGamesGrid)", "\"function\"")
            s.js(
                "window.__errs=[]; window.addEventListener('error', e => window.__errs.push(String(e.message)));" +
                    "const mk=(id,done)=>{const g={id,name:id,url:'https://example.com/'+id,resetTime:'00:00',resetZone:'utc',status:done?'completed':'ready'," +
                    "streak:0,notifyEnabled:false,dayHistory:{}}; g.lastCycleKey=getCycleDateKey(g.resetTime,g.resetZone); if(done) g.dayHistory[g.lastCycleKey]='completed'; return g;};" +
                    "state.games=[mk('fold-open',false),mk('fold-done',true)]; state.activeFilter='all'; state.settings.groupDone=true; doneSectionOpen=false;" +
                    "switchNavigation('dashboard'); renderGamesGrid(); 'ok'"
            )
            s.waitForJs("String(!!document.querySelector('.done-section-head'))", "\"true\"")
            // Folded by default: only the unplayed game shows.
            assertEquals("\"false\"", s.js("document.querySelector('.done-section-head').getAttribute('aria-expanded')"))
            assertEquals("\"1\"", s.js("String(document.querySelectorAll('#gamesGrid [data-card-game-id]').length)"))
            s.tapElement(".done-section-head")
            assertEquals("unfolded after one tap; errors: " + s.js("JSON.stringify(window.__errs)"),
                "\"true\"", s.js("String(document.querySelector('.done-section-head').getAttribute('aria-expanded'))"))
            assertEquals("\"2\"", s.js("String(document.querySelectorAll('#gamesGrid [data-card-game-id]').length)"))
            s.tapElement(".done-section-head")
            assertEquals("\"false\"", s.js("String(document.querySelector('.done-section-head').getAttribute('aria-expanded'))"))
            s.js("state.games=[]; saveState(); flushSyncNativeNow(); 'ok'")
        }
    }

    @Test
    fun aFriendsListLinkOpensTheAddGamesDialog() {
        val link = GameListCodec.encode("""[{"name":"Smoke List Game","url":"https://example.org/daily","resetTime":"00:00","resetZone":"utc"}]""")!!
        ActivityScenario.launch(HubActivity::class.java).use { s ->
            s.waitForJs("String(typeof window.onSharedGameList)", "\"function\"")
            // The hub is singleTask: a tapped link or scanned QR code reaches the running hub (onNewIntent).
            // Exactly what Android does for a singleTask activity that's already running.
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                .setClass(ctx, HubActivity::class.java)
            var launchIntent: android.content.Intent? = null
            s.onActivity { a ->
                launchIntent = android.content.Intent(a.intent)
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(a, intent)
            }
            s.waitForJs("String(!document.getElementById('modalImportList').classList.contains('hidden'))", "\"true\"")
            assertEquals("\"Smoke List Game\"", s.js("document.querySelector('#importListRows .name').textContent"))
            s.js("closeModal('modalImportList'); 'ok'")
            // ActivityScenario follows the hub by the intent it launched; onNewIntent replaced it with the
            // link, so put the launch intent back before closing, or its lifecycle events are ignored.
            s.onActivity { it.intent = launchIntent }
        }
    }

    @Test
    fun qrCodeIsGeneratedForAGameList() {
        val link = GameListCodec.encode("""[{"name":"Wordle","url":"https://www.nytimes.com/games/wordle","resetTime":"00:00","resetZone":"local"}]""")!!
        val qr = GameListCodec.qrDataUrl(link)
        assertNotNull(qr)
        assertTrue(qr!!.startsWith("data:image/png;base64,"))
    }
}
