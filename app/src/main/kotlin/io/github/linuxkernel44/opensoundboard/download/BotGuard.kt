package io.github.linuxkernel44.opensoundboard.download

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Mints YouTube PO tokens on the device: runs Google's BotGuard challenge in a hidden WebView, trades its answer for an
 * integrity token, then signs identifiers (a visitor id, an account id or a video id) with it.
 *
 * The approach and the page in assets/po_token.html come from NewPipe's `PoTokenWebView` (GPL-3.0,
 * https://github.com/TeamNewPipe/NewPipe), ported here to plain futures.
 */
class BotGuard private constructor(private val appCtx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private val ready = CompletableFuture<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    @Volatile private var expiresAtMs = 0L

    val expired get() = System.currentTimeMillis() > expiresAtMs
    val secondsLeft get() = ((expiresAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    @SuppressLint("SetJavaScriptEnabled")
    private fun start() {
        val wv = WebView(appCtx).also { webView = it }
        wv.settings.javaScriptEnabled = true
        wv.settings.userAgentString = USER_AGENT
        wv.settings.blockNetworkLoads = true       // the page itself never needs the network
        wv.settings.safeBrowsingEnabled = false
        wv.addJavascriptInterface(this, JS)
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) fail(PoTokenException("WebView cannot run BotGuard: ${m.message()}"))
                return super.onConsoleMessage(m)
            }
        }
        val html = appCtx.assets.open("po_token.html").bufferedReader().use { it.readText() }
        wv.loadDataWithBaseURL("https://www.youtube.com", html.replaceFirst("</script>", "\n$JS.downloadAndRunBotguard()</script>"), "text/html", "utf-8", null)
    }

    private fun fail(e: Throwable) {
        ready.completeExceptionally(e)
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
        main.post { close() }
    }

    fun close() {
        webView?.let { w -> w.loadUrl("about:blank"); w.onPause(); w.removeAllViews(); w.destroy() }
        webView = null
    }

    private fun post(url: String, body: String): String {
        val req = Request.Builder().url(url).post(body.toRequestBody("application/json+protobuf".toMediaType()))
            .header("User-Agent", USER_AGENT).header("Accept", "application/json").header("x-goog-api-key", API_KEY).header("x-user-agent", "grpc-web-javascript/0.1").build()
        http.newCall(req).execute().use { r ->
            if (r.code != 200) throw PoTokenException("HTTP ${r.code} from $url")
            return r.body?.string() ?: throw PoTokenException("empty response")
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Thread {
            try {
                val challenge = parseChallenge(post("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]"))
                main.post {
                    webView?.evaluateJavascript("""try {
                        data = $challenge
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS.onRunBotguardResult(result.botguardResponse)
                        }, function (error) { $JS.onJsError(error + "\n" + error.stack) })
                    } catch (error) { $JS.onJsError(error + "\n" + error.stack) }""", null)
                }
            } catch (e: Throwable) { fail(e) }
        }.start()
    }

    @JavascriptInterface
    fun onJsError(error: String) { Log.w(TAG, "BotGuard script error: $error"); fail(PoTokenException(error.lineSequence().firstOrNull() ?: "script error")) }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        Thread {
            try {
                val raw = post("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"$REQUEST_KEY\", ${JSONObject.quote(botguardResponse)} ]")
                val arr = JSONArray(raw)
                val token = "new Uint8Array([" + b64(arr.getString(0)).joinToString(",") { (it.toInt() and 0xFF).toString() } + "])"
                expiresAtMs = System.currentTimeMillis() + (arr.getLong(1) - 600) * 1000      // 10 minutes of margin
                main.post { webView?.evaluateJavascript("this.integrityToken = $token") { ready.complete(Unit) } }
            } catch (e: Throwable) { fail(e) }
        }.start()
    }

    /** Signs [identifier] and returns the PO token. Blocking; call off the main thread. */
    fun mint(identifier: String): String {
        ready.get(45, TimeUnit.SECONDS)
        val future = CompletableFuture<String>()
        pending[identifier] = future
        val bytes = "new Uint8Array([" + identifier.toByteArray().joinToString(",") { (it.toInt() and 0xFF).toString() } + "])"
        main.post {
            webView?.evaluateJavascript("""try {
                identifier = ${JSONObject.quote(identifier)}
                poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, $bytes)
                poTokenU8String = ""
                for (i = 0; i < poTokenU8.length; i++) { if (i != 0) poTokenU8String += ","; poTokenU8String += poTokenU8[i] }
                $JS.onMinted(identifier, poTokenU8String)
            } catch (error) { $JS.onMintError(identifier, error + "\n" + error.stack) }""", null)
        }
        return future.get(30, TimeUnit.SECONDS)
    }

    @JavascriptInterface
    fun onMinted(identifier: String, u8: String) {
        val token = runCatching {
            Base64.getEncoder().encodeToString(u8.split(",").map { it.toInt().toByte() }.toByteArray()).replace('+', '-').replace('/', '_')
        }
        pending.remove(identifier)?.let { f -> token.onSuccess { f.complete(it) }.onFailure { f.completeExceptionally(it) } }
    }

    @JavascriptInterface
    fun onMintError(identifier: String, error: String) {
        pending.remove(identifier)?.completeExceptionally(PoTokenException(error.lineSequence().firstOrNull() ?: "mint error"))
    }

    class PoTokenException(msg: String) : Exception(msg)

    companion object {
        private const val TAG = "OsbBotGuard"
        private const val JS = "OsbBotGuard"
        // Public values used by BotGuard's own requests (taken from them, as NewPipe does).
        private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        /** Loads BotGuard and obtains an integrity token. Blocking; call off the main thread. */
        fun create(ctx: Context): BotGuard {
            val g = BotGuard(ctx.applicationContext)
            Handler(Looper.getMainLooper()).post { runCatching { g.start() }.onFailure { g.fail(it) } }
            g.ready.get(60, TimeUnit.SECONDS)
            return g
        }

        private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').replace('.', '='))

        /** The challenge from the Create endpoint as a JavaScript object literal. It may be scrambled (base64, each byte + 97). */
        private fun parseChallenge(raw: String): String {
            val scrambled = JSONArray(raw)
            val c = if (scrambled.length() > 1 && scrambled.opt(1) is String) JSONArray(String(b64(scrambled.getString(1)).map { (it + 97).toByte() }.toByteArray())) else scrambled.getJSONArray(0)
            fun firstString(a: JSONArray?): Any = a?.let { arr -> (0 until arr.length()).map { arr.get(it) }.firstOrNull { it is String } } ?: JSONObject.NULL
            return JSONObject()
                .put("messageId", c.getString(0))
                .put("interpreterJavascript", JSONObject()
                    .put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(c.optJSONArray(1)))
                    .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(c.optJSONArray(2))))
                .put("interpreterHash", c.getString(3)).put("program", c.getString(4)).put("globalName", c.getString(5))
                .put("clientExperimentsStateBlob", c.optString(7)).toString()
        }
    }
}
