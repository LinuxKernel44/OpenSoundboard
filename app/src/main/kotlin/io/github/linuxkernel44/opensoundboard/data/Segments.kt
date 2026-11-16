package io.github.linuxkernel44.opensoundboard.data

/** A kept part of a sound, as fractions (0..1) of the original file. */
data class Seg(val start: Float, val end: Float) { val length: Float get() = end - start }

val WHOLE_SOUND: List<Seg> = listOf(Seg(0f, 1f))

fun List<Seg>.isWhole(): Boolean = size == 1 && this[0].start <= 0f && this[0].end >= 1f

/** Total kept length as a fraction of the original. */
fun List<Seg>.keptLength(): Float = sumOf { it.length.toDouble() }.toFloat()

/** The flat form sent to the engine: [start0, end0, start1, end1, ...]. */
fun List<Seg>.toFlat(): FloatArray = FloatArray(size * 2) { if (it % 2 == 0) this[it / 2].start else this[it / 2].end }

/**
 * Keeps only the part between [x0] and [x1], which are positions on the current (already edited) timeline, 0..1.
 * Positions are measured by kept length, which ignores the 5 ms crossfade at each join (a negligible difference).
 */
fun List<Seg>.keep(x0: Float, x1: Float): List<Seg> = slice(x0, x1, keepInside = true)

/** Removes the part between [x0] and [x1] on the current timeline, joining what is left on both sides. */
fun List<Seg>.remove(x0: Float, x1: Float): List<Seg> = slice(x0, x1, keepInside = false)

private fun List<Seg>.slice(x0: Float, x1: Float, keepInside: Boolean): List<Seg> {
    val total = keptLength()
    val lo = x0.coerceIn(0f, 1f) * total
    val hi = x1.coerceIn(0f, 1f) * total
    val out = ArrayList<Seg>()
    var offset = 0f
    for (seg in this) {
        val segLo = offset
        val segHi = offset + seg.length
        fun add(from: Float, to: Float) { if (to - from > 1e-6f) out += Seg(seg.start + (from - segLo), seg.start + (to - segLo)) }
        if (keepInside) add(maxOf(segLo, lo), minOf(segHi, hi))
        else { add(segLo, minOf(segHi, lo)); add(maxOf(segLo, hi), segHi) }
        offset = segHi
    }
    // Neighbours that touch in the original are one piece again (no join needed).
    val merged = ArrayList<Seg>()
    for (s in out) {
        val last = merged.lastOrNull()
        if (last != null && kotlin.math.abs(last.end - s.start) < 1e-5f) merged[merged.size - 1] = Seg(last.start, s.end) else merged += s
    }
    return merged
}
