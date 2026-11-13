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
    private const val KEY_PLAYER_COLORS = "player_colors"
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

    /**
     * The hub's colour theme or gold accents for the game player's bar, as JSON
     * {light, container, primary, onPrimary, primaryContainer, onPrimaryContainer} ("" = none).
     */
    fun savePlayerColors(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_PLAYER_COLORS, json.take(2000)) }
    }

    /** The saved player colours that apply in the current light / dark theme (missing ones are absent). */
    fun playerColors(ctx: Context): Map<String, Int> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLAYER_COLORS, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return emptyMap()
        if (o.optBoolean("light") != isLight(ctx)) return emptyMap()
        val out = HashMap<String, Int>()
        for (k in listOf("container", "primary", "onPrimary", "primaryContainer", "onPrimaryContainer")) {
            parseColor(o.optString(k))?.let { out[k] = it }
        }
        return out
    }

    /** "#RRGGBB" or "rgb(r, g, b)" (what getComputedStyle returns) to a colour, else null. */
    fun parseColor(v: String?): Int? {
        val s = v?.trim().orEmpty()
        Regex("^#([0-9a-fA-F]{6})$").find(s)?.let { return (0xFF000000.toInt() or it.groupValues[1].toInt(16)) }
        Regex("^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})").find(s)?.let { m ->
            val (r, g, b) = m.destructured
            if (listOf(r, g, b).all { it.toInt() <= 255 }) return Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }
        return null
    }

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
