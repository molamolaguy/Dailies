package com.dailygames.hub

import java.util.Locale

/**
 * Harmless stand-ins for ad / analytics libraries a game may call directly. When the blocker stops
 * one of these scripts, the page gets a tiny script that defines the same globals as no-ops (and runs
 * any queued callbacks), so code like `gtag('event', …)` or `googletag.cmd.push(fn)` keeps working
 * instead of throwing and breaking the game. Same idea as uBlock Origin's "redirect" resources.
 */
object Surrogates {
    /**
     * gtag.js / gtm.js / analytics.js. Besides no-op globals, it runs the callbacks pages wait on before
     * navigating or sharing: `gtag('event', …, {event_callback})`, `dataLayer.push({eventCallback})`
     * (queued before or after it loads) and analytics.js `hitCallback`.
     */
    private val GTAG = """
        (function(){var w=window;
        function later(f,arg){if(typeof f==='function')setTimeout(function(){try{f(arg)}catch(e){}},0)}
        function handle(e){try{if(!e||typeof e!=='object')return;later(e.eventCallback);
        if(e.length>=3&&e[0]==='event'&&e[2]&&typeof e[2]==='object')later(e[2].event_callback)}catch(x){}}
        var dl=w.dataLayer=w.dataLayer||[];if(typeof w.gtag!=='function'){w.gtag=function(){dl.push(arguments)}}
        if(!dl.__dailes){try{Object.defineProperty(dl,'__dailes',{value:true})}catch(e){}
        var op=dl.push;dl.push=function(){for(var i=0;i<arguments.length;i++)handle(arguments[i]);return op.apply(dl,arguments)};
        for(var i=0;i<dl.length;i++)handle(dl[i])}
        try{dl.hide&&dl.hide.end&&dl.hide.end()}catch(e){}
        var tracker={get:function(){return''},set:function(){},send:function(){}};
        if(typeof w.ga!=='function'||!w.ga.loaded){var q=(w.ga&&w.ga.q)||[];
        w.ga=function(){var a=arguments,c=a[a.length-1];if(c&&typeof c==='object')later(c.hitCallback);if(typeof a[0]==='function')later(a[0],tracker)};
        w.ga.create=function(){return tracker};w.ga.getAll=function(){return[tracker]};
        w.ga.getByName=function(){return tracker};w.ga.loaded=true;
        for(var j=0;j<q.length;j++){try{w.ga.apply(null,q[j])}catch(e){}}}})();
    """.trimIndent()

    private val GPT = """
        (function(){var w=window;var n=function(){};var s=function(){return slot};
        var slot={addService:s,clearCategoryExclusions:s,clearTargeting:s,defineSizeMapping:s,get:n,getAdUnitPath:function(){return''},
        getSlotElementId:function(){return''},getTargeting:function(){return[]},getTargetingKeys:function(){return[]},setTargeting:s,
        setCollapseEmptyDiv:s,setForceSafeFrame:s,setClickUrl:s,setCategoryExclusion:s,setSafeFrameConfig:s,getResponseInformation:function(){return null}};
        var pa={addEventListener:function(){return pa},removeEventListener:function(){return pa},enableSingleRequest:function(){return true},
        setTargeting:function(){return pa},clearTargeting:function(){return pa},collapseEmptyDivs:n,disableInitialLoad:n,enableLazyLoad:n,
        refresh:n,clear:n,getSlots:function(){return[]},setRequestNonPersonalizedAds:function(){return pa},setPrivacySettings:function(){return pa},
        setCentering:n,enableVideoAds:n,updateCorrelator:n,getTargeting:function(){return[]},getTargetingKeys:function(){return[]},set:function(){return pa}};
        var gt=w.googletag=w.googletag||{};var q=gt.cmd||[];gt.apiReady=true;gt.pubadsReady=true;
        gt.pubads=function(){return pa};gt.companionAds=function(){return pa};gt.content=function(){return pa};
        gt.defineSlot=function(){return slot};gt.defineOutOfPageSlot=function(){return slot};gt.destroySlots=n;gt.display=n;
        gt.enableServices=n;gt.disablePublisherConsole=n;gt.openConsole=n;gt.setAdIframeTitle=n;gt.sizeMapping=function(){
        var b={addSize:function(){return b},build:function(){return[]}};return b};
        gt.cmd={push:function(f){try{f()}catch(e){}return 1}};
        for(var i=0;i<q.length;i++){try{q[i]()}catch(e){}}})();
    """.trimIndent()

