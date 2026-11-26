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
        val presetIcons = mapOf(
            "iris" to ".IconIris",
            "forest" to ".IconForest",
            "rose" to ".IconRose",
            "teal" to ".IconTeal",
            "amber" to ".IconAmber",
            "crimson" to ".IconCrimson",
            "cobalt" to ".IconCobalt",
            "sage" to ".IconSage",
            "plum" to ".IconPlum",
            "slate" to ".IconSlate"
        )
        presetIcons.forEach { (style, entry) ->
            assertEquals(style, AppIcon.normalize(style))
            assertEquals(entry, AppIcon.wanted(style, "dark", true))
            assertEquals(entry, AppIcon.wanted(style, "light", false))
            assertEquals(entry, AppIcon.wanted(style, "system", true))
            assertTrue("$entry must be in AppIcon.ALL", entry in AppIcon.ALL)
        }
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
