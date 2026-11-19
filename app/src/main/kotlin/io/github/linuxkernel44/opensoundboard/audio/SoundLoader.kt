package io.github.linuxkernel44.opensoundboard.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.linuxkernel44.opensoundboard.data.Settings
import io.github.linuxkernel44.opensoundboard.data.SourceInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes a stored sound into the native engine and reports technical info about the source file. */
object SoundLoader {
    fun load(id: Int, file: File): SourceInfo? {
        val size = file.length()
        val gapless = Settings.gapless
        NativeEngine.loadFile(id, file.absolutePath, gapless)?.let { r ->
            val (ch, rate, depth, frames) = r
            val ms = frames * 1000L / rate.coerceAtLeast(1)
            return SourceInfo(NativeEngine.codecOf(id), rate, ch, depth, kbps(size, ms), ms, size, r[4], r[5])
        }
        return decodeWithMediaCodec(id, file, size)
    }

    private fun kbps(size: Long, ms: Long) = if (ms > 0) (size * 8 / ms).toInt() else 0

    private fun decodeWithMediaCodec(id: Int, file: File, size: Long): SourceInfo? {
        val ex = MediaExtractor()
        var raw: File? = null
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // Decoded samples go to a temp file as float32, so a long track never has to fit in the Java heap.
            val tmp = File.createTempFile("pcm", ".raw", file.parentFile).also { raw = it }
            var totalFloats = 0L
            var floatOut = false
            val bi = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 18).use { out ->
                var chunk = ByteArray(1 shl 16)
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(bi, 5_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatOut = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                            val floats = if (floatOut) bi.size / 4 else bi.size / 2
                            if (chunk.size < floats * 4) chunk = ByteArray(floats * 4)
                            val cb = ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder())
                            if (floatOut) cb.asFloatBuffer().put(buf.order(ByteOrder.nativeOrder()).asFloatBuffer())
                            else { val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer(); val fb = cb.asFloatBuffer(); repeat(floats) { fb.put(sb.get() / 32768f) } }
                            out.write(chunk, 0, floats * 4)
                            totalFloats += floats
                            codec.releaseOutputBuffer(o, false)
                            if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                    }
                }
            }
            codec.stop(); codec.release()
            var frames = (totalFloats / ch).toInt()
            if (frames <= 0) return null
            // Gapless: drop the encoder delay (priming) and padding that AAC / M4A files declare, so the decoded
            // audio is exactly the original length and loops repeat without a gap.
            var delay = 0
            var padding = 0
            if (Settings.gapless) {
                if (fmt.containsKey("encoder-delay")) delay = fmt.getInteger("encoder-delay").coerceAtLeast(0)
                if (fmt.containsKey("encoder-padding")) padding = fmt.getInteger("encoder-padding").coerceAtLeast(0)
                if (delay + padding >= frames) { delay = 0; padding = 0 }
                frames -= delay + padding
            }
            val label = mime.removePrefix("audio/").uppercase().replace("MPEG", "MP3").replace("MP4A-LATM", "AAC")
            if (!NativeEngine.loadPcmFile(id, tmp.absolutePath, delay, frames, ch, rate, label)) return null
            val ms = frames * 1000L / rate
            val br = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) fmt.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 else kbps(size, ms)
            return SourceInfo(label, rate, ch, 0, br, ms, size, delay, padding)
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
            raw?.delete()
        }
    }
}
