package com.dailygames.hub

import androidx.core.content.edit
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide holder for the game player's ad-blocking data. Loading (~0.3 s) happens once on a
 * background thread, started as soon as a game is about to open; request checks wait briefly for it.
 */
object AdBlocker {
    private val started = AtomicBoolean(false)
    private val networkReady = CountDownLatch(1)
    private val cosmeticReady = CountDownLatch(1)
    @Volatile private var engine: AdBlockEngine? = null
    @Volatile private var cosmetic: CosmeticFilters? = null

    fun warmUp(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread({
            try {
                val am = app.assets
                val hosts = am.open("adblock/hosts.bin").use { AdBlockEngine.readHashes(it) }
                val hosts3p = am.open("adblock/hosts_3p.bin").use { AdBlockEngine.readHashes(it) }
                val important = runCatching { am.open("adblock/hosts_important.bin").use { AdBlockEngine.readHashes(it) } }.getOrDefault(LongArray(0))
                val allow = lineSet(am, "adblock/allow_hosts.txt")
                val docAllow = lineSet(am, "adblock/document_allow.txt")
                PublicSuffix.ensureLoaded(app)
                engine = am.open("adblock/url_rules.txt").bufferedReader().useLines { lines ->
                    AdBlockEngine(hosts, hosts3p, allow, lines, important, docAllow)
                }
            } catch (e: Throwable) {
                // Missing/corrupt data: the player simply runs without network blocking.
            } finally {
                networkReady.countDown()
            }
            try {
                val am = app.assets
                cosmetic = am.open("adblock/cosmetic_generic.txt").bufferedReader().useLines { g ->
                    am.open("adblock/cosmetic_specific.txt").bufferedReader().useLines { sp ->
                        am.open("adblock/cosmetic_except.txt").bufferedReader().useLines { ex ->
                            val nohide = runCatching {
                                am.open("adblock/cosmetic_nohide.txt").bufferedReader().useLines { it.toList() }
                            }.getOrDefault(emptyList())
                            CosmeticFilters(g, sp, ex, nohide.asSequence())
                        }
                    }
                }
            } catch (e: Throwable) {
            } finally {
                cosmeticReady.countDown()
            }
        }, "adblock-loader").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private fun lineSet(am: android.content.res.AssetManager, path: String): Set<String> = runCatching {
        am.open(path).bufferedReader().useLines { l -> l.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet() }
    }.getOrDefault(emptySet())

    /** Network engine, waiting up to [timeoutMs] for the first load. Null if unavailable. */
    fun network(timeoutMs: Long = 2000): AdBlockEngine? {
        if (engine == null) runCatching { networkReady.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return engine
    }

    fun cosmetics(timeoutMs: Long = 3000): CosmeticFilters? {
        if (cosmetic == null) runCatching { cosmeticReady.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return cosmetic
    }
}

/**
 * Game-player settings mirrored from the hub's Settings tab, plus per-site ad-blocking exceptions
 * (toggled from the shield button in the game bar).
 */
object GamePrefs {
    private const val PREFS = "dailes_game_prefs"
    private const val KEY_BLOCK_ADS = "block_ads"
    private const val KEY_AUTO_LOG = "auto_log_share"
    private const val KEY_ALLOWED_SITES = "ads_allowed_sites"

    fun save(ctx: Context, blockAds: Boolean, autoLogShare: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_BLOCK_ADS, blockAds); putBoolean(KEY_AUTO_LOG, autoLogShare) }
    }

    private const val KEY_DARK_PAGES = "dark_pages"

    /** Darken game pages that have no dark theme of their own (only while the app theme is dark). */
    fun setDarkPages(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DARK_PAGES, on) }
    }

    fun darkPages(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK_PAGES, true)

    fun blockAds(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK_ADS, true)
    fun autoLogShare(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_LOG, true)

    fun isSiteAllowed(ctx: Context, host: String): Boolean =
        AdBlockEngine.siteOf(host) in ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_ALLOWED_SITES, emptySet())!!

    fun setSiteAllowed(ctx: Context, host: String, allowed: Boolean) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(KEY_ALLOWED_SITES, emptySet())!!)
        val site = AdBlockEngine.siteOf(host)
        if (allowed) set.add(site) else set.remove(site)
        prefs.edit { putStringSet(KEY_ALLOWED_SITES, set) }
    }
}
