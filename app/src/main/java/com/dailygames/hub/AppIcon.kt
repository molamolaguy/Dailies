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
        "ocean" to ".IconOcean", "mono" to ".IconMono", "gold" to ".IconGold"
    )
    val ALL = listOf(
        DEFAULT, ".IconAuto", ".IconLight", ".IconDark", ".IconLightClassic", ".IconDarkClassic", ".IconAutoClassic",
        ".IconMidnight", ".IconSunset", ".IconOcean", ".IconMono", ".IconGold"
    )
    /** Each launcher entry's icon (the activity-alias icons in the manifest). */
    private val ICON_RES = mapOf(
        DEFAULT to R.mipmap.ic_launcher, ".IconAuto" to R.mipmap.ic_launcher_auto, ".IconLight" to R.mipmap.ic_launcher_light,
        ".IconDark" to R.mipmap.ic_launcher_dark, ".IconLightClassic" to R.mipmap.ic_launcher_lightc,
        ".IconDarkClassic" to R.mipmap.ic_launcher_darkc, ".IconAutoClassic" to R.mipmap.ic_launcher_autoc,
        ".IconMidnight" to R.mipmap.ic_launcher_midnight, ".IconSunset" to R.mipmap.ic_launcher_sunset,
        ".IconOcean" to R.mipmap.ic_launcher_ocean, ".IconMono" to R.mipmap.ic_launcher_mono, ".IconGold" to R.mipmap.ic_launcher_gold
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
        "mono" -> 0xFF757575.toInt()
        "gold" -> 0xFFBAA465.toInt()
        else -> ThemePrefs.playerColors(ctx)["primary"]
    }

    /** The chosen launcher icon as a bitmap (the notification's large icon), or null if it can't be drawn. */
    fun chosenIconBitmap(ctx: Context, sizePx: Int): android.graphics.Bitmap? = runCatching {
        // The icon image itself: the chosen launcher entry is often still switched off (it changes
        // when the app is left), and Android doesn't hand out a disabled entry's icon.
        val res = ICON_RES[chosenEntry(ctx)] ?: R.mipmap.ic_launcher
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, res) ?: return null
        val bmp = androidx.core.graphics.createBitmap(sizePx, sizePx)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(canvas)
        bmp
    }.getOrNull()

    /** Enables the wanted entry first, then disables the rest (so there's always one). Call when leaving the app. */
    fun apply(ctx: Context) {
        val target = chosenEntry(ctx)
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
