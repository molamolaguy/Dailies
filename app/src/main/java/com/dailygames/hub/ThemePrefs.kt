package com.dailygames.hub

import android.content.Context
import android.graphics.Color
import android.os.Build

/** Display settings mirrored from the hub so native screens (launch frame, game player) match it. */
object ThemePrefs {
    private const val PREFS = "dailes_theme_prefs"
    private const val KEY_DYNAMIC = "dynamic_wallpaper"
    private const val KEY_AMOLED = "amoled"
    val DEFAULT_SURFACE: Int = Color.parseColor("#121118")

    fun save(ctx: Context, dynamicWallpaper: Boolean, amoled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DYNAMIC, dynamicWallpaper)
            .putBoolean(KEY_AMOLED, amoled)
            .apply()
    }

    fun isAmoled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AMOLED, false)

    fun isDynamic(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC, true) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Window / page surface colour for the current settings. */
    fun surfaceColor(ctx: Context): Int = when {
        isAmoled(ctx) -> Color.BLACK
        isDynamic(ctx) -> ctx.getColor(android.R.color.system_neutral1_900)
        else -> DEFAULT_SURFACE
    }
}
