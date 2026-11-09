package com.dailygames.hub

import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

/** Display settings mirrored from the hub so native screens (launch frame, game player) match it. */
object ThemePrefs {
    private const val PREFS = "dailes_theme_prefs"
    private const val KEY_DYNAMIC = "dynamic_wallpaper"
    private const val KEY_AMOLED = "amoled"
    private const val KEY_MODE = "theme_mode"
    val DEFAULT_SURFACE: Int = "#121118".toColorInt()
    val DEFAULT_LIGHT_SURFACE: Int = "#FEF7FF".toColorInt()

    fun save(ctx: Context, dynamicWallpaper: Boolean, amoled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DYNAMIC, dynamicWallpaper); putBoolean(KEY_AMOLED, amoled) }
    }

    /** "dark", "light" or "system" (follow the phone's dark-theme setting). */
    fun saveMode(ctx: Context, mode: String) {
        val m = if (mode == "light" || mode == "system") mode else "dark"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_MODE, m) }
    }

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "dark") ?: "dark"

    fun systemIsDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isLight(ctx: Context): Boolean = isLight(mode(ctx), systemIsDark(ctx))

    fun isLight(mode: String, systemDark: Boolean): Boolean = mode == "light" || (mode == "system" && !systemDark)

    /** AMOLED black only applies to the dark theme. */
    fun isAmoled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AMOLED, false) && !isLight(ctx)

    fun isDynamic(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC, true) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Window / page surface colour for the current settings. */
    fun surfaceColor(ctx: Context): Int {
        val light = isLight(ctx)
        return when {
            light && isDynamic(ctx) -> ctx.getColor(android.R.color.system_neutral1_10)
            light -> DEFAULT_LIGHT_SURFACE
            isAmoled(ctx) -> Color.BLACK
            isDynamic(ctx) -> ctx.getColor(android.R.color.system_neutral1_900)
            else -> DEFAULT_SURFACE
        }
    }
}
