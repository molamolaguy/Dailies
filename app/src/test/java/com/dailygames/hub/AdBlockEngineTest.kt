package com.dailygames.hub

import com.dailygames.hub.AdBlockEngine.RequestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdBlockEngineTest {
    private fun hashes(vararg hosts: String) = hosts.map { AdBlockEngine.fnv1a64(it) }.sorted().toLongArray()

    private val engine = AdBlockEngine(
        blockedHosts = hashes("doubleclick.net", "ads.example.org", "tracker.io"),
        blockedThirdPartyHosts = hashes("cdn-ads.net"),
        allowedHosts = setOf("safe.doubleclick.net"),
        urlRuleLines = sequenceOf(
            "/adsbygoogle.js",
            "||example.com/ads/*\$script",
            "||promo.example.com^\$domain=news.com|~sports.news.com",
            "@@||example.com/ads/needed.js",
            "||evil.com^\$important",
            "@@||evil.com^",
            "-ad-banner-",
            "|https://start.example.net/pixel",
            "||end.example.net/x.gif|",
            "! a comment",
            "/regexrule/"
        )
    )

    @Test
    fun hostLists_blockDomainAndSubdomains_butNeverTheGameItself() {
        assertTrue(engine.shouldBlock("https://stats.g.doubleclick.net/r/collect", "fe-guess.com", RequestType.IMAGE))
        assertTrue(engine.shouldBlock("https://doubleclick.net/x", "fe-guess.com", RequestType.SCRIPT))
        assertFalse(engine.shouldBlock("https://notdoubleclick.net/x", "fe-guess.com", RequestType.SCRIPT))
        // First-party: the page's own site is never blocked by the big host lists.
        assertFalse(engine.shouldBlock("https://tracker.io/app.js", "tracker.io", RequestType.SCRIPT))
        assertFalse(engine.shouldBlock("https://cdn.tracker.io/app.js", "www.tracker.io", RequestType.SCRIPT))
        // Allow-listed host beats the block list.
        assertFalse(engine.shouldBlock("https://safe.doubleclick.net/x", "fe-guess.com", RequestType.SCRIPT))
        // The main document is never blocked.
        assertFalse(engine.shouldBlock("https://doubleclick.net/", null, RequestType.DOCUMENT))
    }

    @Test
    fun thirdPartyOnlyHosts() {
        assertTrue(engine.shouldBlock("https://cdn-ads.net/a.js", "game.com", RequestType.SCRIPT))
        assertFalse(engine.shouldBlock("https://cdn-ads.net/a.js", "cdn-ads.net", RequestType.SCRIPT))
    }

    @Test
    fun urlRules_patternsAnchorsTypesDomainsAndExceptions() {
        assertTrue(engine.shouldBlock("https://anything.com/js/adsbygoogle.js?client=1", "game.com", RequestType.SCRIPT))
        assertTrue(engine.shouldBlock("https://example.com/ads/banner.js", "game.com", RequestType.SCRIPT))
        assertFalse("type restricted to scripts", engine.shouldBlock("https://example.com/ads/banner.png", "game.com", RequestType.IMAGE))
        assertFalse("exception", engine.shouldBlock("https://example.com/ads/needed.js", "game.com", RequestType.SCRIPT))
        assertTrue(engine.shouldBlock("https://promo.example.com/x", "news.com", RequestType.IMAGE))
        assertTrue(engine.shouldBlock("https://promo.example.com/x", "www.news.com", RequestType.IMAGE))
        assertFalse("excluded domain", engine.shouldBlock("https://promo.example.com/x", "sports.news.com", RequestType.IMAGE))
        assertFalse("other domain", engine.shouldBlock("https://promo.example.com/x", "game.com", RequestType.IMAGE))
        assertTrue("important beats exception", engine.shouldBlock("https://evil.com/x.js", "game.com", RequestType.SCRIPT))
        assertTrue(engine.shouldBlock("https://site.com/img/top-ad-banner-1.png", "game.com", RequestType.IMAGE))
        assertTrue(engine.shouldBlock("https://start.example.net/pixel?id=1", "game.com", RequestType.IMAGE))
        assertFalse("start anchor", engine.shouldBlock("https://other.net/?u=https://start.example.net/pixel", "game.com", RequestType.IMAGE))
        assertTrue(engine.shouldBlock("https://end.example.net/x.gif", "game.com", RequestType.IMAGE))
        assertFalse("end anchor", engine.shouldBlock("https://end.example.net/x.gif?y", "game.com", RequestType.IMAGE))
        assertFalse(engine.shouldBlock("https://site.com/regexrule/", "game.com", RequestType.IMAGE))
    }

    @Test
    fun separatorAndHostAnchorSemantics() {
        val e = AdBlockEngine(LongArray(0), LongArray(0), emptySet(), sequenceOf("||ads.net^", "||cdn.org/a^b"))
        assertTrue(e.shouldBlock("https://ads.net/x", "g.com", RequestType.SCRIPT))
        assertTrue(e.shouldBlock("https://sub.ads.net:8443/x", "g.com", RequestType.SCRIPT))
        assertFalse(e.shouldBlock("https://ads.network/x", "g.com", RequestType.SCRIPT))
        assertFalse(e.shouldBlock("https://badads.net/x", "g.com", RequestType.SCRIPT))
        assertTrue(e.shouldBlock("https://cdn.org/a/b", "g.com", RequestType.SCRIPT))
        assertTrue(e.shouldBlock("https://cdn.org/a?b", "g.com", RequestType.SCRIPT))
        assertFalse(e.shouldBlock("https://cdn.org/axb", "g.com", RequestType.SCRIPT))
    }

    @Test
    fun inferTypeAndSites() {
        assertEquals(RequestType.SCRIPT, AdBlockEngine.inferType("https://a.com/x.js?v=1", "*/*", false))
        assertEquals(RequestType.IMAGE, AdBlockEngine.inferType("https://a.com/pixel", "image/avif,image/webp", false))
        assertEquals(RequestType.SUBDOCUMENT, AdBlockEngine.inferType("https://a.com/frame", "text/html,application/xhtml+xml", false))
        assertEquals(RequestType.DOCUMENT, AdBlockEngine.inferType("https://a.com/", "text/html", true))
        assertEquals("bbc.co.uk", AdBlockEngine.siteOf("www.news.bbc.co.uk"))
        assertTrue(AdBlockEngine.sameSite("cdn.nytimes.com", "www.nytimes.com"))
        assertFalse(AdBlockEngine.sameSite("nytimes.com", "doubleclick.net"))
    }

    @Test
    fun importantPrecedence_onlyImportantExceptionsLiftImportantBlocks() {
        val e = AdBlockEngine(LongArray(0), LongArray(0), emptySet(), sequenceOf(
            "||bad.net^\$important", "@@||bad.net/ok.js", "@@||bad.net/really-ok.js\$important", "||plain.net^", "@@||plain.net/ok.js"
        ), importantHosts = hashes("vip-ads.net"))
        assertTrue(e.shouldBlock("https://bad.net/ok.js", "g.com", RequestType.SCRIPT))
        assertFalse(e.shouldBlock("https://bad.net/really-ok.js", "g.com", RequestType.SCRIPT))
        assertFalse(e.shouldBlock("https://plain.net/ok.js", "g.com", RequestType.SCRIPT))
        assertTrue(e.shouldBlock("https://plain.net/x.js", "g.com", RequestType.SCRIPT))
        assertTrue(e.shouldBlock("https://cdn.vip-ads.net/a.js", "g.com", RequestType.SCRIPT))
    }

    @Test
    fun typeOptions_pingNeverInferred_scriptOnlyThirdPartyForUnknown_notXhr() {
        val e = AdBlockEngine(LongArray(0), LongArray(0), emptySet(), sequenceOf(
            "/collect\$ping", "/tracker.\$script", "/widget\$~xmlhttprequest", "/beacon\$beacon"
        ))
        assertFalse("fetch() isn't a ping", e.shouldBlock("https://a.com/collect", "g.com", RequestType.OTHER))
        assertTrue(e.shouldBlock("https://a.com/collect", "g.com", RequestType.PING))
        assertTrue("unknown 3p request may be a script", e.shouldBlock("https://cdn.x.com/tracker.min", "g.com", RequestType.OTHER))
        assertFalse("unknown 1p request isn't treated as a script", e.shouldBlock("https://g.com/tracker.min", "g.com", RequestType.OTHER))
        assertFalse("might be an XHR", e.shouldBlock("https://a.com/widget", "g.com", RequestType.OTHER))
        assertTrue(e.shouldBlock("https://a.com/widget", "g.com", RequestType.IMAGE))
        assertFalse("XHR excluded", e.shouldBlock("https://a.com/widget", "g.com", RequestType.XHR))
    }

    @Test
    fun hostParsing_userinfoPortIpv6AndTrailingDot() {
        assertEquals("ads.net", AdBlockEngine.hostOf("https://user:pw@ads.net:8080/x"))
        assertEquals("::1", AdBlockEngine.hostOf("http://[::1]:80/"))
        assertEquals("ads.net", AdBlockEngine.hostOf("https://ads.net./x"))
        val e = AdBlockEngine(LongArray(0), LongArray(0), emptySet(), sequenceOf("||ads.net^", "||adserver."))
        assertTrue(e.shouldBlock("https://user@ads.net/x", "g.com", RequestType.SCRIPT))
        assertFalse("userinfo isn't the host", e.shouldBlock("https://ads.net@good.com/x", "g.com", RequestType.SCRIPT))
        assertTrue("trailing-dot rules match any TLD", e.shouldBlock("https://adserver.io/x", "g.com", RequestType.SCRIPT))
    }

    @Test
    fun documentExceptionAndPsl() {
        PublicSuffix.load(sequenceOf("com", "uk", "co.uk", "github.io", "*.ck", "!www.ck"))
        assertEquals("me.github.io", AdBlockEngine.siteOf("a.me.github.io"))
        assertFalse(AdBlockEngine.sameSite("me.github.io", "you.github.io"))
        assertEquals("www.ck", AdBlockEngine.siteOf("a.www.ck"))
        assertEquals("x.y.ck", AdBlockEngine.siteOf("x.y.ck"))
        val e = AdBlockEngine(hashes("ads.net"), LongArray(0), emptySet(), emptySequence(), documentAllowedSites = setOf("trusted.com"))
        assertFalse(e.shouldBlock("https://ads.net/a.js", "www.trusted.com", RequestType.SCRIPT))
        assertTrue(e.shouldBlock("https://ads.net/a.js", "other.com", RequestType.SCRIPT))
    }

    @Test
    fun surrogatesForCommonLibraries() {
        assertTrue(Surrogates.forUrl("https://www.googletagmanager.com/gtag/js?id=G-1")!!.contains("gtag"))
        assertTrue(Surrogates.forUrl("https://securepubads.g.doubleclick.net/tag/js/gpt.js")!!.contains("googletag"))
        assertTrue(Surrogates.forUrl("https://imasdk.googleapis.com/js/sdkloader/ima3.js")!!.contains("AD_ERROR"))
        assertEquals(null, Surrogates.forUrl("https://ads.net/banner.png"))
    }

    /** Smoke test against the real bundled lists (assets/adblock). */
    @Test
    fun realLists_blockCommonAdsAndTrackers_andLeaveGamesAlone() {
        val dir = File("src/main/assets/adblock")
        val real = AdBlockEngine(
            dir.resolve("hosts.bin").inputStream().use { AdBlockEngine.readHashes(it) },
            dir.resolve("hosts_3p.bin").inputStream().use { AdBlockEngine.readHashes(it) },
            dir.resolve("allow_hosts.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            dir.resolve("url_rules.txt").readLines().asSequence(),
            dir.resolve("hosts_important.bin").inputStream().use { AdBlockEngine.readHashes(it) },
            dir.resolve("document_allow.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        )
        PublicSuffix.load(dir.resolve("psl.txt").readLines().asSequence())
        val page = "fe-guess.com"
        val ads = listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-1",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://www.googletagmanager.com/gtag/js?id=G-XXXX",
            "https://www.google-analytics.com/analytics.js",
            "https://c.amazon-adsystem.com/aax2/apstag.js",
            "https://cdn.taboola.com/libtrc/site/loader.js",
            "https://static.criteo.net/js/ld/publishertag.js",
            "https://ads.pubmatic.com/AdServer/js/pwt/1/pwt.js",
            "https://connect.facebook.net/en_US/fbevents.js",
            "https://script.hotjar.com/modules.js",
            "https://fe-guess.com/img/promo-300x250-a.png",
            "https://fe-guess.com/img/side-banner-ads-2.png"
        )
        for (u in ads) assertTrue("should block $u", real.shouldBlock(u, page, AdBlockEngine.inferType(u, "*/*", false)))
        val fine = listOf(
            "https://fe-guess.com/assets/index-abc123.js",
            "https://fe-guess.com/api/daily",
            "https://www.nytimes.com/games-assets/v2/wordle.js",
            "https://cdn.jsdelivr.net/npm/react@18.3.1/umd/react.production.min.js",
            "https://fonts.googleapis.com/css2?family=Inter",
            "https://fonts.gstatic.com/s/inter/v12/x.woff2",
            "https://www.google.com/recaptcha/api.js",
            "https://unpkg.com/three@0.160.0/build/three.module.js",
            "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg"
        )
        for (u in fine) assertFalse("should allow $u", real.shouldBlock(u, page, AdBlockEngine.inferType(u, "*/*", false)))

        // Speed: a page makes a few hundred requests; checks must be cheap.
        val urls = (ads + fine)
        val n = 20_000
        val t0 = System.nanoTime()
        repeat(n) { i -> real.shouldBlock(urls[i % urls.size] + "&r=$i", page, RequestType.SCRIPT) }
        val perCallUs = (System.nanoTime() - t0) / 1000.0 / n
        println("adblock: ${real.ruleCount} url rules, %.1f µs per check".format(perCallUs))
        assertTrue("too slow: $perCallUs µs", perCallUs < 500)
    }
}
