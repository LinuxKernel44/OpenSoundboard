package io.github.linuxkernel44.opensoundboard.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** High resolution recorder: unprocessed mic source, float capture, WAV output (24-bit PCM or 32-bit float). */
object Recorder {
    var recording by mutableStateOf(false); private set
    var peak by mutableFloatStateOf(0f); private set
    var elapsedMs by mutableLongStateOf(0); private set
    var actualRate by mutableStateOf(0); private set
    var error by mutableStateOf<String?>(null)

    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    var lastFile: File? = null; private set

    fun inputDevices(ctx: Context): List<AudioDeviceInfo> =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    /** Sample rates that can actually be opened with float capture on this device. */
    fun supportedRates(): List<Int> = listOf(44100, 48000, 96000, 192000).filter {
        AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT) > 0
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, rate: Int, deviceId: Int, float32: Boolean) {
        if (recording) return
        error = null
        val out = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.wav")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = if (deviceId > 0) am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId } else null
        val channels = if (device != null && device.channelCounts.isNotEmpty() && device.channelCounts.none { it >= 2 }) 1 else 2
        val chMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) { error = "Unsupported sample rate"; return }
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(chMask).build())
                .setBufferSizeInBytes(minBuf * 4).build()
        } catch (e: Exception) { error = e.message; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error = "Could not open the input"; return }
        if (device != null) rec.preferredDevice = device
        actualRate = rec.sampleRate
        stopFlag = false; recording = true; elapsedMs = 0; peak = 0f
        thread = Thread({
            val raf = RandomAccessFile(out, "rw")
            writeHeader(raf, actualRate, channels, float32, 0)
            val fbuf = FloatArray(minBuf)
            val bytes = ByteBuffer.allocate(fbuf.size * (if (float32) 4 else 3)).order(ByteOrder.LITTLE_ENDIAN)
            var frames = 0L
            rec.startRecording()
            while (!stopFlag) {
                val n = rec.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                bytes.clear()
                var p = 0f
                for (i in 0 until n) {
                    val v = fbuf[i].coerceIn(-1f, 1f)
                    p = maxOf(p, abs(v))
                    if (float32) bytes.putFloat(v) else {
                        val s = (v * 8388607f).toInt()
                        bytes.put(s.toByte()).put((s shr 8).toByte()).put((s shr 16).toByte())
                    }
                }
                raf.write(bytes.array(), 0, bytes.position())
                frames += n / channels
                peak = p; elapsedMs = frames * 1000 / actualRate
            }
            rec.stop(); rec.release()
            writeHeader(raf, actualRate, channels, float32, frames * channels * (if (float32) 4 else 3))
            raf.close()
            lastFile = out; recording = false
        }, "osb-recorder").also { it.start() }
    }

    fun consumeLast() { lastFile = null }

    fun stop() { stopFlag = true; thread?.join(2000) }

    private fun writeHeader(f: RandomAccessFile, rate: Int, ch: Int, float32: Boolean, dataBytes: Long) {
        val bps = if (float32) 32 else 24
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(if (float32) 3 else 1).putShort(ch.toShort())
            .putInt(rate).putInt(rate * ch * bps / 8).putShort((ch * bps / 8).toShort()).putShort(bps.toShort())
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        f.seek(0); f.write(b.array())
    }
}
