package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconTest {
    @Test
    fun picksTheLauncherEntryForTheThemeAndColours() {
        assertEquals(AppIcon.DEFAULT, AppIcon.wanted(false, "light", true))
        assertEquals(".IconLight", AppIcon.wanted(true, "light", true))
        assertEquals(".IconDark", AppIcon.wanted(true, "dark", true))
        assertEquals(".IconLightClassic", AppIcon.wanted(true, "light", false))
        assertEquals(".IconDarkClassic", AppIcon.wanted(true, "dark", false))
        assertEquals(AppIcon.DEFAULT, AppIcon.wanted(true, "system", true))
        assertEquals(".IconAutoClassic", AppIcon.wanted(true, "system", false))
        // Every answer is a declared launcher entry, and ".MainActivity" (1.x's launcher name) stays the default.
        listOf(true, false).forEach { f -> listOf("dark", "light", "system").forEach { m -> listOf(true, false).forEach { w ->
            assertTrue(AppIcon.wanted(f, m, w) in AppIcon.ALL)
        } } }
    }
}
