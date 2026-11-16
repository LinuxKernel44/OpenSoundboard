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
import io.github.linuxkernel44.opensoundboard.ui.SeekWaveform
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Job
import androidx.compose.animation.core.animate
import io.github.linuxkernel44.opensoundboard.ui.LocalToast
import io.github.linuxkernel44.opensoundboard.ui.GlassDialog
import io.github.linuxkernel44.opensoundboard.data.linearGain
import io.github.linuxkernel44.opensoundboard.data.isEdited
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
import io.github.linuxkernel44.opensoundboard.ui.DbReadout
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
    var shareDialog by remember(id) { mutableStateOf(false) }
    val toast = LocalToast.current
    val renderFailed = stringResource(R.string.share_failed)
    // Loudest sample of the trimmed range, measured once off the main thread (linear, 1.0 = full scale).
    val peak by produceState(0f, id, s.trimStart, s.trimEnd, s.info) { value = withContext(Dispatchers.Default) { NativeEngine.peakLevel(id, s.trimStart, s.trimEnd) } }

    DisposableEffect(id) { onDispose { if (name.isNotBlank()) Library.update(id) { it.copy(name = name.trim()) } } }
    // gainDb is the raw slider value; the rounded value (with a small detent at 0 dB) is what is shown and saved.
    LaunchedEffect(gainDb) { delay(250); val g = snapGain(gainDb); if (g != s.gainDb) Library.update(id) { it.copy(gainDb = g) } }
    LaunchedEffect(volume) { delay(250); if (volume != s.volume) Library.update(id) { it.copy(volume = volume) } }

    // Drag handle: pull the sheet down to close it (or tap the handle). The scrim fades as the sheet moves.
    // The offset is plain state updated synchronously while dragging; only the settle animation runs in a coroutine,
    // and a new drag cancels it, so a late update can never leave the sheet stuck half way.
    var sheetOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    fun settleTo(target: Float, then: () -> Unit = {}) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(sheetOffset, target, animationSpec = if (target == 0f) spring(dampingRatio = 0.8f, stiffness = 500f) else tween(220)) { v, _ -> sheetOffset = v }
            then()
        }
    }
    fun dismiss() = settleTo(screenPx) { onClose() }
    val dragState = rememberDraggableState { dy -> sheetOffset = (sheetOffset + dy).coerceAtLeast(0f) }

    Box(
        Modifier.fillMaxSize().background(p.scrim.copy(alpha = p.scrim.alpha * (1f - (sheetOffset / (screenPx * 0.5f)).coerceIn(0f, 1f))))
            .clickable(MutableInteractionSource(), null, onClick = { dismiss() }),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassPanel(
            backdrop,
            Modifier.statusBarsPadding().padding(top = 24.dp).fillMaxWidth().fillMaxHeight(0.9f)
                .graphicsLayer { translationY = sheetOffset }
                .clickable(MutableInteractionSource(), null) {},
            radius = 36.dp, strong = true,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassField(name, { name = it }, stringResource(R.string.sound_name), Modifier.weight(1f))
                    GlassAction(backdrop, onClose, icon = Ico.Close)
                }
                SeekWaveform(s, 64, Modifier.fillMaxWidth().height(56.dp), hPad = 0.dp, vPad = 6.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassAction(backdrop, { Player.preview(s) }, icon = Ico.Play, label = stringResource(R.string.play))
                    GlassAction(backdrop, { Library.update(id) { it.copy(favorite = !it.favorite) } }, icon = if (s.favorite) Ico.HeartFilled else Ico.Heart, label = stringResource(R.string.tab_favorites), accent = s.favorite)
                    GlassAction(backdrop, { if (s.isEdited()) shareDialog = true else shareOriginal(ctx, s) }, icon = Ico.Share, label = stringResource(R.string.share))
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
                    DbReadout(volume)
                    LiquidSlider({ volume }, { volume = it }, 0f..1.5f, 0.01f, backdrop, Modifier.padding(horizontal = 4.dp), defaultValue = 1f)
                }
                Section(stringResource(R.string.gain)) {
                    GainControl(gainDb, { gainDb = it }, peak, volume, backdrop)
                }
                Section(stringResource(R.string.trim)) { TrimEditor(s) }
                Section(stringResource(R.string.audio_info)) { InfoTable(s) }
            }
            // Large touch area (full width, 48 dp tall) around a small visible pill.
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(48.dp)
                    .draggable(
                        state = dragState, orientation = Orientation.Vertical,
                        onDragStarted = { settleJob?.cancel() },
                        // Closes after a pull of about 56 dp or a quick flick; the finger does not have to reach the bottom.
                        onDragStopped = { v -> if (sheetOffset > with(density) { 56.dp.toPx() } || v > 700f) dismiss() else settleTo(0f) },
                    )
                    .clickable(role = Role.Button) { dismiss() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.text.copy(0.38f))) }
        }
        // An edited sound (trimmed, or volume / gain changed) can be shared as the original file or as the edited version.
        if (shareDialog) GlassDialog({ shareDialog = false }, stringResource(R.string.share)) {
            AText(stringResource(R.string.share_edited_body), color = p.muted, size = 14.sp)
            GlassAction(backdrop, {
                shareDialog = false
                scope.launch {
                    val file = withContext(Dispatchers.IO) { renderEdited(ctx, s) }
                    if (file != null) sendFile(ctx, file) else toast(renderFailed)
                }
            }, Modifier.fillMaxWidth(), label = stringResource(R.string.share_edited), accent = true)
            GlassAction(backdrop, { shareDialog = false; shareOriginal(ctx, s) }, Modifier.fillMaxWidth(), label = stringResource(R.string.share_original))
            GlassAction(backdrop, { shareDialog = false }, Modifier.fillMaxWidth(), label = stringResource(R.string.cancel))
        }
    }
}

