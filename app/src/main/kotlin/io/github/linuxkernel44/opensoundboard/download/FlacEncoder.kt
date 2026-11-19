package io.github.linuxkernel44.opensoundboard.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Lossless FLAC writer for the 24-bit WAV files the engine exports. Fixed predictors (orders 0-4) with partitioned
 * Rice coding, a verbatim fallback and the MD5 of the audio in the header, so any player can verify the file.
 */
object FlacEncoder {
    private const val BLOCK = 4096

    private class Bits {
        val out = ByteArrayOutputStream(BLOCK * 4)
        private var acc = 0L
        private var n = 0
        fun put(value: Long, bits: Int) {
            if (bits == 0) return
            acc = (acc shl bits) or (value and ((1L shl bits) - 1)); n += bits
            while (n >= 8) { out.write(((acc shr (n - 8)) and 0xFF).toInt()); n -= 8 }
            acc = if (n == 0) 0 else acc and ((1L shl n) - 1)
        }
        fun unary(q: Long) { var left = q; while (left >= 32) { put(0, 32); left -= 32 }; put(1, (left + 1).toInt()) }
        fun align() { if (n > 0) put(0, 8 - n) }
        fun bytes(): ByteArray { align(); return out.toByteArray() }
    }

    private val crc8 = IntArray(256) { i -> var c = i; repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }; c and 0xFF }
    private val crc16 = IntArray(256) { i -> var c = i shl 8; repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }; c and 0xFFFF }
    private fun crc8(b: ByteArray, len: Int = b.size): Int { var c = 0; for (i in 0 until len) c = crc8[c xor (b[i].toInt() and 0xFF)]; return c }
    private fun crc16(b: ByteArray): Int { var c = 0; for (x in b) c = ((c shl 8) and 0xFFFF) xor crc16[((c shr 8) xor (x.toInt() and 0xFF)) and 0xFF]; return c }

    private fun zigzag(v: Int): Long = ((v.toLong() shl 1) xor (v.toLong() shr 63))

    /** Rice-codes [res] (residuals of one subframe, [order] warm-up samples already removed). Returns the bit cost and writes when [w] is set. */
    private fun rice(res: IntArray, n: Int, order: Int, w: Bits?, forcedPo: Int = -1): Pair<Long, Int> {
        var bestBits = Long.MAX_VALUE
        var bestPo = 0
        val count = n - order
        for (po in 0..6) {
            if (forcedPo >= 0 && po != forcedPo) continue
            if (n % (1 shl po) != 0 || (n shr po) <= order) continue
            var bits = 4L + 4 + 5L * (1 shl po)   // method + order + parameters
            var start = order   // residuals begin after the warm-up samples
            for (p in 0 until (1 shl po)) {
                val len = (n shr po) - (if (p == 0) order else 0)
                var sum = 0L
                for (i in start until start + len) sum += zigzag(res[i])
                bits += riceBits(res, start, len, sum).first
                start += len
            }
            if (bits < bestBits) { bestBits = bits; bestPo = po }
        }
        if (w != null) {
            w.put(1, 2); w.put(bestPo.toLong(), 4)   // coding method 1: 5-bit parameters
            var start = order   // residuals begin after the warm-up samples
            for (p in 0 until (1 shl bestPo)) {
                val len = (n shr bestPo) - (if (p == 0) order else 0)
                var sum = 0L
                for (i in start until start + len) sum += zigzag(res[i])
                val k = riceBits(res, start, len, sum).second
                w.put(k.toLong(), 5)
                for (i in start until start + len) { val u = zigzag(res[i]); w.unary(u shr k); w.put(u, k) }
                start += len
            }
        }
        return bestBits to bestPo.also { if (count < 0) error("n") }
    }

    private fun riceBits(res: IntArray, start: Int, len: Int, sum: Long): Pair<Long, Int> {
        if (len <= 0) return 0L to 0
        var k = 0
        val mean = sum / len
        while (k < 30 && (1L shl (k + 1)) <= mean) k++
        var best = Long.MAX_VALUE; var bestK = k
        for (c in maxOf(0, k - 1)..minOf(30, k + 1)) {
            var bits = len.toLong() * (c + 1)
            for (i in start until start + len) bits += zigzag(res[i]) shr c
            if (bits < best) { best = bits; bestK = c }
        }
        return best to bestK
    }

    private fun residual(x: IntArray, n: Int, order: Int): IntArray {
        val r = IntArray(n)
        for (i in order until n) r[i] = when (order) {
            0 -> x[i]
            1 -> x[i] - x[i - 1]
            2 -> x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
        return r
    }

    private fun subframe(w: Bits, x: IntArray, n: Int, bps: Int) {
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) { w.put(0, 8); w.put(x[0].toLong(), bps); return }
        var bestOrder = -1
        var bestBits = 8L + n.toLong() * bps          // verbatim
        var bestRes: IntArray? = null
        for (order in 0..4) {
            if (n <= order + 1) break
            val res = residual(x, n, order)
            val bits = 8L + order.toLong() * bps + rice(res, n, order, null).first
            if (bits < bestBits) { bestBits = bits; bestOrder = order; bestRes = res }
        }
        if (bestOrder < 0) { w.put(2, 8); for (i in 0 until n) w.put(x[i].toLong(), bps); return }
        w.put((0x10 or (bestOrder shl 1)).toLong(), 8)       // 001xxx: fixed predictor, no wasted bits
        for (i in 0 until bestOrder) w.put(x[i].toLong(), bps)
        rice(bestRes!!, n, bestOrder, w)
    }

    private fun utf8Number(v: Long): ByteArray {
        if (v < 0x80) return byteArrayOf(v.toByte())
        var bytes = 2
        while (v >= (1L shl (5 * bytes + 1))) bytes++
        val out = ByteArray(bytes)
        var rest = v
        for (i in bytes - 1 downTo 1) { out[i] = (0x80 or (rest and 0x3F).toInt()).toByte(); rest = rest shr 6 }
        out[0] = ((0xFF shl (8 - bytes)) and 0xFF or rest.toInt()).toByte()
        return out
    }

    /** Encodes a 16- or 24-bit PCM WAV into [dst]. Returns false when the WAV cannot be read. */
    fun encodeWav(src: File, dst: File): Boolean = runCatching {
        RandomAccessFile(src, "r").use { f ->
            val head = ByteArray(minOf(f.length(), 4096L).toInt()); f.readFully(head)
            fun le(o: Int, n: Int): Long { var v = 0L; for (i in n - 1 downTo 0) v = (v shl 8) or (head[o + i].toLong() and 0xFF); return v }
            var p = 12; var ch = 0; var rate = 0; var bps = 0; var dataAt = -1L; var dataLen = 0L
            while (p + 8 <= head.size) {
                val id = String(head, p, 4); val len = le(p + 4, 4)
                if (id == "fmt ") { ch = le(p + 10, 2).toInt(); rate = le(p + 12, 4).toInt(); bps = le(p + 22, 2).toInt() }
                if (id == "data") { dataAt = (p + 8).toLong(); dataLen = len; break }
                p += 8 + len.toInt() + (len.toInt() and 1)
            }
            if (dataAt < 0 || ch !in 1..8 || (bps != 16 && bps != 24)) return false
            val bytes = bps / 8
            val total = minOf(dataLen, f.length() - dataAt) / (ch * bytes)
            f.seek(dataAt)
            dst.outputStream().buffered().use { out ->
                out.write("fLaC".toByteArray()); out.write(0x80); out.write(byteArrayOf(0, 0, 34)); out.write(ByteArray(34))
                val md5 = MessageDigest.getInstance("MD5")
                val raw = ByteArray(BLOCK * ch * bytes)
                val chans = Array(ch) { IntArray(BLOCK) }
                var frameNo = 0L; var done = 0L; var minF = Int.MAX_VALUE; var maxF = 0
                while (done < total) {
                    val n = minOf(BLOCK.toLong(), total - done).toInt()
                    f.readFully(raw, 0, n * ch * bytes)
                    md5.update(raw, 0, n * ch * bytes)
                    for (i in 0 until n) for (c in 0 until ch) {
                        val o = (i * ch + c) * bytes
                        chans[c][i] = if (bytes == 3) ((raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() shl 16))
                        else ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8))
                    }
                    val h = ByteArrayOutputStream()
                    h.write(0xFF); h.write(0xF8)
                    val sizeCode = if (n == BLOCK) 12 else 7
                    h.write((sizeCode shl 4) or 0)                       // block size code, sample rate from STREAMINFO
                    h.write(((ch - 1) shl 4) or ((if (bps == 24) 6 else 4) shl 1))
                    h.write(utf8Number(frameNo))
                    if (sizeCode == 7) { h.write((n - 1) shr 8); h.write((n - 1) and 0xFF) }
                    val hb = h.toByteArray()
                    val w = Bits()
                    for (c in 0 until ch) subframe(w, chans[c], n, bps)
                    val body = w.bytes()
                    val frame = ByteArrayOutputStream(hb.size + body.size + 3)
                    frame.write(hb); frame.write(crc8(hb)); frame.write(body)
                    val fb = frame.toByteArray()
                    val c16 = crc16(fb)
                    out.write(fb); out.write(c16 shr 8); out.write(c16 and 0xFF)
                    val size = fb.size + 2
                    if (size < minF) minF = size; if (size > maxF) maxF = size
                    done += n; frameNo++
                }
                out.flush()
                // Fill STREAMINFO now that sizes, length and MD5 are known.
                val info = Bits()
                val fixed = if (total < BLOCK) total else BLOCK.toLong()
                info.put(fixed, 16); info.put(fixed, 16)
                info.put(if (minF == Int.MAX_VALUE) 0 else minF.toLong(), 24); info.put(maxF.toLong(), 24)
                info.put(rate.toLong(), 20); info.put((ch - 1).toLong(), 3); info.put((bps - 1).toLong(), 5); info.put(total, 36)
                val si = info.bytes() + md5.digest()
                RandomAccessFile(dst, "rw").use { r -> r.seek(8); r.write(si) }
            }
        }
        true
    }.getOrElse { false }
}
