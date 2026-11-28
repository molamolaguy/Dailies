package com.dailygames.hub

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit

/**
 * Picks which launcher entry (activity-alias in the manifest) is enabled, so the home-screen icon is
 * the style chosen in Settings. "default" is the white icon with the yellow-to-green star, "themed"
 * follows the app's theme (and wallpaper colours), and the rest are fixed styles. Gold is offered
 * once every achievement is earned (the hub checks that). The entry changes when leaving the app,
 * since some launchers drop a home-screen shortcut when its entry changes.
 */
object AppIcon {
    const val DEFAULT = ".MainActivity"

    /** Styles the hub can choose, and their launcher entries ("themed" is resolved from the theme). */
    val STYLES = mapOf(
        "default" to DEFAULT, "midnight" to ".IconMidnight", "sunset" to ".IconSunset",
        "ocean" to ".IconOcean", "iris" to ".IconIris", "forest" to ".IconForest",
        "rose" to ".IconRose", "teal" to ".IconTeal", "amber" to ".IconAmber",
        "crimson" to ".IconCrimson", "cobalt" to ".IconCobalt", "sage" to ".IconSage",
        "plum" to ".IconPlum", "slate" to ".IconSlate", "mono" to ".IconMono",
        "gold" to ".IconGold", "ruby" to ".IconRuby",
        // A custom icon (1.7.0) is a home-screen shortcut; the app's own launcher entry stays the default.
        "custom" to DEFAULT
    )
    val ALL = listOf(
        DEFAULT, ".IconAuto", ".IconLight", ".IconDark", ".IconLightClassic", ".IconDarkClassic", ".IconAutoClassic",
        ".IconMidnight", ".IconSunset", ".IconOcean", ".IconIris", ".IconForest", ".IconRose", ".IconTeal",
        ".IconAmber", ".IconCrimson", ".IconCobalt", ".IconSage", ".IconPlum", ".IconSlate",
        ".IconMono", ".IconGold", ".IconRuby"
    )
    private const val PREFS = "dailes_theme_prefs"
    private const val KEY_FOLLOW = "icon_follows_theme"
    private const val KEY_STYLE = "icon_style"

    fun normalize(style: String?): String = if (style == "themed" || style in STYLES) style!! else "default"

    fun setStyle(ctx: Context, style: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_STYLE, normalize(style)) }

    /** The chosen style; the 1.2–1.3 "App Icon Matches Theme" switch counts as "themed". */
    fun style(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_STYLE, null)?.let { normalize(it) }
            ?: if (prefs.getBoolean(KEY_FOLLOW, false)) "themed" else "default"
    }

    /** The launcher entry for [style], the app theme [mode] ("dark" / "light" / "system") and wallpaper colours. */
    fun wanted(style: String, mode: String, wallpaperColors: Boolean): String = when (style) {
        "themed" -> when (mode) {
            "light" -> if (wallpaperColors) ".IconLight" else ".IconLightClassic"
            "dark" -> if (wallpaperColors) ".IconDark" else ".IconDarkClassic"
            else -> if (wallpaperColors) ".IconAuto" else ".IconAutoClassic"
        }
        else -> STYLES[style] ?: DEFAULT
    }

    /** The launcher entry for the style chosen in Settings (what the home screen shows once the app is left). */
    fun chosenEntry(ctx: Context): String = wanted(style(ctx), ThemePrefs.mode(ctx), ThemePrefs.isDynamic(ctx))

    /** The accent of an icon style, for tinting notifications to match the app icon. Null: the app's default. */
    fun accentColor(ctx: Context): Int? = when (style(ctx)) {
        "default" -> 0xFF2FB86A.toInt()
        "midnight" -> 0xFF8B7CF6.toInt()
        "sunset" -> 0xFFF43F5E.toInt()
        "ocean" -> 0xFF2563EB.toInt()
        "iris" -> 0xFF8B5CF6.toInt()
        "forest" -> 0xFF2E8B57.toInt()
        "rose" -> 0xFFD9467A.toInt()
        "teal" -> 0xFF0F9D9A.toInt()
        "amber" -> 0xFFD98A16.toInt()
        "crimson" -> 0xFFC82838.toInt()
        "cobalt" -> 0xFF3B5BDB.toInt()
        "sage" -> 0xFF5F8D6B.toInt()
        "plum" -> 0xFF9C36B5.toInt()
        "slate" -> 0xFF5C728A.toInt()
        "mono" -> 0xFF757575.toInt()
        "gold" -> 0xFFCBA854.toInt()
        "ruby" -> 0xFFC8163F.toInt()
        "custom" -> customStar(ctx)
        else -> ThemePrefs.playerColors(ctx)["primary"]
            ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && ThemePrefs.isDynamic(ctx)) {
                runCatching { ctx.getColor(android.R.color.system_accent1_600) }.getOrNull()
            } else null
    }

    // ---- Custom icon (1.7.0): made in the hub's icon editor; added to the home screen as a shortcut ----
    private const val KEY_CUSTOM_STAR = "custom_star"

    fun saveCustom(ctx: Context, @Suppress("UNUSED_PARAMETER") png: ByteArray, starColor: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_CUSTOM_STAR, starColor) }
    }

    private fun customStar(ctx: Context): Int? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CUSTOM_STAR, 0).takeIf { it != 0 }

    private const val NAMESPACE = "com.dailygames.hub"

    /** Enables the wanted entry first, then disables the rest (so there's always one). Call when leaving the app. */
    fun apply(ctx: Context) {
        val target = chosenEntry(ctx)
        val pm = ctx.packageManager
        fun component(name: String) = ComponentName(ctx.packageName, NAMESPACE + name)
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
