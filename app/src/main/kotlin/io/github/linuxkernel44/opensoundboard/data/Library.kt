package io.github.linuxkernel44.opensoundboard.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import io.github.linuxkernel44.opensoundboard.audio.SoundLoader
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Persistent sound library: a JSON index plus the audio files, all inside the app's private storage. */
object Library {
    private lateinit var dir: File
    private lateinit var index: File
    var data by mutableStateOf(LibraryData())
        private set

    fun soundFile(s: SoundItem) = File(dir, s.file)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "sounds").apply { mkdirs() }
        index = File(ctx.filesDir, "library.json")
        data = runCatching { libraryFromJson(JSONObject(index.readText())) }.getOrDefault(LibraryData())
    }

    /** Decode every stored sound into the native engine (call off the main thread). */
    fun loadAllIntoEngine() {
        var changed = false
        val updated = data.sounds.map { s ->
            val info = SoundLoader.load(s.id, soundFile(s))
            if (info != null) WaveformCache.ensure(s)
            if (info != null && info != s.info) { changed = true; s.copy(info = info) } else s
        }
        if (changed) { data = data.copy(sounds = updated); save() }
    }

    private fun save() {
        val tmp = File(index.parentFile, "library.json.tmp")
        tmp.writeText(data.toJson().toString())
        tmp.renameTo(index)
    }

    private fun mutate(f: (LibraryData) -> LibraryData) { data = f(data); save() }

    fun update(id: Int, f: (SoundItem) -> SoundItem) = mutate { d -> d.copy(sounds = d.sounds.map { if (it.id == id) f(it) else it }) }

    fun delete(id: Int) {
        val s = data.sounds.firstOrNull { it.id == id } ?: return
        NativeEngine.unload(id)
        WaveformCache.remove(id)
        soundFile(s).delete()
        mutate { d -> d.copy(sounds = d.sounds.filterNot { it.id == id }) }
    }

    fun addCategory(name: String) = mutate { d -> d.copy(categories = d.categories + Category(UUID.randomUUID().toString(), name.trim())) }
    fun renameCategory(id: String, name: String) = mutate { d -> d.copy(categories = d.categories.map { if (it.id == id) it.copy(name = name.trim()) else it }) }
    fun deleteCategory(id: String) = mutate { d ->
        d.copy(categories = d.categories.filterNot { it.id == id }, sounds = d.sounds.map { if (it.categoryId == id) it.copy(categoryId = null) else it })
    }

    /** Copies [uri] into the library, decodes it and registers it. Returns the new item or null if undecodable. */
    fun importUri(ctx: Context, uri: Uri, categoryId: String? = null): SoundItem? {
        var display = "Sound"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) display = it.getString(0) ?: display }
        val ext = display.substringAfterLast('.', "").lowercase().ifEmpty { "bin" }
        val name = display.substringBeforeLast('.')
        val id = synchronized(this) { val n = data.nextId; data = data.copy(nextId = n + 1); n }
        val file = File(dir, "${id}_${UUID.randomUUID().toString().take(8)}.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { i -> file.outputStream().use { o -> i.copyTo(o) } } ?: return null
        return register(id, file, name, categoryId)
    }

    /** Registers an existing file inside the library directory (used by the recorder). */
    fun importFile(src: File, name: String, categoryId: String? = null): SoundItem? {
        val id = synchronized(this) { val n = data.nextId; data = data.copy(nextId = n + 1); n }
        val file = File(dir, "${id}_${UUID.randomUUID().toString().take(8)}.${src.extension}")
        src.copyTo(file, overwrite = true); src.delete()
        return register(id, file, name, categoryId)
    }

    private fun register(id: Int, file: File, name: String, categoryId: String?): SoundItem? {
        val info = SoundLoader.load(id, file)
        if (info == null) { file.delete(); return null }
        val item = SoundItem(id, file.name, name, categoryId, info = info)
        WaveformCache.ensure(item)
        mutate { d -> d.copy(sounds = d.sounds + item) }
        return item
    }

    // ---- backup (zip: library.json + sounds/*) ----
    fun exportTo(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os.buffered()).use { z ->
                z.putNextEntry(ZipEntry("library.json")); z.write(data.toJson().toString().toByteArray()); z.closeEntry()
                data.sounds.forEach { s ->
                    val f = soundFile(s)
                    if (f.exists()) { z.putNextEntry(ZipEntry("sounds/${s.file}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                }
            }
        }
    }

    /** Merges a backup into the current library. Returns the number of sounds added. */
    fun importFrom(ctx: Context, uri: Uri): Int {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var json: JSONObject? = null
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name
                    if (n == "library.json") json = JSONObject(z.readBytes().decodeToString())
                    else if (n.startsWith("sounds/") && !n.contains("..")) File(tmp, n.removePrefix("sounds/")).outputStream().use { z.copyTo(it) }
                    e = z.nextEntry
                }
            }
        }
        val incoming = json?.let { libraryFromJson(it) } ?: return 0
        val catMap = HashMap<String, String>()
        incoming.categories.forEach { c ->
            val existing = data.categories.firstOrNull { it.name.equals(c.name, true) }
            if (existing != null) catMap[c.id] = existing.id else { addCategory(c.name); catMap[c.id] = data.categories.last().id }
        }
        var added = 0
        incoming.sounds.forEach { s ->
            val src = File(tmp, s.file)
            if (!src.exists()) return@forEach
            val id = synchronized(this) { val n = data.nextId; data = data.copy(nextId = n + 1); n }
            val file = File(dir, "${id}_${UUID.randomUUID().toString().take(8)}.${src.extension}")
            src.copyTo(file, true)
            val info = SoundLoader.load(id, file)
            if (info == null) { file.delete(); return@forEach }
            val item = s.copy(id = id, file = file.name, categoryId = s.categoryId?.let { catMap[it] }, info = info)
            WaveformCache.ensure(item)
            mutate { d -> d.copy(sounds = d.sounds + item) }
            added++
        }
        tmp.deleteRecursively()
        return added
    }
}
