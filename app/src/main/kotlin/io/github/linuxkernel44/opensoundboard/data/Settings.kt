package io.github.linuxkernel44.opensoundboard.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Settings {
    private lateinit var prefs: android.content.SharedPreferences

    var outputDeviceId by mutableIntStateOf(0)       // 0 = system default
    var outputSampleRate by mutableIntStateOf(0)     // 0 = device native
    var exclusive by mutableStateOf(true)
    var masterVolume by mutableFloatStateOf(1f)
    var recordDeviceId by mutableIntStateOf(0)
    var recordSampleRate by mutableIntStateOf(48000)
    var recordFloat by mutableStateOf(false)         // false = 24-bit PCM, true = 32-bit float
    var spectrumBands = 32

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        outputDeviceId = prefs.getInt("outDev", 0)
        outputSampleRate = prefs.getInt("outRate", 0)
        exclusive = prefs.getBoolean("exclusive", true)
        masterVolume = prefs.getFloat("master", 1f)
        recordDeviceId = prefs.getInt("recDev", 0)
        recordSampleRate = prefs.getInt("recRate", 48000)
        recordFloat = prefs.getBoolean("recFloat", false)
    }

    fun save() {
        prefs.edit().putInt("outDev", outputDeviceId).putInt("outRate", outputSampleRate).putBoolean("exclusive", exclusive)
            .putFloat("master", masterVolume).putInt("recDev", recordDeviceId).putInt("recRate", recordSampleRate)
            .putBoolean("recFloat", recordFloat).apply()
    }
}