    /** Google IMA (video ads): ad requests fail at once with an error, so games carry on without the ad. */
    private val IMA = """
        (function(){var w=window;if(w.google&&w.google.ima)return;var n=function(){};
        var AdErrorEvent=function(){};AdErrorEvent.Type={AD_ERROR:'adError'};
        AdErrorEvent.prototype.getError=function(){return{getErrorCode:function(){return 1009},getMessage:function(){return'blocked'},
        getType:function(){return'adLoadError'},getVastErrorCode:function(){return 1009},getInnerError:function(){return null},toString:function(){return'AdError 1009'}}};
        AdErrorEvent.prototype.getUserRequestContext=function(){return{}};
        var ImaSdkSettings=function(){};
        ImaSdkSettings.VpaidMode={DISABLED:0,ENABLED:1,INSECURE:2};
        ImaSdkSettings.prototype.setLocale=n;ImaSdkSettings.prototype.setVpaidMode=n;ImaSdkSettings.prototype.setNumRedirects=n;
        ImaSdkSettings.prototype.setPlayerType=n;ImaSdkSettings.prototype.setPlayerVersion=n;
        ImaSdkSettings.prototype.setDisableCustomPlaybackForIOS10Plus=n;ImaSdkSettings.prototype.setAutoPlayAdBreaks=n;
        ImaSdkSettings.prototype.setCookiesEnabled=n;ImaSdkSettings.prototype.setSessionId=n;
        ImaSdkSettings.prototype.getLocale=function(){return'en'};
        var settings=new ImaSdkSettings();
        var AdsLoader=function(){this.l={};this._d=false;this._t=0;this._s=new ImaSdkSettings()};
        AdsLoader.prototype.addEventListener=function(t,f){if(this._d||typeof f!=='function')return;var a=this.l[t]=this.l[t]||[];if(a.indexOf(f)<0)a.push(f)};
        AdsLoader.prototype.removeEventListener=function(t,f){var a=this.l[t];if(!a)return;var i=a.indexOf(f);if(i>=0)a.splice(i,1)};
        AdsLoader.prototype.contentComplete=n;
        AdsLoader.prototype.destroy=function(){this._d=true;this.l={};if(this._t){clearTimeout(this._t);this._t=0}};
        AdsLoader.prototype.getSettings=function(){return this._s};
        AdsLoader.prototype.requestAds=function(){var s=this;if(s._d)return;s._t=setTimeout(function(){s._t=0;if(s._d)return;
        var list=(s.l[AdErrorEvent.Type.AD_ERROR]||[]).slice();list.forEach(function(f){
        try{var e=new AdErrorEvent();e.type=AdErrorEvent.Type.AD_ERROR;f(e)}catch(x){}})},0)};
        var AdDisplayContainer=function(){};AdDisplayContainer.prototype.initialize=n;AdDisplayContainer.prototype.destroy=n;
        w.google=w.google||{};w.google.ima={AdDisplayContainer:AdDisplayContainer,AdsLoader:AdsLoader,AdsRequest:function(){},
        AdsRenderingSettings:function(){},AdErrorEvent:AdErrorEvent,AdError:{ErrorCode:{},Type:{AD_LOAD:'adLoadError',AD_PLAY:'adPlayError'}},
        AdEvent:{Type:{}},AdsManagerLoadedEvent:{Type:{ADS_MANAGER_LOADED:'adsManagerLoaded'}},ViewMode:{NORMAL:'normal',FULLSCREEN:'fullscreen'},
        ImaSdkSettings:ImaSdkSettings,UiElements:{},settings:settings,VERSION:'3.0.0',OmidAccessMode:{}};})();
    """.trimIndent()

