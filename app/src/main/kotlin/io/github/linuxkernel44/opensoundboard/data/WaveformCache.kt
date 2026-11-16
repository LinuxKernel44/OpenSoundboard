package io.github.linuxkernel44.opensoundboard.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Waveform peaks are computed once per sound at a fixed resolution and kept in memory and on disk, so pads
 * draw instantly (even before the sounds finish decoding) and scrolling never recomputes anything.
 * An entry is invalidated when the file or the gapless setting changes (both change the decoded length).
 */
object WaveformCache {
    const val RES = 512
    private lateinit var dir: File
    private val peaks = mutableStateMapOf<Int, FloatArray>()
    private val keys = HashMap<Int, Int>()

    fun init(ctx: Context) { dir = File(ctx.cacheDir, "waveforms").apply { mkdirs() } }

    private fun keyOf(s: SoundItem) = (s.file + "|" + Settings.gapless + "|" + s.segments.hashCode()).hashCode()
    private fun fileOf(id: Int) = File(dir, "$id.wf")

    /** Reads cached waveforms from disk (call off the main thread, before decoding). */
    fun preload(sounds: List<SoundItem>) {
        for (s in sounds) runCatching {
            val f = fileOf(s.id)
            if (!f.exists()) return@runCatching
            DataInputStream(f.inputStream().buffered()).use { i ->
                val key = i.readInt()
                if (key != keyOf(s)) return@use
                val arr = FloatArray(RES) { i.readFloat() }
                synchronized(keys) { keys[s.id] = key }
                peaks[s.id] = arr
            }
        }
    }

    /** Computes and stores the waveform of a sound that is loaded in the engine, unless it is already cached. */
    fun ensure(s: SoundItem) {
        val key = keyOf(s)
        if (synchronized(keys) { keys[s.id] } == key && peaks.containsKey(s.id)) return
        val arr = NativeEngine.peaks(s.id, RES) ?: return
        synchronized(keys) { keys[s.id] = key }
        peaks[s.id] = arr
        runCatching {
            DataOutputStream(fileOf(s.id).outputStream().buffered()).use { o -> o.writeInt(key); arr.forEach { o.writeFloat(it) } }
        }
    }

    fun remove(id: Int) {
        synchronized(keys) { keys.remove(id) }
        peaks.remove(id)
        runCatching { fileOf(id).delete() }
    }

    /** Full-resolution peaks of a sound, or null while unknown. Reading it from Compose observes updates. */
    fun base(id: Int): FloatArray? = peaks[id]

    /** Reduces (max) or repeats the stored peaks to [n] bars covering the file fractions [from]..[to] (all of it by default). */
    fun resample(base: FloatArray, n: Int, from: Float = 0f, to: Float = 1f): FloatArray = FloatArray(n) { b ->
        val f0 = from + (to - from) * b / n
        val f1 = from + (to - from) * (b + 1) / n
        val i0 = (f0 * base.size).toInt().coerceIn(0, base.size - 1)
        val i1 = kotlin.math.ceil(f1 * base.size).toInt().coerceIn(i0 + 1, base.size)
        var m = 0f
        for (k in i0 until i1) if (base[k] > m) m = base[k]
        m
    }
}
