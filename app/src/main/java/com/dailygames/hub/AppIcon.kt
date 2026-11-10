package com.dailygames.hub

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit

/**
 * Picks which launcher entry (activity-alias in the manifest) is enabled, so the home-screen icon can
 * match the app's theme. Off by default: the default icon is the white one with the yellow-to-green
 * star, and some launchers drop a home-screen shortcut when its entry changes.
 */
object AppIcon {
    const val DEFAULT = ".MainActivity"
    val ALL = listOf(DEFAULT, ".IconAuto", ".IconLight", ".IconDark", ".IconLightClassic", ".IconDarkClassic", ".IconAutoClassic")
    private const val PREFS = "dailes_theme_prefs"
    private const val KEY_FOLLOW = "icon_follows_theme"

    fun setFollowsTheme(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_FOLLOW, on) }

    fun followsTheme(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FOLLOW, false)

    /** The launcher entry for the app theme [mode] ("dark" / "light" / "system") and wallpaper colours on or off. */
    fun wanted(follow: Boolean, mode: String, wallpaperColors: Boolean): String = when {
        !follow -> DEFAULT
        mode == "light" -> if (wallpaperColors) ".IconLight" else ".IconLightClassic"
        mode == "dark" -> if (wallpaperColors) ".IconDark" else ".IconDarkClassic"
        else -> if (wallpaperColors) ".IconAuto" else ".IconAutoClassic"
    }

    /** Enables the wanted entry first, then disables the rest (so there's always one). Call when leaving the app. */
    fun apply(ctx: Context) {
        val target = wanted(followsTheme(ctx), ThemePrefs.mode(ctx), ThemePrefs.isDynamic(ctx))
        val pm = ctx.packageManager
        fun component(name: String) = ComponentName(ctx.packageName, ctx.packageName + name)
        fun isOn(name: String): Boolean = when (pm.getComponentEnabledSetting(component(name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == DEFAULT
            else -> false
        }
        if (isOn(target) && ALL.none { it != target && isOn(it) }) return
        runCatching {
            pm.setComponentEnabledSetting(component(target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            ALL.filter { it != target }.forEach {
                pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
