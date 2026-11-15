package io.github.linuxkernel44.opensoundboard.data

import org.json.JSONArray
import org.json.JSONObject

enum class PlayMode { ONE_SHOT, LOOP, HOLD }

data class SourceInfo(
    val codec: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
)

data class SoundItem(
    val id: Int,
    val file: String,
    val name: String,
    val categoryId: String? = null,
    val favorite: Boolean = false,
    val mode: PlayMode = PlayMode.ONE_SHOT,
    val volume: Float = 1f,
    val trimStart: Float = 0f,
    val trimEnd: Float = 1f,
    val info: SourceInfo = SourceInfo(),
)

data class Category(val id: String, val name: String)

data class LibraryData(
    val nextId: Int = 1,
    val sounds: List<SoundItem> = emptyList(),
    val categories: List<Category> = emptyList(),
)

internal fun LibraryData.toJson(): JSONObject = JSONObject().apply {
    put("version", 1)
    put("nextId", nextId)
    put("categories", JSONArray().also { a -> categories.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } })
    put("sounds", JSONArray().also { a ->
        sounds.forEach { s ->
            a.put(JSONObject().apply {
                put("id", s.id); put("file", s.file); put("name", s.name)
                put("category", s.categoryId ?: JSONObject.NULL)
                put("favorite", s.favorite); put("mode", s.mode.name)
                put("volume", s.volume.toDouble()); put("trimStart", s.trimStart.toDouble()); put("trimEnd", s.trimEnd.toDouble())
                put("info", JSONObject().apply {
                    put("codec", s.info.codec); put("sampleRate", s.info.sampleRate); put("channels", s.info.channels)
                    put("bitDepth", s.info.bitDepth); put("bitrate", s.info.bitrateKbps)
                    put("durationMs", s.info.durationMs); put("size", s.info.sizeBytes)
                })
            })
        }
    })
}

internal fun libraryFromJson(o: JSONObject): LibraryData {
    val cats = o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { c -> Category(c.getString("id"), c.getString("name")) } } } ?: emptyList()
    val sounds = o.optJSONArray("sounds")?.let { a ->
        (0 until a.length()).map {
            val s = a.getJSONObject(it)
            val i = s.optJSONObject("info") ?: JSONObject()
            SoundItem(
                id = s.getInt("id"), file = s.getString("file"), name = s.getString("name"),
                categoryId = if (s.isNull("category")) null else s.getString("category"),
                favorite = s.optBoolean("favorite"),
                mode = runCatching { PlayMode.valueOf(s.optString("mode", "ONE_SHOT")) }.getOrDefault(PlayMode.ONE_SHOT),
                volume = s.optDouble("volume", 1.0).toFloat(),
                trimStart = s.optDouble("trimStart", 0.0).toFloat(), trimEnd = s.optDouble("trimEnd", 1.0).toFloat(),
                info = SourceInfo(i.optString("codec"), i.optInt("sampleRate"), i.optInt("channels"), i.optInt("bitDepth"),
                    i.optInt("bitrate"), i.optLong("durationMs"), i.optLong("size")),
            )
        }
    } ?: emptyList()
    return LibraryData(o.optInt("nextId", (sounds.maxOfOrNull { it.id } ?: 0) + 1), sounds, cats)
}
