package io.github.linuxkernel44.opensoundboard.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import io.github.linuxkernel44.opensoundboard.audio.Player
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.PlayMode
import io.github.linuxkernel44.opensoundboard.data.SoundItem
import io.github.linuxkernel44.opensoundboard.ui.GlassField
import io.github.linuxkernel44.opensoundboard.ui.KeyValue
import io.github.linuxkernel44.opensoundboard.ui.LocalBackdrop
import io.github.linuxkernel44.opensoundboard.ui.Section
import io.github.linuxkernel44.opensoundboard.ui.Waveform
import io.github.linuxkernel44.opensoundboard.ui.fmtDuration
import io.github.linuxkernel44.opensoundboard.ui.fmtSize
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassChip
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.glass.LiquidSlider
import io.github.linuxkernel44.opensoundboard.ui.glass.Segmented
import io.github.linuxkernel44.opensoundboard.ui.khz
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundSheet(id: Int, onClose: () -> Unit) {
    val s = Library.data.sounds.firstOrNull { it.id == id } ?: run { onClose(); return }
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    val ctx = LocalContext.current
    var name by remember(id) { mutableStateOf(s.name) }
    var volume by remember(id) { mutableFloatStateOf(s.volume) }
    var confirmDelete by remember(id) { mutableStateOf(false) }

    DisposableEffect(id) { onDispose { if (name.isNotBlank()) Library.update(id) { it.copy(name = name.trim()) } } }
    LaunchedEffect(volume) { delay(250); if (volume != s.volume) Library.update(id) { it.copy(volume = volume) } }

    Box(Modifier.fillMaxSize().background(p.scrim).clickable(MutableInteractionSource(), null, onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        GlassPanel(
            backdrop, Modifier.statusBarsPadding().padding(top = 24.dp).fillMaxWidth().fillMaxHeight(0.9f).clickable(MutableInteractionSource(), null) {},
            radius = 36.dp, strong = true,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassField(name, { name = it }, stringResource(R.string.sound_name), Modifier.weight(1f))
                    GlassAction(backdrop, onClose, icon = Ico.Close)
                }
                Waveform(id, 64, Modifier.fillMaxWidth().height(44.dp), p.accent.copy(0.7f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassAction(backdrop, { Player.preview(s) }, icon = Ico.Play, label = stringResource(R.string.play))
                    GlassAction(backdrop, { Library.update(id) { it.copy(favorite = !it.favorite) } }, icon = if (s.favorite) Ico.HeartFilled else Ico.Heart, label = stringResource(R.string.tab_favorites), accent = s.favorite)
                    GlassAction(backdrop, { share(ctx, s) }, icon = Ico.Share, label = stringResource(R.string.share))
                    GlassAction(
                        backdrop, { if (confirmDelete) { Library.delete(id); onClose() } else confirmDelete = true },
                        icon = Ico.Trash, label = stringResource(if (confirmDelete) R.string.confirm_delete else R.string.delete), danger = true,
                    )
                }
                Section(stringResource(R.string.category)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip(backdrop, stringResource(R.string.uncategorized), s.categoryId == null, { Library.update(id) { it.copy(categoryId = null) } })
                        Library.data.categories.forEach { c -> GlassChip(backdrop, c.name, s.categoryId == c.id, { Library.update(id) { it.copy(categoryId = c.id) } }) }
                    }
                }
                Section(stringResource(R.string.playback_mode)) {
                    Segmented(backdrop, listOf(stringResource(R.string.mode_one_shot), stringResource(R.string.mode_loop), stringResource(R.string.mode_hold)), s.mode.ordinal, { i -> Library.update(id) { it.copy(mode = PlayMode.entries[i]) } })
                }
                Section(stringResource(R.string.volume)) {
                    LiquidSlider({ volume }, { volume = it }, 0f..1.5f, 0.01f, backdrop, Modifier.padding(horizontal = 4.dp))
                }
                Section(stringResource(R.string.trim)) { TrimEditor(s) }
                Section(stringResource(R.string.audio_info)) { InfoTable(s) }
            }
        }
    }
}

