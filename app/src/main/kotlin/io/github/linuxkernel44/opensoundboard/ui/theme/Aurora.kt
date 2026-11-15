package io.github.linuxkernel44.opensoundboard.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.linuxkernel44.opensoundboard.data.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.github.linuxkernel44.opensoundboard.R
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

val DarkAurora = AuroraPalette(
    dark = true, text = Color(0xFFF2F4FF), muted = Color(0xFFC9CFE6), accent = AuroraColors.accentDark, onAccent = Color(0xFF0B1030),
    ok = Color(0xFF3DDC97), danger = Color(0xFFFF6B70), glass = Color(0xFF121626).copy(0.36f), glassStrong = Color(0xFF121626).copy(0.62f),
    scrim = Color(0xFF05060C).copy(0.55f),
    gradient = listOf(Color(0xFF2A1740), Color(0xFF14213F), Color(0xFF0E2E33)),
    blobs = listOf(Color(0xFFB0408A), Color(0xFF3A5BD0), Color(0xFF1E9C86)),
)

val LightAurora = AuroraPalette(
    dark = false, text = Color(0xFF141A2E), muted = Color(0xFF414866), accent = AuroraColors.accentLight, onAccent = Color.White,
    ok = Color(0xFF138A5A), danger = Color(0xFFD13438), glass = Color(0xFFFFFFFF).copy(0.52f), glassStrong = Color(0xFFFAFAFF).copy(0.74f),
    scrim = Color(0xFF29293A).copy(0.34f),
    gradient = listOf(Color(0xFFFFD9E8), Color(0xFFCFE0FF), Color(0xFFC9F2EA)),
    blobs = listOf(Color(0xFFFF8AB8), Color(0xFF6FA8FF), Color(0xFF5FE3C8)),
)

val LocalAurora = staticCompositionLocalOf { DarkAurora }

/** Resolves the palette from the Appearance setting (0 system, 1 light, 2 dark). */
@Composable
fun auroraPalette(): AuroraPalette {
    val system = isSystemInDarkTheme()
    return when (Settings.themeMode) { 1 -> LightAurora; 2 -> DarkAurora; else -> if (system) DarkAurora else LightAurora }
}

/** The five bundled photographs (public domain). [scrim] darkens brighter photos so text stays readable. */
enum class Backdrops(val photo: Int, val thumb: Int, val label: Int, val scrim: Float, val wash: Float) {
    Aurora(R.drawable.bg_aurora, R.drawable.bgt_aurora, R.string.bg_aurora, 0.12f, 0.40f),
    MountainLake(R.drawable.bg_denali, R.drawable.bgt_denali, R.string.bg_denali, 0.14f, 0.40f),
    MistyForest(R.drawable.bg_forest, R.drawable.bgt_forest, R.string.bg_forest, 0.40f, 0.24f),
    MilkyWay(R.drawable.bg_milkyway, R.drawable.bgt_milkyway, R.string.bg_milkyway, 0.10f, 0.44f),
    PacificSunset(R.drawable.bg_ocean, R.drawable.bgt_ocean, R.string.bg_ocean, 0.30f, 0.34f),
}

@Composable
fun PhotoBackground(index: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val bg = Backdrops.entries[index.coerceIn(0, Backdrops.entries.size - 1)]
    Box(modifier.fillMaxSize()) {
        Crossfade(bg, label = "bg") { b ->
            Box(Modifier.fillMaxSize()) {
                Image(painterResource(b.photo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(if (dark) Color.Black.copy(b.scrim) else Color.White.copy(b.wash)))
            }
        }
    }
}

@Composable
fun AText(    text: String, modifier: Modifier = Modifier, size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal,
    color: Color = LocalAurora.current.text, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null,
    mono: Boolean = false, letterSpacing: TextUnit = TextUnit.Unspecified,
) = BasicText(
    text, modifier, TextStyle(color, size, weight, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, textAlign = align ?: TextAlign.Unspecified, letterSpacing = letterSpacing),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

