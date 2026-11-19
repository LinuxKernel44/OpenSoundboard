package io.github.linuxkernel44.opensoundboard.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Settings {
    // Factory values, also used by the double-tap reset of the sliders.
    const val DEFAULT_GLASS_OPACITY = 0.0f
    const val DEFAULT_GLASS_BLUR = 2f
    const val DEFAULT_GLASS_REFRACTION = 0.73f
    const val DEFAULT_GLASS_SATURATION = 1.79f
    const val DEFAULT_GLASS_HIGHLIGHT = 1f

    private lateinit var prefs: android.content.SharedPreferences

    var outputDeviceId by mutableIntStateOf(0)       // 0 = system default
    var outputSampleRate by mutableIntStateOf(0)     // 0 = device native
    var exclusive by mutableStateOf(true)
    var masterVolume by mutableFloatStateOf(1f)
    var recordDeviceId by mutableIntStateOf(0)
    var recordSampleRate by mutableIntStateOf(48000)
    var recordFloat by mutableStateOf(false)         // false = 24-bit PCM, true = 32-bit float
    var themeMode by mutableIntStateOf(0)            // 0 system, 1 light, 2 dark
    var glassOpacity by mutableFloatStateOf(DEFAULT_GLASS_OPACITY)     // surface tint, 0 = clear glass
    var glassBlur by mutableFloatStateOf(DEFAULT_GLASS_BLUR)         // dp
    var glassRefraction by mutableFloatStateOf(DEFAULT_GLASS_REFRACTION) // 0 = flat, 1 = strong lens
    var glassChromatic by mutableStateOf(false)       // dispersion on refracted edges
    var glassSaturation by mutableFloatStateOf(DEFAULT_GLASS_SATURATION)
    var glassHighlight by mutableFloatStateOf(DEFAULT_GLASS_HIGHLIGHT)     // edge light
    var gapless by mutableStateOf(true)              // trim encoder delay / padding so loops repeat seamlessly
    var autoUpdateCheck by mutableStateOf(true)      // look for a new release at launch (at most every 12 h)
    var lastUpdateCheck = 0L
    var skippedVersion = ""
    var singleSound by mutableStateOf(true)          // starting a sound cuts the one that is playing
    var background by mutableIntStateOf(0)           // index into Backgrounds
    var autoPoToken by mutableStateOf(true)          // generate a YouTube PO token on the device when a link needs it
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
        autoUpdateCheck = prefs.getBoolean("autoUpd", true)
        lastUpdateCheck = prefs.getLong("lastUpd", 0L)
        skippedVersion = prefs.getString("skipVer", "") ?: ""
        glassOpacity = prefs.getFloat("gOpacity", DEFAULT_GLASS_OPACITY)
        glassBlur = prefs.getFloat("gBlur", DEFAULT_GLASS_BLUR)
        glassRefraction = prefs.getFloat("gRefr", DEFAULT_GLASS_REFRACTION)
        glassChromatic = prefs.getBoolean("gChroma", false)
        glassSaturation = prefs.getFloat("gSat", DEFAULT_GLASS_SATURATION)
        glassHighlight = prefs.getFloat("gHigh", DEFAULT_GLASS_HIGHLIGHT)
        background = prefs.getInt("bg", 0).coerceIn(0, 4)
        autoPoToken = prefs.getBoolean("autoPot", true)
    }

    fun resetGlass() {
        glassOpacity = DEFAULT_GLASS_OPACITY; glassBlur = DEFAULT_GLASS_BLUR; glassRefraction = DEFAULT_GLASS_REFRACTION; glassChromatic = false; glassSaturation = DEFAULT_GLASS_SATURATION; glassHighlight = DEFAULT_GLASS_HIGHLIGHT
        save()
    }

    fun save() {
        prefs.edit().putInt("outDev", outputDeviceId).putInt("outRate", outputSampleRate).putBoolean("exclusive", exclusive)
            .putFloat("master", masterVolume).putInt("recDev", recordDeviceId).putInt("recRate", recordSampleRate)
            .putBoolean("recFloat", recordFloat).putBoolean("single", singleSound).putInt("theme", themeMode).putBoolean("gapless", gapless).putBoolean("autoUpd", autoUpdateCheck).putLong("lastUpd", lastUpdateCheck).putString("skipVer", skippedVersion).putFloat("gOpacity", glassOpacity).putFloat("gBlur", glassBlur).putFloat("gRefr", glassRefraction).putBoolean("gChroma", glassChromatic).putFloat("gSat", glassSaturation).putFloat("gHigh", glassHighlight).putInt("bg", background).putBoolean("autoPot", autoPoToken).apply()
    }
}