/** The gain actually applied: 0.1 dB steps, with a small detent at 0 dB. */
private fun snapGain(raw: Float): Float = if (kotlin.math.abs(raw) < 0.4f) 0f else (raw * 10f).roundToInt() / 10f

/** Gain in dB (-24..+24) on top of the volume, with the resulting peak level so boosts do not clip by accident. */
@Composable
private fun GainControl(gainDb: Float, onChange: (Float) -> Unit, peak: Float, volume: Float, backdrop: com.kyant.backdrop.Backdrop) {
    val p = LocalAurora.current
    fun db(x: Float) = if (x <= 1e-6f) Float.NEGATIVE_INFINITY else 20f * kotlin.math.log10(x)
    fun fmt(v: Float) = if (v.isInfinite()) "-∞" else String.format("%.1f", v)
    val shown = snapGain(gainDb)
    val after = db(peak * volume) + shown
    val clips = after > 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AText(String.format("%+.1f dB", shown), size = 16.sp, mono = true, weight = FontWeight.SemiBold)
            AText(stringResource(R.string.gain_peak, fmt(db(peak))), size = 12.sp, mono = true, color = p.muted)
        }
        // The slider moves by tiny steps while dragging, so it must keep the exact value: rounding it here made it jump or stick.
        LiquidSlider({ gainDb }, onChange, -24f..24f, 0.01f, backdrop, Modifier.padding(horizontal = 4.dp), defaultValue = 0f)
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
            if (s.trimStart > 0f || s.trimEnd < 1f) KeyValue(stringResource(R.string.info_trimmed), fmtDuration(((s.trimEnd - s.trimStart) * i.durationMs).toLong()))
            KeyValue(stringResource(R.string.info_size), fmtSize(i.sizeBytes))
            if (i.gaplessDelay > 0 || i.gaplessPadding > 0) KeyValue(stringResource(R.string.info_gapless), stringResource(R.string.gapless_trimmed, i.gaplessDelay, i.gaplessPadding))
        }
    }
}

/**
 * Waveform with two draggable handles. Moving them only changes a draft: the sound keeps playing and showing as before until
 * you tap Apply trim. Reset trim goes back to the whole sound. The file itself is never modified.
 */
@Composable
private fun TrimEditor(s: SoundItem) {
    val p = LocalAurora.current
    val toast = LocalToast.current
    var a by remember(s.id) { mutableFloatStateOf(s.trimStart) }
    var b by remember(s.id) { mutableFloatStateOf(s.trimEnd) }
    val dirty = kotlin.math.abs(a - s.trimStart) > 1e-4f || kotlin.math.abs(b - s.trimEnd) > 1e-4f
    // The shortest allowed trim is 50 ms, whatever the length of the sound.
    val minGap = if (s.info.durationMs > 0) (50f / s.info.durationMs).coerceIn(0.0005f, 0.5f) else 0.01f
    val density = LocalDensity.current
    val applied = stringResource(R.string.trim_applied)
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
        if (dirty) AText(stringResource(R.string.trim_pending), size = 12.sp, color = p.accent, weight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassAction(LocalBackdrop.current, { Player.preview(s, a, b) }, icon = Ico.Play, label = stringResource(R.string.play))
            GlassAction(
                LocalBackdrop.current, { if (dirty) { Library.update(s.id) { it.copy(trimStart = a, trimEnd = b) }; toast(applied) } },
                icon = Ico.Check, label = stringResource(R.string.trim_apply), accent = dirty,
            )
            GlassAction(LocalBackdrop.current, { a = 0f; b = 1f; Library.update(s.id) { it.copy(trimStart = 0f, trimEnd = 1f) } }, icon = Ico.Close, label = stringResource(R.string.trim_reset))
        }
    }
}

private fun safeName(s: SoundItem) = s.name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").ifBlank { "sound" }

private fun shareDir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

/** Shares the stored file exactly as imported. */
private fun shareOriginal(ctx: Context, s: SoundItem) {
    val src = Library.soundFile(s)
    val out = File(shareDir(ctx), "${safeName(s)}.${src.extension}")
    src.copyTo(out, overwrite = true)
    sendFile(ctx, out)
}

/** Renders the sound as it plays in the app (trim, volume and gain applied) to a 24-bit WAV, or null on failure. */
private fun renderEdited(ctx: Context, s: SoundItem): File? {
    val out = File(shareDir(ctx), "${safeName(s)} (edited).wav")
    return if (NativeEngine.exportWav(s.id, s.trimStart, s.trimEnd, s.linearGain(), out.absolutePath)) out else { out.delete(); null }
}

private fun sendFile(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val i = Intent(Intent.ACTION_SEND).apply { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

