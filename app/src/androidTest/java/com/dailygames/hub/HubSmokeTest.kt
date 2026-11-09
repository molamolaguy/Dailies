package com.dailygames.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun ActivityScenario<MainActivity>.js(script: String): String {
        var result: String? = null
        val latch = CountDownLatch(1)
        onActivity { a ->
            val wv = findWebView(a.window.decorView) ?: error("no WebView")
            wv.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue("JS timed out: $script", latch.await(10, TimeUnit.SECONDS))
        return result ?: "null"
    }

    private fun ActivityScenario<MainActivity>.waitForJs(script: String, expected: String, timeoutMs: Long = 20_000) {
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
        ActivityScenario.launch(MainActivity::class.java).use { s ->
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
        ActivityScenario.launch(MainActivity::class.java).use { s ->
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
}
