package io.github.linuxkernel44.opensoundboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import io.github.linuxkernel44.opensoundboard.data.linearGain
import io.github.linuxkernel44.opensoundboard.data.SoundItem
import io.github.linuxkernel44.opensoundboard.audio.Player
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.data.WaveformCache
import io.github.linuxkernel44.opensoundboard.data.SourceInfo
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora

class Meters {
    var peakL by mutableFloatStateOf(0f)
    var peakR by mutableFloatStateOf(0f)
    var rmsL by mutableFloatStateOf(0f)
    var rmsR by mutableFloatStateOf(0f)
    var spectrum by mutableStateOf(FloatArray(32))
    var lastSoundId by mutableIntStateOf(-1)
}

val LocalMeters = staticCompositionLocalOf { Meters() }
val LocalBackdrop = staticCompositionLocalOf<Backdrop> { error("no backdrop") }
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

/** A linear level (1.0 = unity) as decibels, e.g. 0.5 -> "-6.0 dB". */
fun fmtDb(linear: Float): String = when {
    linear <= 0.0005f -> "-∞ dB"
    else -> { val db = 20f * kotlin.math.log10(linear); if (kotlin.math.abs(db) < 0.05f) "0.0 dB" else String.format("%+.1f dB", db) }
}

/** The decibel value shown above a volume slider, right-aligned. */
@Composable
fun DbReadout(linear: Float, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.End) {
        AText(fmtDb(linear), size = 13.sp, mono = true, weight = FontWeight.Medium, color = LocalAurora.current.muted)
    }
}

fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000}" else String.format("%.1f", rate / 1000f)

fun shortInfo(i: SourceInfo): String = buildString {
    append(i.codec.substringBefore(' '))
    if (i.bitDepth > 0) append(" · ${i.bitDepth}/${khz(i.sampleRate)}") else if (i.sampleRate > 0) append(" · ${khz(i.sampleRate)} kHz")
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000.0
    return if (s < 60) String.format("%.2f s", s) else String.format("%d:%02d", (s / 60).toInt(), (s % 60).toInt())
}

fun fmtSize(b: Long): String = when {
    b >= 1 shl 20 -> String.format("%.1f MB", b / 1048576.0)
    b >= 1024 -> String.format("%.0f KB", b / 1024.0)
    else -> "$b B"
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AText(text, Modifier.weight(1f), size = 30.sp, weight = FontWeight.Bold, maxLines = 1)
        trailing()
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val p = LocalAurora.current
    Column(modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AText(title, size = 20.sp, weight = FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Center)
        AText(body, color = p.muted, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalAurora.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AText(title.uppercase(), Modifier.padding(horizontal = 6.dp), size = 12.sp, weight = FontWeight.SemiBold, color = p.muted, letterSpacing = 1.sp)
        content()
    }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalAurora.current
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        AText(key, color = p.muted, size = 14.sp)
        AText(value, Modifier.padding(start = 16.dp), size = 14.sp, weight = FontWeight.Medium, mono = true, maxLines = 2)
    }
}

/**
 * Waveform from the cached peaks of the sound as it plays (so a trimmed or cut sound shows its edited shape). [scale] is the
 * sound's volume and gain, so a boosted sound draws taller. [progress] (0..1) colors the part already played.
 */
@Composable
fun Waveform(
    id: Int, buckets: Int, modifier: Modifier = Modifier, color: Color = LocalAurora.current.accent,
    progress: Float = -1f, scale: Float = 1f,
) {
    val base = WaveformCache.base(id)
    val peaks = remember(base, buckets) { base?.let { WaveformCache.resample(it, buckets) } }
    Canvas(modifier) {
        val p = peaks ?: return@Canvas
        val w = size.width / p.size
        p.forEachIndexed { i, v ->
            val h = (v * scale).coerceIn(0f, 1f).let { (it * size.height).coerceAtLeast(2f) }
            // With a progress value, played bars are solid and the rest is dimmed.
            val c = if (progress < 0f || (i + 0.5f) / p.size <= progress) color else color.copy(alpha = color.alpha * 0.3f)
            drawRoundRect(c, Offset(i * w + w * 0.2f, (size.height - h) / 2f), Size(w * 0.6f, h), CornerRadius(w * 0.3f))
        }
    }
}

/**
 * A sound's waveform that doubles as a seek bar, used on pads and at the top of the sound sheet. The part already played
 * is colored, and a tap or a horizontal drag jumps to that point (a tap on a silent pad starts it from there). Nothing
 * happens on touch-down and vertical swipes are left to the list, so scrolling never plays a sound.
 */
@Composable
fun SeekWaveform(s: SoundItem, buckets: Int, modifier: Modifier = Modifier, hPad: Dp = 10.dp, vPad: Dp = 11.dp) {
    val p = LocalAurora.current
    val m = LocalMeters.current
    val cur by rememberUpdatedState(s)   // gesture handlers must see the current volume and mode
    val playing = Player.playing.contains(s.id)
    val progress = if (playing) (Player.progress[s.id] ?: 0f).coerceIn(0f, 1f) else -1f
    val padPx = with(LocalDensity.current) { hPad.toPx() }
    /** Touch x (in pixels over the whole element) to a fraction of the sound. */
    fun fraction(x: Float, width: Int): Float = ((x - padPx) / (width - 2 * padPx).coerceAtLeast(1f)).coerceIn(0f, 0.999f)
    Box(
        modifier
            .pointerInput(s.id) {
                detectTapGestures(onTap = { o ->
                    m.lastSoundId = cur.id
                    Player.scrubStart(cur, fraction(o.x, size.width), NativeEngine.isPlaying(cur.id))
                })
            }
            .pointerInput(s.id) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        m.lastSoundId = cur.id
                        Player.scrubStart(cur, fraction(o.x, size.width), NativeEngine.isPlaying(cur.id))
                    },
                ) { change, _ ->
                    change.consume()
                    NativeEngine.seek(cur.id, fraction(change.position.x, size.width))
                }
            },
    ) {
        Waveform(s.id, buckets, Modifier.fillMaxSize().padding(hPad, vPad), color = p.accent.copy(if (playing) 1f else 0.6f), progress = progress, scale = s.linearGain())
    }
}

@Composable
fun GlassField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, backdrop: Backdrop = LocalBackdrop.current) {
    val p = LocalAurora.current
    GlassPanel(backdrop, modifier.fillMaxWidth(), radius = 16.dp) {
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), singleLine = true,
            textStyle = TextStyle(p.text, 16.sp), cursorBrush = SolidColor(p.accent),
            decorationBox = { inner -> Box { if (value.isEmpty()) AText(hint, color = p.muted, size = 16.sp); inner() } },
        )
    }
}

/** Modal glass dialog with a dimmed scrim. */
@Composable
fun GlassDialog(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    Box(Modifier.fillMaxSize().background(p.scrim).clickable(MutableInteractionSource(), null, onClick = onDismiss), contentAlignment = Alignment.Center) {
        GlassPanel(backdrop, Modifier.padding(24.dp).fillMaxWidth().clickable(MutableInteractionSource(), null) {}, radius = 32.dp, strong = true, refract = true) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AText(title, size = 22.sp, weight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val backdrop = LocalBackdrop.current
    GlassDialog(onDismiss, title) {
        GlassField(text, { text = it }, stringResource(R.string.category_name))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassAction(backdrop, onDismiss, Modifier.weight(1f), label = stringResource(R.string.cancel))
            GlassAction(backdrop, { if (text.isNotBlank()) onConfirm(text) }, Modifier.weight(1f), label = stringResource(R.string.save), accent = true)
        }
    }
}

