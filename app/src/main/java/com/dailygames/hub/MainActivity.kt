package com.dailygames.hub

import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var scaffoldRoot: FrameLayout
    private lateinit var webView: WebView
    private lateinit var stateStore: HubStateStore
    private var pageReady = false
    private val pendingJs = mutableListOf<String>()

    // Read from the JS bridge thread by getInitialLaunchThemeJson(), written on the main thread.
    @Volatile private var lastTopInsetDp = 0
    @Volatile private var lastBottomInsetDp = 0
    @Volatile private var lastImeVisible = false

    private var surfaceColor: Int = ThemePrefs.DEFAULT_SURFACE
    private val scrapeExecutor = Executors.newSingleThreadExecutor()
    private val faviconCache by lazy { FaviconCache(File(filesDir, "favicons")) }

    // ---- Activity Result API (replaces startActivityForResult / onActivityResult) ----
    private val gameLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@registerForActivityResult
        val id = data.getStringExtra(GameActivity.EXTRA_ID).orEmpty()
        val openLog = data.getBooleanExtra(GameActivity.EXTRA_LOG, false)
        val elapsedMs = data.getLongExtra(GameActivity.EXTRA_ELAPSED_MS, 0L)
        val openedAt = data.getLongExtra(GameActivity.EXTRA_OPENED_AT, 0L)
        val shareText = data.getStringExtra(GameActivity.EXTRA_SHARE_TEXT).orEmpty()
        if (id.isNotBlank()) {
            runJs("window.onReturnFromGame(${JSONObject.quote(id)}, $openLog, $elapsedMs, ${JSONObject.quote(shareText)}, $openedAt)")
        }
    }

    /** System "Save to…" picker; the backup is staged in a cache file until a destination is chosen. */
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            handleExportResult(uri)
        }

    /** Photo picker for "Read a screenshot" in the log dialog. */
    private val screenshotPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) runJs("window.onScreenshotText && window.onScreenshotText(null, 'picker')")
        else readScreenshot(uri, "picker")
    }

    private fun readScreenshot(uri: Uri, source: String) {
        runJs("window.onScreenshotReading && window.onScreenshotReading()")
        ScreenshotReader.read(applicationContext, uri) { text ->
            runOnUiThread {
                val arg = text?.take(4000)?.let { JSONObject.quote(it) } ?: "null"
                runJs("window.onScreenshotText && window.onScreenshotText($arg, ${JSONObject.quote(source)})")
            }
        }
    }

    /** "Save to…" picker for the CSV history export. */
    private val csvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val temp = File(cacheDir, CSV_TEMP_FILE)
        if (uri == null) { runCatching { temp.delete() }; runJs("window.onCsvSaved && window.onCsvSaved(false)"); return@registerForActivityResult }
        Thread {
            val ok = runCatching {
                (runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: contentResolver.openOutputStream(uri, "w"))
                    ?.use { out -> temp.inputStream().use { it.copyTo(out) }; true } ?: false
            }.getOrDefault(false)
            runCatching { temp.delete() }
            runOnUiThread { runJs("window.onCsvSaved && window.onCsvSaved($ok)") }
        }.start()
    }

    /** System document picker (Storage Access Framework) for importing a backup. */
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handleImportResult(uri)
    }

    /** System folder picker for weekly automatic backups. */
    private val backupFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { pushAutoBackupStatus(); return@registerForActivityResult }
        AutoBackup.enable(applicationContext, uri)
        Thread {
            AutoBackup.runIfDue(applicationContext, force = true)
            runOnUiThread { pushAutoBackupStatus() }
        }.start()
    }

    private fun pushAutoBackupStatus() {
        runJs("window.onAutoBackupStatus && window.onAutoBackupStatus(${AutoBackup.statusJson(applicationContext)})")
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            runJs("window.onNotificationPermissionResult && window.onNotificationPermissionResult($granted)")
            pushNotificationStatus()
        }

    /**
     * Enabled only while the hub has something to close (modal / non-home tab). While disabled the
     * system owns Back, so Android 13+ can play the predictive back-to-home animation (K1).
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = handleAppBackPress()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateStore = HubStateStore(File(filesDir, HubStateStore.FILE_NAME), onWriteResult = { ok ->
            // Writer thread: tell the hub that saving is failing (e.g. storage full), or works again.
            runOnUiThread { runJs("window.onHubSaveResult && window.onHubSaveResult($ok)") }
        })
        Notifier.ensureChannel(this)
        DailyRefreshWorker.scheduleNextReset(this)
        AutoBackup.schedule(this)

        // Edge-to-edge: draw behind the status & navigation bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        applySystemBarAppearance()
        lastSystemDark = ThemePrefs.systemIsDark(this)

        // Paint the window in the same surface colour the hub will use (wallpaper / AMOLED / Iris), so
        // there is no colour jump between the first frame and the WebView's first paint.
        surfaceColor = ThemePrefs.surfaceColor(this)
        window.setBackgroundDrawable(surfaceColor.toDrawable())

        // Accurate insets before the first layout pass (API 30+), so the page's very first frame already
        // has correct edge-to-edge padding instead of a guess that is corrected later.
        seedInitialInsets()

        scaffoldRoot = FrameLayout(this).apply { setBackgroundColor(surfaceColor) }
        createAndAttachWebView()

        // System bars/cutouts AND the IME so text inputs are never covered (K2).
        ViewCompat.setOnApplyWindowInsetsListener(scaffoldRoot) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density.coerceAtLeast(1f)
            lastTopInsetDp = (sysBars.top / density).toInt()
            lastBottomInsetDp = (sysBars.bottom / density).toInt()
            lastImeVisible = ime.bottom > 0

            scaffoldRoot.setPadding(sysBars.left, 0, sysBars.right, ime.bottom)
            // applySafeAreaInsets is defined in the page <head>, so push immediately; no need to wait
            // for the page to finish loading.
            pushInsetsToWebView()
            WindowInsetsCompat.CONSUMED
        }

        setContentView(scaffoldRoot)
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Only act on the launching intent the first time; a system-restored activity must not re-run it.
        if (savedInstanceState == null) handleIntent(intent)
    }

    private fun seedInitialInsets() {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            lastTopInsetDp = (insets.top / density).toInt()
            lastBottomInsetDp = (insets.bottom / density).toInt()
            return
        }
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (statusBarResId > 0) {
            lastTopInsetDp = (resources.getDimensionPixelSize(statusBarResId) / density).toInt()
        }
        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        val navBarResId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (navBarResId > 0) {
            lastBottomInsetDp = (resources.getDimensionPixelSize(navBarResId) / density).toInt()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndAttachWebView() {
        pageReady = false
        webView = WebView(this).apply {
            setBackgroundColor(surfaceColor)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // The hub UI only ever loads from packaged assets (keep standard WebView cache intact — P1).
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    // Only our own packaged page may load in this WebView (it holds the JS bridge).
                    if (uri.toString().startsWith(ASSET_PREFIX)) return false
                    val scheme = uri.scheme?.lowercase(Locale.US)
                    if (scheme == "http" || scheme == "https") {
                        launchGame("external", uri.host ?: "Daily Game", uri.toString())
                        return true
                    }
                    openExternal(uri)
                    return true
                }

                /**
                 * Serves game icons from the on-device favicon cache (downloading each once). Runs on a
                 * WebView background thread, so blocking I/O here is fine.
                 */
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (url.host != FaviconCache.INTERCEPT_HOST) return null
                    val src = url.getQueryParameter("src").orEmpty()
                    val icon = faviconCache.load(src)
                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return WebResourceResponse(
                        icon.mimeType, null, 200, "OK",
                        mapOf("Cache-Control" to "max-age=31536000", "Access-Control-Allow-Origin" to "*"),
                        ByteArrayInputStream(icon.bytes)
                    )
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    pageReady = false
                    setBackIntercept(false)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    if (url != null && url.startsWith(ASSET_PREFIX)) pushEdgeToEdgeAndDynamicThemeToWebView()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Fallback only: the page normally signals readiness earlier via AndroidBridge.onHubReady().
                    if (url != null && url.startsWith(ASSET_PREFIX)) markPageReady()
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // Recover gracefully if the OS kills the WebView renderer process under low memory (K5).
                    (view?.parent as? ViewGroup)?.removeView(view)
                    runCatching { view?.destroy() }
                    if (!isFinishing && !isDestroyed) createAndAttachWebView()
                    return true
                }
            }
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        }

        scaffoldRoot.removeAllViews()
        scaffoldRoot.addView(
            webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        webView.loadUrl(ASSET_PREFIX + "index.html")
    }

    /** Main thread. Idempotent: called from AndroidBridge.onHubReady() and, as a fallback, onPageFinished. */
    private fun markPageReady() {
        if (pageReady || isFinishing || isDestroyed) return
        pageReady = true
        pushEdgeToEdgeAndDynamicThemeToWebView()
        val queued = pendingJs.toList()
        pendingJs.clear()
        queued.forEach { webView.evaluateJavascript(it, null) }
    }

    private fun setBackIntercept(enabled: Boolean) {
        backCallback.isEnabled = enabled
    }

    private fun handleAppBackPress() {
        if (!pageReady || !::webView.isInitialized) {
            leaveViaSystemBack()
            return
        }
        // Let the page close modals / return to the dashboard before leaving the app.
        webView.evaluateJavascript("(window.handleBackPress ? window.handleBackPress() : false)") { result ->
            if (result != "true") leaveViaSystemBack()
        }
    }

    /** Nothing left to close: hand Back to the system (moves the task to the background on Android 12+). */
    private fun leaveViaSystemBack() {
        backCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
    }

    private fun pushInsetsToWebView() {
        if (!::webView.isInitialized) return
        val effectiveBottomDp = if (lastImeVisible) 0 else lastBottomInsetDp
        webView.evaluateJavascript(
            "window.applySafeAreaInsets && window.applySafeAreaInsets($lastTopInsetDp, $effectiveBottomDp, $lastImeVisible)",
            null
        )
    }

    /** Dark status / navigation bar icons on the light theme, light icons on the dark one. */
    private fun applySystemBarAppearance() {
        val light = ThemePrefs.isLight(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // The phone switched between light and dark (uiMode is handled here, not by recreating).
        val dark = ThemePrefs.systemIsDark(this)
        if (dark != lastSystemDark) {
            lastSystemDark = dark
            applySystemBarAppearance()
            surfaceColor = ThemePrefs.surfaceColor(this)
            window.setBackgroundDrawable(surfaceColor.toDrawable())
            runJs("window.onSystemThemeChanged && window.onSystemThemeChanged($dark)")
            pushEdgeToEdgeAndDynamicThemeToWebView()
        }
    }
    private var lastSystemDark: Boolean? = null

    private fun pushEdgeToEdgeAndDynamicThemeToWebView() {
        if (!::webView.isInitialized) return
        val paletteJson = extractDeviceWallpaperPalette().toString()
        pushInsetsToWebView()
        webView.evaluateJavascript(
            "window.applyDeviceDynamicColors && window.applyDeviceDynamicColors($paletteJson);",
            null
        )
    }

    /**
     * Android 12+ (API 31+) Material You wallpaper colours from the system tonal palettes.
     * Locale.US keeps hex digits ASCII in Arabic/Persian locales (K7).
     */
    private fun extractDeviceWallpaperPalette(): JSONObject {
        val obj = JSONObject()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun hex(resId: Int): String = String.format(Locale.US, "#%06X", 0xFFFFFF and getColor(resId))
            try {
                obj.put("surface", hex(android.R.color.system_neutral1_900))
                obj.put("containerLow", hex(android.R.color.system_neutral1_800))
                obj.put("container", hex(android.R.color.system_neutral2_800))
                obj.put("containerHigh", hex(android.R.color.system_neutral2_700))
                obj.put("containerHighest", hex(android.R.color.system_neutral2_600))
                obj.put("primary", hex(android.R.color.system_accent1_200))
                obj.put("onPrimary", hex(android.R.color.system_accent1_800))
                obj.put("primaryContainer", hex(android.R.color.system_accent1_700))
                obj.put("onPrimaryContainer", hex(android.R.color.system_accent1_100))
                obj.put("secondaryContainer", hex(android.R.color.system_accent2_700))
                obj.put("tertiary", hex(android.R.color.system_accent3_200))
                obj.put("tertiaryContainer", hex(android.R.color.system_accent3_700))
                // Light-theme tones of the same wallpaper palette.
                obj.put("light", JSONObject().apply {
                    put("surface", hex(android.R.color.system_neutral1_10))
                    put("containerLow", hex(android.R.color.system_neutral1_50))
                    put("container", hex(android.R.color.system_neutral2_50))
                    put("containerHigh", hex(android.R.color.system_neutral2_100))
                    put("containerHighest", hex(android.R.color.system_neutral2_200))
                    put("primary", hex(android.R.color.system_accent1_600))
                    put("onPrimary", hex(android.R.color.system_accent1_0))
                    put("primaryContainer", hex(android.R.color.system_accent1_100))
                    put("onPrimaryContainer", hex(android.R.color.system_accent1_900))
                    put("secondaryContainer", hex(android.R.color.system_accent2_100))
                    put("tertiary", hex(android.R.color.system_accent3_600))
                    put("tertiaryContainer", hex(android.R.color.system_accent3_100))
                })
            } catch (e: Exception) {
                // Fall back to the default Iris tokens if system tonal resources are unavailable.
            }
        }
        return obj
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
        // Make sure the latest hub state reaches disk before the process can be killed.
        stateStore.flushAsync()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            // The wallpaper (and so the Material You palette) may have changed while we were away, and
            // notifications may have been switched on/off in Android Settings.
            if (pageReady) {
                pushEdgeToEdgeAndDynamicThemeToWebView()
                pushNotificationStatus()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * The pending backup lives only in a cache file (never in the saved-instance Bundle, which is
     * limited to ~1 MB and would throw TransactionTooLargeException for large histories).
     */
    private fun handleExportResult(uri: Uri?) {
        val tempFile = File(cacheDir, BACKUP_TEMP_FILE)
        if (uri == null) {
            runCatching { tempFile.delete() }
            runJs("window.onBackupFileSaved && window.onBackupFileSaved(false)")
            return
        }
        Executors.newSingleThreadExecutor().apply {
            execute {
                val ok = try {
                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        false
                    } else {
                        // Some document providers reject "wt"; the file is newly created, so "w" is equivalent.
                        (runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                            ?: contentResolver.openOutputStream(uri, "w"))?.use { out ->
                            tempFile.inputStream().use { it.copyTo(out) }
                            out.flush()
                            true
                        } ?: false
                    }
                } catch (e: Exception) {
                    false
                } finally {
                    runCatching { tempFile.delete() }
                }
                runOnUiThread { runJs("window.onBackupFileSaved && window.onBackupFileSaved($ok)") }
            }
            shutdown()
        }
    }

    private fun handleImportResult(uri: Uri?) {
        if (uri == null) {
            runJs("window.onBackupFileImported && window.onBackupFileImported(\"\")")
            return
        }
        Executors.newSingleThreadExecutor().apply {
            execute {
                // Cap the size so picking a huge file by mistake can't exhaust memory.
                val content = try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_IMPORT_BYTES) throw IllegalStateException("too_large")
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: ""
                } catch (e: IllegalStateException) {
                    runOnUiThread {
                        runJs("window.showToast && showToast('Backup too large', 'That file is over 50 MB, so it can\\'t be a Dailies backup.', 'indigo')")
                    }
                    return@execute
                } catch (e: Throwable) {
                    // IOException, or OutOfMemoryError on a huge file: report it, never crash.
                    showImportError("Could not read that file")
                    return@execute
                }
                deliverImportToHub(content)
            }
            shutdown()
        }
    }

    private fun showImportError(title: String) {
        runOnUiThread {
            runJs("window.showToast && showToast(${JSONObject.quote(title)}, 'The backup could not be imported. Your current data was not changed.', 'indigo')")
        }
    }

    /**
     * Hands an imported backup to the hub in slices: one evaluateJavascript() call with the whole
     * (up to 50 MB) file quoted into its source could run out of memory and crash the app.
     */
    private fun deliverImportToHub(content: String) {
        try {
            val chunks = ArrayList<String>((content.length / IMPORT_CHUNK_CHARS) + 1)
            var i = 0
            while (i < content.length) {
                var end = minOf(content.length, i + IMPORT_CHUNK_CHARS)
                // Never split a surrogate pair across two slices.
                if (end < content.length && Character.isHighSurrogate(content[end - 1])) end -= 1
                chunks.add(JSONObject.quote(content.substring(i, end)))
                i = end
            }
            runOnUiThread {
                try {
                    runJs("window.__dailiesImportBegin && window.__dailiesImportBegin()")
                    chunks.forEach { runJs("window.__dailiesImportChunk && window.__dailiesImportChunk($it)") }
                    chunks.clear()
                    runJs("window.__dailiesImportEnd ? window.__dailiesImportEnd() : (window.onBackupFileImported && window.onBackupFileImported(\"\"))")
                } catch (e: Throwable) {
                    runJs("window.__dailiesImportAbort && window.__dailiesImportAbort()")
                    showImportError("Backup too large to import")
                }
            }
        } catch (e: Throwable) {
            showImportError("Backup too large to import")
        }
    }

    override fun onDestroy() {
        scrapeExecutor.shutdownNow()
        stateStore.close()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Intents: Android Share (Chrome -> Share -> Dailies) and notification taps
    // ---------------------------------------------------------------------------------------
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val gameId = intent.getStringExtra(Notifier.EXTRA_GAME_ID)
        // MainActivity is exported (launcher + share target): only our own notifications, which carry
        // the app-private token, may ask it to open a game.
        val trusted = intent.getStringExtra(Notifier.EXTRA_TOKEN) == Notifier.intentToken(this)
        if (!gameId.isNullOrBlank() && trusted) {
            intent.removeExtra(Notifier.EXTRA_GAME_ID)
            runJs("window.openGameInPlayer(${JSONObject.quote(gameId)})")
            return
        }

        // A screenshot shared to Dailies: read its text on the phone and offer to log it.
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            intent.action = Intent.ACTION_MAIN
            if (uri != null) readScreenshot(uri, "share")
            return
        }

        // A friend's game list: an opened dailies:// link, or a shared message that contains one.
        val listSource = if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == GameListCodec.SCHEME) intent.dataString
            else intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { intent.action == Intent.ACTION_SEND }
        GameListCodec.decode(listSource)?.let { list ->
            intent.action = Intent.ACTION_MAIN
            intent.data = null
            runJs("window.onSharedGameList && window.onSharedGameList($list)")
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val shared = SharedLinkParser.extractSharedUrl(intent.action, intent.type, sharedText)
        if (!shared.isNullOrBlank()) {
            intent.action = Intent.ACTION_MAIN // mark as consumed
            // The whole text goes along too: a game's share result sent to Dailies is logged, not reopened.
            val fullText = SharedLinkParser.sharedTextForLogging(sharedText)
            runJs("window.handleSharedUrl(${JSONObject.quote(shared)}, ${JSONObject.quote(fullText)})")
        }
    }

    /** Runs JS once the hub page is interactive (queues it until then). Main thread only. */
    private fun runJs(js: String) {
        if (isFinishing || isDestroyed) return
        if (pageReady && ::webView.isInitialized) webView.evaluateJavascript(js, null) else pendingJs.add(js)
    }

    private fun launchGame(gameId: String, name: String, url: String) {
        val i = Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_ID, gameId)
            .putExtra(GameActivity.EXTRA_NAME, name)
            .putExtra(GameActivity.EXTRA_URL, url)
        gameLauncher.launch(i)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            // No app can handle this link; ignore.
        }
    }

    /**
     * Whether reset alerts can actually reach the user: notifications allowed for the app (this
     * includes the Android 13+ POST_NOTIFICATIONS permission) and the reset-alert channel not blocked.
     */
    private fun notificationStatusJson(): String {
        val nm = NotificationManagerCompat.from(this)
        val appEnabled = nm.areNotificationsEnabled()
        val channelBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(Notifier.channelId(this))?.importance == android.app.NotificationManager.IMPORTANCE_NONE
        return JSONObject()
            .put("enabled", appEnabled && !channelBlocked)
            .put("appEnabled", appEnabled)
            .put("channelBlocked", channelBlocked)
            .toString()
    }

    private fun pushNotificationStatus() {
        runJs("window.onNotificationStatusChanged && window.onNotificationStatusChanged(${notificationStatusJson()})")
    }

    /**
     * "Allow Notifications": shows Android's permission prompt when it still can; otherwise (already
     * denied twice, Android 12 and older, or the alert channel was turned off) opens the app's
     * notification settings so the user can switch them on there.
     */
    private fun requestNotificationPermissionIfNeeded() {
        val nm = NotificationManagerCompat.from(this)
        if (JSONObject(notificationStatusJson()).optBoolean("enabled")) {
            pushNotificationStatus()
            return
        }
        val prefs = getSharedPreferences("dailes_permissions", Context.MODE_PRIVATE)
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) {
            val askedBefore = prefs.getBoolean("asked_post_notifications", false)
            val systemWillShowPrompt = !askedBefore ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (systemWillShowPrompt) {
                prefs.edit { putBoolean("asked_post_notifications", true) }
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        openNotificationSettings(channelOnly = nm.areNotificationsEnabled())
    }

    private fun openNotificationSettings(channelOnly: Boolean) {
        val intent = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && channelOnly ->
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.channelId(this))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(intent)
            runJs("window.onNotificationSettingsOpened && window.onNotificationSettingsOpened()")
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // JS bridge. Only reachable from the packaged hub page (see shouldOverrideUrlLoading).
    // Methods run on a WebView background thread, so UI work is posted to the main thread.
    // ---------------------------------------------------------------------------------------
    inner class AndroidBridge {

        /** Insets + wallpaper palette for the synchronous <head> script, before first paint. */
        @JavascriptInterface
        fun getInitialLaunchThemeJson(): String {
            return JSONObject().apply {
                put("topPx", lastTopInsetDp)
                put("bottomPx", if (lastImeVisible) 0 else lastBottomInsetDp)
                put("imeOpen", lastImeVisible)
                put("palette", extractDeviceWallpaperPalette())
                put("systemDark", ThemePrefs.systemIsDark(this@MainActivity))
            }.toString()
        }

        /** Called at DOMContentLoaded: the hub is interactive even if favicons are still downloading. */
        @JavascriptInterface
        fun onHubReady() {
            runOnUiThread { markPageReady() }
        }

        /** Full hub state (games, history, logs). Empty string on first run. */
        @JavascriptInterface
        fun loadHubState(): String = stateStore.read()

        /** Moves unreadable saved data aside (never deletes it) so the hub can start fresh. */
        @JavascriptInterface
        fun quarantineHubState(): Boolean = stateStore.quarantine()

        /** Persists the full hub state to app-private storage with no size cap. */
        @JavascriptInterface
        fun saveHubState(json: String) {
            stateStore.write(json)
        }

        /** Mirrors Display settings so the next cold start paints the right surface colour immediately. */
        @JavascriptInterface
        fun setThemePrefs(dynamicWallpaper: Boolean, amoled: Boolean) {
            ThemePrefs.save(applicationContext, dynamicWallpaper, amoled)
        }

        /** Settings → Theme: "dark", "light" or "system". */
        @JavascriptInterface
        fun setThemeMode(mode: String) {
            ThemePrefs.saveMode(applicationContext, mode)
            runOnUiThread {
                applySystemBarAppearance()
                surfaceColor = ThemePrefs.surfaceColor(this@MainActivity)
                window.setBackgroundDrawable(surfaceColor.toDrawable())
            }
        }

        /** The hub tells us whether Back should close something (modal / non-home tab) or leave the app. */
        @JavascriptInterface
        fun setBackInterceptEnabled(enabled: Boolean) {
            runOnUiThread { if (!isFinishing && !isDestroyed) setBackIntercept(enabled) }
        }

        /** Mirrors the tracked games so background alarms can notify at the next reset (P12). */
        @JavascriptInterface
        fun syncGames(json: String) {
            GameStore.sync(applicationContext, json)
            DailyRefreshWorker.scheduleNextReset(applicationContext)
        }

        /** Weekly automatic backups: pick (or change) the folder, which also turns them on. */
        @JavascriptInterface
        fun chooseAutoBackupFolder() {
            runOnUiThread { runCatching { backupFolderLauncher.launch(null) }.onFailure { pushAutoBackupStatus() } }
        }

        @JavascriptInterface
        fun disableAutoBackup() {
            AutoBackup.disable(applicationContext)
            runOnUiThread { pushAutoBackupStatus() }
        }

        @JavascriptInterface
        fun getAutoBackupStatus(): String = AutoBackup.statusJson(applicationContext)

        @JavascriptInterface
        fun runAutoBackupNow() {
            Thread {
                AutoBackup.runIfDue(applicationContext, force = true)
                runOnUiThread { pushAutoBackupStatus() }
            }.start()
        }

        /** Opt-in update check against the GitHub release; answers window.onUpdateCheckResult. */
        @JavascriptInterface
        fun checkForUpdate() {
            val current = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            Thread {
                val json = UpdateCheck.check(current)
                runOnUiThread { runJs("window.onUpdateCheckResult && window.onUpdateCheckResult($json)") }
            }.start()
        }

        /** Opens the latest release page in the browser (a fixed address; the page can't pass one). */
        @JavascriptInterface
        fun openReleasePage() {
            runOnUiThread {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, UpdateCheck.RELEASES_PAGE.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                }
            }
        }

        /** Your game list as a link and a QR code: {link, qr} (qr is a PNG data: URL). */
        @JavascriptInterface
        fun makeGameListShare(gamesJson: String): String {
            val link = GameListCodec.encode(gamesJson) ?: return "{}"
            return JSONObject().put("link", link).put("qr", GameListCodec.qrDataUrl(link) ?: "").toString()
        }

        /** A pasted message or link that holds a friend's game list → the games JSON, or "" if none. */
        @JavascriptInterface
        fun decodeGameList(text: String): String = GameListCodec.decode(text) ?: ""

        /** Saves a CSV the hub built through the system "Save to…" picker; answers window.onCsvSaved. */
        @JavascriptInterface
        fun saveCsvFile(suggestedFileName: String, content: String) {
            val staged = content.isNotBlank() && runCatching { File(cacheDir, CSV_TEMP_FILE).writeText(content, Charsets.UTF_8) }.isSuccess
            runOnUiThread {
                if (!staged) { runJs("window.onCsvSaved && window.onCsvSaved(false)"); return@runOnUiThread }
                runCatching { csvLauncher.launch(suggestedFileName.ifBlank { "dailies_history.csv" }) }
                    .onFailure { runJs("window.onCsvSaved && window.onCsvSaved(false)") }
            }
        }

        /** "Read a screenshot": opens the photo picker; the text arrives in window.onScreenshotText. */
        @JavascriptInterface
        fun pickScreenshot() {
            runOnUiThread {
                runCatching { screenshotPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .onFailure { runJs("window.onScreenshotText && window.onScreenshotText(null, 'picker')") }
            }
        }

        /** Settings → Game Player → Dark Game Pages. */
        @JavascriptInterface
        fun setDarkGamePages(on: Boolean) {
            GamePrefs.setDarkPages(applicationContext, on)
        }

        /** Android share sheet for a text the hub built (monthly recap, game list). */
        @JavascriptInterface
        fun shareText(text: String, subject: String) {
            if (text.length > 20_000) return
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(Intent.EXTRA_SUBJECT, subject.take(120))
                runCatching { startActivity(Intent.createChooser(send, subject.take(120))) }
            }
        }

        /** "Remind me before a reset" lead time in minutes (0 = off). */
        @JavascriptInterface
        fun setStreakReminder(minutes: Int) {
            StreakReminders.setLeadMinutes(applicationContext, minutes)
        }

        /** In-game options from Settings: ad blocking and auto-logging captured share results. */
        @JavascriptInterface
        fun setGamePrefs(blockAds: Boolean, autoLogShare: Boolean) {
            GamePrefs.save(applicationContext, blockAds, autoLogShare)
        }

        /** Summary of the bundled ad-blocking lists (counts, build date) for the Settings subtitle. */
        @JavascriptInterface
        fun getAdBlockInfo(): String =
            runCatching { assets.open("adblock/sources.json").bufferedReader().use { it.readText() } }.getOrDefault("{}")

        /** Opens the game in the native full-screen player. */
        @JavascriptInterface
        fun openGame(gameId: String, name: String, url: String) {
            if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
            // Start loading the ad-block lists while the player opens.
            if (GamePrefs.blockAds(applicationContext)) AdBlocker.warmUp(applicationContext)
            runOnUiThread { launchGame(gameId, name, url) }
        }

        /** {"enabled": bool, "appEnabled": bool, "channelBlocked": bool} for the Settings button. */
        @JavascriptInterface
        fun getNotificationStatus(): String = notificationStatusJson()

        /** JSON array of the zone ids java.util.TimeZone knows here, so the picker only offers zones alerts can use. */
        @JavascriptInterface
        fun getSupportedTimeZones(): String = org.json.JSONArray(java.util.TimeZone.getAvailableIDs().toList()).toString()

        /** Haptic feedback for drag-to-reorder: "start" (lift), "tick" (passed a card), "drop". */
        @JavascriptInterface
        fun performHaptic(kind: String) {
            runOnUiThread {
                if (!::webView.isInitialized) return@runOnUiThread
                val constant = when (kind) {
                    "start" -> HapticFeedbackConstants.LONG_PRESS
                    "tick" -> HapticFeedbackConstants.CLOCK_TICK
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END
                        else HapticFeedbackConstants.VIRTUAL_KEY
                }
                webView.performHapticFeedback(constant)
            }
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread { requestNotificationPermissionIfNeeded() }
        }

        /** Opens the system file picker (ACTION_CREATE_DOCUMENT) to save the JSON backup. */
        @JavascriptInterface
        fun saveBackupToFile(suggestedFileName: String, content: String) {
            if (content.isBlank()) {
                runOnUiThread { runJs("window.onBackupFileSaved && window.onBackupFileSaved(false)") }
                return
            }
            val staged = runCatching {
                File(cacheDir, BACKUP_TEMP_FILE).writeText(content, Charsets.UTF_8)
            }.isSuccess
            runOnUiThread {
                if (!staged) {
                    runJs("window.onBackupFileSaved && window.onBackupFileSaved(false)")
                    return@runOnUiThread
                }
                try {
                    exportLauncher.launch(suggestedFileName.ifBlank { "dailies_backup.json" })
                } catch (e: Exception) {
                    runJs("window.onBackupFileSaved && window.onBackupFileSaved(false)")
                }
            }
        }

        /** Opens the system file picker (ACTION_OPEN_DOCUMENT) to import a backup (.json or legacy .txt). */
        @JavascriptInterface
        fun openBackupFilePicker() {
            runOnUiThread {
                try {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } catch (e: Exception) {
                    runJs("window.onBackupFileImported && window.onBackupFileImported(\"\")")
                }
            }
        }

        /** True when game icons can be served from the on-device cache (see shouldInterceptRequest). */
        @JavascriptInterface
        fun isFaviconCacheAvailable(): Boolean = true

        /**
         * Current clipboard text, read only when the user taps "Paste" / "Log with score" (the app is in
         * the foreground then, as Android 10+ requires). Android 12+ shows its own "pasted" notice.
         */
        @JavascriptInterface
        fun readClipboardText(requestId: Int) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = runCatching {
                    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                }.getOrNull().orEmpty()
                runJs("window.onClipboardText && window.onClipboardText($requestId, ${JSONObject.quote(text)})")
            }
        }

        /** Fetches the URL on a single-thread executor; replies via window.onWebsiteScraped (P9, K13, B5). */
        @JavascriptInterface
        fun scrapeWebsite(requestId: Int, rawUrl: String) {
            try {
                scrapeExecutor.execute {
                    val res = WebsiteScraper.fetchAndScrape(rawUrl)
                    res.put("requestId", requestId)
                    val payload = res.toString()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            runJs("window.onWebsiteScraped && window.onWebsiteScraped($payload)")
                        }
                    }
                }
            } catch (e: Exception) {
                // Executor shut down because the activity is being destroyed.
            }
        }
    }

    companion object {
        private const val ASSET_PREFIX = "file:///android_asset/"
        private const val MAX_IMPORT_BYTES = 50 * 1024 * 1024
        private const val BACKUP_TEMP_FILE = "pending_backup_export.json"
        private const val CSV_TEMP_FILE = "pending_history_export.csv"
        /** Characters per slice when handing an imported backup to the page. */
        private const val IMPORT_CHUNK_CHARS = 512 * 1024
    }
}
