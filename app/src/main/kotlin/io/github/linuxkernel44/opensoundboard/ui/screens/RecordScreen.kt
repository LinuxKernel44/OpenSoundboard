package io.github.linuxkernel44.opensoundboard.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.audio.Recorder
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.Settings
import io.github.linuxkernel44.opensoundboard.ui.GlassDialog
import io.github.linuxkernel44.opensoundboard.ui.GlassField
import io.github.linuxkernel44.opensoundboard.ui.LocalBackdrop
import io.github.linuxkernel44.opensoundboard.ui.ScreenTitle
import io.github.linuxkernel44.opensoundboard.ui.Section
import io.github.linuxkernel44.opensoundboard.ui.fmtDuration
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassChip
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.khz
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.Icon
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(bottomInset: Dp) {
    val ctx = LocalContext.current
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val rates = remember { Recorder.supportedRates() }
    val inputs = remember(granted) { Recorder.inputDevices(ctx) }
    var pending by remember { mutableStateOf<java.io.File?>(null) }
    var name by remember { mutableStateOf("") }
    val rec = Recorder.recording

    LaunchedEffect(rec) { if (!rec && Recorder.lastFile != null) { pending = Recorder.lastFile; name = ctx.getString(R.string.recording_default_name, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomInset + 8.dp)) {
        ScreenTitle(stringResource(R.string.record_title))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 32.dp) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AText(fmtDuration(Recorder.elapsedMs), size = 44.sp, weight = FontWeight.Light, mono = true)
                    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                        drawRoundRect(p.muted.copy(0.25f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                        drawRoundRect(if (Recorder.peak > 0.9f) p.danger else p.accent, size = Size(size.width * Recorder.peak.coerceIn(0f, 1f), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                    }
                    if (!granted) {
                        AText(stringResource(R.string.permission_needed), color = p.muted, align = androidx.compose.ui.text.style.TextAlign.Center)
                        GlassAction(backdrop, { ask.launch(Manifest.permission.RECORD_AUDIO) }, label = stringResource(R.string.grant_permission), accent = true)
                    } else {
                        GlassAction(
                            backdrop, {
                                if (rec) Recorder.stop() else Recorder.start(ctx, Settings.recordSampleRate, Settings.recordDeviceId, Settings.recordFloat)
                            },
                            icon = if (rec) Ico.Stop else Ico.Record, label = stringResource(if (rec) R.string.record_stop else R.string.record_start), accent = !rec, danger = rec,
                        )
                    }
                    Recorder.error?.let { AText(it, color = p.danger, size = 13.sp) }
                }
            }
            Section(stringResource(R.string.record_input)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(backdrop, stringResource(R.string.automatic), Settings.recordDeviceId == 0, { Settings.recordDeviceId = 0; Settings.save() })
                    inputs.forEach { d -> GlassChip(backdrop, d.productName.toString().take(24), Settings.recordDeviceId == d.id, { Settings.recordDeviceId = d.id; Settings.save() }) }
                }
            }
            Section(stringResource(R.string.record_rate)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rates.forEach { r -> GlassChip(backdrop, stringResource(R.string.khz, khz(r)), Settings.recordSampleRate == r, { Settings.recordSampleRate = r; Settings.save() }) }
                }
            }
            Section(stringResource(R.string.record_format)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(backdrop, stringResource(R.string.record_pcm24), !Settings.recordFloat, { Settings.recordFloat = false; Settings.save() })
                    GlassChip(backdrop, stringResource(R.string.record_float32), Settings.recordFloat, { Settings.recordFloat = true; Settings.save() })
                }
            }
            AText(stringResource(R.string.record_hint), color = p.muted, size = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }

    pending?.let { f ->
        GlassDialog({ f.delete(); pending = null; Recorder.consumeLast() }, stringResource(R.string.save_recording)) {
            GlassField(name, { name = it }, stringResource(R.string.sound_name))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassAction(backdrop, { f.delete(); pending = null; Recorder.consumeLast() }, Modifier.weight(1f), label = stringResource(R.string.discard))
                GlassAction(backdrop, {
                    Library.importFile(f, name.ifBlank { "Recording" }); pending = null; Recorder.consumeLast()
                }, Modifier.weight(1f), label = stringResource(R.string.save), accent = true)
            }
        }
    }
}

