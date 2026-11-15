package io.github.linuxkernel44.opensoundboard.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.PlayMode
import io.github.linuxkernel44.opensoundboard.data.Settings
import io.github.linuxkernel44.opensoundboard.data.SoundItem
import kotlin.concurrent.thread

data class OutputInfo(
    val deviceName: String = "", val deviceType: String = "",
    val sampleRate: Int = 0, val burst: Int = 0, val bufferFrames: Int = 0,
    val exclusive: Boolean = false, val mmap: Boolean = false, val format: String = "",
    val latencyMs: Double = -1.0, val xruns: Int = 0, val running: Boolean = false,
)

object Player {
    private lateinit var appCtx: Context
    private lateinit var audio: AudioManager
    var output by mutableStateOf(OutputInfo()); private set
    var ready by mutableStateOf(false); private set
    val playing = mutableStateListOf<Int>()
    /** Playback position (0..1 of the whole file) of every sounding pad, refreshed by the UI poll loop. */
    val progress = androidx.compose.runtime.mutableStateMapOf<Int, Float>()
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        thread(name = "osb-init") {
            restartOutput()
            Library.loadAllIntoEngine()
            main.post { ready = true }
        }
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { if (Settings.outputDeviceId == 0) thread { restartOutput() } }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { if (Settings.outputDeviceId == 0) thread { restartOutput() } }
        }, main)
    }

    @Synchronized
    fun restartOutput() {
        val ok = NativeEngine.start(Settings.outputDeviceId, Settings.outputSampleRate, Settings.exclusive)
        NativeEngine.setMasterGain(Settings.masterVolume)
        refreshOutput(ok)
    }

    fun refreshOutput(running: Boolean = true) {
        val v = NativeEngine.streamInfo()
        val dev = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == v[5].toInt() }
        val fmt = when (v[4].toInt()) { 1 -> "16-bit PCM"; 2 -> "32-bit float"; 3 -> "24-bit PCM"; 4 -> "32-bit PCM"; 6 -> "24-bit PCM"; else -> "Float" }
        output = OutputInfo(
            deviceName = dev?.productName?.toString() ?: "", deviceType = dev?.let { typeName(it.type) } ?: "",
            sampleRate = v[0].toInt(), burst = v[1].toInt(), bufferFrames = v[2].toInt(),
            exclusive = v[3] > 0, mmap = v[6] > 0, format = fmt, latencyMs = v[8], xruns = v[9].toInt(),
            running = running && v[0] > 0,
        )
    }

    fun outputDevices(): List<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }

    fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line out"
        else -> "Audio device"
    }

    // ---- pad actions ----
    fun press(s: SoundItem) {
        val single = Settings.singleSound
        when (s.mode) {
            PlayMode.ONE_SHOT -> if (NativeEngine.isPlaying(s.id)) NativeEngine.stopSound(s.id)
                else { if (single) NativeEngine.stopAll(); NativeEngine.play(s.id, 0, s.volume, s.trimStart, s.trimEnd) }
            PlayMode.LOOP -> if (NativeEngine.isPlaying(s.id)) NativeEngine.stopSound(s.id)
                else { if (single) NativeEngine.stopAll(); NativeEngine.play(s.id, 1, s.volume, s.trimStart, s.trimEnd) }
            PlayMode.HOLD -> { if (single) NativeEngine.stopAll(); NativeEngine.play(s.id, 2, s.volume, s.trimStart, s.trimEnd) }
        }
    }
    /** Touch on a pad's waveform: starts the sound if needed, then jumps to [fraction] of the file. */
    fun scrubStart(s: SoundItem, fraction: Float, wasPlaying: Boolean) {
        if (!wasPlaying) {
            if (Settings.singleSound) NativeEngine.stopAll()
            NativeEngine.play(s.id, if (s.mode == PlayMode.LOOP) 1 else 0, s.volume, s.trimStart, s.trimEnd)
        }
        NativeEngine.seek(s.id, fraction)
    }
    fun release(s: SoundItem) { if (s.mode == PlayMode.HOLD) NativeEngine.stopSound(s.id) }
    fun stopAll() = NativeEngine.stopAll()
    fun preview(s: SoundItem, start: Float = s.trimStart, end: Float = s.trimEnd) { if (Settings.singleSound) NativeEngine.stopAll() else NativeEngine.stopSound(s.id); NativeEngine.play(s.id, 0, s.volume, start, end) }

    /** Syncs the list of currently sounding ids (call from a UI polling loop). */
    fun pollPlaying(ids: List<Int>) {
        val now = ids.filter { NativeEngine.isPlaying(it) }
        if (now.size != playing.size || !playing.containsAll(now)) { playing.clear(); playing.addAll(now) }
        progress.keys.toList().forEach { if (it !in now) progress.remove(it) }
        for (id in now) { val v = NativeEngine.progress(id); if (kotlin.math.abs((progress[id] ?: -1f) - v) > 0.002f) progress[id] = v }
    }
}
