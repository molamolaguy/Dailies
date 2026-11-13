package com.dailygames.hub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
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
}
