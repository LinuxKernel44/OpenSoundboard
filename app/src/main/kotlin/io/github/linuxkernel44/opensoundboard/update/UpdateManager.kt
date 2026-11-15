package io.github.linuxkernel44.opensoundboard.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.linuxkernel44.opensoundboard.BuildConfig
import io.github.linuxkernel44.opensoundboard.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val version: String, val notes: String, val apkUrl: String, val apkSize: Long,
    val shaUrl: String?, val pageUrl: String,
)

enum class UpdateError { NETWORK, RATE_LIMITED, NO_APK, CHECKSUM, SIGNATURE, STORAGE, INSTALL }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState
    data class Verifying(val release: ReleaseInfo) : UpdateState
    data class Ready(val release: ReleaseInfo, val file: File, val needsPermission: Boolean = false) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val error: UpdateError, val detail: String? = null, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * In-app updates from the project's GitHub releases. Only the public release API is contacted and no
 * information about the user or device is sent. A downloaded APK is installed only if its SHA-256 matches the
 * checksum published with the release AND it is signed with the same certificate as the installed app.
 */
object UpdateManager {
    private const val OWNER = "LinuxKernel44"
    private const val REPO = "OpenSoundboard"
    private const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    const val ACTION_INSTALL_STATUS = "io.github.linuxkernel44.opensoundboard.INSTALL_STATUS"

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** The release worth showing a banner for: newer than the installed version and not skipped. */
    fun pendingRelease(): ReleaseInfo? = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Verifying -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Installing -> s.release
        else -> null
    }?.takeIf { it.version != Settings.skippedVersion }

    fun autoCheck(ctx: Context) {
        cleanup(ctx)
        if (!Settings.autoUpdateCheck) return
        if (System.currentTimeMillis() - Settings.lastUpdateCheck < AUTO_CHECK_INTERVAL_MS) return
        check(ctx, silent = true)
    }

    fun check(ctx: Context, silent: Boolean = false) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing) return
        if (!silent) state = UpdateState.Checking
        job = scope.launch {
            try {
                val r = fetchLatest()
                Settings.lastUpdateCheck = System.currentTimeMillis(); Settings.save()
                state = if (r != null && isNewer(r.version, currentVersion())) UpdateState.Available(r) else if (silent) UpdateState.Idle else UpdateState.UpToDate
            } catch (e: RateLimit) {
                if (!silent) state = UpdateState.Failed(UpdateError.RATE_LIMITED)
            } catch (e: Exception) {
                if (!silent) state = UpdateState.Failed(UpdateError.NETWORK, e.message)
            }
        }
    }

    fun skip(version: String) { Settings.skippedVersion = version; Settings.save(); state = UpdateState.Idle }

    fun cancel() { job?.cancel(); (pendingRelease() ?: releaseOf(state))?.let { state = UpdateState.Available(it) } }

    private fun releaseOf(s: UpdateState): ReleaseInfo? = when (s) {
        is UpdateState.Failed -> s.release
        else -> null
    }

    fun download(ctx: Context) {
        val r = releaseOf(state) ?: (state as? UpdateState.Available)?.release ?: (state as? UpdateState.Ready)?.release ?: return
        if (state is UpdateState.Downloading) return
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "OpenSoundboard-${r.version}.apk")
        job = scope.launch {
            try {
                state = UpdateState.Downloading(r, 0, r.apkSize)
                dir.listFiles()?.forEach { if (it != out) it.delete() }
                download(r.apkUrl, out) { done -> state = UpdateState.Downloading(r, done, r.apkSize) }
                ensureActive()
                state = UpdateState.Verifying(r)
                val expected = r.shaUrl?.let { runCatching { parseSha(httpText(it)) }.getOrNull() }
                if (expected == null || !sha256(out).equals(expected, ignoreCase = true)) { out.delete(); state = UpdateState.Failed(UpdateError.CHECKSUM, null, r); return@launch }
                if (!sameSigner(ctx, out)) { out.delete(); state = UpdateState.Failed(UpdateError.SIGNATURE, null, r); return@launch }
                state = UpdateState.Ready(r, out, needsPermission = !canInstall(ctx))
            } catch (e: kotlinx.coroutines.CancellationException) {
                out.delete(); throw e
            } catch (e: IOException) {
                out.delete()
                state = UpdateState.Failed(if (e.message?.contains("ENOSPC") == true) UpdateError.STORAGE else UpdateError.NETWORK, e.message, r)
            } catch (e: Exception) {
                out.delete(); state = UpdateState.Failed(UpdateError.NETWORK, e.message, r)
            }
        }
    }

    private fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates. */
    fun requestInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context) {
        val s = state as? UpdateState.Ready ?: return
        if (!canInstall(ctx)) { state = s.copy(needsPermission = true); requestInstallPermission(ctx); return }
        state = UpdateState.Installing(s.release)
        scope.launch {
            try {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(ctx.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    s.file.inputStream().use { i -> session.openWrite("OpenSoundboard", 0, s.file.length()).use { o -> i.copyTo(o); session.fsync(o) } }
                    val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS).setPackage(ctx.packageName)
                    val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                state = UpdateState.Failed(UpdateError.INSTALL, e.message, s.release)
            }
        }
    }

    /** Called by [InstallReceiver] when the installer reports a failure. */
    fun installFailed(message: String?) {
        val r = pendingRelease() ?: releaseOf(state)
        state = UpdateState.Failed(UpdateError.INSTALL, message, r)
    }

    /** Removes downloaded APKs left over after a successful update. */
    private fun cleanup(ctx: Context) {
        val dir = File(ctx.cacheDir, "updates")
        dir.listFiles()?.forEach { f ->
            val v = f.name.removePrefix("OpenSoundboard-").removeSuffix(".apk")
            if (!isNewer(v, currentVersion())) f.delete()
        }
    }

    // ---- network ----
    private class RateLimit : IOException()

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        require(u.protocol == "https") { "https only" }
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "OpenSoundboard-updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private fun httpText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode == 403 || c.responseCode == 429) throw RateLimit()
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun fetchLatest(): ReleaseInfo? {
        val j = JSONObject(httpText("https://api.github.com/repos/$OWNER/$REPO/releases/latest"))
        val assets = j.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.getString("name")
            if (n.endsWith(".apk")) apk = a else if (n.endsWith(".apk.sha256")) sha = a.getString("browser_download_url")
        }
        val a = apk ?: return null
        return ReleaseInfo(
            version = j.getString("tag_name").removePrefix("v"), notes = j.optString("body", "").trim(),
            apkUrl = a.getString("browser_download_url"), apkSize = a.optLong("size"), shaUrl = sha, pageUrl = j.optString("html_url"),
        )
    }

    private fun download(url: String, out: File, onProgress: (Long) -> Unit) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            var done = 0L
            var last = 0L
            c.inputStream.use { i -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val now = System.nanoTime()
                    if (now - last > 100_000_000L) { last = now; onProgress(done) }
                }
            } }
            onProgress(done)
        } finally { c.disconnect() }
    }

    // ---- verification ----
    private fun parseSha(text: String): String? = Regex("[0-9a-fA-F]{64}").find(text)?.value

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signers(pm: PackageManager, info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = signers(pm, pm.getPackageArchiveInfo(apk.absolutePath, flag))
        val installed = signers(pm, pm.getPackageInfo(ctx.packageName, flag))
        return archive.isNotEmpty() && archive == installed
    }

    /** True when [a] is a higher dotted version than [b] (numeric parts only, missing parts count as 0). */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }
}
