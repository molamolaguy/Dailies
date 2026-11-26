package com.dailygames.hub

import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.graphics.drawable.LayerDrawable
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
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    @Volatile private var gameId = ""
    private var gameUrl = ""
    /** Effective URL after an initial server-side redirect before user interaction. */
    private var effectiveGameUrl = ""
    /** Enabled while there is page history, an overlay card, or fullscreen to undo (predictive back, K1). */
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
    @Volatile private var gameHost = ""
    private var initialHost = ""
    private var surfaceColor = 0
    private var titleColor = Color.WHITE
    /** Last http(s) page shown, so a replacement WebView can reload it after the renderer is gone. */
    private var lastPageUrl: String? = null
    private var lastRendererCrashAt = 0L
    private var lastRendererGoneAt = 0L
    /** Last real touch / key press anywhere in the player (elapsedRealtime). Pages can't fake these. */
    @Volatile private var lastUserInputAt = 0L
    private var hasUserInteracted = false
    private var lastNightMode = 0

    // ---- Share-result capture (layer 1: clipboard listener, layer 2: page share/copy hooks) ----
    private var openedAt = System.currentTimeMillis()

    // ---- Today's games (switch between them without going back to the hub) ----
    private class LineupGame(val id: String, val name: String, val url: String, var done: Boolean)
    private val lineup = ArrayList<LineupGame>()
    /** This game's files preloaded after its reset (PageCache), or null. */
    @Volatile private var preloaded: PageCache.Index? = null
    @Volatile private var preloadLatch: CountDownLatch? = null

    private fun loadPreloaded(id: String) {
        preloaded = null
        val latch = CountDownLatch(1)
        preloadLatch = latch
        val app = applicationContext
        Thread {
            try {
                val idx = PageCache.load(app, id)
                if (gameId == id && preloadLatch === latch) preloaded = idx
            } finally {
                latch.countDown()
            }
        }.start()
    }

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
    private var inFinish = false
    private var findResults = true
    /** Marks the page hook's messages as the app's own; the page can't read it (see share-hook.js). */
    private val pageKey: String = UUID.randomUUID().toString().replace("-", "")
    /** After the hook says it pressed a Share button: windows, other apps and downloads stay shut. */
    @Volatile private var probeGuardUntil = 0L
    /** A found result counts only right after the hook pressed a button, and once per page. */
    private var probeAcceptUntil = 0L
    private var probeResultsThisPage = 0
    private var lastPromptAt = 0L
    private val openPopups = ArrayList<PopupWindow>()
    private var zoomDialog: ComponentDialog? = null
    private var capturedShare: String? = null
    /** The captured result was confirmed by the player (an on-screen result they chose to log, 1.8.3). */
    private var capturedConfirmed = false
    /** An on-screen result was offered on this page already (once per page). */
    private var screenOfferedThisPage = false
    private var screenPrompt: View? = null
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
        CrashLog.install(this)

        gameId = intent.getStringExtra(EXTRA_ID).orEmpty()
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Daily Game" }
        gameUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        // After switching games and a recreate, carry on with the game that was on screen.
        savedInstanceState?.getString(STATE_GAME_ID)?.takeIf { it.isNotEmpty() }?.let { id ->
            gameId = id
            gameName = savedInstanceState.getString(STATE_GAME_NAME).orEmpty().ifBlank { "Daily Game" }
            gameUrl = savedInstanceState.getString(STATE_GAME_URL).orEmpty()
        }
        effectiveGameUrl = gameUrl
        readLineup(savedInstanceState?.getString(STATE_LINEUP) ?: intent.getStringExtra(EXTRA_LINEUP))
        loadPreloaded(gameId)
        playAll = savedInstanceState?.getBoolean(STATE_PLAY_ALL) ?: intent.getBooleanExtra(EXTRA_PLAY_ALL, false)
        savedInstanceState?.getString(STATE_EARLIER)?.let { earlierResults = runCatching { JSONArray(it) }.getOrDefault(JSONArray()) }

        if (!gameUrl.startsWith("https://", true) && !gameUrl.startsWith("http://", true)) {
            finish()
            return
        }
        if (savedInstanceState != null) {
            openedAt = savedInstanceState.getLong(STATE_OPENED_AT, openedAt)
            if (savedInstanceState.containsKey(STATE_OPENED_ELAPSED)) {
                val age = savedInstanceState.getLong(STATE_OPENED_ELAPSED, 0L).coerceAtLeast(0L)
                openedAtElapsed = (SystemClock.elapsedRealtime() - age).coerceAtLeast(0L)
            } else {
                openedAtElapsed = SystemClock.elapsedRealtime()
            }
            capturedShare = savedInstanceState.getString(STATE_SHARE_TEXT)
            capturedConfirmed = savedInstanceState.getBoolean(STATE_SHARE_CONFIRMED, false)
            capturedAt = savedInstanceState.getLong(STATE_SHARE_AT, 0L)
            foregroundMs = savedInstanceState.getLong(STATE_FOREGROUND_MS, 0L)
            openLogOnReturn = savedInstanceState.getBoolean(STATE_OPEN_LOG, false)
            lastClipStampChecked = savedInstanceState.getLong(STATE_CLIP_STAMP, 0L)
            clearHistoryAtFinish = savedInstanceState.getBoolean(STATE_CLEAR_HISTORY, false)
            lastPageUrl = savedInstanceState.getString(STATE_LAST_PAGE_URL)
            hasUserInteracted = savedInstanceState.getBoolean(STATE_USER_INTERACTED, false)
            savedInstanceState.getString(STATE_EFFECTIVE_GAME_URL)
                ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                ?.let { effectiveGameUrl = it }
        }
        autoLogEnabled = GamePrefs.autoLogShare(this)
        findResults = GamePrefs.findResults(this)
        blockAdsSetting = GamePrefs.blockAds(this)
        PublicSuffix.ensureLoaded(this) // so per-site choices use the same "site" as the blocker
        initialHost = gameUrl.toUri().host?.lowercase(Locale.US).orEmpty()
        gameHost = effectiveGameUrl.toUri().host?.lowercase(Locale.US).orEmpty().ifEmpty { initialHost }
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
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
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
        accentColor = if (light) primaryColor else primaryContainerColor
        accentColors = intArrayOf(primaryColor, onPrimaryColor)
        menuColors = selectMenuColors(light, containerColor, titleColor, primaryColor, primaryContainerColor, onPrimaryContainerColor)

        // ---- Top app bar (Material 3 Expressive tonal pills + Google Sans) ----
        // At 360 dp: 4 icon pills of 48 dp + "Log" + gaps leave room for the title, which ellipsizes.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBackground(containerColor, light)
            setPadding(dp(BAR_SIDE_PADDING), dp(6), dp(BAR_SIDE_PADDING), dp(7))
        }
        bar.addView(pill("", "Back to hub", primaryContainerColor, onPrimaryContainerColor, R.drawable.ic_bar_arrow_back) { finish() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
        })
        val switchable = lineup.size > 1
        titleView = TextView(this).apply {
            text = gameName
            contentDescription = if (switchable) "$gameName. Switch game" else gameName
            setTextColor(titleColor)
            textSize = 15.5f
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
                    compoundDrawablePadding = dp(4)
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
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(primaryContainerColor)
                setStroke(maxOf(1, dp(1)), sheetStrokeColor())
            }
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
                dp(BAR_SIDE_PADDING),
                dp(6) + sysBars.top,
                dp(BAR_SIDE_PADDING),
                dp(7)
            )
            val bottomInset = maxOf(sysBars.bottom, ime.bottom)
            root.setPadding(sysBars.left, 0, sysBars.right, bottomInset)
            // The floating cards and banner follow the keyboard, navigation bar, and horizontal cutouts.
            listOfNotNull(donePrompt, screenPrompt).forEach { card ->
                (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.leftMargin = dp(12) + sysBars.left
                    lp.rightMargin = dp(12) + sysBars.right
                    lp.bottomMargin = dp(20) + bottomInset
                    card.layoutParams = lp
                }
            }
            (banner.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = dp(16) + sysBars.left
                lp.rightMargin = dp(16) + sysBars.right
                lp.bottomMargin = dp(28) + bottomInset
                banner.layoutParams = lp
            }
            WindowInsetsCompat.CONSUMED
        }

        window.setBackgroundDrawable(surfaceColor.toDrawable())
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, backCallback)

        // If killed mid-switch before the new game finished loading, don't restore old history.
        val restored = savedInstanceState != null && !clearHistoryAtFinish &&
            runCatching { webView.restoreState(savedInstanceState) }.getOrNull() != null
        if (!restored) {
            clearHistoryAtFinish = false
            webView.loadUrl(gameUrl)
        }
        updateBackIntercept()
    }

    private var barColors = intArrayOf(0, 0, 0)
    /** The pull-to-refresh ring's color: the theme's accent, dark enough to show on white. */
    private var accentColor = 0
    private var accentColors = intArrayOf(0, 0)
    private var donePrompt: View? = null

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
                // A share link (X, WhatsApp, a text message…) carries the result itself (1.8.3).
                captureShareLink(uri.toString(), request.hasGesture())
                val scheme = uri.scheme?.lowercase(Locale.US)
                if (scheme == "http" || scheme == "https") {
                    // A Share button the hook pressed doesn't get to take the game page elsewhere.
                    if (probeGuarded() && request.isForMainFrame && !request.isRedirect) return true
                    if (request.isForMainFrame && request.isRedirect && !hasUserInteracted) {
                        adoptInitialRedirect(uri.toString())
                    }
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
                request?.let { req ->
                    filterRequest(req) ?: run {
                        if (!req.isForMainFrame && preloaded == null) {
                            preloadLatch?.let { latch -> runCatching { latch.await(100, TimeUnit.MILLISECONDS) } }
                        }
                        PageCache.respond(preloaded, req)
                    }
                }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                this@GameActivity.progress.visibility = View.VISIBLE
                // Commit the new page's host now (main-frame requests only record it as pending, so a
                // cancelled navigation can't change which site the blocker treats as first-party).
                val started = url?.toUri()?.host?.lowercase(Locale.US)
                pageHost = started ?: pendingPageHost ?: pageHost
                pendingPageHost = null
                if (view === webView && !hasUserInteracted && url != null) {
                    adoptInitialRedirect(url)
                }
                rememberPageUrl(url)
                // A new document: every frame asks for its page CSS again, and old frames are gone.
                helloAnswered.clear()
                blobFrames.clear()
                if (view === webView) { probeResultsThisPage = 0; probeAcceptUntil = 0L; screenOfferedThisPage = false }
                if (!documentStartScriptsSupported) injectPageScriptsFallback()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                this@GameActivity.progress.visibility = View.GONE
                if (view === webView && !hasUserInteracted && url != null) {
                    adoptInitialRedirect(url)
                }
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
                // On API 24-25 RenderProcessGoneDetail.didCrash() doesn't exist, so treat it as crashed to bound crash loops.
                val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() == true else true
                recoverFromRendererGone(view, crashed)
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
     * Adopts a redirected domain/URL observed during the initial load before any user interaction
     * (e.g. m-w.com/games/quordle -> merriam-webster.com/games/quordle) so share hooks, clipboard
     * checks, and ShareResultDetector accept captures from the canonical game host.
     */
    private fun adoptInitialRedirect(url: String) {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
        val host = url.toUri().host?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return
        if (host != gameHost) {
            gameHost = host
            if (pageHost.isNullOrEmpty() || pageHost == initialHost) pageHost = host
            adBlockActive = blockAdsSetting && !GamePrefs.isSiteAllowed(this, gameHost)
            if (::webView.isInitialized) webView.settings.javaScriptCanOpenWindowsAutomatically = !adBlockActive
            shieldPill?.let { p ->
                val icon = androidx.core.content.ContextCompat.getDrawable(
                    this, if (adBlockActive) R.drawable.ic_bar_shield else R.drawable.ic_bar_shield_off
                )?.mutate()
                icon?.setBounds(0, 0, dp(20), dp(20))
                p.setCompoundDrawablesRelative(icon, null, null, null)
            }
            updateShieldPill()
        }
        effectiveGameUrl = url
    }

    /**
     * The renderer was killed (low memory, often while the player was in the background) or crashed:
     * put a fresh WebView in place and reload the page. A second crash within a minute (or any rapid
     * renderer loss within 15s) closes the player instead, so a page that always crashes can't loop.
     */
    private fun recoverFromRendererGone(view: WebView?, crashed: Boolean) {
        if (view == null) return
        if (view === popupView) { closePopup(); return }
        if (!::webView.isInitialized || view !== webView) {
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
            return
        }
        dismissTransientPopups()
        closePopup()
        exitFullscreen()
        val index = root.indexOfChild(view).takeIf { it >= 0 } ?: root.childCount
        root.removeView(view)
        runCatching { view.destroy() }
        val now = SystemClock.elapsedRealtime()
        val repeatedCrash = (crashed && lastRendererCrashAt > 0 && now - lastRendererCrashAt < RENDERER_CRASH_WINDOW_MS) ||
            (lastRendererGoneAt > 0 && now - lastRendererGoneAt < RENDERER_RAPID_GONE_MS)
        if (crashed) lastRendererCrashAt = now
        lastRendererGoneAt = now
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

    private fun dismissTransientPopups() {
        openPopups.toList().forEach { runCatching { it.dismiss() } }
        openPopups.clear()
        zoomDialog?.let { runCatching { it.dismiss() } }
        zoomDialog = null
    }

    // =====================================================================================
    // Popup windows
    // =====================================================================================

    /**
     * Child WebView for window.open / target=_blank. Its first real address decides where it goes:
     * same-site and sign-in pages stay in a dialog (so the opener keeps its window.opener link), a
     * tapped link to another site opens in the browser, anything else is dropped.
     */
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
            // A share window the game opened (1.8.3): the result is in its address.
            if (scheme != "about" && scheme != "javascript" && scheme != null) captureShareLink(uri.toString(), userGesture)
            // window.open('') / about:blank first, the real address follows.
            if (scheme == "about" || scheme == "javascript" || scheme == null) return true
            decided = true
            if (scheme != "http" && scheme != "https") {
                container.post { closePopup() }
                if (userGesture && !probeGuarded() && scheme != "blob" && scheme != "data") launchExternalScheme(uri)
                return false
            }
            return when (GamePlayerPolicy.popupAction(uri.host, uri.path, gameHost, userGesture)) {
                GamePlayerPolicy.PopupAction.DIALOG -> { showPopupDialog(child, uri.host.orEmpty()); true }
                GamePlayerPolicy.PopupAction.EXTERNAL -> { container.post { closePopup() }; viewExternally(uri); false }
                GamePlayerPolicy.PopupAction.BLOCK -> { container.post { closePopup() }; onRequestBlocked(); false }
            }
        }
        // If the page opened a blank window (window.open(''); w.document.write(...)) and never navigated, show it anyway.
        container.postDelayed({
            if (!decided && popupView === child && !isFinishing && !isDestroyed) {
                decided = true
                popupHost = gameHost.ifEmpty { null }
                showPopupDialog(child, gameHost)
            }
        }, 300)
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (!decided) return !decide(uri)
                captureShareLink(uri.toString(), request.hasGesture())
                val scheme = uri.scheme?.lowercase(Locale.US)
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" ||
                    scheme == "data" || scheme == "javascript") return false
                if (request.hasGesture() && !probeGuarded()) launchExternalScheme(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Fallback in case the first load didn't pass through shouldOverrideUrlLoading.
                if (!decided && url != null && !decide(url.toUri())) view?.stopLoading()
                val h = url?.toUri()?.host?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
                popupTitle?.text = h ?: gameHost
                h?.let { popupHost = it }
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
                container.post { closePopup() }
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
        val light = ThemePrefs.isLight(this)
        val dlg = ComponentDialog(this, if (light) R.style.Theme_Dailies_Light else R.style.Theme_Dailies)
        val title = TextView(this).apply {
            text = host
            setTextColor(titleColor)
            textSize = 15f
            typeface = googleSansBold
            includeFontPadding = false
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        popupTitle = title
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBackground(containerColor, light)
            setPadding(dp(BAR_SIDE_PADDING), dp(6), dp(BAR_SIDE_PADDING), dp(7))
            addView(pill("", "Close and return to the game", pillBg, pillFg, R.drawable.ic_bar_arrow_back) { closePopup() }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
            })
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
        dlg.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.onBackPressedDispatcher.addCallback(dlg, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = popupView
                val list = view?.copyBackForwardList()
                val prev = if (list != null && list.currentIndex > 0) list.getItemAtIndex(list.currentIndex - 1)?.url else null
                if (view != null && view.canGoBack() && prev != "about:blank") view.goBack() else closePopup()
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
                    // A result read off the end screen (no Share button): offered, never logged by itself.
                    if (msg.optString("via") == "screen") {
                        if (findResults) offerScreenResult(msg.optString("text"))
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
                WebViewCompat.addDocumentStartJavaScript(webView, selectMenuScript(), anyOrigin)
                if (GamePrefs.hideCookies(this)) WebViewCompat.addDocumentStartJavaScript(webView, pageScript("game/cookie-hide.js"), anyOrigin)
                if (GamePrefs.pullRefresh(this)) WebViewCompat.addDocumentStartJavaScript(webView, pullRefreshScript(), anyOrigin)
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            if (autoLogEnabled) webView.evaluateJavascript(shareHookScript(), null)
            if (blockAdsSetting && adBlockActive) webView.evaluateJavascript(pageScript("game/cosmetic.js"), null)
            webView.evaluateJavascript(pageScript("game/download-hook.js"), null)
        }
        webView.evaluateJavascript(selectMenuScript(), null)
        if (GamePrefs.hideCookies(this)) webView.evaluateJavascript(pageScript("game/cookie-hide.js"), null)
        if (GamePrefs.pullRefresh(this)) webView.evaluateJavascript(pullRefreshScript(), null)
    }

    /** The colours of the in-page drop-down menu (select-menu.js), as a JS object literal. */
    private var menuColors = "{}"

    private fun selectMenuColors(light: Boolean, bg: Int, fg: Int, accent: Int, sel: Int, selFg: Int): String {
        fun hex(c: Int) = String.format(Locale.US, "#%06X", 0xFFFFFF and c)
        fun rgba(c: Int, a: Double) = String.format(Locale.US, "rgba(%d,%d,%d,%.2f)", Color.red(c), Color.green(c), Color.blue(c), a)
        return JSONObject()
            .put("light", light)
            .put("bg", hex(bg))
            .put("fg", hex(fg))
            .put("accent", hex(accent))
            .put("sel", hex(sel))
            .put("selFg", hex(selFg))
            .put("outline", rgba(fg, 0.14))
            .put("press", rgba(fg, 0.08))
            .toString()
    }

    /** select-menu.js with the player's colours filled in. */
    private fun selectMenuScript(): String = pageScript("game/select-menu.js").replace("__DAILIES_MENU_COLORS__", menuColors)

    /** pull-refresh.js in the player's accent color. */
    private fun pullRefreshScript(): String =
        pageScript("game/pull-refresh.js").replace("__DAILIES_COLOR__", String.format(Locale.US, "#%06X", 0xFFFFFF and (if (accentColor == 0) LIGHT_PRIMARY else accentColor)))

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
    private fun checkClipboardSinceOpen(ignoreFocus: Boolean = false) {
        if (!autoLogEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || (!ignoreFocus && !hasWindowFocus())) return
        val cm = clipboardManager ?: return
        val stamp = runCatching { cm.primaryClipDescription?.timestamp }.getOrNull() ?: return
        if (!clipNewSinceOpen(stamp) || stamp == lastClipStampChecked) return
        lastClipStampChecked = stamp
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        // Found after the fact, so it must explicitly name this game (not just any `#12` puzzle number).
        if (!ShareResultDetector.mentionsGameExplicitly(text, gameName, effectiveGameUrl)) return
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
        if (!ShareResultDetector.isStrictShareResult(clean, gameName, effectiveGameUrl)) return
        acceptShare(clean, via, confirmed = false)
    }

    /** Keeps [clean] as this game's result and shows the "result saved" card. */
    private fun acceptShare(clean: String, via: String, confirmed: Boolean) {
        hideScreenPrompt()
        val entry = lineup.firstOrNull { it.id == gameId }
        val wasDone = entry?.done == true
        capturedShare = clean
        capturedConfirmed = confirmed
        capturedAt = System.currentTimeMillis()
        entry?.done = true
        setResultWithLog(openLogOnReturn)
        if (inFinish || isFinishing || isDestroyed) return
        // Back on a game that was already done: keep the result quietly instead of asking again.
        if (via == "probe" && wasDone) return
        // A page can't keep a card over the game by sending results over and over.
        val now = SystemClock.elapsedRealtime()
        if (donePrompt != null && now - lastPromptAt < PROMPT_MIN_GAP_MS) return
        lastPromptAt = now
        showDonePrompt(clean)
    }

    /**
     * A link the game opens to share its result (1.8.3): X, WhatsApp, a text message… The result is in
     * the link, so it's read without opening anything. Taken like a copy: after the player's own tap,
     * or right after the hook pressed the game's Share button (once per page).
     */
    private fun captureShareLink(link: String, gesture: Boolean) {
        if (!autoLogEnabled) return
        val text = ShareLinkText.extract(link) ?: return
        val now = SystemClock.elapsedRealtime()
        if (gesture || userActedWithin(SHARE_GESTURE_WINDOW_MS)) {
            offerShare(text, "link")
        } else if (findResults && now <= probeAcceptUntil && probeResultsThisPage < 1) {
            probeResultsThisPage += 1
            offerShare(text, "probe")
        }
    }

    /**
     * A result the end screen shows when the game has no Share / Copy button (1.8.3), e.g. "Solved in
     * 4 guesses". Never logged by itself: a card asks, and only "Log it" keeps it. Once per page, and
     * not when a result was already found.
     */
    private fun offerScreenResult(raw: String?) {
        val line = raw?.replace(Regex("\\s+"), " ")?.trim()?.take(80).orEmpty()
        if (line.length < 3 || switching || capturedShare != null || screenOfferedThisPage || donePrompt != null) return
        if (lineup.firstOrNull { it.id == gameId }?.done == true) return
        if (!::container.isInitialized || isFinishing) return
        screenOfferedThisPage = true
        hideScreenPrompt()
        val (containerColor, tonalBg, tonalFg) = Triple(barColors[0], barColors[1], barColors[2])
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(containerColor)
                setStroke(maxOf(1, dp(1)), sheetStrokeColor())
            }
            elevation = dp(10).toFloat()
            isClickable = true
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.screen_result_title, gameName)
            setTextColor(titleColor)
            textSize = 15f
            typeface = googleSansBold
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(TextView(this).apply {
            text = line
            setTextColor(titleColor)
            alpha = 0.8f
            textSize = 13.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        buttons.addView(pill(getString(R.string.not_now), getString(R.string.not_now), tonalBg, tonalFg, R.drawable.ic_bar_close) { hideScreenPrompt() })
        buttons.addView(pill(getString(R.string.log_it), getString(R.string.log_it), accentColors[0], accentColors[1]) {
            hideScreenPrompt()
            // The game's name in front, so the hub knows whose result it is.
            acceptShare(if (line.contains(gameName, ignoreCase = true)) line else "$gameName: $line", "screen", confirmed = true)
        })
        card.addView(buttons)
        container.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
            leftMargin = dp(12) + root.paddingLeft
            rightMargin = dp(12) + root.paddingRight
            bottomMargin = dp(20) + root.paddingBottom
        })
        card.translationY = dp(40).toFloat()
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(220).start()
        card.announceForAccessibility(getString(R.string.screen_result_title, gameName))
        screenPrompt = card
        updateBackIntercept()
    }

    private fun hideScreenPrompt() {
        screenPrompt?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        screenPrompt = null
        updateBackIntercept()
    }

    /**
     * After a result is captured: "Play next" (opens the next unplayed game right here) or "Back to
     * hub". ✕ keeps playing. With no game left, only "Back to hub".
     */
    private fun showDonePrompt(result: String) {
        if (!::container.isInitialized || inFinish || isFinishing || isDestroyed) return
        hideDonePrompt()
        banner.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        val (containerColor, tonalBg, tonalFg) = Triple(barColors[0], barColors[1], barColors[2])
        val next = nextGame()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(containerColor)
                setStroke(maxOf(1, dp(1)), sheetStrokeColor())
            }
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
        val narrow = next != null && (resources.configuration.screenWidthDp < 380 || resources.configuration.fontScale > 1.15f)
        val buttons = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (narrow) Gravity.END else (Gravity.END or Gravity.CENTER_VERTICAL)
            setPadding(0, dp(12), 0, 0)
        }
        val hubPill = pill(getString(R.string.back_to_hub), getString(R.string.back_to_hub), tonalBg, tonalFg, R.drawable.ic_bar_arrow_back) {
            hideDonePrompt()
            finish()
        }
        buttons.addView(hubPill)
        var nextPill: TextView? = null
        if (next != null) {
            val availWidth = (resources.displayMetrics.widthPixels - root.paddingLeft - root.paddingRight - dp(if (narrow) 72 else 210))
                .coerceAtLeast(dp(140))
            nextPill = pill(getString(R.string.play_next_named, next.name), getString(R.string.play_next_named, next.name),
                accentColors[0], accentColors[1], R.drawable.ic_bar_next) {
                hideDonePrompt()
                switchToGame(next)
            }.apply {
                maxWidth = minOf(dp(240), availWidth)
                ellipsize = TextUtils.TruncateAt.END
                if (narrow) {
                    (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.marginStart = 0
                        lp.topMargin = dp(8)
                        layoutParams = lp
                    }
                }
            }
            buttons.addView(nextPill)
        }
        card.addView(buttons)
        container.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
            leftMargin = dp(12) + root.paddingLeft
            rightMargin = dp(12) + root.paddingRight
            bottomMargin = dp(20) + root.paddingBottom
        })
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
        banner.animate().cancel()
        banner.animate().withEndAction(null)
        banner.text = text
        banner.alpha = 0f
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(180).withEndAction(null).start()
        banner.postDelayed(hideBanner, 3500)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) {
            lastUserInputAt = SystemClock.elapsedRealtime()
            hasUserInteracted = true
        }
        // A real tap is the player's own: nothing the hook pressed is guarded or waiting for probe acceptance.
        if (a == MotionEvent.ACTION_DOWN) {
            probeGuardUntil = 0L
            probeAcceptUntil = 0L
        }
        // Play all: touching the game (not the card) means "I'm still playing".
        if (a == MotionEvent.ACTION_DOWN && autoNext != null) {
            val card = donePrompt
            val hitCard = if (card != null && card.isShown) {
                val loc = IntArray(2)
                card.getLocationOnScreen(loc)
                val r = android.graphics.Rect(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
                r.contains(ev.rawX.toInt(), ev.rawY.toInt())
            } else false
            if (!hitCard) stopAutoNext()
        }
        return super.dispatchTouchEvent(ev)
    }

    // Key presses (hardware keyboard, D-pad, accessibility) count as user input too.
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastUserInputAt = SystemClock.elapsedRealtime()
        hasUserInteracted = true
    }

    private fun userActedWithin(ms: Long): Boolean =
        lastUserInputAt > 0 && SystemClock.elapsedRealtime() - lastUserInputAt <= ms

    // =====================================================================================
    // Ad blocking
    // =====================================================================================

    private fun filterRequest(request: WebResourceRequest, fromServiceWorker: Boolean = false): WebResourceResponse? {
        val url = request.url ?: return null
        if (request.isForMainFrame) {
            if (!fromServiceWorker) {
                url.host?.let { pendingPageHost = it.lowercase(Locale.US) }
            }
            return null
        }
        val firstParty = if (fromServiceWorker) {
            GamePlayerPolicy.serviceWorkerFirstPartyHost(request.requestHeaders, pageHost)
        } else {
            pageHost
        }
        return blockedResponseFor(request, firstParty)
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
        val secFetchDest = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        val type = AdBlockEngine.inferType(s, accept, false, requestedWith, secFetchDest)
        if (!engine.shouldBlock(s, firstPartyHost, type)) return null
        onRequestBlocked()
        // Ad libraries a game may call directly get a harmless stand-in so the game doesn't stall.
        if (type == AdBlockEngine.RequestType.SCRIPT || type == AdBlockEngine.RequestType.OTHER) {
            Surrogates.forUrl(s)?.let { js -> return scriptResponse(js) }
        }
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
        if (showCount) p.setPadding(dp(14), dp(10), dp(16), dp(10)) else p.setPadding(dp(14), dp(14), dp(14), dp(14))
        p.contentDescription = when {
            !adBlockActive -> "Ad blocking off for this site. Tap to block ads."
            n == 1 -> "Ad blocking on, 1 request blocked. Tap to allow ads on this site."
            n > 1 -> "Ad blocking on, $n requests blocked. Tap to allow ads on this site."
            else -> "Ad blocking on. Tap to allow ads on this site."
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        openPopups.toList().forEach { runCatching { it.dismiss() } }
        openPopups.clear()
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastNightMode) {
            lastNightMode = nightMode
            val light = ThemePrefs.isLight(this)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
            surfaceColor = ThemePrefs.surfaceColor(this)
            window.setBackgroundDrawable(surfaceColor.toDrawable())
            if (::container.isInitialized) container.setBackgroundColor(surfaceColor)
            if (::root.isInitialized) root.setBackgroundColor(surfaceColor)
            if (::webView.isInitialized) {
                webView.setBackgroundColor(surfaceColor)
                applyDarkPages(webView)
            }
            popupView?.let { pv ->
                pv.setBackgroundColor(surfaceColor)
                applyDarkPages(pv)
            }
            popupDialog?.window?.let { w ->
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        updateShieldPill()
    }

    /**
     * Documents already sent their page CSS. Keyed by the per-document nonce cosmetic.js sends, so a
     * reload or the next page on the same host gets its CSS again; cleared on every new page.
     */
    private val helloAnswered = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun cosmeticCssFor(host: String, data: String, isMainFrame: Boolean): String? {
        val filters = AdBlocker.cosmeticsForPage(host) ?: return null
        return runCatching {
            val m = JSONObject(data)
            when (m.optString("t")) {
                "hello" -> {
                    // Cap total hello replies (including main frame) so a hostile page can't flood cosmeticExecutor.
                    if (helloAnswered.size >= MAX_FRAME_HELLOS) return@runCatching null
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
        backCallback.isEnabled = customView != null || donePrompt != null || screenPrompt != null || (::webView.isInitialized && webView.canGoBack())
    }

    private fun handleGameBackPress() {
        when {
            customView != null -> exitFullscreen()
            donePrompt != null -> hideDonePrompt()
            screenPrompt != null -> hideScreenPrompt()
            ::webView.isInitialized && webView.canGoBack() -> {
                webView.evaluateJavascript("(window.__dailiesCloseSelectMenu ? window.__dailiesCloseSelectMenu() : false)") { closed ->
                    if (closed != "true" && ::webView.isInitialized && webView.canGoBack()) webView.goBack()
                }
            }
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
        outState.putLong(STATE_OPENED_ELAPSED, (SystemClock.elapsedRealtime() - openedAtElapsed).coerceAtLeast(0L))
        capturedShare?.let { outState.putString(STATE_SHARE_TEXT, it) }
        outState.putBoolean(STATE_SHARE_CONFIRMED, capturedConfirmed)
        outState.putLong(STATE_SHARE_AT, capturedAt)
        outState.putLong(STATE_FOREGROUND_MS, foregroundMs + if (resumedAt > 0) SystemClock.elapsedRealtime() - resumedAt else 0L)
        outState.putBoolean(STATE_OPEN_LOG, openLogOnReturn)
        outState.putString(STATE_GAME_ID, gameId)
        outState.putString(STATE_GAME_NAME, gameName)
        outState.putString(STATE_GAME_URL, gameUrl)
        outState.putString(STATE_EFFECTIVE_GAME_URL, effectiveGameUrl)
        lastPageUrl?.let { outState.putString(STATE_LAST_PAGE_URL, it) }
        outState.putBoolean(STATE_USER_INTERACTED, hasUserInteracted)
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
        dismissTransientPopups()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        exitFullscreen()
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
        val earlierStr = if (earlierResults.length() > 0) earlierResults.toString() else ""
        val data = Intent().putExtra(EXTRA_ID, gameId).putExtra(EXTRA_LOG, openLog).putExtra(EXTRA_ELAPSED_MS, played)
            .putExtra(EXTRA_OPENED_AT, openedAt)
        capturedShare?.let { data.putExtra(EXTRA_SHARE_TEXT, it) }
        if (capturedShare != null && capturedConfirmed) data.putExtra(EXTRA_SHARE_CONFIRMED, true)
        if (earlierStr.isNotEmpty()) data.putExtra(EXTRA_EARLIER, earlierStr)
        setResult(RESULT_OK, data)
        if (gameId.isNotEmpty()) {
            InFlightGameSession.save(
                applicationContext,
                InFlightGameSession.Snapshot(
                    gameId = gameId,
                    openLog = openLog,
                    elapsedMs = played,
                    openedAt = openedAt,
                    shareText = capturedShare.orEmpty(),
                    shareConfirmed = capturedShare != null && capturedConfirmed,
                    earlierJson = earlierStr
                )
            )
        }
    }

    /** Every exit path (← Hub, ✓ Log, Back, renderer crash) reports how long the game was on screen. */
    override fun finish() {
        inFinish = true
        checkClipboardSinceOpen(ignoreFocus = true)
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

    private class ExpressiveMenuItem(
        val label: String,
        val contentDesc: String,
        val selected: Boolean = false,
        val trailingBadge: String? = null,
        val leadingMark: String? = null,
        val onClick: () -> Unit
    )

    private fun showGameSwitcher(anchor: View) {
        val items = lineup.map { g ->
            val isCurrent = g.id == gameId
            val mark = when {
                isCurrent -> "▶"
                g.done -> "✓"
                else -> "○"
            }
            // TalkBack reads the state in words, not the glyph.
            val state = getString(
                when {
                    isCurrent -> R.string.switcher_playing
                    g.done -> R.string.switcher_done
                    else -> R.string.switcher_not_done
                }
            )
            ExpressiveMenuItem(
                label = g.name,
                contentDesc = "${g.name}, $state",
                selected = isCurrent,
                leadingMark = mark
            ) { switchToGame(g) }
        }
        showExpressiveMenu(anchor, items)
    }

    private fun showMoreMenu(anchor: View) {
        val zoomPct = "${(siteZoom() * 100).toInt()}%"
        val items = listOf(
            ExpressiveMenuItem(
                label = "Reload",
                contentDesc = "Reload"
            ) { webView.reload() },
            ExpressiveMenuItem(
                label = "Open in browser",
                contentDesc = "Open in browser"
            ) {
                val current = webView.url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: gameUrl
                viewExternally(current.toUri())
            },
            ExpressiveMenuItem(
                label = "Zoom",
                contentDesc = "Zoom ($zoomPct)",
                trailingBadge = zoomPct
            ) { showZoomDialog() }
        )
        showExpressiveMenu(anchor, items)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showExpressiveMenu(anchor: View, items: List<ExpressiveMenuItem>) {
        if (isFinishing || isDestroyed || items.isEmpty()) return
        openPopups.toList().forEach { runCatching { it.dismiss() } }
        openPopups.clear()

        val light = ThemePrefs.isLight(this)
        val strokeColor = sheetStrokeColor(lightAlpha = 32, darkAlpha = 42)
        val pressColor = Color.argb(
            if (light) 20 else 28,
            Color.red(titleColor),
            Color.green(titleColor),
            Color.blue(titleColor)
        )
        val maxMenuHeight = (resources.displayMetrics.heightPixels * 0.58f).toInt().coerceAtLeast(dp(160))
        val popupWidth = minOf(resources.displayMetrics.widthPixels - dp(24), maxOf(dp(220), dp(250)))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedHeightSpec = when (MeasureSpec.getMode(heightMeasureSpec)) {
                    MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(
                        minOf(MeasureSpec.getSize(heightMeasureSpec), maxMenuHeight),
                        MeasureSpec.AT_MOST
                    )
                    MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxMenuHeight, MeasureSpec.AT_MOST)
                    else -> heightMeasureSpec
                }
                super.onMeasure(widthMeasureSpec, cappedHeightSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(barColors[0])
                setStroke(maxOf(1, dp(1)), strokeColor)
            }
            elevation = dp(12).toFloat()
            clipToOutline = true
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val popup = PopupWindow(scroll, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            elevation = dp(14).toFloat()
        }

        var selectedRow: View? = null
        items.forEach { item ->
            val baseRowColor = if (item.selected) barColors[1] else Color.TRANSPARENT
            val restCorner = dp(16).toFloat()
            val pressedCorner = dp(11).toFloat()
            val rowBg = GradientDrawable().apply {
                cornerRadius = restCorner
                setColor(baseRowColor)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rowBg
                contentDescription = item.contentDesc
                isClickable = true
                isFocusable = true
                if (item.leadingMark != null) {
                    addView(
                        TextView(this@GameActivity).apply {
                            text = item.leadingMark
                            width = dp(22)
                            gravity = Gravity.CENTER
                            textSize = 13f
                            typeface = googleSansBold
                            includeFontPadding = false
                            setTextColor(if (item.selected) barColors[2] else accentColors[0])
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                        LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = dp(8)
                        }
                    )
                }
                addView(
                    TextView(this@GameActivity).apply {
                        text = item.label
                        textSize = 14f
                        typeface = googleSansBold
                        includeFontPadding = false
                        setSingleLine(true)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(if (item.selected) barColors[2] else titleColor)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                if (item.trailingBadge != null) {
                    addView(
                        TextView(this@GameActivity).apply {
                            text = item.trailingBadge
                            textSize = 12f
                            typeface = googleSansBold
                            includeFontPadding = false
                            gravity = Gravity.CENTER
                            setTextColor(barColors[2])
                            setPadding(dp(9), dp(4), dp(9), dp(4))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(999).toFloat()
                                setColor(barColors[1])
                            }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(8) }
                    )
                }
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            rowBg.setColor(if (item.selected) barColors[1] else pressColor)
                            rowBg.cornerRadius = pressedCorner
                            v.animate().cancel()
                            v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(110).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            rowBg.setColor(baseRowColor)
                            rowBg.cornerRadius = restCorner
                            v.animate().cancel()
                            v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        }
                    }
                    false
                }
                setOnClickListener {
                    popup.dismiss()
                    item.onClick()
                }
            }
            if (item.selected && selectedRow == null) selectedRow = row
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val margin = dp(12)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorLeft = loc[0]
        val anchorRight = anchorLeft + anchor.width
        val alignRight = anchorLeft + anchor.width / 2 > screenWidth / 2
        val desiredLeft = if (alignRight) anchorRight - popupWidth else anchorLeft
        val clampedLeft = desiredLeft.coerceIn(margin, maxOf(margin, screenWidth - margin - popupWidth))
        val baseLeft = if (anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL) anchorRight - popupWidth else anchorLeft
        val offsetX = clampedLeft - baseLeft

        openPopups += popup
        popup.setOnDismissListener { openPopups.remove(popup) }
        scroll.alpha = 0f
        scroll.translationY = -dp(6).toFloat()
        scroll.scaleX = 0.97f
        scroll.scaleY = 0.97f
        scroll.pivotY = 0f
        scroll.pivotX = if (alignRight) popupWidth.toFloat() else 0f
        runCatching {
            popup.showAsDropDown(anchor, offsetX, dp(6))
            scroll.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(170)
                .setInterpolator(DecelerateInterpolator())
                .start()
            selectedRow?.let { sel ->
                scroll.post {
                    val targetY = (sel.top - scroll.height / 2 + sel.height / 2).coerceAtLeast(0)
                    scroll.scrollTo(0, targetY)
                }
            }
        }
    }

    /** One entry per game in [earlierResults]: play time added up, the latest result kept. */
    private fun addEarlierResult(id: String, playedMs: Long, opened: Long, share: String?, confirmed: Boolean = false) {
        earlierResults = GamePlayerPolicy.mergeEarlierResult(earlierResults, id, playedMs, opened, share, confirmed, MAX_LINEUP)
    }

    /**
     * Swaps the player over to [target]: this game's play time and any captured result are kept for
     * the hub, then the new game loads in the same player with its own timer, ad-block choice and zoom.
     */
    private fun switchToGame(target: LineupGame) {
        if (target.id == gameId) return
        dismissTransientPopups()
        hideDonePrompt()
        checkClipboardSinceOpen()
        val now = SystemClock.elapsedRealtime()
        val played = foregroundMs + if (resumedAt > 0) now - resumedAt else 0L
        addEarlierResult(gameId, played, openedAt, capturedShare, capturedConfirmed)
        // Whatever is on the clipboard now was there before the new game opened.
        currentClipStamp()?.let { lastClipStampChecked = it }
        // The old page stays live until the new one is on screen; nothing it reports counts.
        switching = true
        banner.removeCallbacks(endSwitch)
        banner.postDelayed(endSwitch, SWITCH_TIMEOUT_MS)
        // A tap in the old game isn't a tap in the new one.
        lastUserInputAt = 0L
        hasUserInteracted = false
        probeGuardUntil = 0L
        probeAcceptUntil = 0L
        probeResultsThisPage = 0
        screenOfferedThisPage = false
        hideScreenPrompt()
        closePopup()
        exitFullscreen()
        gameId = target.id
        loadPreloaded(target.id)
        gameName = target.name
        gameUrl = target.url
        effectiveGameUrl = gameUrl
        initialHost = gameUrl.toUri().host?.lowercase(Locale.US).orEmpty()
        gameHost = initialHost
        pageHost = gameHost.ifEmpty { null }
        pendingPageHost = null
        openedAt = System.currentTimeMillis()
        openedAtElapsed = now
        foregroundMs = 0L
        if (resumedAt > 0) resumedAt = now
        capturedShare = null
        capturedConfirmed = false
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
        if (isFinishing || isDestroyed) return
        val host = pageHost ?: gameHost
        val title = TextView(this).apply {
            text = getString(R.string.zoom_title)
            textSize = 17f
            typeface = googleSansBold
            includeFontPadding = false
            setTextColor(titleColor)
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            textSize = 14f
            typeface = googleSansBold
            includeFontPadding = false
            setTextColor(titleColor)
            alpha = 0.8f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        fun refresh() {
            label.text = getString(
                R.string.zoom_label,
                (GamePrefs.siteZoom(this, host) * 100).toInt(),
                AdBlockEngine.siteOf(host)
            )
        }
        fun step(delta: Float) {
            val next = ((GamePrefs.siteZoom(this, host) + delta) * 10).let { kotlin.math.round(it) / 10f }
            GamePrefs.setSiteZoom(this, host, next)
            applySiteZoom()
            refresh()
        }
        refresh()
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(pill("−", "Zoom out", barColors[1], barColors[2]) { step(-0.1f) }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
            })
            addView(pill(getString(R.string.zoom_reset), getString(R.string.zoom_reset), barColors[1], barColors[2]) {
                GamePrefs.setSiteZoom(this@GameActivity, host, 1f)
                applySiteZoom()
                refresh()
            }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
            })
            addView(pill("+", "Zoom in", accentColors[0], accentColors[1]) { step(0.1f) }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(barColors[0])
                setStroke(maxOf(1, dp(1)), sheetStrokeColor())
            }
            elevation = dp(12).toFloat()
            addView(title)
            addView(label)
            addView(buttons)
        }
        zoomDialog?.let { runCatching { it.dismiss() } }
        val dlg = ComponentDialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(card)
        dlg.setCanceledOnTouchOutside(true)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            val dialogWidth = minOf(resources.displayMetrics.widthPixels - dp(32), dp(320))
            w.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
        }
        dlg.setOnDismissListener { if (zoomDialog === dlg) zoomDialog = null }
        zoomDialog = dlg
        runCatching { dlg.show() }
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
        val dataUri = parsed.data
        val dataScheme = dataUri?.scheme?.lowercase(Locale.US)
        val pkg = parsed.`package`
        var launched = false
        if (GamePlayerPolicy.isSafeIntentLaunch(dataScheme, pkg, packageName) && dataUri != null) {
            val safe = Intent(Intent.ACTION_VIEW).apply {
                data = dataUri
                pkg?.let { setPackage(it) }
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            launched = runCatching { startActivity(safe) }.isSuccess
        }
        if (!launched && !probeGuarded()) {
            val fb = fallback?.toUri()
            val s = fb?.scheme?.lowercase(Locale.US)
            if (fb != null && (s == "https" || s == "http")) {
                val blocked = adBlockActive && AdBlocker.network(0)
                    ?.shouldBlock(fb.toString(), pageHost, AdBlockEngine.RequestType.SUBDOCUMENT) == true
                if (blocked) onRequestBlocked() else webView.loadUrl(fb.toString())
            }
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
        if (result.resultCode == RESULT_OK && bytes == null) {
            showBanner(getString(R.string.download_failed))
            return@registerForActivityResult
        }
        if (result.resultCode != RESULT_OK || uri == null || bytes == null) return@registerForActivityResult
        val resolver = applicationContext.contentResolver
        Thread({
            val ok = runCatching { resolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    showBanner(getString(if (ok) R.string.download_saved else R.string.download_failed))
                }
            }
        }, "game-saf-write").start()
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
        Thread({
            val decoded = runCatching {
                GamePlayerPolicy.decodeDataUrl(url) { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (decoded == null) { showBanner(getString(R.string.download_failed)); return@runOnUiThread }
                val mime = decoded.mime.ifBlank { mimeType.orEmpty() }.ifBlank { "application/octet-stream" }
                promptSave(decoded.bytes, mime, suggestedName(contentDisposition, mime))
            }
        }, "game-dataurl-decode").start()
    }

    /**
     * Web downloads keep the game's login: on Android 10+ DownloadManager fetches them with the page's
     * cookies into Downloads (no storage permission needed). On Android 7-9, where DownloadManager
     * would need storage permission, the app fetches the file itself and saves it through the system
     * file picker; only if that fails does the link go to the browser.
     */
    private fun startHttpDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { fetchForSave(url, userAgent, contentDisposition, mimeType); return }
        val rawName = GamePlayerPolicy.filenameFromContentDisposition(contentDisposition)
            ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        val name = GamePlayerPolicy.sanitizeDownloadFilename(rawName)
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
                        val rawName = GamePlayerPolicy.filenameFromContentDisposition(contentDisposition)
                            ?: URLUtil.guessFileName(url, contentDisposition, mime)
                        promptSave(r.body, mime, GamePlayerPolicy.sanitizeDownloadFilename(rawName))
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
            if (origin == null) return
            val originHost = sourceOrigin.host?.lowercase(Locale.US).orEmpty()
            if (blobFrames.size >= MAX_BLOB_FRAMES) {
                if (gameHost.isNotEmpty() && originHost.isNotEmpty() && AdBlockEngine.sameSite(originHost, gameHost)) {
                    blobFrames.removeAt(0)
                    blobFrames.add(origin to reply)
                }
            } else {
                blobFrames.add(origin to reply)
            }
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

    /** Top bar fill with a crisp 1dp bottom tonal hairline so AMOLED (#000000) and light mode have a clean edge. */
    private fun barBackground(containerColor: Int, light: Boolean): LayerDrawable {
        val fill = containerColor.toDrawable()
        val hairline = Color.argb(
            if (light) 26 else 32,
            Color.red(titleColor),
            Color.green(titleColor),
            Color.blue(titleColor)
        ).toDrawable()
        return LayerDrawable(arrayOf(fill, hairline)).apply {
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, maxOf(1, dp(1)))
        }
    }

    /** Subtle 1dp tonal border stroke for floating 28dp sheets, banners, and dialogs. */
    private fun sheetStrokeColor(lightAlpha: Int = 28, darkAlpha: Int = 38): Int {
        val alpha = if (ThemePrefs.isLight(this)) lightAlpha else darkAlpha
        return Color.argb(alpha, Color.red(titleColor), Color.green(titleColor), Color.blue(titleColor))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPillMorph(view: View, bgDrawable: GradientDrawable, fullRadius: Float, pressedRadius: Float) {
        var currentRadius = fullRadius
        var radiusAnim: ValueAnimator? = null
        fun morphRadius(target: Float, durationMs: Long, restoreFullOnEnd: Boolean) {
            radiusAnim?.cancel()
            val half = if (view.height > 0) view.height / 2f else dp(24).toFloat()
            val start = minOf(currentRadius, half)
            radiusAnim = ValueAnimator.ofFloat(start, target).apply {
                duration = durationMs
                addUpdateListener { anim ->
                    val r = anim.animatedValue as Float
                    currentRadius = r
                    bgDrawable.cornerRadius = r
                }
                if (restoreFullOnEnd) {
                    addListener(object : AnimatorListenerAdapter() {
                        private var cancelled = false
                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }
                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled) {
                                currentRadius = fullRadius
                                bgDrawable.cornerRadius = fullRadius
                            }
                        }
                    })
                }
                start()
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(140)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    morphRadius(pressedRadius, 140L, restoreFullOnEnd = false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                    val half = if (v.height > 0) v.height / 2f else dp(24).toFloat()
                    morphRadius(half, 200L, restoreFullOnEnd = true)
                }
            }
            false
        }
    }

    /**
     * Material 3 Expressive tonal pill with an optional icon (tinted to [fg]) before the label,
     * balanced optical padding, and pill-to-squircle spring touch morphing.
     * Every pill is at least a 48 dp touch target; icon-only pills are exact 48 dp circles.
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
            when {
                label.isEmpty() -> setPadding(dp(14), dp(14), dp(14), dp(14))
                iconRes != 0 -> setPadding(dp(14), dp(10), dp(16), dp(10))
                else -> setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val fullRadius = dp(100).toFloat()
            val bgDrawable = GradientDrawable().apply {
                cornerRadius = fullRadius
                setColor(bg)
            }
            background = bgDrawable
            attachPillMorph(this, bgDrawable, fullRadius, dp(13).toFloat())
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
        /** The result was confirmed by the player (an on-screen result they chose to log). */
        const val EXTRA_SHARE_CONFIRMED = "share_confirmed"
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
        private const val STATE_EFFECTIVE_GAME_URL = "state_effective_game_url"
        private const val STATE_LAST_PAGE_URL = "state_last_page_url"
        private const val STATE_USER_INTERACTED = "state_user_interacted"
        private const val STATE_LINEUP = "state_lineup"
        private const val STATE_EARLIER = "state_earlier"
        private const val MAX_LINEUP = 200
        private const val STATE_OPENED_AT = "state_opened_at"
        private const val STATE_OPENED_ELAPSED = "state_opened_elapsed"
        private const val STATE_SHARE_TEXT = "state_share_text"
        private const val STATE_SHARE_CONFIRMED = "state_share_confirmed"
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
        private const val RENDERER_RAPID_GONE_MS = 15_000L
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
                        activeFilter.get()?.filterRequest(request, fromServiceWorker = true)
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
 * The game player's pure decisions (popups, download names, data: URLs, earlier results, intent launch),
 * free of Android types so they run in JVM unit tests.
 */
internal object GamePlayerPolicy {
    enum class PopupAction { DIALOG, EXTERNAL, BLOCK }

    const val MAX_EARLIER_SHARE_CHARS = 4000
    private val UNSAFE_INTENT_SCHEMES = setOf("file", "content", "javascript", "data", "blob", "dailies", "intent")
    private val DOWNLOAD_FILENAME_UNSAFE = Regex("""[\\/:*?"<>|]""")

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

    /** Rejects dangerous URI schemes and self-package targeting when launching an `intent://` link. */
    fun isSafeIntentLaunch(scheme: String?, targetPackage: String?, selfPackage: String): Boolean {
        val s = scheme?.lowercase(Locale.US)?.trim().orEmpty()
        if (s.isEmpty() || s in UNSAFE_INTENT_SCHEMES) return false
        if (!targetPackage.isNullOrBlank() && targetPackage == selfPackage) return false
        return true
    }

    /** Derives the first-party host for a Service Worker request from Origin/Referer headers, falling back to [fallbackPageHost]. */
    fun serviceWorkerFirstPartyHost(requestHeaders: Map<String, String>?, fallbackPageHost: String?): String? {
        val headers = requestHeaders.orEmpty()
        val origin = headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val initiatorHost = listOfNotNull(origin, referer).firstNotNullOfOrNull { headerUrl ->
            AdBlockEngine.hostOf(headerUrl)
        }
        return initiatorHost ?: fallbackPageHost
    }

    /** Merges a switched-away game's session into [earlierResults] while bounding size and preserving accurate cycle `openedAt`. */
    fun mergeEarlierResult(
        earlierResults: JSONArray,
        id: String,
        playedMs: Long,
        opened: Long,
        share: String?,
        confirmed: Boolean = false,
        maxEntries: Int = 200
    ): JSONArray {
        val boundedShare = share?.take(MAX_EARLIER_SHARE_CHARS)
        val merged = JSONArray()
        var found = false
        for (i in 0 until earlierResults.length()) {
            val o = earlierResults.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                found = true
                o.put("elapsedMs", o.optLong("elapsedMs") + playedMs)
                if (!boundedShare.isNullOrEmpty()) {
                    val prevShare = o.optString("share")
                    val prevOpened = o.optLong("openedAt", 0L)
                    if (prevShare.isEmpty() || (opened > 0L && (prevOpened <= 0L || opened > prevOpened))) {
                        o.put("openedAt", opened)
                    }
                    o.put("share", boundedShare)
                    o.put("confirmed", confirmed)
                } else if (opened in 1 until o.optLong("openedAt", Long.MAX_VALUE)) {
                    // The earliest opening tells the hub whether the game reset while it was being played.
                    o.put("openedAt", opened)
                }
            }
            merged.put(o)
        }
        if (!found) {
            merged.put(
                JSONObject()
                    .put("id", id)
                    .put("elapsedMs", playedMs)
                    .put("openedAt", opened)
                    .put("share", boundedShare ?: "")
                    .put("confirmed", confirmed && !boundedShare.isNullOrEmpty())
            )
        }
        if (merged.length() <= maxEntries) return merged
        val capped = JSONArray()
        for (i in (merged.length() - maxEntries) until merged.length()) {
            merged.optJSONObject(i)?.let { capped.put(it) }
        }
        return capped
    }

    /** Replaces filesystem-unsafe characters in an HTTP download filename before passing to DownloadManager/SAF. */
    fun sanitizeDownloadFilename(name: String?): String {
        val cleaned = name.orEmpty().replace(DOWNLOAD_FILENAME_UNSAFE, "_").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "download" else cleaned
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
            val clean = if ('%' in payload) String(percentDecode(payload, Charsets.UTF_8), Charsets.ISO_8859_1) else payload
            val ascii = clean.filterNot { it.isWhitespace() }
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
