package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconTest {
    @Test
    fun picksTheLauncherEntryForTheStyleThemeAndColours() {
        assertEquals(AppIcon.DEFAULT, AppIcon.wanted("default", "light", true))
        assertEquals(".IconLight", AppIcon.wanted("themed", "light", true))
        assertEquals(".IconDark", AppIcon.wanted("themed", "dark", true))
        assertEquals(".IconLightClassic", AppIcon.wanted("themed", "light", false))
        assertEquals(".IconDarkClassic", AppIcon.wanted("themed", "dark", false))
        assertEquals(".IconAuto", AppIcon.wanted("themed", "system", true))
        assertEquals(".IconAutoClassic", AppIcon.wanted("themed", "system", false))
        assertEquals(".IconGold", AppIcon.wanted("gold", "dark", true))
        assertEquals(".IconMidnight", AppIcon.wanted("midnight", "light", false))
        // Unknown styles fall back to the default; ".MainActivity" (1.x's launcher name) stays the default.
        assertEquals(AppIcon.DEFAULT, AppIcon.wanted("sparkly", "dark", true))
        assertEquals("default", AppIcon.normalize("sparkly"))
        assertEquals("themed", AppIcon.normalize("themed"))
        val styles = AppIcon.STYLES.keys + "themed"
        styles.forEach { s -> listOf("dark", "light", "system").forEach { m -> listOf(true, false).forEach { w ->
            assertTrue(AppIcon.wanted(s, m, w) in AppIcon.ALL)
        } } }
    }
}
