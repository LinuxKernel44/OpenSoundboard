package io.github.linuxkernel44.opensoundboard.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object AuroraColors {
    val accentLight = Color(0xFF4F6BFF)
    val accentDark = Color(0xFF7B97FF)
}

/** Design "A · Aurora": soft pastel backdrop, bright frosted glass, rounded shapes. */
class AuroraPalette(
    val dark: Boolean,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val ok: Color,
    val danger: Color,
    val glass: Color,        // surface drawn over the blurred backdrop
    val glassStrong: Color,  // sheets and dialogs
    val scrim: Color,
    val gradient: List<Color>,
    val blobs: List<Color>,
)

val LightAurora = AuroraPalette(
    dark = false, text = Color(0xFF1A1F33), muted = Color(0xFF5B6280), accent = AuroraColors.accentLight, onAccent = Color.White,
    ok = Color(0xFF1FB878), danger = Color(0xFFE5484D), glass = Color(0xFFFFFFFF).copy(0.38f), glassStrong = Color(0xFFFAFAFF).copy(0.66f),
    scrim = Color(0xFF29293A).copy(0.30f),
    gradient = listOf(Color(0xFFFFD9E8), Color(0xFFCFE0FF), Color(0xFFC9F2EA)),
    blobs = listOf(Color(0xFFFF8AB8), Color(0xFF6FA8FF), Color(0xFF5FE3C8)),
)
val DarkAurora = AuroraPalette(
    dark = true, text = Color(0xFFF2F4FF), muted = Color(0xFFA7AECB), accent = AuroraColors.accentDark, onAccent = Color(0xFF0B1030),
    ok = Color(0xFF3DDC97), danger = Color(0xFFFF6B70), glass = Color(0xFF121626).copy(0.36f), glassStrong = Color(0xFF121626).copy(0.62f),
    scrim = Color(0xFF05060C).copy(0.55f),
    gradient = listOf(Color(0xFF2A1740), Color(0xFF14213F), Color(0xFF0E2E33)),
    blobs = listOf(Color(0xFFB0408A), Color(0xFF3A5BD0), Color(0xFF1E9C86)),
)

val LocalAurora = staticCompositionLocalOf { LightAurora }

@Composable
fun auroraPalette() = if (isSystemInDarkTheme()) DarkAurora else LightAurora

@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val p = LocalAurora.current
    val t = rememberInfiniteTransition(label = "aurora")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(p.gradient, Offset(0f, 0f), Offset(size.width * 0.6f, size.height)))
        val r = size.minDimension * 0.62f
        val spots = listOf(
            Offset(size.width * (0.1f + 0.25f * drift), size.height * 0.12f),
            Offset(size.width * (0.95f - 0.3f * drift), size.height * 0.52f),
            Offset(size.width * (0.2f + 0.2f * drift), size.height * 0.92f),
        )
        spots.forEachIndexed { i, c ->
            drawCircle(Brush.radialGradient(listOf(p.blobs[i].copy(0.85f), p.blobs[i].copy(0f)), c, r), r, c)
        }
    }
}

@Composable
fun AText(
    text: String, modifier: Modifier = Modifier, size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal,
    color: Color = LocalAurora.current.text, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null,
    mono: Boolean = false, letterSpacing: TextUnit = TextUnit.Unspecified,
) = BasicText(
    text, modifier, TextStyle(color, size, weight, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, textAlign = align ?: TextAlign.Unspecified, letterSpacing = letterSpacing),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

