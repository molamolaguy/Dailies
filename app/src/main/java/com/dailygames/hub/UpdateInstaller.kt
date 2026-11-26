package com.dailygames.hub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Downloads the latest release's APK from GitHub and hands it to Android's package installer, which
 * asks the user to confirm. The APK must be this app, newer, and signed with the same key as the
 * installed copy (Android enforces the key too; checking first gives a clear message).
 */
object UpdateInstaller {
    const val ACTION_STATUS = "com.dailygames.hub.UPDATE_INSTALL_STATUS"
    private const val MAX_APK_BYTES = 80L * 1024 * 1024
    private val ALLOWED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    sealed class Result {
        data class Ready(val file: File) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Deletes a downloaded update. The installer copies the APK into its own session when the install
     * starts, so the file is never needed again once the app restarts (as the new version or not).
     */
    fun clearDownloads(ctx: Context) {
        runCatching { File(ctx.cacheDir, "updates").deleteRecursively() }
    }

    /** Whether Android lets this app install packages (Android 8+ asks per app). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        return h in ALLOWED_HOSTS
    }

    /** Fetches and checks the latest APK; [progress] gets 0-100 (or -1 when the size is unknown). */
    fun download(ctx: Context, progress: (Int) -> Unit): Result {
        val release = UpdateCheck.latestRelease() ?: return Result.Failed("Couldn't reach GitHub. Try again later.")
        val current = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        if (!UpdateCheck.isNewer(release.optString("tag_name"), current)) return Result.Failed("You're already on the latest version.")
        val (url, size) = UpdateCheck.apkAsset(release) ?: return Result.Failed("This release has no APK to install.")
        if (size > MAX_APK_BYTES) return Result.Failed("The update is too large to download.")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Dailies-update.apk")
        val err = fetch(url, file, size, progress)
        if (err != null) { file.delete(); return Result.Failed(err) }
        val problem = verify(ctx, file)
        if (problem != null) { file.delete(); return Result.Failed(problem) }
        return Result.Ready(file)
    }

    private fun fetch(start: String, out: File, expected: Long, progress: (Int) -> Unit): String? {
        var current = start
        repeat(6) {
            val u = runCatching { URL(current) }.getOrNull() ?: return "Bad download link."
            if (u.protocol != "https" || !isAllowedHost(u.host)) return "The download was redirected somewhere unexpected."
            val conn = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Dailies-Android")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = URL(u, conn.getHeaderField("Location") ?: return "Download failed.").toString()
                    return@repeat
                }
                if (code !in 200..299) return "Download failed (HTTP $code)."
                val contentLen = conn.contentLengthLong
                val total = contentLen.takeIf { it > 0 } ?: expected
                if (total > MAX_APK_BYTES) return "The update is too large to download."
                var read = 0L
                var lastPct = -2
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > MAX_APK_BYTES) return "The update is too large to download."
                            o.write(buf, 0, n)
                            val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else -1
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
                val expectedBytes = if (expected > 0) expected else contentLen
                if (expectedBytes > 0 && read != expectedBytes) return "The download was incomplete. Try again."
                return null
            } catch (e: Exception) {
                return "Download failed. Check your connection and try again."
            } finally {
                conn.disconnect()
            }
        }
        return "Too many redirects."
    }

    /** Null when [apk] is a newer build of this app signed like the installed one; otherwise why not. */
    fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file isn't a valid app."
        if (archive.packageName != ctx.packageName) return "The downloaded file is a different app."
        val installed = runCatching { pm.getPackageInfo(ctx.packageName, flags) }.getOrNull() ?: return "Couldn't read this app's details."
        if (versionCode(archive) <= versionCode(installed)) return "You're already on the latest version."
        if (!isCompatibleSignature(installed, archive)) {
            return "The update is signed with a different key than this copy, so Android can't install it over it. Download it from GitHub instead."
        }
        return null
    }

    internal fun isCompatibleSignature(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val instInfo = installed.signingInfo
            val archInfo = archive.signingInfo
            if (instInfo != null && archInfo != null) {
                if (!instInfo.hasMultipleSigners() && !archInfo.hasMultipleSigners()) {
                    val instCurrent = digestsOf(instInfo.apkContentsSigners)
                    val instHistory = digestsOf(instInfo.signingCertificateHistory).ifEmpty { instCurrent }
                    val archHistory = digestsOf(archInfo.signingCertificateHistory)
                    val archCurrent = digestsOf(archInfo.apkContentsSigners).ifEmpty { archHistory }
                    if (instCurrent.isEmpty() || archCurrent.isEmpty()) return false
                    // Same current signer, or archive's v3 signing history contains the installed signer (rotation).
                    return instCurrent == archCurrent ||
                        (archHistory.isNotEmpty() && instCurrent.all { it in archHistory }) ||
                        (instHistory.isNotEmpty() && instHistory == archHistory)
                }
                val instMulti = digestsOf(instInfo.apkContentsSigners)
                val archMulti = digestsOf(archInfo.apkContentsSigners)
                return instMulti.isNotEmpty() && instMulti == archMulti
            }
        }
        val mine = certDigests(installed)
        val theirs = certDigests(archive)
        return mine.isNotEmpty() && mine == theirs
    }

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certDigests(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = p.signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners
            else info.signingCertificateHistory?.takeIf { it.isNotEmpty() } ?: info.apkContentsSigners
        } else {
            p.signatures
        }
        return digestsOf(sigs)
    }

    private fun digestsOf(sigs: Array<android.content.pm.Signature>?): Set<String> {
        if (sigs.isNullOrEmpty()) return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    const val NOTIFICATION_ID = 0x0DA1E9

    /** Starts an install session; Android then shows its "Update this app?" confirmation. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        // Abandon any stale uncommitted sessions owned by us so we never leak towards OS session caps.
        runCatching {
            installer.mySessions.forEach { info ->
                val uncommitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) !info.isCommitted else !info.isActive
                if (uncommitted) runCatching { installer.abandonSession(info.sessionId) }
            }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(ACTION_STATUS).setClass(ctx, UpdateInstallReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Posts a notification that opens Android's update confirmation (fallback when backgrounded on Android 14+). */
    fun postConfirmNotification(ctx: Context, confirm: Intent): Boolean {
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        Notifier.ensureChannel(ctx)
        val launch = Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(ctx, NOTIFICATION_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, Notifier.channelId(ctx))
            .setSmallIcon(R.drawable.ic_stat_dailes)
            .setContentTitle("Dailies update ready")
            .setContentText("Tap to finish installing the update.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .let { Notifier.withAppIcon(ctx, it) }
            .build()
        return runCatching { nm.notify(NOTIFICATION_ID, n) }.isSuccess
    }
}

/** Receives the installer's status: shows Android's confirmation screen, or reports a failure. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    if (!HubActivity.launchUpdateConfirm(it)) {
                        val started = runCatching { context.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                        if (!started || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            UpdateInstaller.postConfirmNotification(context, it)
                        }
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> HubActivity.reportUpdateStatus("error", -1, "Update cancelled.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                HubActivity.reportUpdateStatus("error", -1, if (msg.isNullOrBlank()) "The update wasn't installed." else "The update wasn't installed ($msg).")
            }
        }
    }
}
