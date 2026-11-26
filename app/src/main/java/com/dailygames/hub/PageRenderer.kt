package com.dailygames.hub

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders a pasted game link with its scripts run, to read what a single-page app only builds in
 * JavaScript: its title, meta tags, JSON-LD and text, including "How to play" / rules sections that
 * are hidden until opened (1.8.3).
 *
 * The page loads in a WebView placed behind the hub (covered by it, so it lays out and runs normally
 * but is never seen). It has no JavaScript bridge, no file or content access, loads no images, never
 * leaves public http(s) hosts, blocks WebSocket/WebRTC probing, and is destroyed after one read. Main thread only.
 */
class PageRenderer(private val host: ViewGroup) {
    private val main = Handler(Looper.getMainLooper())
    private val active = HashSet<() -> Unit>()

    /** Cancels and destroys any in-flight render (e.g. from HubActivity.onDestroy). */
    fun cancel() {
        val copy = active.toList()
        active.clear()
        copy.forEach { runCatching { it() } }
    }

    /**
     * Loads [url] and calls [done] once with what the page shows ([EXTRACT_JS]'s JSON), or null if it
     * couldn't be read. Reads [SETTLE_MS] after the page finished loading, and never later than [timeoutMs].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun render(url: String, timeoutMs: Long, done: (JSONObject?) -> Unit) {
        var finished = false
        val token = Any()
        val hostCache = ConcurrentHashMap<String, Boolean>()
        lateinit var wv: WebView
        lateinit var cancelThis: () -> Unit
        fun finish(json: JSONObject?) {
            if (finished) return
            finished = true
            active.remove(cancelThis)
            main.removeCallbacksAndMessages(token)
            runCatching { done(json) }
            runCatching {
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        fun isAllowedUrl(rawUrl: String?, hostStr: String?): Boolean {
            if (rawUrl.isNullOrBlank()) return false
            val key = hostStr?.trim('[', ']')?.lowercase(Locale.US)?.trimEnd('.').orEmpty()
            if (key.isEmpty()) return false
            return hostCache.getOrPut(key) { SafeHttp.isAllowedForScrape(rawUrl) }
        }
        cancelThis = { finish(null) }
        fun read() {
            if (finished) return
            runCatching {
                wv.evaluateJavascript(EXTRACT_JS) { raw ->
                    // evaluateJavascript hands back the returned string JSON-encoded.
                    val json = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
                    finish(json?.takeIf { it.length() > 0 })
                }
            }.onFailure { finish(null) }
        }
        try {
            wv = WebView(host.context)
        } catch (e: Throwable) {
            done(null)
            return
        }
        active.add(cancelThis)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            blockNetworkImage = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        wv.isFocusable = false
        wv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val docStartInstalled = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, DISABLE_NET_APIS_JS, setOf("*")) }.isSuccess
        } else {
            false
        }
        wv.webViewClient = object : WebViewClient() {
            private var loads = 0
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                val scheme = u.scheme?.lowercase(Locale.US)
                if (scheme != "http" && scheme != "https") return true
                return !isAllowedUrl(u.toString(), u.host)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return blockedResponse()
                val scheme = u.scheme?.lowercase(Locale.US)
                if (scheme != "http" && scheme != "https") return blockedResponse()
                if (!isAllowedUrl(u.toString(), u.host)) {
                    if (request.isForMainFrame) main.post { finish(null) }
                    return blockedResponse()
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loads += 1
                if (!docStartInstalled) {
                    runCatching { view?.evaluateJavascript(DISABLE_NET_APIS_JS, null) }
                }
            }

            /** The page crashed its renderer (or it was stopped for memory): give up on it, never crash. */
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                main.post { finish(null) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // A redirect chain finishes several pages: read once the last one has settled.
                val at = loads
                main.postAtTime({ if (at == loads) read() }, token, android.os.SystemClock.uptimeMillis() + SETTLE_MS)
            }
        }
        // Behind everything in the hub's layout, at the screen's size (pages lay out for a phone).
        runCatching { host.addView(wv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        val now = android.os.SystemClock.uptimeMillis()
        main.postAtTime({ read() }, token, now + timeoutMs)
        // A read that never answers (a hung page) still ends.
        main.postAtTime({ finish(null) }, token, now + timeoutMs + 1_500)
        runCatching { wv.loadUrl(url) }.onFailure { finish(null) }
    }

    private fun blockedResponse() =
        WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        /** Wait after the page finished loading, for a single-page app to build its screen. */
        const val SETTLE_MS = 2_000L

        private const val DISABLE_NET_APIS_JS = """(function(){try{
var nope=function(){throw new Error('Blocked');};
if(typeof window!=='undefined'){
try{Object.defineProperty(window,'WebSocket',{value:nope,configurable:false});}catch(e){window.WebSocket=nope;}
try{Object.defineProperty(window,'RTCPeerConnection',{value:nope,configurable:false});}catch(e){window.RTCPeerConnection=nope;}
try{Object.defineProperty(window,'webkitRTCPeerConnection',{value:nope,configurable:false});}catch(e){window.webkitRTCPeerConnection=nope;}
}
}catch(e){}})();"""

        /**
         * Runs in the rendered page: its title and address, meta tags (first of each), JSON-LD blocks,
         * and text: what's on screen plus help / rules / "how to play" sections that are often hidden
         * until opened.
         */
        const val EXTRACT_JS = """(function(){try{
var q=function(s){try{return Array.prototype.slice.call(document.querySelectorAll(s));}catch(e){return [];}};
var meta={};q('meta[name],meta[property]').forEach(function(m){var k=(m.getAttribute('property')||m.getAttribute('name')||'').toLowerCase();if(k&&!(k in meta))meta[k]=String(m.getAttribute('content')||'').slice(0,600);});
var ld=q('script[type="application/ld+json"]').slice(0,5).map(function(s){return String(s.textContent||'').slice(0,20000);});
var lines=function(el){var out=[],w=document.createTreeWalker(el,NodeFilter.SHOW_TEXT),n;while((n=w.nextNode())&&out.length<200){var t=String(n.nodeValue||'').trim();if(t&&!/^(script|style|noscript)$/i.test(n.parentNode&&n.parentNode.nodeName))out.push(t);}return out.join('\n');};
var help=q('[id*="help" i],[class*="help" i],[id*="how" i],[class*="how-to" i],[class*="howto" i],[id*="rules" i],[class*="rules" i],[id*="instructions" i],[class*="instructions" i],[id*="tutorial" i],[class*="tutorial" i]').slice(0,8).map(lines).join('\n');
var text=((document.body&&document.body.innerText)||'')+'\nHow to play\n'+help;
var frames=q('iframe[src]').filter(function(f){var r=f.getBoundingClientRect();return /^https?:/i.test(f.src)&&r.width>=150&&r.height>=150;}).slice(0,8).map(function(f){return String(f.src).slice(0,500);});
return JSON.stringify({title:document.title||'',url:location.href,meta:meta,ld:ld,frames:frames,text:text.replace(/[ \t ]+/g,' ').replace(/\n\s*\n+/g,'\n').slice(0,40000)});
}catch(e){return '{}';}})()"""
    }
}
