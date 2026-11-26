package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurrogatesTest {
    @Test
    fun knownLibrariesGetAStandIn() {
        val gtag = Surrogates.forUrl("https://www.googletagmanager.com/gtag/js?id=G-XYZ")
        assertNotNull(gtag)
        assertEquals(gtag, Surrogates.forUrl("https://www.googletagmanager.com/gtm.js?id=GTM-1"))
        assertEquals(gtag, Surrogates.forUrl("https://www.google-analytics.com/analytics.js"))
        assertTrue("gtag stand-in runs event callbacks", gtag!!.contains("event_callback") && gtag.contains("eventCallback"))
        assertNotNull(Surrogates.forUrl("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
        assertNotNull(Surrogates.forUrl("https://imasdk.googleapis.com/js/sdkloader/ima3.js"))
        assertNotNull(Surrogates.forUrl("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-1"))
        val sentry = Surrogates.forUrl("https://browser.sentry-cdn.com/8.30.0/bundle.tracing.min.js")
        assertNotNull(sentry)
        assertEquals(sentry, Surrogates.forUrl("https://js.sentry-cdn.com/0123abcd.min.js"))
        assertTrue(sentry!!.contains("onLoad"))
    }

    @Test
    fun otherUrlsGetNone() {
        assertNull(Surrogates.forUrl("https://example.com/ga.js"))
        assertNull(Surrogates.forUrl("https://sentry-cdn.com.evil.net/x.png"))
        assertNull(Surrogates.forUrl("https://ads.example.com/banner.js"))
        assertNull(Surrogates.forUrl("https://example.com/app.js?ref=https://www.googletagmanager.com/gtag/js"))
        // Spoofed hostnames must not match surrogate scripts via substring matching
        assertNull(Surrogates.forUrl("https://evil.com/googletagmanager.com/gtag/js"))
        assertNull(Surrogates.forUrl("https://evil-googletagmanager.com/gtag/js"))
        assertNull(Surrogates.forUrl("https://evil.com/tag/js/gpt.js"))
        assertNull(Surrogates.forUrl("https://not-imasdk.googleapis.com/js/sdkloader/ima3.js"))
        assertNull(Surrogates.forUrl("https://not-sentry-cdn.com/8.30.0/bundle.js"))
        assertNull(Surrogates.forUrl("https://evil.com/pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
    }

    @Test
    fun gtagPassesTrackerStubToGaReadyCallback() {
        val gtag = Surrogates.forUrl("https://www.google-analytics.com/analytics.js")!!
        assertTrue(gtag.contains("var tracker={get:function(){return''}"))
        assertTrue(gtag.contains("later(a[0],tracker)"))
        assertTrue(gtag.contains("w.ga.getAll=function(){return[tracker]}"))
        assertTrue(gtag.contains("w.ga.getByName=function(){return tracker}"))
    }

    @Test
    fun imaProvidesImaSdkSettingsConstructorAndSupportsCancellation() {
        val ima = Surrogates.forUrl("https://imasdk.googleapis.com/js/sdkloader/ima3.js")!!
        assertTrue(ima.contains("var ImaSdkSettings=function(){}"))
        assertTrue(ima.contains("ImaSdkSettings.VpaidMode="))
        assertTrue(ima.contains("new ImaSdkSettings()"))
        assertTrue(ima.contains("AdsLoader.prototype.removeEventListener=function("))
        assertTrue(ima.contains("AdsLoader.prototype.destroy=function("))
        assertTrue(ima.contains("clearTimeout(this._t)"))
    }
}