@Composable
private fun InfoTable(s: SoundItem) {
    val i = s.info
    GlassPanel(LocalBackdrop.current, Modifier.fillMaxWidth(), radius = 20.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            KeyValue(stringResource(R.string.info_codec), i.codec)
            KeyValue(stringResource(R.string.info_sample_rate), stringResource(R.string.khz, khz(i.sampleRate)))
            KeyValue(stringResource(R.string.info_bit_depth), if (i.bitDepth > 0) stringResource(R.string.bits, i.bitDepth) else stringResource(R.string.lossy))
            KeyValue(stringResource(R.string.info_channels), when (i.channels) { 1 -> stringResource(R.string.mono); 2 -> stringResource(R.string.stereo); else -> stringResource(R.string.channels_n, i.channels) })
            KeyValue(stringResource(R.string.info_bitrate), "${i.bitrateKbps} kb/s")
            KeyValue(stringResource(R.string.info_duration), fmtDuration(i.durationMs))
            KeyValue(stringResource(R.string.info_size), fmtSize(i.sizeBytes))
            if (i.gaplessDelay > 0 || i.gaplessPadding > 0) KeyValue(stringResource(R.string.info_gapless), stringResource(R.string.gapless_trimmed, i.gaplessDelay, i.gaplessPadding))
        }
    }
}

/** Waveform with two draggable handles. Trim is applied at playback; the file is never modified. */
@Composable
private fun TrimEditor(s: SoundItem) {
    val p = LocalAurora.current
    var a by remember(s.id) { mutableFloatStateOf(s.trimStart) }
    var b by remember(s.id) { mutableFloatStateOf(s.trimEnd) }
    var dragging by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(96.dp).pointerInput(s.id) {
                // Horizontal only, so scrolling the sheet vertically over the waveform never edits the trim.
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = if (kotlin.math.abs(o.x / size.width - a) <= kotlin.math.abs(o.x / size.width - b)) 1 else 2 },
                    onDragEnd = { Library.update(s.id) { it.copy(trimStart = a, trimEnd = b) } },
                    onDragCancel = { Library.update(s.id) { it.copy(trimStart = a, trimEnd = b) } },
                ) { change, _ ->
                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                    if (dragging == 1) a = x.coerceAtMost(b - 0.01f) else b = x.coerceAtLeast(a + 0.01f)
                }
            },
        ) {
            GlassPanel(LocalBackdrop.current, Modifier.fillMaxSize(), radius = 18.dp) {}
            Waveform(s.id, 96, Modifier.fillMaxSize().padding(10.dp), p.text.copy(0.35f))
            Canvas(Modifier.fillMaxSize()) {
                val x0 = a * size.width; val x1 = b * size.width
                drawRect(p.accent.copy(0.18f), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height))
                drawRect(p.scrim, Offset(0f, 0f), androidx.compose.ui.geometry.Size(x0, size.height))
                drawRect(p.scrim, Offset(x1, 0f), androidx.compose.ui.geometry.Size(size.width - x1, size.height))
                for (x in listOf(x0, x1)) drawLine(p.accent, Offset(x, 6f), Offset(x, size.height - 6f), 6f, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val dur = s.info.durationMs
            AText(fmtDuration((a * dur).toLong()), size = 12.sp, mono = true, color = p.muted)
            AText(fmtDuration(((b - a) * dur).toLong()), size = 12.sp, mono = true, weight = FontWeight.Medium)
            AText(fmtDuration((b * dur).toLong()), size = 12.sp, mono = true, color = p.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassAction(LocalBackdrop.current, { Player.preview(s, a, b) }, icon = Ico.Play, label = stringResource(R.string.play))
            GlassAction(LocalBackdrop.current, { a = 0f; b = 1f; Library.update(s.id) { it.copy(trimStart = 0f, trimEnd = 1f) } }, icon = Ico.Close, label = "0–100%")
        }
    }
}

private fun share(ctx: Context, s: SoundItem) {
    val src = Library.soundFile(s)
    val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val safe = s.name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").ifBlank { "sound" }
    val out = File(dir, "$safe.${src.extension}")
    src.copyTo(out, overwrite = true)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
    val i = Intent(Intent.ACTION_SEND).apply { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

