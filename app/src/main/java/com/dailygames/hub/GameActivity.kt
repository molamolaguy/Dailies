package com.dailygames.hub

import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
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
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
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
    private lateinit var root: LinearLayout
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
    private var surfaceColor = 0
    private var titleColor = Color.WHITE
    /** Last http(s) page shown, so a replacement WebView can reload it after the renderer is gone. */
    private var lastPageUrl: String? = null
    private var lastRendererCrashAt = 0L
    /** Last real touch / key press anywhere in the player (elapsedRealtime). Pages can't fake these. */
    @Volatile private var lastUserInputAt = 0L

    // ---- Share-result capture (layer 1: clipboard listener, layer 2: page share/copy hooks) ----
    private var openedAt = System.currentTimeMillis()

    // ---- Today's games (switch between them without going back to the hub) ----
    private class LineupGame(val id: String, val name: String, val url: String, var done: Boolean)
    private val lineup = ArrayList<LineupGame>()
    /** Play all: after each result the player moves on to the next unplayed game by itself. */
    private var playAll = false
    private var autoNext: Runnable? = null
    private var autoNextPill: TextView? = null
    private var autoNextName = ""
    /** Results of games played earlier in this player session, handed to the hub on return. */
    private var earlierResults = JSONArray()
    private var titleView: TextView? = null
    private var clearHistoryOnLoad = false
    /** History is cleared again once the new game's page has finished loading (see [switchToGame]). */
    private var clearHistoryAtFinish = false
    /** From [switchToGame] until the new game's page is on screen: the old page can't report results. */
    private var switching = false
    private val endSwitch = Runnable { switching = false }
    private var findResults = true
    /** Marks the page hook's messages as the app's own; the page can't read it (see share-hook.js). */
    private val pageKey: String = UUID.randomUUID().toString().replace("-", "")
    /** After the hook says it pressed a Share button: windows, other apps and downloads stay shut. */
    @Volatile private var probeGuardUntil = 0L
    /** A found result counts only right after the hook pressed a button, and once per page. */
    private var probeAcceptUntil = 0L
    private var probeResultsThisPage = 0
    private var lastPromptAt = 0L
    private val openMenus = ArrayList<android.widget.PopupMenu>()
    private var zoomDialog: android.app.AlertDialog? = null
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
    private val cosmeticExecutor = Executors.newSingleThreadExecutor()

    // ---- Popup windows (sign-in popups, same-site target=_blank) ----
    private var popupView: WebView? = null
    private var popupDialog: ComponentDialog? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Pages see the app's theme as prefers-color-scheme (light theme → light pages).
        if (ThemePrefs.isLight(this)) setTheme(R.style.Theme_Dailies_Light)
        super.onCreate(savedInstanceState)

        gameId = intent.getStringExtra(EXTRA_ID).orEmpty()
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Daily Game" }
        gameUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        // After switching games and a recreate, carry on with the game that was on screen.
        savedInstanceState?.getString(STATE_GAME_ID)?.takeIf { it.isNotEmpty() }?.let { id ->
            gameId = id
            gameName = savedInstanceState.getString(STATE_GAME_NAME).orEmpty().ifBlank { "Daily Game" }
            gameUrl = savedInstanceState.getString(STATE_GAME_URL).orEmpty()
        }
        readLineup(savedInstanceState?.getString(STATE_LINEUP) ?: intent.getStringExtra(EXTRA_LINEUP))
        playAll = savedInstanceState?.getBoolean(STATE_PLAY_ALL) ?: intent.getBooleanExtra(EXTRA_PLAY_ALL, false)
        savedInstanceState?.getString(STATE_EARLIER)?.let { earlierResults = runCatching { JSONArray(it) }.getOrDefault(JSONArray()) }

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
            lastClipStampChecked = savedInstanceState.getLong(STATE_CLIP_STAMP, 0L)
            clearHistoryAtFinish = savedInstanceState.getBoolean(STATE_CLEAR_HISTORY, false)
        }
        autoLogEnabled = GamePrefs.autoLogShare(this)
        findResults = GamePrefs.findResults(this)
        blockAdsSetting = GamePrefs.blockAds(this)
        PublicSuffix.ensureLoaded(this) // so per-site choices use the same "site" as the blocker
        gameHost = gameUrl.toUri().host?.lowercase(Locale.US).orEmpty()
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
        // Same Display settings as the hub: light / dark theme, AMOLED black, wallpaper colours (Android 12+).
        val light = ThemePrefs.isLight(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        val amoled = ThemePrefs.isAmoled(this)
        val isDynamic = ThemePrefs.isDynamic(this)
        surfaceColor = ThemePrefs.surfaceColor(this)
        val containerColor0 = when {
            light && isDynamic -> getColor(android.R.color.system_neutral2_50)
            light -> LIGHT_CONTAINER
            amoled -> Color.BLACK
            isDynamic -> getColor(android.R.color.system_neutral2_800)
            else -> CONTAINER
        }
        val primaryColor0 = when { light && isDynamic -> getColor(android.R.color.system_accent1_600); light -> LIGHT_PRIMARY; isDynamic -> getColor(android.R.color.system_accent1_200); else -> PRIMARY }
        val onPrimaryColor0 = when { light -> Color.WHITE; isDynamic -> getColor(android.R.color.system_accent1_800); else -> ON_PRIMARY }
        val primaryContainerColor0 = when { light && isDynamic -> getColor(android.R.color.system_accent1_100); light -> LIGHT_PRIMARY_CONTAINER; isDynamic -> getColor(android.R.color.system_accent1_700); else -> PRIMARY_CONTAINER }
        val onPrimaryContainerColor0 = when { light && isDynamic -> getColor(android.R.color.system_accent1_900); light -> LIGHT_ON_PRIMARY_CONTAINER; isDynamic -> getColor(android.R.color.system_accent1_100); else -> ON_PRIMARY_CONTAINER }
        titleColor = if (light) LIGHT_ON_SURFACE else Color.WHITE
        // The hub's colour theme or gold accents, when one is on.
        val themed = ThemePrefs.playerColors(this)
        val containerColor = if (amoled) containerColor0 else themed["container"] ?: containerColor0
        val primaryColor = themed["primary"] ?: primaryColor0
        val onPrimaryColor = themed["onPrimary"] ?: onPrimaryColor0
        val primaryContainerColor = themed["primaryContainer"] ?: primaryContainerColor0
        val onPrimaryContainerColor = themed["onPrimaryContainer"] ?: onPrimaryContainerColor0
        barColors = intArrayOf(containerColor, primaryContainerColor, onPrimaryContainerColor)
        accentColors = intArrayOf(primaryColor, onPrimaryColor)

        // ---- Top app bar (Material 3 Expressive tonal pills + Google Sans) ----
        // At 360 dp: 4 icon pills of 48 dp + "Log" + gaps leave about 55 dp for the title, which ellipsizes.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(containerColor)
            setPadding(dp(BAR_SIDE_PADDING), dp(6), dp(BAR_SIDE_PADDING), dp(6))
        }
        bar.addView(pill("", "Back to hub", primaryContainerColor, onPrimaryContainerColor, R.drawable.ic_bar_arrow_back) { finish() })
        val switchable = lineup.size > 1
        titleView = TextView(this).apply {
            text = gameName
            contentDescription = if (switchable) "$gameName. Switch game" else gameName
            setTextColor(titleColor)
            textSize = 15f
            typeface = googleSansBold
            // Google Sans's extra top/bottom font padding is uneven; without it text centres on its letters.
            includeFontPadding = false
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            if (switchable) {
                // Tap the title to switch to another of today's games.
                androidx.core.content.ContextCompat.getDrawable(this@GameActivity, R.drawable.ic_bar_expand)?.mutate()?.let { icon ->
                    icon.setBounds(0, 0, dp(18), dp(18))
                    setCompoundDrawablesRelative(null, null, icon, null)
                    androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(titleColor))
                    compoundDrawablePadding = dp(2)
                }
                isClickable = true
                setOnClickListener { v -> showGameSwitcher(v) }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(2)
            }
        }
        bar.addView(titleView)
        if (switchable) {
            bar.addView(pill("", "Next game", primaryContainerColor, onPrimaryContainerColor, R.drawable.ic_bar_next) { playNextGame() })
        }
        if (blockAdsSetting) {
            shieldPill = pill("", "Ad blocking", primaryContainerColor, onPrimaryContainerColor,
                if (adBlockActive) R.drawable.ic_bar_shield else R.drawable.ic_bar_shield_off) { toggleSiteAdBlocking() }
            bar.addView(shieldPill)
            updateShieldPill()
        }
        // Reload, Open in browser and Zoom live in a menu so the bar has room for Next.
        bar.addView(pill("", "More options", primaryContainerColor, onPrimaryContainerColor, R.drawable.ic_bar_more) { v -> showMoreMenu(v) })
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

        webView = createGameWebView()

        root = LinearLayout(this).apply {
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
            // Google Sans's extra top/bottom font padding is uneven; without it text centres on its letters.
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(primaryContainerColor) }
            elevation = dp(6).toFloat()
            visibility = View.GONE
            androidx.core.content.ContextCompat.getDrawable(this@GameActivity, R.drawable.ic_bar_check)?.mutate()?.let { icon ->
                icon.setBounds(0, 0, dp(18), dp(18))
                setCompoundDrawablesRelative(icon, null, null, null)
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(onPrimaryContainerColor))
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
                dp(BAR_SIDE_PADDING) + sysBars.left,
                dp(6) + sysBars.top,
                dp(BAR_SIDE_PADDING) + sysBars.right,
                dp(6)
            )
            root.setPadding(0, 0, 0, maxOf(sysBars.bottom, ime.bottom))
            // The "result saved" card follows the keyboard and the navigation bar.
            donePrompt?.let { card ->
                (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = dp(20) + maxOf(sysBars.bottom, ime.bottom)
                    card.layoutParams = lp
                }
            }
            WindowInsetsCompat.CONSUMED
        }

        window.setBackgroundDrawable(surfaceColor.toDrawable())
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, backCallback)

        // restoreState returns null if the saved WebView state is missing or unusable: start fresh then.
        val restored = savedInstanceState != null && runCatching { webView.restoreState(savedInstanceState) }.getOrNull() != null
        if (!restored) webView.loadUrl(gameUrl)
        updateBackIntercept()
    }

    private var barColors = intArrayOf(0, 0, 0)
    private var accentColors = intArrayOf(0, 0)
    private var donePrompt: View? = null

    /** Builds the game WebView (also used to replace one whose renderer process went away). */
    @SuppressLint("SetJavaScriptEnabled")
    /**
     * Dark theme + "Dark Game Pages": WebView darkens pages that don't offer a dark theme of their
     * own (pages that do, via prefers-color-scheme or color-scheme, keep their own design).
     */
    private fun applyDarkPages(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        val on = GamePrefs.darkPages(this) && !ThemePrefs.isLight(this)
        runCatching { androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, on) }
    }

    // JavaScript is required: the games are web apps.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun createGameWebView(): WebView = WebView(this).apply {
        setBackgroundColor(surfaceColor)
        applyDarkPages(this)
        settings.javaScriptEnabled = true
        // No Android scroll bar on the right while scrolling (1.5.1).
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.domStorageEnabled = true
        // With ad blocking on, scripts can't open pop-ups/pop-unders without a tap.
        settings.javaScriptCanOpenWindowsAutomatically = !adBlockActive
        // New windows go through onCreateWindow: sign-in / same-site popups open in a dialog (so
        // window.opener keeps working), tapped links to other sites open in the browser.
        settings.setSupportMultipleWindows(true)
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
                    // A Share button the hook pressed doesn't get to take the game page elsewhere.
                    if (probeGuarded() && request.isForMainFrame && !request.isRedirect) return true
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
                // Other apps (Play Store, intent://, tel:, custom schemes) only open from a real tap,
                // whatever the shield says: ads like to bounce there on their own.
                if (!request.hasGesture() || probeGuarded()) {
                    if (adBlockActive) onRequestBlocked()
                    return true
                }
                launchExternalScheme(uri)
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.let { filterRequest(it) }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                this@GameActivity.progress.visibility = View.VISIBLE
                // Commit the new page's host now (main-frame requests only record it as pending, so a
                // cancelled navigation can't change which site the blocker treats as first-party).
                val started = url?.toUri()?.host?.lowercase(Locale.US)
                pageHost = started ?: pendingPageHost ?: pageHost
                pendingPageHost = null
                rememberPageUrl(url)
                // A new document: every frame asks for its page CSS again, and old frames are gone.
                helloAnswered.clear()
                blobFrames.clear()
                if (view === webView) { probeResultsThisPage = 0; probeAcceptUntil = 0L }
                if (!documentStartScriptsSupported) injectPageScriptsFallback()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                this@GameActivity.progress.visibility = View.GONE
                // The new game's page has loaded: clear once more (the first clear ran while it was loading).
                if (clearHistoryAtFinish && !switching && view === webView) {
                    clearHistoryAtFinish = false
                    view.clearHistory()
                    updateBackIntercept()
                }
                if (view === webView) applySiteZoom()
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (view !== webView) return
                // The new game's page is on screen (a cancelled load of the old one never gets here).
                if (switching) {
                    switching = false
                    banner.removeCallbacks(endSwitch)
                }
                // After switching games, Back shouldn't return to the previous game.
                if (clearHistoryOnLoad) {
                    clearHistoryOnLoad = false
                    clearHistoryAtFinish = true
                    view.clearHistory()
                    updateBackIntercept()
                }
                applySiteZoom()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                rememberPageUrl(url)
                updateBackIntercept()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // Never let a dead renderer crash the app (K5); rebuild the page instead of closing.
                recoverFromRendererGone(view, Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail?.didCrash() == true)
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

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                if (probeGuarded()) return false
                if (!isUserGesture && adBlockActive) {
                    onRequestBlocked()
                    return false
                }
                closePopup()
                transport.webView = createPopupWebView(isUserGesture)
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (window != null && window === popupView) closePopup()
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
        // Downloads (share images, exported results), only right after a real tap so ads can't push
        // files or the Save picker on their own. Web links download with the page's cookies; blob: and
        // data: files (made by the page itself) are saved through the system file picker.
        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!userActedWithin(DOWNLOAD_GESTURE_WINDOW_MS) || probeGuarded()) return@DownloadListener
            when {
                url.startsWith("http://", true) || url.startsWith("https://", true) ->
                    startHttpDownload(url, userAgent, contentDisposition, mimeType)
                url.startsWith("data:", true) -> saveDataUrl(url, contentDisposition, mimeType)
                url.startsWith("blob:", true) -> fetchBlobForSave(url, contentDisposition, mimeType)
            }
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun rememberPageUrl(url: String?) {
        if (url != null && (url.startsWith("https://", true) || url.startsWith("http://", true))) lastPageUrl = url
    }

    /**
     * The renderer was killed (low memory, often while the player was in the background) or crashed:
     * put a fresh WebView in place and reload the page. A second crash within a minute closes the
     * player instead, so a page that always crashes can't loop.
     */
    private fun recoverFromRendererGone(view: WebView?, crashed: Boolean) {
        if (view == null) return
        if (view === popupView) { closePopup(); return }
        if (!::webView.isInitialized || view !== webView) {
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
            return
        }
        closePopup()
        exitFullscreen()
        val index = root.indexOfChild(view).takeIf { it >= 0 } ?: root.childCount
        root.removeView(view)
        runCatching { view.destroy() }
        val now = SystemClock.elapsedRealtime()
        val repeatedCrash = crashed && lastRendererCrashAt > 0 && now - lastRendererCrashAt < RENDERER_CRASH_WINDOW_MS
        if (crashed) lastRendererCrashAt = now
        if (repeatedCrash || isFinishing || isDestroyed) {
            if (!isFinishing) finish()
            return
        }
        helloAnswered.clear()
        blobFrames.clear()
        webView = createGameWebView()
        root.addView(webView, index)
        setUpPageHooks()
        webView.loadUrl(lastPageUrl ?: gameUrl)
        updateBackIntercept()
        if (crashed) showBanner(RELOADED_AFTER_CRASH)
    }

    // =====================================================================================
    // Popup windows
    // =====================================================================================

    /**
     * Child WebView for window.open / target=_blank. Its first real address decides where it goes:
     * same-site and sign-in pages stay in a dialog (so the opener keeps its window.opener link), a
     * tapped link to another site opens in the browser, anything else is dropped.
     */
    @SuppressLint("SetJavaScriptEnabled")
    /** The site the popup is showing, for first-party checks in its ad blocking. */
    @Volatile private var popupHost: String? = null

    // JavaScript is required: popups are sign-in pages and game windows.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun createPopupWebView(userGesture: Boolean): WebView {
        popupHost = null
        val child = WebView(this)
        popupView = child
        child.setBackgroundColor(surfaceColor)
        applyDarkPages(child)
        child.settings.javaScriptEnabled = true
        child.settings.domStorageEnabled = true
        child.settings.setSupportMultipleWindows(false)
        child.settings.javaScriptCanOpenWindowsAutomatically = false
        child.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        child.settings.allowFileAccess = false
        child.settings.allowContentAccess = false
        child.settings.userAgentString = child.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
        var decided = false
        /** True to let [uri] load in the popup. */
        fun decide(uri: Uri): Boolean {
            if (decided) return true
            val scheme = uri.scheme?.lowercase(Locale.US)
            // window.open('') / about:blank first, the real address follows.
            if (scheme == "about" || scheme == "javascript" || scheme == null) return true
            decided = true
            if (scheme != "http" && scheme != "https") {
                child.post { closePopup() }
                if (userGesture && scheme != "blob" && scheme != "data") launchExternalScheme(uri)
                return false
            }
            return when (GamePlayerPolicy.popupAction(uri.host, uri.path, gameHost, userGesture)) {
                GamePlayerPolicy.PopupAction.DIALOG -> { showPopupDialog(child, uri.host.orEmpty()); true }
                GamePlayerPolicy.PopupAction.EXTERNAL -> { child.post { closePopup() }; viewExternally(uri); false }
                GamePlayerPolicy.PopupAction.BLOCK -> { child.post { closePopup() }; onRequestBlocked(); false }
            }
        }
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (!decided) return !decide(uri)
                val scheme = uri.scheme?.lowercase(Locale.US)
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" ||
                    scheme == "data" || scheme == "javascript") return false
                if (request.hasGesture()) launchExternalScheme(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Fallback in case the first load didn't pass through shouldOverrideUrlLoading.
                if (!decided && url != null && !decide(url.toUri())) view?.stopLoading()
                popupTitle?.text = url?.toUri()?.host.orEmpty()
                url?.toUri()?.host?.lowercase(Locale.US)?.let { popupHost = it }
                if (!documentStartScriptsSupported && blockAdsSetting && adBlockActive &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    view?.evaluateJavascript(pageScript("game/cosmetic.js"), null)
                }
            }

            // Popups get the same ad and tracker blocking as the game page, judged against the
            // popup's own site (a sign-in page's own scripts are first-party there).
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return null
                if (r.isForMainFrame) {
                    r.url?.host?.let { popupHost = it.lowercase(Locale.US) }
                    return null
                }
                return blockedResponseFor(r, popupHost)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                child.post { closePopup() }
                return true
            }
        }
        child.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) { closePopup() }
        }
        if (blockAdsSetting) installCosmeticHooks(child)
        return child
    }

    private var popupTitle: TextView? = null

    private fun showPopupDialog(child: WebView, host: String) {
        if (popupDialog != null || isFinishing || isDestroyed) return
        val (containerColor, pillBg, pillFg) = barColors.let { Triple(it[0], it[1], it[2]) }
        val dlg = ComponentDialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val title = TextView(this).apply {
            text = host
            setTextColor(titleColor)
            textSize = 14f
            typeface = googleSansBold
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        popupTitle = title
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(containerColor)
            setPadding(dp(BAR_SIDE_PADDING), dp(6), dp(BAR_SIDE_PADDING), dp(6))
            addView(pill("", "Close and return to the game", pillBg, pillFg, R.drawable.ic_bar_arrow_back) { closePopup() })
            addView(title)
        }
        (child.parent as? ViewGroup)?.removeView(child)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        dlg.setContentView(layout)
        dlg.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.onBackPressedDispatcher.addCallback(dlg, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupView?.canGoBack() == true) popupView?.goBack() else closePopup()
            }
        })
        dlg.setOnDismissListener { if (popupDialog === dlg) closePopup() }
        popupDialog = dlg
        runCatching { dlg.show() }
    }

    private fun closePopup() {
        val dlg = popupDialog
        val view = popupView
        popupDialog = null
        popupView = null
        popupTitle = null
        if (dlg != null) runCatching { dlg.dismiss() }
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.stopLoading(); view.destroy() }
        }
    }

    // =====================================================================================
    // Share-result capture
    // =====================================================================================

    private var documentStartScriptsSupported = false

    /** Layer 2: page scripts (document start) + origin-scoped message channels (androidx.webkit). */
    private fun setUpPageHooks() {
        documentStartScriptsSupported = false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val anyOrigin = setOf("*")
        try {
            if (autoLogEnabled) {
                WebViewCompat.addWebMessageListener(webView, "DailesShare", anyOrigin) { _, message, sourceOrigin, isMainFrame, _ ->
                    val scheme = sourceOrigin.scheme?.lowercase(Locale.US)
                    if (scheme != "http" && scheme != "https") return@addWebMessageListener
                    // Only the game's own site: its page or a frame from the same site (some games run
                    // inside one), never an ad frame or another site it navigated to...
                    val src = sourceOrigin.host?.lowercase(Locale.US) ?: return@addWebMessageListener
                    if (gameHost.isEmpty() || !AdBlockEngine.sameSite(src, gameHost)) return@addWebMessageListener
                    if (!isMainFrame && pageHost?.let { AdBlockEngine.sameSite(it, gameHost) } == false) return@addWebMessageListener
                    // Only messages from the app's own hook (it carries a key the page can't read)...
                    val msg = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                    if (msg.optString("n") != pageKey) return@addWebMessageListener
                    val now = SystemClock.elapsedRealtime()
                    if (msg.optString("kind") == "press") {
                        // The hook is about to press the game's Share button on its end screen.
                        if (!findResults) return@addWebMessageListener
                        probeGuardUntil = now + PROBE_GUARD_MS
                        probeAcceptUntil = now + PROBE_ACCEPT_MS
                        return@addWebMessageListener
                    }
                    // ...and only right after the player was really tapped, or right after the hook
                    // pressed the game's own Share button (once per page).
                    val probe = msg.optString("via") == "probe"
                    if (probe) {
                        if (!findResults || now > probeAcceptUntil || probeResultsThisPage >= 1) return@addWebMessageListener
                        probeResultsThisPage += 1
                    } else if (!userActedWithin(SHARE_GESTURE_WINDOW_MS)) return@addWebMessageListener
                    offerShare(msg.optString("text"), if (probe) "probe" else "page")
                }
            }
            if (blockAdsSetting) addCosmeticListener(webView)
            WebViewCompat.addWebMessageListener(webView, "DailesDownload", anyOrigin) { _, message, sourceOrigin, _, reply ->
                onDownloadChannelMessage(message.data, sourceOrigin, reply)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                if (autoLogEnabled) WebViewCompat.addDocumentStartJavaScript(webView, shareHookScript(), anyOrigin)
                if (blockAdsSetting) WebViewCompat.addDocumentStartJavaScript(webView, pageScript("game/cosmetic.js"), anyOrigin)
                WebViewCompat.addDocumentStartJavaScript(webView, pageScript("game/download-hook.js"), anyOrigin)
                documentStartScriptsSupported = true
            }
        } catch (e: Exception) {
            // Older WebView: the clipboard listener (layer 1) still captures results.
        }
    }

    /** Element hiding for [target]: answers cosmetic.js's requests for a page's CSS. */
    private fun addCosmeticListener(target: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(target, "DailesAdBlock", setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
            if (!adBlockActive) {
                // Tell the page script to stop scanning (blocking is off for this site).
                runCatching { reply.postMessage("{\"disabled\":true}") }
                return@addWebMessageListener
            }
            val host = sourceOrigin.host?.lowercase(Locale.US) ?: return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            if (data.length > MAX_COSMETIC_MESSAGE) return@addWebMessageListener
            runCatching {
                cosmeticExecutor.execute {
                    val css = cosmeticCssFor(host, data, isMainFrame) ?: return@execute
                    runOnUiThread { runCatching { reply.postMessage(JSONObject().put("css", css).toString()) } }
                }
            }
        }
    }

    /** Cosmetic filtering for a popup window (the game page sets its hooks up in [setUpPageHooks]). */
    private fun installCosmeticHooks(target: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            addCosmeticListener(target)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(target, pageScript("game/cosmetic.js"), setOf("*"))
            }
        }
    }

    /** Fallback for WebViews without document-start scripts: inject as early as onPageStarted. */
    private fun injectPageScriptsFallback() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        if (autoLogEnabled) webView.evaluateJavascript(shareHookScript(), null)
        if (blockAdsSetting && adBlockActive) webView.evaluateJavascript(pageScript("game/cosmetic.js"), null)
        webView.evaluateJavascript(pageScript("game/download-hook.js"), null)
    }

    /** share-hook.js with this player's key and the Find Results setting filled in. */
    private fun shareHookScript(): String = pageScript("game/share-hook.js")
        .replace("__DAILIES_NONCE__", pageKey)
        .replace("__DAILIES_FIND__", (autoLogEnabled && findResults).toString())

    private fun probeGuarded(): Boolean = SystemClock.elapsedRealtime() < probeGuardUntil

    private val scriptCache = HashMap<String, String>()
    private fun pageScript(path: String): String =
        scriptCache.getOrPut(path) { assets.open(path).bufferedReader().use { it.readText() } }

    /** Layer 1: anything copied while the game is on screen (Android only reports it to the foreground app). */
    private fun onClipboardChanged() {
        if (!autoLogEnabled) return
        // Off the game's own site (a sign-in page, an article it linked to), copies aren't results.
        val here = pageHost
        if (here != null && gameHost.isNotEmpty() && !AdBlockEngine.sameSite(here, gameHost)) return
        if (popupDialog != null || switching) return
        val text = runCatching {
            clipboardManager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        offerShare(text, "clipboard")
    }

    private var openedAtElapsed = SystemClock.elapsedRealtime()
    private var lastClipStampChecked = 0L

    /**
     * Catches a result copied while the game was open that the clipboard listener missed (for example
     * while a pop-up had focus). Looks only at the clip's timestamp until there's a new clip, so the
     * "pasted from your clipboard" notice doesn't show for nothing. Android 8+ (needs the timestamp).
     */
    private fun checkClipboardSinceOpen() {
        if (!autoLogEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasWindowFocus()) return
        val cm = clipboardManager ?: return
        val stamp = runCatching { cm.primaryClipDescription?.timestamp }.getOrNull() ?: return
        if (!clipNewSinceOpen(stamp) || stamp == lastClipStampChecked) return
        lastClipStampChecked = stamp
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        // Found after the fact, so it must name this game (not just look like any game's result).
        if (!ShareResultDetector.mentionsGame(text, gameName, gameUrl)) return
        offerShare(text, "clipboard-late")
    }

    /**
     * Whether a clip's timestamp is after this game was opened. The platform stamps clips with
     * System.currentTimeMillis(); a value that small is treated as elapsedRealtime() instead.
     */
    private fun clipNewSinceOpen(stamp: Long): Boolean =
        if (stamp > WALL_CLOCK_MIN) stamp >= openedAt else stamp >= openedAtElapsed

    /** The current clip's timestamp, without reading the clip (which would show Android's "pasted" notice). */
    private fun currentClipStamp(): Long? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) null
        else runCatching { clipboardManager?.primaryClipDescription?.timestamp }.getOrNull()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboardSinceOpen()
    }

    /** Keeps the latest text that passes the strict share-result check; it is logged back in the hub. */
    private fun offerShare(text: String?, via: String) {
        // While switching games the old page is still alive: whatever it reports isn't the new game's.
        if (!autoLogEnabled || text.isNullOrBlank() || switching) return
        val clean = text.replace("\r\n", "\n").trim()
        if (clean == capturedShare) return
        if (!ShareResultDetector.isStrictShareResult(clean, gameName, gameUrl)) return
        val entry = lineup.firstOrNull { it.id == gameId }
        val wasDone = entry?.done == true
        capturedShare = clean
        capturedAt = System.currentTimeMillis()
        entry?.done = true
        setResultWithLog(openLogOnReturn)
        // Back on a game that was already done: keep the result quietly instead of asking again.
        if (via == "probe" && wasDone) return
        // A page can't keep a card over the game by sending results over and over.
        val now = SystemClock.elapsedRealtime()
        if (donePrompt != null && now - lastPromptAt < PROMPT_MIN_GAP_MS) return
        lastPromptAt = now
        showDonePrompt(clean)
    }

    /**
     * After a result is captured: "Play next" (opens the next unplayed game right here) or "Back to
     * hub". ✕ keeps playing. With no game left, only "Back to hub".
     */
    private fun showDonePrompt(result: String) {
        if (!::container.isInitialized || isFinishing) return
        hideDonePrompt()
        banner.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        val (containerColor, tonalBg, tonalFg) = Triple(barColors[0], barColors[1], barColors[2])
        val next = nextGame()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(containerColor) }
            elevation = dp(10).toFloat()
            isClickable = true // taps on the card don't reach the game
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = getString(R.string.result_saved_title, gameName)
            setTextColor(titleColor)
            textSize = 16f
            typeface = googleSansBold
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = if (next == null) getString(R.string.all_games_done) else result.lineSequence().firstOrNull().orEmpty().take(60)
            setTextColor(titleColor)
            alpha = 0.75f
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The game may say "Copied!" for a result found automatically (nothing was copied): copy it here.
        head.addView(pill("", getString(R.string.copy_result), tonalBg, tonalFg, R.drawable.ic_bar_copy) {
            stopAutoNext()
            runCatching { clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText(gameName, result)) }
            showBanner(getString(R.string.result_copied))
        })
        head.addView(pill("", getString(R.string.keep_playing), tonalBg, tonalFg, R.drawable.ic_bar_close) { hideDonePrompt() })
        card.addView(head)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(pill(getString(R.string.back_to_hub), getString(R.string.back_to_hub), tonalBg, tonalFg, R.drawable.ic_bar_arrow_back) {
            hideDonePrompt()
            finish()
        })
        var nextPill: TextView? = null
        if (next != null) {
            nextPill = pill(getString(R.string.play_next_named, next.name), getString(R.string.play_next_named, next.name),
                accentColors[0], accentColors[1], R.drawable.ic_bar_next) {
                hideDonePrompt()
                switchToGame(next)
            }.apply { maxWidth = dp(240); ellipsize = TextUtils.TruncateAt.END }
            buttons.addView(nextPill)
        }
        card.addView(buttons)
        container.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(20) + root.paddingBottom })
        card.translationY = dp(40).toFloat()
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(220).start()
        card.announceForAccessibility(getString(R.string.result_saved_title, gameName))
        donePrompt = card
        updateBackIntercept()
        // Play all: count down on the Play button, then move on (Keep playing, Copy or a tap stops it).
        if (playAll && next != null && nextPill != null) startAutoNext(card, nextPill, next)
    }

    private fun startAutoNext(card: View, nextPill: TextView, next: LineupGame) {
        stopAutoNext()
        var left = AUTO_NEXT_SECONDS
        autoNextPill = nextPill
        autoNextName = next.name
        nextPill.text = getString(R.string.next_game_in, next.name, left)
        val tick = object : Runnable {
            override fun run() {
                if (donePrompt !== card || autoNext !== this) return
                left -= 1
                if (left <= 0) {
                    autoNext = null
                    hideDonePrompt()
                    switchToGame(next)
                    return
                }
                nextPill.text = getString(R.string.next_game_in, next.name, left)
                banner.postDelayed(this, 1000)
            }
        }
        autoNext = tick
        banner.postDelayed(tick, 1000)
    }

    /** Stops Play all's countdown; the button goes back to "Play <game>". */
    private fun stopAutoNext() {
        autoNext?.let { if (::banner.isInitialized) banner.removeCallbacks(it) }
        autoNext = null
        autoNextPill?.text = getString(R.string.play_next_named, autoNextName)
        autoNextPill = null
    }

    private fun hideDonePrompt() {
        stopAutoNext()
        donePrompt?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        donePrompt = null
        updateBackIntercept()
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) lastUserInputAt = SystemClock.elapsedRealtime()
        // A real tap is the player's own: nothing the hook pressed is guarded any more.
        if (a == MotionEvent.ACTION_DOWN) probeGuardUntil = 0L
        // Play all: touching the game (not the card) means "I'm still playing".
        if (a == MotionEvent.ACTION_DOWN && autoNext != null) {
            val card = donePrompt
            val r = android.graphics.Rect()
            if (card == null || !card.getGlobalVisibleRect(r) || !r.contains(ev.rawX.toInt(), ev.rawY.toInt())) stopAutoNext()
        }
        return super.dispatchTouchEvent(ev)
    }

    // Key presses (hardware keyboard, D-pad, accessibility) count as user input too.
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastUserInputAt = SystemClock.elapsedRealtime()
    }

    private fun userActedWithin(ms: Long): Boolean =
        lastUserInputAt > 0 && SystemClock.elapsedRealtime() - lastUserInputAt <= ms

    // =====================================================================================
    // Ad blocking
    // =====================================================================================

    private fun filterRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        if (request.isForMainFrame) {
            url.host?.let { pendingPageHost = it.lowercase(Locale.US) }
            return null
        }
        return blockedResponseFor(request, pageHost)
    }

    /** The ad-blocking verdict for a sub-resource of a page on [firstPartyHost]: null to let it load. */
    private fun blockedResponseFor(request: WebResourceRequest, firstPartyHost: String?): WebResourceResponse? {
        val url = request.url ?: return null
        if (!adBlockActive) return null
        val scheme = url.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        val engine = AdBlocker.network() ?: return null
        val s = url.toString()
        val headers = request.requestHeaders.orEmpty()
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        val requestedWith = headers.entries.firstOrNull { it.key.equals("X-Requested-With", true) }?.value
        val type = AdBlockEngine.inferType(s, accept, false, requestedWith)
        if (!engine.shouldBlock(s, firstPartyHost, type)) return null
        onRequestBlocked()
        // Ad libraries a game may call directly get a harmless stand-in so the game doesn't stall.
        Surrogates.forUrl(s)?.let { js -> return scriptResponse(js) }
        return emptyResponse()
    }

    private fun onRequestBlocked() {
        blockedCount.incrementAndGet()
        if (shieldUpdatePending.compareAndSet(false, true)) {
            container.postDelayed({
                shieldUpdatePending.set(false)
                updateShieldPill()
            }, 250)
        }
    }

    /** Shield pill: icon, the blocked count when the bar has room for it, and a spoken state. */
    private fun updateShieldPill() {
        val p = shieldPill ?: return
        val n = blockedCount.get()
        val showCount = adBlockActive && n > 0 && resources.configuration.screenWidthDp >= WIDE_BAR_DP
        p.text = if (showCount) (if (n > 999) "999+" else n.toString()) else ""
        p.compoundDrawablePadding = if (p.text.isNullOrEmpty()) 0 else dp(6)
        p.contentDescription = when {
            !adBlockActive -> "Ad blocking off for this site. Tap to block ads."
            n == 1 -> "Ad blocking on, 1 request blocked. Tap to allow ads on this site."
            n > 1 -> "Ad blocking on, $n requests blocked. Tap to allow ads on this site."
            else -> "Ad blocking on. Tap to allow ads on this site."
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateShieldPill()
    }

    /**
     * Documents already sent their page CSS. Keyed by the per-document nonce cosmetic.js sends, so a
     * reload or the next page on the same host gets its CSS again; cleared on every new page.
     */
    private val helloAnswered = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun cosmeticCssFor(host: String, data: String, isMainFrame: Boolean): String? {
        val filters = AdBlocker.cosmetics() ?: return null
        return runCatching {
            val m = JSONObject(data)
            when (m.optString("t")) {
                "hello" -> {
                    // Frames can't make the app rebuild CSS without limit (each frame load asks once).
                    if (!isMainFrame && helloAnswered.size >= MAX_FRAME_HELLOS) return@runCatching null
                    val key = host + "|" + isMainFrame + "|" + m.optString("n").take(64)
                    if (helloAnswered.add(key)) filters.pageCss(host, includeComplexGeneric = isMainFrame) else null
                }
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
        }
        updateShieldPill()
        showBanner(getString(if (nowActive) R.string.adblock_on_site else R.string.adblock_off_site, AdBlockEngine.siteOf(host)))
        webView.reload()
    }

    private fun updateBackIntercept() {
        backCallback.isEnabled = customView != null || donePrompt != null || (::webView.isInitialized && webView.canGoBack())
    }

    private fun handleGameBackPress() {
        when {
            customView != null -> exitFullscreen()
            donePrompt != null -> hideDonePrompt()
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
        outState.putString(STATE_GAME_ID, gameId)
        outState.putString(STATE_GAME_NAME, gameName)
        outState.putString(STATE_GAME_URL, gameUrl)
        outState.putString(STATE_LINEUP, lineupJson())
        outState.putBoolean(STATE_PLAY_ALL, playAll)
        outState.putString(STATE_EARLIER, earlierResults.toString())
        outState.putLong(STATE_CLIP_STAMP, lastClipStampChecked)
        outState.putBoolean(STATE_CLEAR_HISTORY, clearHistoryOnLoad || clearHistoryAtFinish)
    }

    override fun onPause() {
        super.onPause()
        stopAutoNext()
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
        if (::banner.isInitialized) { banner.removeCallbacks(hideBanner); banner.removeCallbacks(endSwitch) }
        autoNext?.let { if (::banner.isInitialized) banner.removeCallbacks(it) }
        autoNext = null
        openMenus.toList().forEach { runCatching { it.dismiss() } }
        openMenus.clear()
        zoomDialog?.let { runCatching { it.dismiss() } }
        zoomDialog = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        closePopup()
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
        if (earlierResults.length() > 0) data.putExtra(EXTRA_EARLIER, earlierResults.toString())
        setResult(RESULT_OK, data)
    }

    /** Every exit path (← Hub, ✓ Log, Back, renderer crash) reports how long the game was on screen. */
    override fun finish() {
        checkClipboardSinceOpen()
        if (gameId.isNotEmpty()) setResultWithLog(openLogOnReturn)
        super.finish()
    }

    @Volatile private var pendingPageHost: String? = null

    // =====================================================================================
    // Today's games: switcher, Next, and in-place switching
    // =====================================================================================

    private fun readLineup(json: String?) {
        lineup.clear()
        val arr = runCatching { JSONArray(json ?: return) }.getOrNull() ?: return
        for (i in 0 until minOf(arr.length(), MAX_LINEUP)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) continue
            val id = o.optString("id").ifEmpty { continue }
            lineup += LineupGame(id, o.optString("name").ifBlank { "Daily Game" }, url, o.optBoolean("done"))
        }
        if (lineup.none { it.id == gameId }) lineup.clear()
    }

    private fun lineupJson(): String = JSONArray().also { arr ->
        lineup.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("done", it.done)) }
    }.toString()

    /** The next game not done yet after the current one (in the hub's order, wrapping), or null. */
    private fun nextGame(): LineupGame? {
        val i = lineup.indexOfFirst { it.id == gameId }
        if (i < 0) return null
        for (k in 1 until lineup.size) {
            val g = lineup[(i + k) % lineup.size]
            if (!g.done) return g
        }
        return null
    }

    private fun playNextGame() {
        val next = nextGame()
        if (next == null) showBanner(getString(R.string.all_games_done)) else switchToGame(next)
    }

    private fun showGameSwitcher(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        lineup.forEachIndexed { i, g ->
            val mark = when { g.id == gameId -> "▶  "; g.done -> "✓  "; else -> "○  " }
            val item = menu.menu.add(0, i, i, mark + g.name)
            // TalkBack reads the state in words, not the glyph.
            val state = getString(when { g.id == gameId -> R.string.switcher_playing; g.done -> R.string.switcher_done; else -> R.string.switcher_not_done })
            androidx.core.view.MenuItemCompat.setContentDescription(item, "${g.name}, $state")
        }
        menu.setOnMenuItemClickListener { item -> lineup.getOrNull(item.itemId)?.let { switchToGame(it) }; true }
        trackMenu(menu)
    }

    private fun showMoreMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 1, "Reload")
        menu.menu.add(0, 2, 2, "Open in browser")
        menu.menu.add(0, 3, 3, "Zoom (${(siteZoom() * 100).toInt()}%)…")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> webView.reload()
                2 -> {
                    val current = webView.url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: gameUrl
                    viewExternally(current.toUri())
                }
                3 -> showZoomDialog()
            }
            true
        }
        trackMenu(menu)
    }

    private fun trackMenu(menu: android.widget.PopupMenu) {
        openMenus += menu
        menu.setOnDismissListener { openMenus.remove(it) }
        menu.show()
    }

    /** One entry per game in [earlierResults]: play time added up, the latest result kept. */
    private fun addEarlierResult(id: String, playedMs: Long, opened: Long, share: String?) {
        val merged = JSONArray()
        var found = false
        for (i in 0 until earlierResults.length()) {
            val o = earlierResults.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                found = true
                o.put("elapsedMs", o.optLong("elapsedMs") + playedMs)
                // The earliest opening tells the hub whether the game reset while it was being played.
                if (opened in 1 until o.optLong("openedAt", Long.MAX_VALUE)) o.put("openedAt", opened)
                if (!share.isNullOrEmpty()) o.put("share", share)
            }
            merged.put(o)
        }
        if (!found) merged.put(JSONObject().put("id", id).put("elapsedMs", playedMs).put("openedAt", opened).put("share", share ?: ""))
        earlierResults = merged
    }

    /**
     * Swaps the player over to [target]: this game's play time and any captured result are kept for
     * the hub, then the new game loads in the same player with its own timer, ad-block choice and zoom.
     */
    private fun switchToGame(target: LineupGame) {
        if (target.id == gameId) return
        hideDonePrompt()
        checkClipboardSinceOpen()
        val now = SystemClock.elapsedRealtime()
        val played = foregroundMs + if (resumedAt > 0) now - resumedAt else 0L
        addEarlierResult(gameId, played, openedAt, capturedShare)
        // Whatever is on the clipboard now was there before the new game opened.
        currentClipStamp()?.let { lastClipStampChecked = it }
        // The old page stays live until the new one is on screen; nothing it reports counts.
        switching = true
        banner.removeCallbacks(endSwitch)
        banner.postDelayed(endSwitch, SWITCH_TIMEOUT_MS)
        // A tap in the old game isn't a tap in the new one.
        lastUserInputAt = 0L
        probeGuardUntil = 0L
        probeAcceptUntil = 0L
        probeResultsThisPage = 0
        closePopup()
        exitFullscreen()
        gameId = target.id
        gameName = target.name
        gameUrl = target.url
        gameHost = gameUrl.toUri().host?.lowercase(Locale.US).orEmpty()
        pageHost = gameHost.ifEmpty { null }
        pendingPageHost = null
        openedAt = System.currentTimeMillis()
        openedAtElapsed = now
        foregroundMs = 0L
        if (resumedAt > 0) resumedAt = now
        capturedShare = null
        capturedAt = 0L
        openLogOnReturn = false
        lastPageUrl = null
        adBlockActive = blockAdsSetting && !GamePrefs.isSiteAllowed(this, gameHost)
        webView.settings.javaScriptCanOpenWindowsAutomatically = !adBlockActive
        blockedCount.set(0)
        helloAnswered.clear()
        blobFrames.clear()
        shieldPill?.let { p ->
            val icon = androidx.core.content.ContextCompat.getDrawable(
                this, if (adBlockActive) R.drawable.ic_bar_shield else R.drawable.ic_bar_shield_off
            )?.mutate()
            icon?.setBounds(0, 0, dp(20), dp(20))
            p.setCompoundDrawablesRelative(icon, null, null, null)
        }
        updateShieldPill()
        titleView?.text = gameName
        titleView?.contentDescription = "$gameName. Switch game"
        clearHistoryOnLoad = true
        clearHistoryAtFinish = false
        webView.stopLoading()
        webView.loadUrl(gameUrl)
        setResultWithLog(false)
        val more = lineup.count { !it.done && it.id != gameId }
        showBanner(if (playAll && more > 0) "${getString(R.string.now_playing, gameName)} · ${resources.getQuantityString(R.plurals.play_all_banner, more, more)}"
            else getString(R.string.now_playing, gameName))
    }

    // ---- Zoom per site ----------------------------------------------------------------------

    private fun siteZoom(): Float = GamePrefs.siteZoom(this, pageHost ?: gameHost)

    /** Applies the site's saved zoom to the page (CSS zoom, so it works even where pinch-zoom is off). */
    private fun applySiteZoom() {
        val z = siteZoom()
        val js = if (z == 1f) "document.documentElement&&document.documentElement.style.zoom&&(document.documentElement.style.zoom='')"
        else "document.documentElement&&(document.documentElement.style.zoom='$z')"
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun showZoomDialog() {
        val host = pageHost ?: gameHost
        val label = TextView(this).apply {
            textSize = 18f
            typeface = googleSansBold
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        fun refresh() { label.text = getString(R.string.zoom_label, (GamePrefs.siteZoom(this, host) * 100).toInt(), AdBlockEngine.siteOf(host)) }
        fun step(delta: Float) {
            val next = ((GamePrefs.siteZoom(this, host) + delta) * 10).let { kotlin.math.round(it) / 10f }
            GamePrefs.setSiteZoom(this, host, next)
            applySiteZoom()
            refresh()
        }
        refresh()
        zoomDialog?.dismiss()
        zoomDialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.zoom_title)
            .setView(label)
            .setNegativeButton("−", null)
            .setPositiveButton("+", null)
            .setNeutralButton(R.string.zoom_reset, null)
            .create().apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener { step(-0.1f) }
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { step(0.1f) }
                    getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        GamePrefs.setSiteZoom(this@GameActivity, host, 1f)
                        applySiteZoom()
                        refresh()
                    }
                }
            }.also { it.show() }
    }

    // ---- Other apps ------------------------------------------------------------------------

    /** A tapped link to another app: `intent://` gets its safe form, any other scheme a browsable VIEW. */
    private fun launchExternalScheme(uri: Uri) {
        if (uri.scheme.equals("intent", true)) openIntentUri(uri.toString()) else viewExternally(uri)
    }

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
        val launched = safe.data != null && runCatching { startActivity(safe) }.isSuccess
        if (!launched) {
            val fb = fallback?.toUri()
            val s = fb?.scheme?.lowercase(Locale.US)
            if (fb != null && (s == "https" || s == "http")) webView.loadUrl(fb.toString())
        }
    }

    /**
     * Opens [uri] in another app the way a browser would: only activities that accept BROWSABLE links,
     * never a specific component.
     */
    private fun viewExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
        runCatching { startActivity(intent) } // no app for it: ignore
    }

    // ---- Downloads ---------------------------------------------------------------------------
    /** blob: download waiting for its bytes from the page: the frame that made it must answer. */
    private class PendingBlob(val token: String, val origin: String?, val contentDisposition: String?, val mimeType: String?)
    private var pendingSave: Triple<ByteArray, String, String>? = null
    private var pendingBlob: PendingBlob? = null
    /** Frames that created blob: URLs on this page (they said "hi" over DailesDownload), by origin. */
    private val blobFrames = ArrayList<Pair<String, JavaScriptReplyProxy>>()
    private val saveDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = pendingSave?.first
        pendingSave = null
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null || bytes == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
        showBanner(getString(if (ok) R.string.download_saved else R.string.download_failed))
    }

    private fun suggestedName(contentDisposition: String?, mime: String): String {
        GamePlayerPolicy.filenameFromContentDisposition(contentDisposition)?.let { return it }
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return "${gameName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "download" }}.$ext"
    }

    private fun promptSave(bytes: ByteArray, mime: String, name: String) {
        if (bytes.isEmpty()) { showBanner(getString(R.string.download_failed)); return }
        if (bytes.size > MAX_DOWNLOAD_BYTES) { showBanner(DOWNLOAD_TOO_BIG); return }
        pendingSave = Triple(bytes, mime, name)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime.ifBlank { "application/octet-stream" }).putExtra(Intent.EXTRA_TITLE, name)
        try { saveDocument.launch(intent) } catch (e: Exception) { pendingSave = null }
    }

    private fun saveDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        if (url.length > MAX_DATA_URL_CHARS) { showBanner(DOWNLOAD_TOO_BIG); return }
        val decoded = runCatching {
            GamePlayerPolicy.decodeDataUrl(url) { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
        }.getOrNull()
        if (decoded == null) { showBanner(getString(R.string.download_failed)); return }
        val mime = decoded.mime.ifBlank { mimeType.orEmpty() }.ifBlank { "application/octet-stream" }
        promptSave(decoded.bytes, mime, suggestedName(contentDisposition, mime))
    }

    /**
     * Web downloads keep the game's login: on Android 10+ DownloadManager fetches them with the page's
     * cookies into Downloads (no storage permission needed). On Android 7-9, where DownloadManager
     * would need storage permission, the app fetches the file itself and saves it through the system
     * file picker; only if that fails does the link go to the browser.
     */
    private fun startHttpDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { fetchForSave(url, userAgent, contentDisposition, mimeType); return }
        val name = GamePlayerPolicy.filenameFromContentDisposition(contentDisposition)
            ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        val ok = runCatching {
            val req = DownloadManager.Request(url.toUri()).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        }.isSuccess
        if (ok) showBanner("Downloading $name") else viewExternally(url.toUri())
    }

    private fun fetchForSave(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        showBanner(getString(R.string.download_fetching))
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        Thread {
            val headers = buildMap {
                if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
            }
            val r = SafeHttp.get(url, headers, maxBytes = MAX_DOWNLOAD_BYTES + 1, deadlineMs = 60_000)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    r == null || r.code !in 200..299 -> viewExternally(url.toUri())
                    r.body.size > MAX_DOWNLOAD_BYTES -> showBanner(DOWNLOAD_TOO_BIG)
                    else -> {
                        val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                            ?: r.contentType?.substringBefore(';')?.trim()?.ifBlank { null }
                            ?: "application/octet-stream"
                        val name = GamePlayerPolicy.filenameFromContentDisposition(contentDisposition)
                            ?: URLUtil.guessFileName(url, contentDisposition, mime)
                        promptSave(r.body, mime, name)
                    }
                }
            }
        }.start()
    }

    /** DailesDownload channel: "hi" from a frame that made blob: URLs, or the bytes of a requested blob. */
    private fun onDownloadChannelMessage(data: String?, sourceOrigin: Uri, reply: JavaScriptReplyProxy) {
        val m = runCatching { JSONObject(data ?: return) }.getOrNull() ?: return
        val origin = GamePlayerPolicy.originOf(sourceOrigin.toString())
        if (m.optString("t") == "hi") {
            if (origin != null && blobFrames.size < MAX_BLOB_FRAMES) blobFrames.add(origin to reply)
            return
        }
        onBlobDownloadMessage(m, origin)
    }

    /**
     * blob: URLs only exist inside the frame that made them, so that frame posts the bytes back
     * (token-guarded). download-hook.js keeps each Blob, so pages that revoke the URL straight after
     * the click still save; without the hook the main frame fetches the URL itself.
     */
    private fun fetchBlobForSave(url: String, contentDisposition: String?, mimeType: String?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val token = UUID.randomUUID().toString()
        val origin = GamePlayerPolicy.blobOrigin(url)
        pendingBlob = PendingBlob(token, origin, contentDisposition, mimeType)
        val request = JSONObject().put("t", "get").put("url", url).put("token", token).put("max", MAX_DOWNLOAD_BYTES).toString()
        val frames = blobFrames.filter { it.first == origin }
        if (frames.isNotEmpty()) {
            frames.forEach { runCatching { it.second.postMessage(request) } }
        } else {
            val js = "(function(){var ch=window.DailesDownload;if(!ch)return;" +
                "fetch(${JSONObject.quote(url)}).then(function(r){return r.blob()}).then(function(b){" +
                "if(b.size>$MAX_DOWNLOAD_BYTES){ch.postMessage(JSON.stringify({token:${JSONObject.quote(token)},error:'size'}));return;}" +
                "var fr=new FileReader();fr.onload=function(){ch.postMessage(JSON.stringify({token:${JSONObject.quote(token)},type:b.type,data:fr.result}))};" +
                "fr.readAsDataURL(b)}).catch(function(){ch.postMessage(JSON.stringify({token:${JSONObject.quote(token)},error:'fetch'}))})})()"
            webView.evaluateJavascript(js, null)
        }
        // No answer at all (frame gone, blob freed): say so instead of failing silently.
        container.postDelayed({
            if (pendingBlob?.token == token) {
                pendingBlob = null
                showBanner(getString(R.string.download_failed))
            }
        }, BLOB_ANSWER_TIMEOUT_MS)
    }

    private fun onBlobDownloadMessage(m: JSONObject, senderOrigin: String?) {
        val pending = pendingBlob ?: return
        if (m.optString("token") != pending.token) return // only the download the user just started
        if (pending.origin != null && senderOrigin != pending.origin) return // only the blob's own frame
        pendingBlob = null
        val dataUrl = m.optString("data")
        when {
            m.optString("error") == "size" -> showBanner(DOWNLOAD_TOO_BIG)
            m.has("error") || !dataUrl.startsWith("data:") -> showBanner(getString(R.string.download_failed))
            else -> saveDataUrl(dataUrl, pending.contentDisposition, m.optString("type").ifBlank { pending.mimeType })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Tonal pill with an optional Material Symbols PNG icon (tinted to [fg]) before the label.
     * Every pill is at least a 48 dp touch target; icon-only pills are 48 dp squares.
     */
    private fun pill(label: String, desc: String, bg: Int, fg: Int, iconRes: Int = 0, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = label
            if (iconRes != 0) {
                val icon = androidx.core.content.ContextCompat.getDrawable(this@GameActivity, iconRes)?.mutate()
                icon?.setBounds(0, 0, dp(20), dp(20))
                setCompoundDrawablesRelative(icon, null, null, null)
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(fg))
                compoundDrawablePadding = if (label.isEmpty()) 0 else dp(6)
            }
            contentDescription = desc
            setTextColor(fg)
            textSize = 13f
            typeface = googleSansBold
            // Google Sans's extra top/bottom font padding is uneven; without it text centres on its letters.
            includeFontPadding = false
            gravity = Gravity.CENTER
            setSingleLine(true)
            minWidth = dp(48)
            minHeight = dp(48)
            if (label.isEmpty()) setPadding(dp(14), dp(14), dp(14), dp(14)) else setPadding(dp(12), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(bg)
            }
            isClickable = true
            setOnClickListener { v -> onClick(v) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        }

    companion object {
        const val EXTRA_ID = "game_id"
        const val EXTRA_NAME = "game_name"
        const val EXTRA_URL = "game_url"
        const val EXTRA_LOG = "open_log"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_OPENED_AT = "opened_at"
        const val EXTRA_SHARE_TEXT = "share_text"
        /** Today's games in the hub's order: [{id, name, url, done}]. */
        const val EXTRA_LINEUP = "lineup"
        const val EXTRA_PLAY_ALL = "play_all"
        private const val STATE_PLAY_ALL = "state_play_all"
        private const val AUTO_NEXT_SECONDS = 4
        /** Games played earlier in this player session: [{id, elapsedMs, openedAt, share}]. */
        const val EXTRA_EARLIER = "earlier_results"
        private const val STATE_GAME_ID = "state_game_id"
        private const val STATE_GAME_NAME = "state_game_name"
        private const val STATE_GAME_URL = "state_game_url"
        private const val STATE_LINEUP = "state_lineup"
        private const val STATE_EARLIER = "state_earlier"
        private const val MAX_LINEUP = 200
        private const val STATE_OPENED_AT = "state_opened_at"
        private const val STATE_SHARE_TEXT = "state_share_text"
        private const val STATE_SHARE_AT = "state_share_at"
        private const val STATE_FOREGROUND_MS = "state_foreground_ms"
        private const val STATE_OPEN_LOG = "state_open_log"
        private const val STATE_CLIP_STAMP = "state_clip_stamp"
        private const val STATE_CLEAR_HISTORY = "state_clear_history"
        /** How long windows, other apps and downloads stay shut after the hook pressed a Share button. */
        private const val PROBE_GUARD_MS = 2_500L
        /** A found result must arrive this soon after the press. */
        private const val PROBE_ACCEPT_MS = 3_000L
        private const val PROMPT_MIN_GAP_MS = 5_000L
        /** Gives up waiting for the new game's page after this long (a load that never commits). */
        private const val SWITCH_TIMEOUT_MS = 15_000L
        /** Clip timestamps above this are wall-clock times (2001-09-09), smaller ones elapsedRealtime. */
        private const val WALL_CLOCK_MIN = 1_000_000_000_000L
        private const val MAX_COSMETIC_MESSAGE = 512 * 1024
        private const val MAX_TOKENS_PER_BATCH = 2000
        private const val MAX_FRAME_HELLOS = 64
        private const val MAX_BLOB_FRAMES = 32
        private const val MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024
        /** A base64 data: URL of MAX_DOWNLOAD_BYTES, plus room for its header. */
        private const val MAX_DATA_URL_CHARS = (MAX_DOWNLOAD_BYTES + 2) / 3 * 4 + 4096
        private const val BLOB_ANSWER_TIMEOUT_MS = 15_000L
        /** A share/copy hook result counts only this soon after a real tap or key press. */
        // Long enough for games that show a results animation before copying.
        private const val SHARE_GESTURE_WINDOW_MS = 30_000L
        private const val DOWNLOAD_GESTURE_WINDOW_MS = 15_000L
        private const val RENDERER_CRASH_WINDOW_MS = 60_000L
        /** Below this width the shield shows only its icon, so the game title keeps some room. */
        private const val WIDE_BAR_DP = 400
        private const val BAR_SIDE_PADDING = 6
        private const val DOWNLOAD_TOO_BIG = "That file is too big to save (25 MB max)."
        private const val RELOADED_AFTER_CRASH = "The game page stopped and was reloaded."

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
        private val CONTAINER = "#211E2B".toColorInt()
        private val PRIMARY = "#D3BCFF".toColorInt()
        private val ON_PRIMARY = "#381E72".toColorInt()
        private val PRIMARY_CONTAINER = "#4F378B".toColorInt()
        private val ON_PRIMARY_CONTAINER = "#EADDFF".toColorInt()
        // Light theme (Material 3 baseline light roles, as in the hub's light theme).
        private val LIGHT_CONTAINER = "#F3EDF7".toColorInt()
        private val LIGHT_PRIMARY = "#6750A4".toColorInt()
        private val LIGHT_PRIMARY_CONTAINER = "#EADDFF".toColorInt()
        private val LIGHT_ON_PRIMARY_CONTAINER = "#21005D".toColorInt()
        private val LIGHT_ON_SURFACE = "#1D1B20".toColorInt()
    }
}

/**
 * The game player's pure decisions (popups, download names, data: URLs), free of Android types so
 * they run in JVM unit tests.
 */
internal object GamePlayerPolicy {
    enum class PopupAction { DIALOG, EXTERNAL, BLOCK }

    /** Hosts that are sign-in pages whatever the path. */
    private val AUTH_HOSTS = setOf(
        "accounts.google.com", "appleid.apple.com", "login.microsoftonline.com", "login.live.com",
        "id.twitch.tv", "accounts.spotify.com", "myaccount.nytimes.com", "auth.nytimes.com"
    )
    private val AUTH_HOST_PREFIXES = listOf("login.", "auth.", "accounts.", "account.", "signin.", "sso.", "id.", "oauth.")
    /** Sign-in paths on general sites: facebook.com/dialog/oauth, github.com/login/oauth, x.com/i/oauth2/…, Firebase's /__/auth/. */
    private val AUTH_PATH = Regex("(^|/)(oauth2?|__|auth|authorize|login|signin|sign-in|sso|openid-connect)(/|$)", RegexOption.IGNORE_CASE)

    fun isAuthPage(host: String?, path: String?): Boolean {
        val h = host?.lowercase(Locale.US)?.trimEnd('.').orEmpty()
        if (h.isEmpty()) return false
        if (h in AUTH_HOSTS || AUTH_HOST_PREFIXES.any { h.startsWith(it) }) return true
        return AUTH_PATH.containsMatchIn(path.orEmpty())
    }

    /**
     * Where a new window goes, once its first real address is known: the game's own site and sign-in
     * pages stay in the in-app popup; another site opens in the browser if the user tapped; otherwise
     * (a pop-up nobody asked for) it is dropped.
     */
    fun popupAction(host: String?, path: String?, gameHost: String, userGesture: Boolean): PopupAction {
        val h = host?.lowercase(Locale.US)?.trimEnd('.').orEmpty()
        if (h.isEmpty()) return PopupAction.BLOCK
        if (gameHost.isNotEmpty() && AdBlockEngine.sameSite(h, gameHost.lowercase(Locale.US))) return PopupAction.DIALOG
        if (isAuthPage(h, path)) return PopupAction.DIALOG
        return if (userGesture) PopupAction.EXTERNAL else PopupAction.BLOCK
    }

    /** "scheme://host[:port]" of an http(s) origin string, lower-cased; null otherwise. */
    fun originOf(origin: String): String? {
        val s = origin.trim().lowercase(Locale.US)
        val sep = s.indexOf("://")
        if (sep <= 0) return null
        val scheme = s.substring(0, sep)
        if (scheme != "http" && scheme != "https") return null
        val end = s.indexOfAny(charArrayOf('/', '?', '#'), sep + 3).let { if (it < 0) s.length else it }
        val authority = s.substring(sep + 3, end).substringAfterLast('@')
        if (authority.isEmpty()) return null
        return "$scheme://$authority"
    }

    /** Origin a blob: URL belongs to ("blob:https://game.com/uuid" -> "https://game.com"). */
    fun blobOrigin(url: String): String? =
        if (url.startsWith("blob:", ignoreCase = true)) originOf(url.substring(5)) else null

    /**
     * File name from a Content-Disposition header: RFC 5987 `filename*=charset''percent-encoded`
     * first, then `filename=` (quoted or not). Path parts and control characters are removed.
     */
    fun filenameFromContentDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*\"?([A-Za-z0-9_-]*)'[^']*'([^\";]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = runCatching { Charset.forName(m.groupValues[1].ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
            val decoded = runCatching { String(percentDecode(m.groupValues[2].trim(), charset), charset) }.getOrNull()
            cleanFileName(decoded)?.let { return it }
        }
        Regex("(?<![*A-Za-z])filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            cleanFileName(m.groupValues[1].ifEmpty { m.groupValues[2] })?.let { return it }
        }
        return null
    }

    private fun cleanFileName(raw: String?): String? {
        val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { it >= ' ' && it != '\u007F' }?.trim()?.take(120)
        return name?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    class DataUrl(val mime: String, val bytes: ByteArray)

    /**
     * Decodes a data: URL to its bytes. Percent-escapes become raw bytes (so binary survives), other
     * characters are UTF-8; `;base64` payloads go through [base64] after removing escapes and spaces.
     */
    fun decodeDataUrl(url: String, base64: (String) -> ByteArray): DataUrl? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val params = meta.split(';').map { it.trim() }
        val mime = params.first().lowercase(Locale.US)
        val payload = url.substring(comma + 1)
        val bytes = if (params.drop(1).any { it.equals("base64", true) }) {
            val ascii = String(percentDecode(payload, Charsets.UTF_8), Charsets.ISO_8859_1).filterNot { it.isWhitespace() }
            base64(ascii)
        } else {
            percentDecode(payload, Charsets.UTF_8)
        }
        return DataUrl(mime, bytes)
    }

    /** `%XX` escapes to their byte; every other character encoded in [charset]. */
    fun percentDecode(s: String, charset: Charset): ByteArray {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) { out.write(plain.toString().toByteArray(charset)); plain.setLength(0) }
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    flushPlain()
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            plain.append(c)
            i++
        }
        flushPlain()
        return out.toByteArray()
    }
}
