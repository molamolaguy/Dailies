package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CosmeticFiltersTest {
    private val filters = CosmeticFilters(
        genericLines = sequenceOf(".ad-banner", "#ad_slot", "div[id^=\"google_ads_\"]", ".sponsored"),
        specificLines = sequenceOf("news.com,blog.org\t.news-ad", "other.com\t.x"),
        exceptionLines = sequenceOf("game.com\t.sponsored", "news.com\tdiv[id^=\"google_ads_\"]")
    )

    @Test
    fun pageCssHasComplexGenericAndSiteSpecificMinusExceptions() {
        val news = filters.pageCss("www.news.com")
        assertTrue(news.contains(".news-ad{display:none!important}"))
        assertFalse("exception on news.com", news.contains("google_ads_"))
        assertFalse("other site's rule", news.contains(".x{"))
        assertTrue(filters.pageCss("game.com").contains("div[id^=\"google_ads_\"]{display:none!important}"))
    }

    @Test
    fun tokenLookupOnlyReturnsKnownSimpleSelectors() {
        val css = filters.cssForTokens("game.com", listOf(".ad-banner", "#ad_slot", ".game-board", ".sponsored"))
        assertTrue(css.contains(".ad-banner{"))
        assertTrue(css.contains("#ad_slot{"))
        assertFalse(css.contains(".game-board"))
        assertFalse("excepted on game.com", css.contains(".sponsored"))
        assertTrue(filters.cssForTokens("elsewhere.com", listOf(".sponsored")).contains(".sponsored{"))
    }

    @Test
    fun negatedDomainsNoHideSitesAndFrames() {
        val f = CosmeticFilters(
            genericLines = sequenceOf(".gen", "div.complex > .ad"),
            specificLines = sequenceOf("example.com,~m.example.com\t.site-ad", "~skip.org\t.mostly", "~skip.org\tdiv.mostly-complex"),
            exceptionLines = emptySequence(),
            noHideLines = sequenceOf("nogeneric.com\tgeneric", "nothing.com\tall")
        )
        assertTrue(f.pageCss("www.example.com").contains(".site-ad{"))
        assertFalse(f.pageCss("m.example.com").contains(".site-ad{"))
        assertTrue(f.cssForTokens("a.com", listOf(".mostly")).contains(".mostly{"))
        assertFalse(f.cssForTokens("skip.org", listOf(".mostly")).contains(".mostly{"))
        assertTrue(f.pageCss("a.com").contains("div.mostly-complex{"))
        assertFalse(f.pageCss("www.skip.org").contains("div.mostly-complex{"))
        assertFalse("frames get no complex generic rules", f.pageCss("a.com", includeComplexGeneric = false).contains("div.complex"))
        assertFalse(f.pageCss("nogeneric.com").contains("div.complex"))
        assertEquals("", f.cssForTokens("nogeneric.com", listOf(".gen")))
        assertEquals("", f.pageCss("www.nothing.com"))
    }

    @Test
    fun realListsLoadAndHideCommonAdContainers() {
        val dir = File("src/main/assets/adblock")
        val real = CosmeticFilters(
            dir.resolve("cosmetic_generic.txt").readLines().asSequence(),
            dir.resolve("cosmetic_specific.txt").readLines().asSequence(),
            dir.resolve("cosmetic_except.txt").readLines().asSequence()
        )
        val css = real.cssForTokens("fe-guess.com", listOf(".game-board", "#root", ".ad-slot", "#ad_slot"))
        assertTrue(css.contains(".ad-slot{"))
        assertTrue(css.contains("#ad_slot{"))
        assertTrue("AdSense slots", real.pageCss("fe-guess.com").contains("ins.adsbygoogle[data-ad-client]{"))
        assertFalse(css.contains(".game-board"))
        assertFalse(css.contains("#root{"))
        assertTrue(real.pageCss("fe-guess.com").length > 1000)
        println("cosmetic: ${real.ruleCount} rules, page css ${real.pageCss("fe-guess.com").length} chars")
    }
}
