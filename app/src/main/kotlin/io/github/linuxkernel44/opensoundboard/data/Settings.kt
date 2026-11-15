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
    var themeMode by mutableIntStateOf(0)            // 0 system, 1 light, 2 dark
    var glassOpacity by mutableFloatStateOf(0.4f)     // surface tint, 0 = clear glass
    var glassBlur by mutableFloatStateOf(10f)         // dp
    var glassRefraction by mutableFloatStateOf(0.35f) // 0 = flat, 1 = strong lens
    var glassChromatic by mutableStateOf(false)       // dispersion on refracted edges
    var glassSaturation by mutableFloatStateOf(1.5f)
    var glassHighlight by mutableFloatStateOf(1f)     // edge light
    var gapless by mutableStateOf(true)              // trim encoder delay / padding so loops repeat seamlessly
    var singleSound by mutableStateOf(true)          // starting a sound cuts the one that is playing
    var background by mutableIntStateOf(0)           // index into Backgrounds
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
        singleSound = prefs.getBoolean("single", true)
        themeMode = prefs.getInt("theme", 0).coerceIn(0, 2)
        gapless = prefs.getBoolean("gapless", true)
        glassOpacity = prefs.getFloat("gOpacity", 0.4f)
        glassBlur = prefs.getFloat("gBlur", 10f)
        glassRefraction = prefs.getFloat("gRefr", 0.35f)
        glassChromatic = prefs.getBoolean("gChroma", false)
        glassSaturation = prefs.getFloat("gSat", 1.5f)
        glassHighlight = prefs.getFloat("gHigh", 1f)
        background = prefs.getInt("bg", 0).coerceIn(0, 4)
    }

    fun resetGlass() {
        glassOpacity = 0.4f; glassBlur = 10f; glassRefraction = 0.35f; glassChromatic = false; glassSaturation = 1.5f; glassHighlight = 1f
        save()
    }

    fun save() {
        prefs.edit().putInt("outDev", outputDeviceId).putInt("outRate", outputSampleRate).putBoolean("exclusive", exclusive)
            .putFloat("master", masterVolume).putInt("recDev", recordDeviceId).putInt("recRate", recordSampleRate)
            .putBoolean("recFloat", recordFloat).putBoolean("single", singleSound).putInt("theme", themeMode).putBoolean("gapless", gapless).putFloat("gOpacity", glassOpacity).putFloat("gBlur", glassBlur).putFloat("gRefr", glassRefraction).putBoolean("gChroma", glassChromatic).putFloat("gSat", glassSaturation).putFloat("gHigh", glassHighlight).putInt("bg", background).apply()
    }
}
