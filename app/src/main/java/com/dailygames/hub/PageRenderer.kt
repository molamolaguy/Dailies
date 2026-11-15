package com.dailygames.hub

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders a pasted game link with its scripts run, to read what a single-page app only builds in
 * JavaScript: its title, meta tags, JSON-LD and text, including "How to play" / rules sections that
 * are hidden until opened (1.8.3).
 *
 * The page loads in a WebView placed behind the hub (covered by it, so it lays out and runs normally
 * but is never seen). It has no JavaScript bridge, no file or content access, loads no images, never
 * leaves http(s) and is destroyed after one read. Main thread only.
 */
class PageRenderer(private val host: ViewGroup) {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Loads [url] and calls [done] once with what the page shows ([EXTRACT_JS]'s JSON), or null if it
     * couldn't be read. Reads [SETTLE_MS] after the page finished loading, and never later than [timeoutMs].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun render(url: String, timeoutMs: Long, done: (JSONObject?) -> Unit) {
        var finished = false
        lateinit var wv: WebView
        fun finish(json: JSONObject?) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            runCatching { done(json) }
            runCatching {
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
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
        wv.webViewClient = object : WebViewClient() {
            private var loads = 0
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loads += 1
            }

            /** The page crashed its renderer (or it was stopped for memory): give up on it, never crash. */
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                main.post { finish(null) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // A redirect chain finishes several pages: read once the last one has settled.
                val at = loads
                main.postDelayed({ if (at == loads) read() }, SETTLE_MS)
            }
        }
        // Behind everything in the hub's layout, at the screen's size (pages lay out for a phone).
        runCatching { host.addView(wv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        main.postDelayed({ read() }, timeoutMs)
        // A read that never answers (a hung page) still ends.
        main.postDelayed({ finish(null) }, timeoutMs + 1_500)
        runCatching { wv.loadUrl(url) }.onFailure { finish(null) }
    }

    companion object {
        /** Wait after the page finished loading, for a single-page app to build its screen. */
        const val SETTLE_MS = 2_000L

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
return JSON.stringify({title:document.title||'',url:location.href,meta:meta,ld:ld,text:text.replace(/[ \t ]+/g,' ').replace(/\n\s*\n+/g,'\n').slice(0,40000)});
}catch(e){return '{}';}})()"""
    }
}
