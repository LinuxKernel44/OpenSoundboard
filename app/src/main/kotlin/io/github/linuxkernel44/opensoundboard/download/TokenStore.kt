package io.github.linuxkernel44.opensoundboard.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A cookie file the user imported: Netscape `cookies.txt` lines, kept encrypted on the device. */
data class CookieSet(val id: String, val label: String, val global: Boolean, val domains: List<String>, val count: Int, val created: Long)

/** One cookie of a stored set, with its value masked. */
data class CookieRow(val domain: String, val name: String, val masked: String)

class TokenException(msg: String) : Exception(msg)

/**
 * The user's own sign-in material for link downloads: cookie files (imported as Netscape `cookies.txt`, either per site or
 * global) and a YouTube PO token with its visitor data. Everything is encrypted with an Android Keystore key that never
 * leaves the device, and is excluded from backups. Cookies are only written in clear to a private temp file for the
 * duration of one download.
 */
object TokenStore {
    private const val ALIAS = "osb-tokens"
    private lateinit var dir: File
    private lateinit var cache: File
    var sets by mutableStateOf<List<CookieSet>>(emptyList()); private set
    var poToken by mutableStateOf(""); private set
    var visitorData by mutableStateOf(""); private set

    fun init(ctx: Context) {
        dir = File(ctx.noBackupFilesDir, "tokens").apply { mkdirs() }
        cache = File(ctx.cacheDir, "cookie-tmp").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        sets = readIndex()
        runCatching { readBlob(File(dir, "youtube.enc"))?.let { JSONObject(it) } }.getOrNull()?.let {
            poToken = it.optString("po"); visitorData = it.optString("visitor")
        }
    }

    // ---- encryption ----
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun writeBlob(f: File, text: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        f.writeBytes(c.iv + c.doFinal(text.toByteArray()))
    }

    private fun readBlob(f: File): String? {
        if (!f.exists()) return null
        val b = f.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }

    // ---- cookie sets ----
    private fun readIndex(): List<CookieSet> = runCatching {
        val a = JSONArray(File(dir, "index.json").readText())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { o ->
                CookieSet(o.getString("id"), o.getString("label"), o.getBoolean("global"),
                    o.getJSONArray("domains").let { d -> (0 until d.length()).map { d.getString(it) } }, o.getInt("count"), o.getLong("created"))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveIndex() {
        File(dir, "index.json").writeText(JSONArray().also { a ->
            sets.forEach { s -> a.put(JSONObject().put("id", s.id).put("label", s.label).put("global", s.global).put("count", s.count)
                .put("created", s.created).put("domains", JSONArray(s.domains))) }
        }.toString())
    }

    /** Keeps the valid Netscape lines (7 tab-separated fields; `#HttpOnly_` prefixes are kept) and drops comments. */
    fun parseNetscape(text: String): List<String> = text.replace("\r\n", "\n").split('\n').mapNotNull { raw ->
        if (raw.isBlank() || (raw.startsWith("#") && !raw.startsWith("#HttpOnly_"))) return@mapNotNull null
        val p = raw.split('\t')
        if (p.size != 7 || p[0].removePrefix("#HttpOnly_").isEmpty() || p[5].isEmpty()) null else raw
    }

    private fun domainOf(line: String) = line.substringBefore('\t').removePrefix("#HttpOnly_").trimStart('.').lowercase()

    /** Stores a cookie file. Throws [TokenException] when no cookie can be read from it. */
    fun addCookies(label: String, text: String, global: Boolean): CookieSet {
        val lines = parseNetscape(text)
        if (lines.isEmpty()) throw TokenException("no-cookies")
        val id = UUID.randomUUID().toString().take(12)
        writeBlob(File(dir, "$id.enc"), lines.joinToString("\n"))
        val set = CookieSet(id, label.trim().ifEmpty { "cookies" }, global, lines.map(::domainOf).distinct().sorted(), lines.size, System.currentTimeMillis())
        sets = listOf(set) + sets
        saveIndex()
        return set
    }

    fun removeCookies(id: String) {
        File(dir, "$id.enc").delete()
        sets = sets.filterNot { it.id == id }
        saveIndex()
    }

    /** Cookie names and domains only; values are masked. */
    fun preview(id: String): List<CookieRow> = (readBlob(File(dir, "$id.enc")) ?: "").split('\n').filter { it.isNotBlank() }.map { l ->
        val p = l.split('\t')
        CookieRow(p[0].removePrefix("#HttpOnly_"), p[5], if (p[6].isEmpty()) "" else p[6].take(2) + "…")
    }

    private fun hostMatches(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    /**
     * A private temp cookies file for [url]: every global set whole, plus the lines of per-site sets that match the host.
     * Returns null when nothing applies. The caller deletes the file when the download ends.
     */
    fun cookieFileFor(url: String): File? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        val lines = sets.flatMap { s ->
            val all = readBlob(File(dir, "${s.id}.enc"))?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            if (s.global) all else all.filter { hostMatches(host, domainOf(it)) }
        }
        if (lines.isEmpty()) return null
        return File.createTempFile("c", ".txt", cache).also {
            it.setReadable(false, false); it.setReadable(true, true); it.setWritable(true, true)
            it.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        }
    }

    // ---- YouTube PO token ----
    fun saveYoutube(po: String, visitor: String) {
        poToken = po.trim(); visitorData = visitor.trim()
        if (poToken.isEmpty() && visitorData.isEmpty()) File(dir, "youtube.enc").delete()
        else writeBlob(File(dir, "youtube.enc"), JSONObject().put("po", poToken).put("visitor", visitorData).toString())
    }

    /** yt-dlp `--extractor-args` value for the stored PO token, or null. A bare token gets the usual `web.gvs+` prefix. */
    fun youtubeExtractorArgs(): String? {
        val parts = ArrayList<String>()
        if (poToken.isNotEmpty()) parts += "po_token=" + (if ('+' in poToken) poToken else "web.gvs+$poToken")
        if (visitorData.isNotEmpty()) parts += "visitor_data=$visitorData"
        return if (parts.isEmpty()) null else "youtube:" + parts.joinToString(";")
    }
}
