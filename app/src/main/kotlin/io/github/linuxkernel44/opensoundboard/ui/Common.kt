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
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
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

/** Mini waveform from the decoded sound, shown on pads and in the trim editor. */
@Composable
fun Waveform(id: Int, buckets: Int, modifier: Modifier = Modifier, color: Color = LocalAurora.current.accent, ready: Boolean = true, progress: Float = -1f) {
    val peaks = remember(id, buckets, ready) { if (ready) NativeEngine.peaks(id, buckets) else null }
    Canvas(modifier) {
        val p = peaks ?: return@Canvas
        val w = size.width / p.size
        p.forEachIndexed { i, v ->
            val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
            // With a progress value, played bars are solid and the rest is dimmed.
            val c = if (progress < 0f || (i + 0.5f) / p.size <= progress) color else color.copy(alpha = color.alpha * 0.3f)
            drawRoundRect(c, Offset(i * w + w * 0.2f, (size.height - h) / 2f), Size(w * 0.6f, h), CornerRadius(w * 0.3f))
        }
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

