package io.github.linuxkernel44.opensoundboard.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import io.github.linuxkernel44.opensoundboard.ui.theme.Backdrops
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.linuxkernel44.opensoundboard.BuildConfig
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.audio.Player
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.Settings
import io.github.linuxkernel44.opensoundboard.ui.KeyValue
import io.github.linuxkernel44.opensoundboard.ui.LocalBackdrop
import io.github.linuxkernel44.opensoundboard.ui.LocalMeters
import io.github.linuxkernel44.opensoundboard.ui.LocalToast
import io.github.linuxkernel44.opensoundboard.ui.ScreenTitle
import io.github.linuxkernel44.opensoundboard.ui.Section
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassChip
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.glass.LiquidSlider
import io.github.linuxkernel44.opensoundboard.ui.glass.LiquidToggle
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import io.github.linuxkernel44.opensoundboard.ui.khz
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.log10

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(bottomInset: Dp) {
    val ctx = LocalContext.current
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val out = Player.output
    val m = LocalMeters.current
    val devices = Player.outputDevices()
    val saved = stringResource(R.string.backup_saved)
    val failed = stringResource(R.string.backup_failed)
    val restored = stringResource(R.string.backup_restored, 0)

    fun apply() { Settings.save(); thread { Player.restartOutput() } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { toast(withContext(Dispatchers.IO) { runCatching { Library.exportTo(ctx, uri); saved }.getOrDefault(failed) }) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Library.importFrom(ctx, uri) }.getOrDefault(-1) }
            toast(if (n < 0) failed else restored.replace("0", n.toString()))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomInset + 8.dp)) {
        ScreenTitle(stringResource(R.string.tab_settings))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Section(stringResource(R.string.live_output)) {
                GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (!out.running) AText(stringResource(R.string.out_stopped), color = p.danger)
                        else {
                            KeyValue(stringResource(R.string.out_device), listOf(out.deviceName, out.deviceType).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" })
                            KeyValue(stringResource(R.string.out_stream_rate), stringResource(R.string.khz, khz(out.sampleRate)))
                            KeyValue(stringResource(R.string.out_format), out.format)
                            KeyValue(stringResource(R.string.out_burst), "${out.burst} fr")
                            KeyValue(stringResource(R.string.out_buffer), "${out.bufferFrames} fr")
                            KeyValue(stringResource(R.string.out_path), listOfNotNull(
                                stringResource(if (out.exclusive) R.string.out_path_exclusive else R.string.out_path_shared),
                                if (out.mmap) stringResource(R.string.out_path_mmap) else null).joinToString(" · "))
                            KeyValue(stringResource(R.string.out_latency), if (out.latencyMs >= 0) stringResource(R.string.ms, String.format("%.1f", out.latencyMs)) else "—")
                            KeyValue(stringResource(R.string.out_xruns), out.xruns.toString())
                        }
                    }
                }
                GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AText(stringResource(R.string.levels), size = 13.sp, color = p.muted)
                        LevelBar(stringResource(R.string.left), m.peakL, m.rmsL)
                        LevelBar(stringResource(R.string.right), m.peakR, m.rmsR)
                        AText(stringResource(R.string.spectrum), size = 13.sp, color = p.muted)
                        Spectrum(m.spectrum, Modifier.fillMaxWidth().height(90.dp))
                    }
                }
            }
            Section(stringResource(R.string.playback)) {
                GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            AText(stringResource(R.string.single_sound), size = 16.sp)
                            AText(stringResource(R.string.single_sound_hint), size = 12.sp, color = p.muted)
                        }
                        LiquidToggle({ Settings.singleSound }, { Settings.singleSound = it; Settings.save() }, backdrop)
                    }
                }
            }
            Section(stringResource(R.string.background)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Backdrops.entries.forEachIndexed { i, b ->
                        val sel = Settings.background == i
                        Column(Modifier.weight(1f).clickable { Settings.background = i; Settings.save() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Image(
                                painterResource(b.thumb), stringResource(b.label),
                                Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(14.dp))
                                    .border(if (sel) 3.dp else 1.dp, if (sel) p.accent else Color.White.copy(0.35f), RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            AText(stringResource(b.label), size = 10.sp, color = if (sel) p.text else p.muted, maxLines = 2, align = TextAlign.Center)
                        }
                    }
                }
            }
            Section(stringResource(R.string.output_device)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(backdrop, stringResource(R.string.automatic), Settings.outputDeviceId == 0, { Settings.outputDeviceId = 0; apply() })
                    devices.forEach { d -> GlassChip(backdrop, "${d.productName} · ${Player.typeName(d.type)}".take(34), Settings.outputDeviceId == d.id, { Settings.outputDeviceId = d.id; apply() }) }
                }
            }
            Section(stringResource(R.string.output_rate)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(backdrop, stringResource(R.string.native_rate), Settings.outputSampleRate == 0, { Settings.outputSampleRate = 0; apply() })
                    listOf(44100, 48000, 96000, 192000).forEach { r -> GlassChip(backdrop, stringResource(R.string.khz, khz(r)), Settings.outputSampleRate == r, { Settings.outputSampleRate = r; apply() }) }
                }
            }
            GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AText(stringResource(R.string.exclusive_mode), size = 16.sp)
                        AText(stringResource(R.string.exclusive_hint), size = 12.sp, color = p.muted)
                    }
                    LiquidToggle({ Settings.exclusive }, { Settings.exclusive = it; apply() }, backdrop)
                }
            }
            Section(stringResource(R.string.master_volume)) {
                LiquidSlider({ Settings.masterVolume }, { Settings.masterVolume = it; NativeEngine.setMasterGain(it) }, 0f..1f, 0.01f, backdrop, Modifier.padding(horizontal = 4.dp))
            }
            Section(stringResource(R.string.backup)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassAction(backdrop, { exportLauncher.launch("OpenSoundboard-backup.zip") }, icon = Ico.Upload, label = stringResource(R.string.export_backup))
                    GlassAction(backdrop, { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, icon = Ico.Download, label = stringResource(R.string.import_backup))
                }
            }
            Section(stringResource(R.string.about)) {
                GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AText("OpenSoundboard ${BuildConfig.VERSION_NAME}", size = 16.sp)
                        AText(stringResource(R.string.about_body), size = 13.sp, color = p.muted)
                        AText(stringResource(R.string.licenses), size = 12.sp, color = p.muted)
                        AText(stringResource(R.string.photo_credits), size = 12.sp, color = p.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelBar(label: String, peak: Float, rms: Float) {
    val p = LocalAurora.current
    fun db(v: Float) = if (v <= 0.00003f) -90f else 20f * log10(v)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AText(label, Modifier.padding(end = 2.dp), size = 12.sp, mono = true, color = p.muted)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(p.muted.copy(0.22f), size = size, cornerRadius = r)
            val w = { v: Float -> size.width * ((db(v) + 60f) / 60f).coerceIn(0f, 1f) }
            drawRoundRect(p.accent.copy(0.55f), size = Size(w(peak), size.height), cornerRadius = r)
            drawRoundRect(p.accent, size = Size(w(rms), size.height), cornerRadius = r)
        }
        AText(if (db(peak) <= -90f) "-∞" else String.format("%.1f", db(peak)), Modifier.padding(start = 2.dp), size = 12.sp, mono = true)
    }
}

@Composable
private fun Spectrum(bands: FloatArray, modifier: Modifier) {
    val p = LocalAurora.current
    Canvas(modifier) {
        val n = bands.size
        val w = size.width / n
        for (i in 0 until n) {
            val h = (bands[i].coerceIn(0f, 1f) * size.height).coerceAtLeast(3f)
            drawRoundRect(p.accent.copy(0.35f + 0.65f * bands[i].coerceIn(0f, 1f)), Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(w * 0.3f))
        }
    }
}
