package io.github.linuxkernel44.opensoundboard.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Chapter(val title: String, val startSec: Float)

/** What a link points to: shown in the preview before anything is downloaded. */
data class LinkInfo(
    val url: String, val title: String, val uploader: String, val durationSec: Int, val thumbnail: String,
    val chapters: List<Chapter>, val engine: String,
)

enum class Phase { Probing, Downloading, UpdatingYtDlp, Fallback }

class DownloadException(val kind: Kind, msg: String = kind.name) : Exception(msg) {
    enum class Kind { Cancelled, NotFound, Failed }
}

/**
 * Downloads the audio of a link. yt-dlp (bundled Python + ffmpeg) runs first with the best audio stream, untouched. If it
 * fails, yt-dlp is updated and tried once more, then NewPipeExtractor takes over. The user's cookies and PO token are applied
 * to yt-dlp. Nothing is re-encoded here: the file keeps the source codec.
 */
object LinkDownloader {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    @Volatile private var ready = false
    @Volatile private var updatedThisRun = false
    @Volatile private var cancelled = false
    @Volatile private var processId: String? = null
    var ytDlpVersion: String = ""; private set

    @Synchronized
    private fun ensureInit(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        runCatching { FFmpeg.getInstance().init(ctx.applicationContext) }
        runCatching { ytDlpVersion = YoutubeDL.getInstance().version(ctx.applicationContext) ?: "" }
        NewPipe.init(object : Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                val b = Request.Builder().url(request.url()).header("User-Agent", UA)
                request.headers().forEach { (k, v) -> b.removeHeader(k); v.forEach { b.addHeader(k, it) } }
                val body = request.dataToSend()
                b.method(request.httpMethod(), body?.let { okhttp3.RequestBody.create(null, it) } ?: if (request.httpMethod() == "POST") okhttp3.RequestBody.create(null, ByteArray(0)) else null)
                http.newCall(b.build()).execute().use { r ->
                    if (r.code == 429) throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("rate limited", r.request.url.toString())
                    return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string() ?: "", r.request.url.toString())
                }
            }
        }, Localization.DEFAULT)
        PoTokenAuto.init(ctx)
        org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setPoTokenProvider(PoTokenAuto)
        ready = true
    }

    fun cancel() {
        cancelled = true
        processId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
    }

    private fun check() { if (cancelled) throw DownloadException(DownloadException.Kind.Cancelled) }

    /** Applies the user's cookies and PO token to a yt-dlp request. Returns the temp cookie file to delete afterwards. */
    private fun YoutubeDLRequest.withTokens(url: String): File? {
        addOption("--no-playlist"); addOption("--no-warnings"); addOption("--socket-timeout", 20)
        // A token the user typed wins; otherwise one is generated on the device for YouTube links.
        (TokenStore.youtubeExtractorArgs() ?: PoTokenAuto.extractorArgsFor(url))?.let { addOption("--extractor-args", it) }
        return TokenStore.cookieFileFor(url)?.also { addOption("--cookies", it.absolutePath) }
    }

    // ---- preview ----
    fun probe(ctx: Context, url: String, onPhase: (Phase) -> Unit): LinkInfo {
        cancelled = false
        ensureInit(ctx)
        onPhase(Phase.Probing)
        return viaYtDlp(ctx, url, onPhase) { probeYtDlp(url) } ?: probeNewPipe(url).also { onPhase(Phase.Fallback) }
    }

    private fun probeYtDlp(url: String): LinkInfo {
        val req = YoutubeDLRequest(url).apply { addOption("--dump-single-json"); addOption("--skip-download") }
        val cookies = req.withTokens(url)
        try {
            val pid = "probe-" + UUID.randomUUID()
            processId = pid
            val out = YoutubeDL.getInstance().execute(req, pid).out
            val j = JSONObject(out.substring(out.indexOf('{')))
            val ch = j.optJSONArray("chapters")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { c -> Chapter(c.optString("title"), c.optDouble("start_time", 0.0).toFloat()) } } } ?: emptyList()
            return LinkInfo(url, j.optString("title", "Audio"), j.optString("uploader", j.optString("channel", "")), j.optDouble("duration", 0.0).toInt(), j.optString("thumbnail"), ch, "yt-dlp")
        } finally { processId = null; cookies?.delete() }
    }

    private fun probeNewPipe(url: String): LinkInfo {
        val si = try { StreamInfo.getInfo(url) } catch (e: Exception) { android.util.Log.w("OsbLink", "NewPipe failed", e); check(); throw DownloadException(DownloadException.Kind.NotFound, e.message ?: "not found") }
        val ch = si.streamSegments.map { Chapter(it.title ?: "", it.startTimeSeconds.toFloat()) }
        return LinkInfo(url, si.name ?: "Audio", si.uploaderName ?: "", si.duration.toInt(), si.thumbnails.maxByOrNull { it.width }?.url ?: "", ch, "newpipe")
    }

    /** Runs [block] with yt-dlp; on failure updates yt-dlp once per launch and retries. Returns null when both attempts failed. */
    private fun <T> viaYtDlp(ctx: Context, url: String, onPhase: (Phase) -> Unit, block: () -> T): T? {
        for (attempt in 0..1) {
            check()
            try { return block() } catch (e: Exception) {
                android.util.Log.w("OsbLink", "yt-dlp attempt $attempt failed: ${e.message?.take(600)}")
                check()
                if (attempt == 0 && !updatedThisRun) {
                    onPhase(Phase.UpdatingYtDlp)
                    updatedThisRun = true
                    runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE); ytDlpVersion = YoutubeDL.getInstance().version(ctx.applicationContext) ?: ytDlpVersion }
                } else return null
            }
        }
        return null
    }

    // ---- download ----
    /** Downloads the best audio of [info] into a fresh folder under the cache and returns the file. */
    fun download(ctx: Context, info: LinkInfo, onProgress: (Float) -> Unit, onPhase: (Phase) -> Unit): File {
        cancelled = false
        ensureInit(ctx)
        val dir = File(ctx.cacheDir, "link-downloads/${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        try {
            onPhase(Phase.Downloading)
            val viaNewPipe = info.engine == "newpipe"
            if (!viaNewPipe) viaYtDlp(ctx, info.url, onPhase) { downloadYtDlp(info.url, dir, onProgress) }?.let { return it }
            check()
            onPhase(Phase.Fallback)
            return downloadNewPipe(info.url, dir, onProgress)
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (cancelled) throw DownloadException(DownloadException.Kind.Cancelled)
            throw if (e is DownloadException) e else DownloadException(DownloadException.Kind.Failed, e.message ?: "failed")
        }
    }

    private fun downloadYtDlp(url: String, dir: File, onProgress: (Float) -> Unit): File {
        val req = YoutubeDLRequest(url).apply {
            addOption("-f", "bestaudio/best"); addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
            addOption("--no-mtime"); addOption("--newline")
        }
        val cookies = req.withTokens(url)
        try {
            val pid = "dl-" + UUID.randomUUID()
            processId = pid
            YoutubeDL.getInstance().execute(req, pid) { p, _, _ -> if (p >= 0f) onProgress((p / 100f).coerceIn(0f, 1f)) }
        } finally { processId = null; cookies?.delete() }
        return dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }?.maxByOrNull { it.length() }
            ?: throw IOException("yt-dlp produced no file")
    }

    private fun downloadNewPipe(url: String, dir: File, onProgress: (Float) -> Unit): File {
        val si = StreamInfo.getInfo(url)
        // Highest bitrate stream a plain HTTP GET can fetch; the order is Opus/WebM, then AAC/M4A.
        val stream = si.audioStreams.filter { it.isUrl && it.content.isNotBlank() }.maxByOrNull { it.averageBitrate }
            ?: throw IOException("no audio stream")
        val ext = stream.format?.suffix ?: "m4a"
        val out = File(dir, "audio.$ext")
        val chunk = 1L shl 20
        var pos = 0L
        var total = -1L
        out.outputStream().buffered().use { os ->
            while (total < 0 || pos < total) {
                check()
                val end = pos + chunk - 1
                val u = if (stream.content.contains("googlevideo.com")) "${stream.content}&range=$pos-$end" else stream.content
                val rb = Request.Builder().url(u).header("User-Agent", UA)
                if (!u.contains("range=")) rb.header("Range", "bytes=$pos-$end")
                http.newCall(rb.build()).execute().use { r ->
                    if (r.code == 416) { total = pos; return@use }
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val cr = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    val body = r.body ?: throw IOException("empty body")
                    val len = body.contentLength()
                    if (cr != null) total = cr
                    else if (total < 0 && len >= 0 && len < chunk) total = pos + len
                    val n = body.byteStream().copyTo(os)
                    if (n == 0L) { total = pos; return@use }
                    pos += n
                    if (total > 0) onProgress((pos.toFloat() / total).coerceIn(0f, 1f))
                    if (r.header("Content-Range") == null && n < chunk && total < 0) total = pos
                }
            }
        }
        if (out.length() == 0L) throw IOException("empty download")
        return out
    }

    /** Updates yt-dlp from its release channel; returns the new version or null on failure. */
    fun updateYtDlp(ctx: Context): String? = runCatching {
        ensureInit(ctx)
        YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        YoutubeDL.getInstance().version(ctx.applicationContext).also { ytDlpVersion = it ?: ytDlpVersion }
    }.getOrNull()

    fun versionLabel(ctx: Context): String = runCatching { ensureInit(ctx); ytDlpVersion }.getOrDefault("")

    fun clearCache(ctx: Context) { File(ctx.cacheDir, "link-downloads").deleteRecursively() }
}
