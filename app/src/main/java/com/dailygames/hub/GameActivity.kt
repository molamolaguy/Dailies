package com.dailygames.hub

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

/**
 * Full-screen Material 3 Expressive game player with Edge-to-Edge display, Scaffold window insets
 * (including IME soft keyboard insets), Predictive Back support, and Android 12+ Dynamic Wallpaper colors.
 */
class GameActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var gameId = ""
    private var gameUrl = ""
    /** Enabled only while there is page history or fullscreen to undo (predictive back, K1). */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = handleGameBackPress()
    }
    private lateinit var container: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var openLogOnReturn = false
    private var foregroundMs = 0L
    private var resumedAt = 0L
    private var gameName = ""
    /** The game's own host: per-site ad-blocking choices are keyed on the game, not wherever it navigated. */
    private var gameHost = ""

    // ---- Share-result capture (layer 1: clipboard listener, layer 2: page share/copy hooks) ----
    private var openedAt = System.currentTimeMillis()
    private var capturedShare: String? = null
    private var capturedAt = 0L
    private var autoLogEnabled = true
    private var clipboardManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }
    private lateinit var banner: TextView
    private val hideBanner = Runnable { if (::banner.isInitialized) banner.animate().alpha(0f).setDuration(220).withEndAction { banner.visibility = View.GONE } }

    // ---- Ad blocking ----
    private var blockAdsSetting = true
    @Volatile private var adBlockActive = false
    @Volatile private var pageHost: String? = null
    private val blockedCount = AtomicInteger(0)
    private val shieldUpdatePending = AtomicBoolean(false)
    private var shieldPill: TextView? = null
    private var shieldColors = intArrayOf(0, 0)
    private val cosmeticExecutor = Executors.newSingleThreadExecutor()

    // ---- Upload pickers (Activity Result API). Images/videos use the system Photo Picker, which needs
    // no storage permission; any other file type uses the Storage Access Framework document picker.
    private val pickOneMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        deliverFiles(listOfNotNull(uri))
    }
    private val pickManyMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_UPLOADS)) { uris ->
        deliverFiles(uris)
    }
    private val pickOneDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        deliverFiles(listOfNotNull(uri))
    }
    private val pickManyDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        deliverFiles(uris)
    }
    /** Bundled Google Sans Flex (bold instance, res/font, SIL OFL) so the bar matches the hub on every phone. */
    private val googleSansBold: Typeface by lazy {
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.google_sans_bold) }.getOrNull()
            ?: Typeface.DEFAULT_BOLD
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gameId = intent.getStringExtra(EXTRA_ID).orEmpty()
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Daily Game" }
        gameUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

        if (!gameUrl.startsWith("https://", true) && !gameUrl.startsWith("http://", true)) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            openedAt = savedInstanceState.getLong(STATE_OPENED_AT, openedAt)
            capturedShare = savedInstanceState.getString(STATE_SHARE_TEXT)
            capturedAt = savedInstanceState.getLong(STATE_SHARE_AT, 0L)
            foregroundMs = savedInstanceState.getLong(STATE_FOREGROUND_MS, 0L)
            openLogOnReturn = savedInstanceState.getBoolean(STATE_OPEN_LOG, false)
        }
        autoLogEnabled = GamePrefs.autoLogShare(this)
        blockAdsSetting = GamePrefs.blockAds(this)
        PublicSuffix.ensureLoaded(this) // so per-site choices use the same "site" as the blocker
        gameHost = Uri.parse(gameUrl).host?.lowercase(Locale.US).orEmpty()
        pageHost = gameHost.ifEmpty { null }
        adBlockActive = blockAdsSetting && !GamePrefs.isSiteAllowed(this, gameHost)
        if (blockAdsSetting) AdBlocker.warmUp(this)

        setResultWithLog(openLogOnReturn)

        // Enable Edge-to-Edge display so the player respects phone UI & system bars via Scaffold insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Extract device wallpaper Material You dynamic colors on Android 12+ (API 31+)
        // Same Display settings as the hub: AMOLED = true black bar and page backdrop.
        val amoled = ThemePrefs.isAmoled(this)
        val isDynamic = ThemePrefs.isDynamic(this)
        val surfaceColor = ThemePrefs.surfaceColor(this)
        val containerColor = when {
            amoled -> Color.BLACK
            isDynamic -> getColor(android.R.color.system_neutral2_800)
            else -> CONTAINER
        }
        val primaryColor = if (isDynamic) getColor(android.R.color.system_accent1_200) else PRIMARY
        val onPrimaryColor = if (isDynamic) getColor(android.R.color.system_accent1_800) else ON_PRIMARY
        val primaryContainerColor = if (isDynamic) getColor(android.R.color.system_accent1_700) else PRIMARY_CONTAINER
        val onPrimaryContainerColor = if (isDynamic) getColor(android.R.color.system_accent1_100) else ON_PRIMARY_CONTAINER

        // ---- Top app bar (Material 3 Expressive tonal pills + Google Sans) ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(containerColor)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(pill("Hub", "Back to hub", primaryContainerColor, onPrimaryContainerColor, R.drawable.ic_bar_arrow_back) { finish() })
        bar.addView(TextView(this).apply {
            text = gameName
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = googleSansBold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(4)
            }
        })
        bar.addView(pill("", "Reload", primaryContainerColor, onPrimaryContainerColor, R.drawable.ic_bar_refresh) { webView.reload() })
        bar.addView(pill("", "Open in browser", primaryContainerColor, onPrimaryContainerColor, R.drawable.ic_bar_open_in_new) {
            openExternal(Uri.parse(webView.url ?: gameUrl))
        })
        if (blockAdsSetting) {
            shieldColors = intArrayOf(primaryContainerColor, onPrimaryContainerColor)
            shieldPill = pill("", "Ad blocking", primaryContainerColor, onPrimaryContainerColor,
                if (adBlockActive) R.drawable.ic_bar_shield else R.drawable.ic_bar_shield_off) { toggleSiteAdBlocking() }
            bar.addView(shieldPill)
        }
        bar.addView(pill("Log", "Log today's result", primaryColor, onPrimaryColor, R.drawable.ic_bar_check) {
            setResultWithLog(true)
            finish()
        })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(primaryColor)
            progressBackgroundTintList = ColorStateList.valueOf(containerColor)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }

        webView = WebView(this).apply {
            setBackgroundColor(surfaceColor)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // With ad blocking on, scripts can't open pop-ups/pop-unders without a tap.
            settings.javaScriptCanOpenWindowsAutomatically = !adBlockActive
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    val scheme = uri.scheme?.lowercase(Locale.US)
                    if (scheme == "http" || scheme == "https") {
                        // Ad redirects / pop-unders: a navigation nobody tapped, to a known ad domain.
                        if (adBlockActive && !request.hasGesture()) {
                            val engine = AdBlocker.network(0)
                            if (engine != null && engine.shouldBlock(uri.toString(), pageHost, AdBlockEngine.RequestType.SUBDOCUMENT)) {
                                onRequestBlocked()
                                return true
                            }
                        }
                        return false
                    }
                    // In-page schemes the WebView handles itself (about:blank frames, blob:/data: documents).
                    if (scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") return false
                    // Ads like to bounce to the Play Store / intent:// links on their own; only follow taps.
                    if (adBlockActive && !request.hasGesture()) {
                        onRequestBlocked()
                        return true
                    }
                    if (scheme == "intent") openIntentUri(uri.toString()) else openExternal(uri)
                    return true
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    request?.let { filterRequest(it) }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    this@GameActivity.progress.visibility = View.VISIBLE
                    // Commit the new page's host now (main-frame requests only record it as pending, so a
                    // cancelled navigation can't change which site the blocker treats as first-party).
                    val started = url?.let { Uri.parse(it).host }?.lowercase(Locale.US)
                    pageHost = started ?: pendingPageHost ?: pageHost
                    pendingPageHost = null
                    if (!documentStartScriptsSupported) injectPageScriptsFallback()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    this@GameActivity.progress.visibility = View.GONE
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    updateBackIntercept()
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // Prevent app crash if the heavy game WebView renderer process is killed (K5).
                    (view?.parent as? ViewGroup)?.removeView(view)
                    runCatching { view?.destroy() }
                    if (!isFinishing) finish()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@GameActivity.progress.progress = newProgress
                    this@GameActivity.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                // Fullscreen <video> / canvas games (requestFullscreen()).
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) return
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    container.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                    updateBackIntercept()
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }

                // <input type="file"> (e.g. uploading an avatar or a puzzle image).
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    return try {
                        launchUploadPicker(fileChooserParams)
                        true
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        false
                    }
                }
            }
            // Downloads (share images, exported results): web links go to the browser / download manager;
            // blob: and data: files (made by the page itself) are saved through the system file picker.
            setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
                when {
                    url.startsWith("http://", true) || url.startsWith("https://", true) -> openExternal(Uri.parse(url))
                    url.startsWith("data:", true) -> saveDataUrl(url, contentDisposition, mimeType)
                    url.startsWith("blob:", true) -> fetchBlobForSave(url, contentDisposition, mimeType)
                }
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress)
            addView(webView)
        }
        banner = TextView(this).apply {
            setTextColor(onPrimaryContainerColor)
            textSize = 13f
            typeface = googleSansBold
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(primaryContainerColor) }
            elevation = dp(6).toFloat()
            visibility = View.GONE
            androidx.core.content.ContextCompat.getDrawable(this@GameActivity, R.drawable.ic_bar_check)?.mutate()?.let { icon ->
                icon.setBounds(0, 0, dp(18), dp(18))
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawableTintList = ColorStateList.valueOf(onPrimaryContainerColor)
                compoundDrawablePadding = dp(8)
            }
        }
        container = FrameLayout(this).apply {
            setBackgroundColor(surfaceColor)
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28); marginStart = dp(16); marginEnd = dp(16) })
        }
        setUpPageHooks()
        installServiceWorkerFilter(this)

        // Scaffold Window Insets listener: includes both systemBars/cutout AND IME keyboard insets (K2).
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            bar.setPadding(
                dp(8) + sysBars.left,
                dp(8) + sysBars.top,
                dp(8) + sysBars.right,
                dp(8)
            )
            root.setPadding(0, 0, 0, maxOf(sysBars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surfaceColor))
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, backCallback)

        // restoreState returns null if the saved WebView state is missing or unusable: start fresh then.
        val restored = savedInstanceState != null && runCatching { webView.restoreState(savedInstanceState) }.getOrNull() != null
        if (!restored) webView.loadUrl(gameUrl)
        updateBackIntercept()
    }

    // =====================================================================================
    // Share-result capture
    // =====================================================================================

    private var documentStartScriptsSupported = false

    /** Layer 2: page scripts (document start) + origin-scoped message channels (androidx.webkit). */
    private fun setUpPageHooks() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val anyOrigin = setOf("*")
        try {
            if (autoLogEnabled) {
                WebViewCompat.addWebMessageListener(webView, "DailesShare", anyOrigin) { _, message, sourceOrigin, isMainFrame, _ ->
                    val scheme = sourceOrigin.scheme?.lowercase(Locale.US)
                    if (scheme != "http" && scheme != "https") return@addWebMessageListener
                    // Only the game page itself (not ad / embedded frames, not another site it navigated to).
                    val src = sourceOrigin.host?.lowercase(Locale.US) ?: return@addWebMessageListener
                    if (!isMainFrame || gameHost.isEmpty() || !AdBlockEngine.sameSite(src, gameHost)) return@addWebMessageListener
                    val text = runCatching { JSONObject(message.data ?: "").optString("text") }.getOrNull()
                    offerShare(text, "page")
                }
            }
            if (blockAdsSetting) {
                WebViewCompat.addWebMessageListener(webView, "DailesAdBlock", anyOrigin) { _, message, sourceOrigin, isMainFrame, reply ->
                    if (!adBlockActive) {
                        // Tell the page script to stop scanning (blocking is off for this site).
                        runCatching { reply.postMessage("{\"disabled\":true}") }
                        return@addWebMessageListener
                    }
                    val host = sourceOrigin.host?.lowercase(Locale.US) ?: return@addWebMessageListener
                    val data = message.data ?: return@addWebMessageListener
                    if (data.length > MAX_COSMETIC_MESSAGE) return@addWebMessageListener
                    cosmeticExecutor.execute {
                        val css = cosmeticCssFor(host, data, isMainFrame) ?: return@execute
                        runOnUiThread { runCatching { reply.postMessage(JSONObject().put("css", css).toString()) } }
                    }
                }
            }
            WebViewCompat.addWebMessageListener(webView, "DailesDownload", anyOrigin) { _, message, _, _, _ ->
                onBlobDownloadMessage(message.data)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                if (autoLogEnabled) WebViewCompat.addDocumentStartJavaScript(webView, pageScript("game/share-hook.js"), anyOrigin)
                if (blockAdsSetting) WebViewCompat.addDocumentStartJavaScript(webView, pageScript("game/cosmetic.js"), anyOrigin)
                documentStartScriptsSupported = true
            }
        } catch (e: Exception) {
            // Older WebView: the clipboard listener (layer 1) still captures results.
        }
    }

    /** Fallback for WebViews without document-start scripts: inject as early as onPageStarted. */
    private fun injectPageScriptsFallback() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        if (autoLogEnabled) webView.evaluateJavascript(pageScript("game/share-hook.js"), null)
        if (blockAdsSetting && adBlockActive) webView.evaluateJavascript(pageScript("game/cosmetic.js"), null)
    }

    private val scriptCache = HashMap<String, String>()
    private fun pageScript(path: String): String =
        scriptCache.getOrPut(path) { assets.open(path).bufferedReader().use { it.readText() } }

    /** Layer 1: anything copied while the game is on screen (Android only reports it to the foreground app). */
    private fun onClipboardChanged() {
        if (!autoLogEnabled) return
        val text = runCatching {
            clipboardManager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        offerShare(text, "clipboard")
    }

    /** Keeps the latest text that passes the strict share-result check; it is logged back in the hub. */
    private fun offerShare(text: String?, @Suppress("UNUSED_PARAMETER") via: String) {
        if (!autoLogEnabled || text.isNullOrBlank()) return
        val clean = text.replace("\r\n", "\n").trim()
        if (clean == capturedShare) return
        if (!ShareResultDetector.isStrictShareResult(clean, gameName, gameUrl)) return
        capturedShare = clean
        capturedAt = System.currentTimeMillis()
        setResultWithLog(openLogOnReturn)
        showBanner(getString(R.string.share_captured))
    }

    private fun showBanner(text: String) {
        if (!::banner.isInitialized) return
        banner.removeCallbacks(hideBanner)
        banner.text = text
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(180).start()
        banner.postDelayed(hideBanner, 3500)
    }

    // =====================================================================================
    // Ad blocking
    // =====================================================================================

    private fun filterRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        if (request.isForMainFrame) {
            url.host?.let { pendingPageHost = it.lowercase(Locale.US) }
            return null
        }
        if (!adBlockActive) return null
        val scheme = url.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        val engine = AdBlocker.network() ?: return null
        val s = url.toString()
        val headers = request.requestHeaders.orEmpty()
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        val requestedWith = headers.entries.firstOrNull { it.key.equals("X-Requested-With", true) }?.value
        val type = AdBlockEngine.inferType(s, accept, false, requestedWith)
        if (!engine.shouldBlock(s, pageHost, type)) return null
        onRequestBlocked()
        // Ad libraries a game may call directly get a harmless stand-in so the game doesn't stall.
        Surrogates.forUrl(s)?.let { js -> return scriptResponse(js) }
        return emptyResponse()
    }

    private fun onRequestBlocked() {
        blockedCount.incrementAndGet()
        if (shieldUpdatePending.compareAndSet(false, true)) {
            webView.postDelayed({
                shieldUpdatePending.set(false)
                shieldPill?.text = if (adBlockActive) blockedCount.get().toString() else ""
                shieldPill?.compoundDrawablePadding = if (shieldPill?.text.isNullOrEmpty()) 0 else dp(6)
            }, 250)
        }
    }

    /** Origins already sent their page CSS in this page load (a frame reloading itself can't spam it). */
    private val helloAnswered = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun cosmeticCssFor(host: String, data: String, isMainFrame: Boolean): String? {
        val filters = AdBlocker.cosmetics() ?: return null
        return runCatching {
            val m = JSONObject(data)
            when (m.optString("t")) {
                "hello" -> if (helloAnswered.add(host + "|" + isMainFrame)) filters.pageCss(host, includeComplexGeneric = isMainFrame) else null
                "tokens" -> {
                    val arr: JSONArray = m.optJSONArray("tokens") ?: return@runCatching null
                    val n = minOf(arr.length(), MAX_TOKENS_PER_BATCH)
                    val tokens = ArrayList<String>(n)
                    for (i in 0 until n) tokens.add(arr.optString(i))
                    filters.cssForTokens(host, tokens)
                }
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Shield button: allow ads on this site (e.g. if blocking breaks it) or turn blocking back on. */
    private fun toggleSiteAdBlocking() {
        val host = gameHost.ifEmpty { return }
        val nowActive = !adBlockActive
        GamePrefs.setSiteAllowed(this, host, !nowActive)
        adBlockActive = nowActive
        webView.settings.javaScriptCanOpenWindowsAutomatically = !nowActive
        blockedCount.set(0)
        helloAnswered.clear()
        shieldPill?.let { p ->
            val icon = androidx.core.content.ContextCompat.getDrawable(
                this, if (nowActive) R.drawable.ic_bar_shield else R.drawable.ic_bar_shield_off
            )?.mutate()
            icon?.setBounds(0, 0, dp(20), dp(20))
            p.setCompoundDrawablesRelative(icon, null, null, null)
            p.text = ""
            p.compoundDrawablePadding = 0
        }
        showBanner(getString(if (nowActive) R.string.adblock_on_site else R.string.adblock_off_site, AdBlockEngine.siteOf(host)))
        webView.reload()
    }

    private fun updateBackIntercept() {
        backCallback.isEnabled = customView != null || (::webView.isInitialized && webView.canGoBack())
    }

    private fun handleGameBackPress() {
        when {
            customView != null -> exitFullscreen()
            ::webView.isInitialized && webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        container.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        updateBackIntercept()
    }

    /** Picks the best system picker for the page's `accept` attribute and `multiple` flag. */
    private fun launchUploadPicker(params: WebChromeClient.FileChooserParams?) {
        val accept = params?.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
        val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val allImages = accept.isNotEmpty() && accept.all { it.startsWith("image/") || it in IMAGE_EXTENSIONS }
        val allVideos = accept.isNotEmpty() && accept.all { it.startsWith("video/") }
        val allMedia = accept.isNotEmpty() && accept.all { it.startsWith("image/") || it.startsWith("video/") || it in IMAGE_EXTENSIONS }
        val mediaType = when {
            allImages -> ActivityResultContracts.PickVisualMedia.ImageOnly
            allVideos -> ActivityResultContracts.PickVisualMedia.VideoOnly
            allMedia -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            else -> null
        }
        if (mediaType != null) {
            val request = PickVisualMediaRequest.Builder().setMediaType(mediaType).build()
            if (multiple) pickManyMedia.launch(request) else pickOneMedia.launch(request)
            return
        }
        val mimeTypes = accept.filter { it.contains('/') }.ifEmpty { listOf("*/*") }.toTypedArray()
        if (multiple) pickManyDocuments.launch(mimeTypes) else pickOneDocument.launch(mimeTypes)
    }

    private fun deliverFiles(uris: List<Uri>) {
        fileChooserCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        fileChooserCallback = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
        outState.putLong(STATE_OPENED_AT, openedAt)
        capturedShare?.let { outState.putString(STATE_SHARE_TEXT, it) }
        outState.putLong(STATE_SHARE_AT, capturedAt)
        outState.putLong(STATE_FOREGROUND_MS, foregroundMs + if (resumedAt > 0) SystemClock.elapsedRealtime() - resumedAt else 0L)
        outState.putBoolean(STATE_OPEN_LOG, openLogOnReturn)
    }

    override fun onPause() {
        super.onPause()
        if (resumedAt > 0) foregroundMs += SystemClock.elapsedRealtime() - resumedAt
        resumedAt = 0L
        // Keep the result current: if the system kills the app in the background, the hub still
        // receives the play time and any captured share text.
        if (gameId.isNotEmpty()) setResultWithLog(openLogOnReturn)
        runCatching { clipboardManager?.removePrimaryClipChangedListener(clipListener) }
        if (::webView.isInitialized) webView.onPause()
        // Flush cookies so in-game logins and progress are persisted to disk immediately (K6).
        runCatching { CookieManager.getInstance().flush() }
    }

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.elapsedRealtime()
        activeFilter = WeakReference(this)
        if (autoLogEnabled) {
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            runCatching { clipboardManager?.addPrimaryClipChangedListener(clipListener) }
        }
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (activeFilter.get() === this) activeFilter = WeakReference(null)
        cosmeticExecutor.shutdownNow()
        if (::banner.isInitialized) banner.removeCallbacks(hideBanner)
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun setResultWithLog(openLog: Boolean) {
        openLogOnReturn = openLog
        val played = foregroundMs + if (resumedAt > 0) SystemClock.elapsedRealtime() - resumedAt else 0L
        val data = Intent().putExtra(EXTRA_ID, gameId).putExtra(EXTRA_LOG, openLog).putExtra(EXTRA_ELAPSED_MS, played)
            .putExtra(EXTRA_OPENED_AT, openedAt)
        capturedShare?.let { data.putExtra(EXTRA_SHARE_TEXT, it) }
        setResult(RESULT_OK, data)
    }

    /** Every exit path (← Hub, ✓ Log, Back, renderer crash) reports how long the game was on screen. */
    override fun finish() {
        if (gameId.isNotEmpty()) setResultWithLog(openLogOnReturn)
        super.finish()
    }

    @Volatile private var pendingPageHost: String? = null

    /**
     * `intent://` links (e.g. "open in app"): launched only as a browsable VIEW of another app, never an
     * internal component; falls back to the link's `browser_fallback_url` when no app handles it.
     */
    private fun openIntentUri(raw: String) {
        val parsed = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
        val safe = Intent(Intent.ACTION_VIEW).apply {
            data = parsed.data
            parsed.`package`?.let { setPackage(it) }
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
        try {
            if (safe.data == null) throw ActivityNotFoundException()
            startActivity(safe)
        } catch (e: Exception) {
            val fb = fallback?.let { Uri.parse(it) }
            val s = fb?.scheme?.lowercase(Locale.US)
            if (fb != null && (s == "https" || s == "http")) webView.loadUrl(fb.toString())
        }
    }

    // ---- blob: / data: downloads -------------------------------------------------------------
    private var pendingSave: Triple<ByteArray, String, String>? = null
    private var blobToken: String? = null
    private val saveDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = pendingSave?.first
        pendingSave = null
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null || bytes == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
        showBanner(getString(if (ok) R.string.download_saved else R.string.download_failed))
    }

    private fun suggestedName(contentDisposition: String?, mime: String): String {
        val fromHeader = contentDisposition?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.substringAfterLast('/')?.trim()?.take(120)
        if (!fromHeader.isNullOrBlank()) return fromHeader
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return "${gameName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "download" }}.$ext"
    }

    private fun promptSave(bytes: ByteArray, mime: String, name: String) {
        if (bytes.isEmpty() || bytes.size > MAX_DOWNLOAD_BYTES) { showBanner(getString(R.string.download_failed)); return }
        pendingSave = Triple(bytes, mime, name)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime.ifBlank { "application/octet-stream" }).putExtra(Intent.EXTRA_TITLE, name)
        try { saveDocument.launch(intent) } catch (e: Exception) { pendingSave = null }
    }

    private fun saveDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        val comma = url.indexOf(',')
        if (comma < 0 || url.length > MAX_DOWNLOAD_BYTES * 2) return
        val meta = url.substring(5, comma)
        val mime = meta.substringBefore(';').ifBlank { mimeType.orEmpty() }.ifBlank { "application/octet-stream" }
        val payload = url.substring(comma + 1)
        val bytes = runCatching {
            if (meta.endsWith(";base64", true)) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            else Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }.getOrNull() ?: return
        promptSave(bytes, mime, suggestedName(contentDisposition, mime))
    }

    /** blob: URLs only exist inside the page, so the page reads the blob and posts it back (token-guarded). */
    private fun fetchBlobForSave(url: String, contentDisposition: String?, mimeType: String?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val token = UUID.randomUUID().toString()
        blobToken = token
        pendingBlobName = contentDisposition to mimeType
        val js = "(function(){var ch=window.DailesDownload;if(!ch)return;" +
            "fetch(${JSONObject.quote(url)}).then(function(r){return r.blob()}).then(function(b){" +
            "if(b.size>$MAX_DOWNLOAD_BYTES){ch.postMessage(JSON.stringify({token:${JSONObject.quote(token)},error:'size'}));return;}" +
            "var fr=new FileReader();fr.onload=function(){ch.postMessage(JSON.stringify({token:${JSONObject.quote(token)},type:b.type,data:fr.result}))};" +
            "fr.readAsDataURL(b)}).catch(function(){ch.postMessage(JSON.stringify({token:${JSONObject.quote(token)},error:'fetch'}))})})()"
        webView.evaluateJavascript(js, null)
    }
    private var pendingBlobName: Pair<String?, String?>? = null

    private fun onBlobDownloadMessage(data: String?) {
        val m = runCatching { JSONObject(data ?: return) }.getOrNull() ?: return
        val token = blobToken ?: return
        if (m.optString("token") != token) return // only the download the user just started
        blobToken = null
        val (cd, mt) = pendingBlobName ?: (null to null)
        pendingBlobName = null
        val dataUrl = m.optString("data")
        if (m.has("error") || !dataUrl.startsWith("data:")) { showBanner(getString(R.string.download_failed)); return }
        saveDataUrl(dataUrl, cd, m.optString("type").ifBlank { mt })
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // No app can handle this link; ignore.
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Tonal pill with an optional Material Symbols PNG icon (tinted to [fg]) before the label. */
    private fun pill(label: String, desc: String, bg: Int, fg: Int, iconRes: Int = 0, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            if (iconRes != 0) {
                val icon = androidx.core.content.ContextCompat.getDrawable(this@GameActivity, iconRes)?.mutate()
                icon?.setBounds(0, 0, dp(20), dp(20))
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawableTintList = ColorStateList.valueOf(fg)
                compoundDrawablePadding = if (label.isEmpty()) 0 else dp(6)
            }
            contentDescription = desc
            setTextColor(fg)
            textSize = 13f
            typeface = googleSansBold
            gravity = Gravity.CENTER
            minWidth = dp(40)
            minHeight = dp(40)
            if (label.isEmpty()) setPadding(dp(10), dp(10), dp(10), dp(10)) else setPadding(dp(12), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(bg)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }

    companion object {
        const val EXTRA_ID = "game_id"
        const val EXTRA_NAME = "game_name"
        const val EXTRA_URL = "game_url"
        const val EXTRA_LOG = "open_log"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_OPENED_AT = "opened_at"
        const val EXTRA_SHARE_TEXT = "share_text"
        private const val STATE_OPENED_AT = "state_opened_at"
        private const val STATE_SHARE_TEXT = "state_share_text"
        private const val STATE_SHARE_AT = "state_share_at"
        private const val STATE_FOREGROUND_MS = "state_foreground_ms"
        private const val STATE_OPEN_LOG = "state_open_log"
        private const val MAX_COSMETIC_MESSAGE = 512 * 1024
        private const val MAX_TOKENS_PER_BATCH = 2000
        private const val MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024

        private fun scriptResponse(js: String) = WebResourceResponse(
            "application/javascript", "utf-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(js.toByteArray(Charsets.UTF_8))
        )

        private fun emptyResponse() = WebResourceResponse(
            "text/plain", "utf-8", 204, "No Content",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )

        /** The game player currently on screen; service-worker requests are filtered through it. */
        @Volatile private var activeFilter: WeakReference<GameActivity> = WeakReference(null)
        private val swInstalled = AtomicBoolean(false)

        /** Service workers fetch outside the page's WebViewClient; route them through the same filter. */
        private fun installServiceWorkerFilter(activity: GameActivity) {
            activeFilter = WeakReference(activity)
            if (!swInstalled.compareAndSet(false, true)) return
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
            ) return
            runCatching {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        activeFilter.get()?.filterRequest(request)
                })
            }
        }
        private const val MAX_UPLOADS = 10
        private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".bmp")

        // Same Material 3 "Iris" dark roles used by the web UI (--md-* variables).
        private val CONTAINER = Color.parseColor("#211E2B")
        private val PRIMARY = Color.parseColor("#D3BCFF")
        private val ON_PRIMARY = Color.parseColor("#381E72")
        private val PRIMARY_CONTAINER = Color.parseColor("#4F378B")
        private val ON_PRIMARY_CONTAINER = Color.parseColor("#EADDFF")
    }
}
