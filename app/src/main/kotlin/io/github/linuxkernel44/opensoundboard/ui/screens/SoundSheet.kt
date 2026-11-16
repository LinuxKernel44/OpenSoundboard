package io.github.linuxkernel44.opensoundboard.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
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
    var gainDb by remember(id) { mutableFloatStateOf(s.gainDb) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    // Loudest sample of the trimmed range, measured once off the main thread (linear, 1.0 = full scale).
    val peak by produceState(0f, id, s.trimStart, s.trimEnd, s.info) { value = withContext(Dispatchers.Default) { NativeEngine.peakLevel(id, s.trimStart, s.trimEnd) } }

    DisposableEffect(id) { onDispose { if (name.isNotBlank()) Library.update(id) { it.copy(name = name.trim()) } } }
    LaunchedEffect(gainDb) { delay(250); if (gainDb != s.gainDb) Library.update(id) { it.copy(gainDb = gainDb) } }
    LaunchedEffect(volume) { delay(250); if (volume != s.volume) Library.update(id) { it.copy(volume = volume) } }

    // Drag handle: pull the sheet down to close it (or tap the handle). The scrim fades as the sheet moves.
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dragState = rememberDraggableState { dy -> scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f)) } }
    fun dismiss() { scope.launch { offsetY.animateTo(3000f, tween(220)); onClose() } }

    Box(
        Modifier.fillMaxSize().background(p.scrim.copy(alpha = p.scrim.alpha * (1f - (offsetY.value / 1200f).coerceIn(0f, 1f))))
            .clickable(MutableInteractionSource(), null, onClick = { dismiss() }),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassPanel(
            backdrop,
            Modifier.statusBarsPadding().padding(top = 24.dp).fillMaxWidth().fillMaxHeight(0.9f)
                .graphicsLayer { translationY = offsetY.value }
                .clickable(MutableInteractionSource(), null) {},
            radius = 36.dp, strong = true,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
                Section(stringResource(R.string.gain)) {
                    GainControl(gainDb, { gainDb = it }, peak, volume, backdrop)
                }
                Section(stringResource(R.string.trim)) { TrimEditor(s) }
                Section(stringResource(R.string.audio_info)) { InfoTable(s) }
            }
            // Large touch area (full width, 36 dp tall) around a small visible pill.
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(36.dp)
                    .draggable(
                        state = dragState, orientation = Orientation.Vertical,
                        onDragStopped = { v ->
                            if (offsetY.value > with(density) { 120.dp.toPx() } || v > 1500f) { offsetY.animateTo(3000f, tween(220)); onClose() }
                            else offsetY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                        },
                    )
                    .clickable(role = Role.Button) { dismiss() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.text.copy(0.38f))) }
        }
    }
}

/** Gain in dB (-24..+24) on top of the volume, with the resulting peak level so boosts do not clip by accident. */
@Composable
private fun GainControl(gainDb: Float, onChange: (Float) -> Unit, peak: Float, volume: Float, backdrop: com.kyant.backdrop.Backdrop) {
    val p = LocalAurora.current
    fun db(x: Float) = if (x <= 1e-6f) Float.NEGATIVE_INFINITY else 20f * kotlin.math.log10(x)
    fun fmt(v: Float) = if (v.isInfinite()) "-∞" else String.format("%.1f", v)
    val after = db(peak * volume) + gainDb
    val clips = after > 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AText(String.format("%+.1f dB", gainDb), size = 16.sp, mono = true, weight = FontWeight.SemiBold)
            AText(stringResource(R.string.gain_peak, fmt(db(peak))), size = 12.sp, mono = true, color = p.muted)
        }
        LiquidSlider(
            { gainDb }, { v -> onChange(if (kotlin.math.abs(v) < 0.4f) 0f else (v * 10f).roundToInt() / 10f) },
            -24f..24f, 0.1f, backdrop, Modifier.padding(horizontal = 4.dp),
        )
        AText(
            stringResource(if (clips) R.string.gain_clip else R.string.gain_after, fmt(after)),
            size = 12.sp, mono = true, color = if (clips) p.danger else p.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassAction(backdrop, { onChange(0f) }, label = stringResource(R.string.gain_reset))
            GlassAction(backdrop, {
                if (peak > 1e-6f) onChange(((20f * kotlin.math.log10(0.891f / (peak * volume.coerceAtLeast(0.01f)))) * 10f).roundToInt().coerceIn(-240, 240) / 10f)
            }, label = stringResource(R.string.gain_normalize))
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
    // The shortest allowed trim is 50 ms, whatever the length of the sound.
    val minGap = if (s.info.durationMs > 0) (50f / s.info.durationMs).coerceIn(0.0005f, 0.5f) else 0.01f
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            // The handles sit near the screen edges: keep the system back gesture from stealing those drags.
            Modifier.fillMaxWidth().height(96.dp).systemGestureExclusion().pointerInput(s.id, minGap) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    // The handle is chosen at touch-down (the closest one; the start wins a tie at the far left, the end at the far right),
                    // and keeps its offset from the finger so it never jumps.
                    val x0 = down.position.x / w
                    val grabStart = if (a >= b - minGap) x0 < a else kotlin.math.abs(x0 - a) <= kotlin.math.abs(x0 - b)
                    val grabOffset = (if (grabStart) a else b) - x0
                    // Only a horizontal drag edits the trim; a vertical swipe scrolls the sheet.
                    val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    fun apply(px: Float) {
                        val x = (px / w + grabOffset).coerceIn(0f, 1f)
                        if (grabStart) a = x.coerceAtMost(b - minGap).coerceAtLeast(0f) else b = x.coerceAtLeast(a + minGap).coerceAtMost(1f)
                    }
                    apply(first.position.x)
                    horizontalDrag(first.id) { change -> apply(change.position.x); change.consume() }
                    Library.update(s.id) { it.copy(trimStart = a, trimEnd = b) }
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
                val knob = with(density) { 7.dp.toPx() }
                for (x in listOf(x0, x1)) {
                    drawLine(p.accent, Offset(x, 6f), Offset(x, size.height - 6f), 5f, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(p.accent, knob, Offset(x, size.height / 2f))
                    drawCircle(p.onAccent, knob * 0.38f, Offset(x, size.height / 2f))
                }
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
            GlassAction(LocalBackdrop.current, { a = 0f; b = 1f; Library.update(s.id) { it.copy(trimStart = 0f, trimEnd = 1f) } }, icon = Ico.Close, label = stringResource(R.string.trim_reset))
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

