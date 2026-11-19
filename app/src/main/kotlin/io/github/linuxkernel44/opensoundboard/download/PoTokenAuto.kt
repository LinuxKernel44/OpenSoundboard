package io.github.linuxkernel44.opensoundboard.download

import android.content.Context
import android.util.Log
import io.github.linuxkernel44.opensoundboard.data.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.TimeUnit

/** Who YouTube sees: the visitor id of the session and, when the imported cookies are signed in, the account's data sync id. */
data class YtIdentity(val visitorData: String, val dataSyncId: String?, val loggedIn: Boolean) {
    /** What a streaming ("gvs") PO token is bound to: the account when signed in, else the visitor. */
    val binding get() = if (loggedIn && dataSyncId != null) dataSyncId else visitorData
}

/**
 * Creates the YouTube PO token on the device (see [BotGuard]) for yt-dlp and NewPipe, so the user does not have to copy one
 * from a browser. The identity comes from YouTube's own page, fetched with the imported cookies when there are some.
 */
object PoTokenAuto : PoTokenProvider {
    private const val TAG = "OsbPoToken"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    private var guard: BotGuard? = null
    private var identity: Pair<YtIdentity, Long>? = null
    private val minted = HashMap<String, Pair<String, Long>>()     // identifier -> token, expiry (ms)
    private lateinit var appCtx: Context

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    fun isYoutube(url: String) = Regex("^https?://([\\w-]+\\.)*(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun videoId(url: String): String? = Regex("(?:[?&]v=|youtu\\.be/|shorts/|embed/|live/)([\\w-]{11})").find(url)?.groupValues?.get(1)

    @Synchronized
    private fun guard(): BotGuard {
        guard?.takeIf { !it.expired }?.let { return it }
        guard?.let { g -> android.os.Handler(android.os.Looper.getMainLooper()).post { g.close() } }
        return BotGuard.create(appCtx).also { guard = it }
    }

    /** Signs [identifier], reusing a token that is still valid. */
    @Synchronized
    fun mint(identifier: String): String {
        val g = guard()
        minted[identifier]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val t = try { g.mint(identifier) } catch (e: Exception) { guard = null; throw e }   // a broken WebView is recreated next time
        minted[identifier] = t to System.currentTimeMillis() + g.secondsLeft * 1000
        return t
    }

    /** Reads the visitor data (and account id, when signed in) from YouTube's home page, with the imported cookies if any. */
    @Synchronized
    fun identity(): YtIdentity {
        identity?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val cookie = TokenStore.cookieHeaderFor("https://www.youtube.com/")
        val req = Request.Builder().url("https://www.youtube.com/").header("User-Agent", BotGuard.USER_AGENT).header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", listOfNotNull(cookie, "SOCS=CAI").joinToString("; ")).build()
        val html = http.newCall(req).execute().use { it.body?.string() ?: "" }
        val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: throw BotGuard.PoTokenException("no visitor data on YouTube's page")
        val loggedIn = cookie != null && Regex("\"LOGGED_IN\":true").containsMatchIn(html)
        val sync = Regex("\"DATASYNC_ID\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.split("||")?.firstOrNull { it.isNotEmpty() }
        return YtIdentity(visitor, sync, loggedIn).also { identity = it to System.currentTimeMillis() + 30 * 60_000 }
    }

    /** Result of a manual "generate now" from the settings: the identity and the streaming token. */
    fun generate(): Pair<YtIdentity, String> { val id = identity(); return id to mint(id.binding) }

    fun secondsLeft(): Long = guard?.secondsLeft ?: 0

    /**
     * The yt-dlp `--extractor-args` value for [url] built from a generated token, or null when it does not apply
     * (not a YouTube link, switched off, or the token could not be created).
     */
    fun extractorArgsFor(url: String): String? {
        if (!Settings.autoPoToken || !isYoutube(url)) return null
        return try {
            val id = identity()
            val gvs = mint(id.binding)
            val clients = listOf("web", "web_safari", "mweb")
            val tokens = ArrayList<String>()
            clients.forEach { tokens += "$it.gvs+$gvs" }
            videoId(url)?.let { v -> val player = mint(v); clients.forEach { tokens += "$it.player+$player" } }
            val parts = arrayListOf("po_token=" + tokens.joinToString(","))
            if (id.loggedIn && id.dataSyncId != null) parts += "data_sync_id=${id.dataSyncId}" else parts += "visitor_data=${id.visitorData}"
            Log.i(TAG, "auto PO token applied (signedIn=${id.loggedIn}, videoId=${videoId(url)}, secondsLeft=${secondsLeft()})")
            "youtube:" + parts.joinToString(";")
        } catch (e: Throwable) {
            Log.w(TAG, "automatic PO token failed: ${e.message}")
            null
        }
    }

    /** NewPipeExtractor asks for a web client token bound to an anonymous visitor (streaming) and to the video (player). */
    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!Settings.autoPoToken) return null
        return try {
            val info = InnertubeClientRequestInfo.ofWebClient().also { it.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion() }
            val visitor = YoutubeParsingHelper.getVisitorDataFromInnertube(info, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(), YoutubeParsingHelper.YOUTUBEI_V1_URL, null, false)
            PoTokenResult(visitor, mint(videoId), mint(visitor))
        } catch (e: Throwable) {
            Log.w(TAG, "NewPipe PO token failed: ${e.message}")
            null
        }
    }
}