    /**
     * Sentry browser SDK / loader: every call is a chainable no-op, `onLoad`, `withScope` and `startSpan`
     * still run their callbacks, and `then` stays undefined so nothing awaits it forever.
     */
    private val SENTRY = """
        (function(){var w=window;if(w.Sentry&&w.Sentry.__dailes)return;if(typeof Proxy!=='function')return;
        var f=function(){return o};var o=new Proxy(f,{get:function(t,k){if(k==='then'||typeof k==='symbol')return undefined;
        if(k in t)return t[k];return o},apply:function(){return o},construct:function(){return o}});
        f.__dailes=true;f.SDK_VERSION='0.0.0';
        f.onLoad=function(cb){if(typeof cb==='function'){try{cb()}catch(e){}}};f.forceLoad=function(){};
        f.withScope=function(cb){if(typeof cb==='function'){try{return cb(o)}catch(e){}}};
        f.startSpan=function(c,cb){return typeof cb==='function'?cb(o):undefined};
        f.startSpanManual=function(c,cb){return typeof cb==='function'?cb(o,function(){}):undefined};
        f.flush=function(){return Promise.resolve(true)};f.close=function(){return Promise.resolve(true)};
        f.captureException=function(){return''};f.captureMessage=function(){return''};f.captureEvent=function(){return''};
        f.lastEventId=function(){return undefined};f.getClient=function(){return undefined};
        var q=(w.Sentry&&w.Sentry._q)||[];w.Sentry=o;
        if(typeof w.sentryOnLoad==='function'){try{w.sentryOnLoad()}catch(e){}}
        for(var i=0;i<q.length;i++){try{var a=q[i];if(a&&a[0]==='onLoad')f.onLoad(a[1])}catch(e){}}})();
    """.trimIndent()

    private val ADSENSE = "(function(){window.adsbygoogle={loaded:true,push:function(){}};})();"

    private fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun pathOf(url: String): String {
        val afterHost = url.indexOf('/', AdBlockEngine.hostStartIndex(url))
        if (afterHost < 0) return ""
        return url.substring(afterHost).substringBefore('?').substringBefore('#')
    }

    /** Script to serve instead of a blocked [url], or null to serve an empty response. */
    fun forUrl(url: String): String? {
        val u = url.lowercase(Locale.US)
        val host = AdBlockEngine.hostOf(u) ?: return null
        val path = pathOf(u)
        return when {
            hostMatches(host, "googletagmanager.com") && (path == "/gtag/js" || path == "/gtm.js" || path.startsWith("/gtag/js/") || path.startsWith("/gtm.js/")) -> GTAG
            hostMatches(host, "google-analytics.com") && (path == "/analytics.js" || path == "/ga.js") -> GTAG
            hostMatches(host, "securepubads.g.doubleclick.net") && (path == "/tag/js/gpt.js" || path.startsWith("/gpt/pubads_impl")) -> GPT
            hostMatches(host, "googletagservices.com") && (path == "/tag/js/gpt.js" || path.startsWith("/gpt/pubads_impl")) -> GPT
            hostMatches(host, "doubleclick.net") && path.startsWith("/gpt/pubads_impl") -> GPT
            hostMatches(host, "imasdk.googleapis.com") && path.startsWith("/js/sdkloader/ima3") && path.endsWith(".js") -> IMA
            hostMatches(host, "pagead2.googlesyndication.com") && (path == "/pagead/js/adsbygoogle.js" || path.endsWith("/show_ads.js")) -> ADSENSE
            hostMatches(host, "sentry-cdn.com") && path.endsWith(".js") -> SENTRY
            else -> null
        }
    }
}
