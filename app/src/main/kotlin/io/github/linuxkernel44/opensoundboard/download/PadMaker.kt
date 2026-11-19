package io.github.linuxkernel44.opensoundboard.download

import android.content.Context
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import io.github.linuxkernel44.opensoundboard.audio.SoundLoader
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.SoundItem
import io.github.linuxkernel44.opensoundboard.data.SourceInfo
import io.github.linuxkernel44.opensoundboard.data.WaveformCache
import java.io.File

/** One part of the downloaded audio, as fractions (0..1) of it. A part that is switched off does not become a pad. */
data class Zone(val start: Float, val end: Float, val on: Boolean = true, val name: String = "")

/** The downloaded audio while it is cut into pads: decoded into the engine under a reserved id, outside the library. */
class Staged(val file: File, val info: SourceInfo, val durationMs: Long, val peaks: FloatArray)

object PadMaker {
    /** Engine slot reserved for the audio being cut (the engine has 8192 slots, the library never gets near the end). */
    const val TEMP_ID = 8191

    fun stage(file: File): Staged? {
        val info = SoundLoader.load(TEMP_ID, file) ?: return null
        val ms = NativeEngine.durationMs(TEMP_ID).takeIf { it > 0 } ?: info.durationMs
        // About 100 buckets per second keeps the cuts precise when zoomed in, up to a sane cap for very long audio.
        val buckets = (ms / 10).toInt().coerceIn(2048, 240_000)
        val peaks = NativeEngine.peaks(TEMP_ID, buckets) ?: return null
        return Staged(file, info, ms, peaks)
    }

    fun discard(ctx: Context, staged: Staged?) {
        NativeEngine.stopSound(TEMP_ID)
        NativeEngine.unload(TEMP_ID)
        WaveformCache.remove(TEMP_ID)
        staged?.file?.parentFile?.deleteRecursively()
        LinkDownloader.clearCache(ctx)
    }

    /** Imports the whole download as one sound, keeping the source stream untouched. */
    fun importWhole(file: File, name: String, categoryId: String?): SoundItem? {
        val copy = File(file.parentFile, "whole." + file.extension).also { file.copyTo(it, overwrite = true) }
        return Library.importFile(copy, name, categoryId)
    }

    /** Exactly the part [z] of the staged audio as a lossless FLAC pad. Call off the main thread. */
    fun importZone(staged: Staged, z: Zone, name: String, categoryId: String?): SoundItem? {
        val dir = staged.file.parentFile ?: return null
        val wav = File(dir, "cut-${System.nanoTime()}.wav")
        val flac = File(dir, "cut-${System.nanoTime()}.flac")
        try {
            if (!NativeEngine.exportWav(TEMP_ID, z.start, z.end, 1f, wav.absolutePath)) return null
            if (!FlacEncoder.encodeWav(wav, flac)) return null
            return Library.importFile(flac, name, categoryId)
        } finally { wav.delete(); flac.delete() }
    }

    // ---- editing the cuts ----
    const val MIN_ZONE = 0.0002f

    fun addCut(zones: List<Zone>, x: Float): List<Zone> {
        val i = zones.indexOfFirst { x > it.start + MIN_ZONE && x < it.end - MIN_ZONE }
        if (i < 0) return zones
        val z = zones[i]
        return zones.take(i) + z.copy(end = x) + z.copy(start = x, name = "") + zones.drop(i + 1)
    }

    /** Moves the boundary between zone [i] and [i]+1. */
    fun moveCut(zones: List<Zone>, i: Int, x: Float): List<Zone> {
        if (i !in 0 until zones.size - 1) return zones
        val v = x.coerceIn(zones[i].start + MIN_ZONE, zones[i + 1].end - MIN_ZONE)
        return zones.mapIndexed { k, z -> when (k) { i -> z.copy(end = v); i + 1 -> z.copy(start = v); else -> z } }
    }

    /** Removes zone [i] by giving its time to the previous zone (or the next one for the first zone). */
    fun mergeZone(zones: List<Zone>, i: Int): List<Zone> {
        if (zones.size < 2) return zones
        val out = zones.toMutableList()
        val z = out.removeAt(i)
        if (i > 0) out[i - 1] = out[i - 1].copy(end = z.end) else out[0] = out[0].copy(start = z.start)
        return out
    }

    fun fromChapters(chapters: List<Chapter>, durationSec: Float): List<Zone> {
        val total = durationSec.coerceAtLeast(0.001f)
        val starts = chapters.map { (it.startSec / total).coerceIn(0f, 1f) }
        return chapters.mapIndexed { i, c -> Zone(starts[i], if (i + 1 < chapters.size) starts[i + 1] else 1f, true, c.title.trim()) }
            .filter { it.end - it.start > MIN_ZONE }.let { if (it.isNotEmpty()) it.let { z -> z.mapIndexed { k, v -> if (k == 0) v.copy(start = 0f) else v } } else listOf(Zone(0f, 1f)) }
    }

    /**
     * Cuts at silences: sound between silent runs of at least [minSilenceSec] below [thresholdDb] becomes a zone, and the
     * silent runs become switched-off zones the user can see and re-enable.
     */
    fun detectSilences(peaks: FloatArray, durationMs: Long, thresholdDb: Float = -42f, minSilenceSec: Float = 0.5f, minSoundSec: Float = 0.25f): List<Zone> {
        val n = peaks.size
        val perBucket = durationMs / 1000f / n
        val limit = Math.pow(10.0, thresholdDb / 20.0).toFloat()
        val minSil = (minSilenceSec / perBucket).toInt().coerceAtLeast(1)
        val minSnd = (minSoundSec / perBucket).toInt().coerceAtLeast(1)
        val runs = ArrayList<IntArray>()           // [start, end) of silent runs
        var i = 0
        while (i < n) {
            if (peaks[i] < limit) { var j = i; while (j < n && peaks[j] < limit) j++; if (j - i >= minSil) runs += intArrayOf(i, j); i = j } else i++
        }
        val zones = ArrayList<Zone>()
        var cursor = 0
        fun sound(a: Int, b: Int) { if (b - a >= minSnd) zones += Zone(a / n.toFloat(), b / n.toFloat()) else if (b > a && zones.isNotEmpty()) zones[zones.size - 1] = zones.last().copy(end = b / n.toFloat()) }
        for (r in runs) {
            sound(cursor, r[0])
            zones += Zone(r[0] / n.toFloat(), r[1] / n.toFloat(), on = false)
            cursor = r[1]
        }
        sound(cursor, n)
        if (zones.isEmpty()) return listOf(Zone(0f, 1f))
        // Zones must tile 0..1: stretch the neighbours over any gap left by a tiny sound that was folded away.
        return zones.mapIndexed { k, z ->
            z.copy(start = if (k == 0) 0f else zones[k - 1].end, end = if (k == zones.size - 1) 1f else z.end)
        }
    }
}
