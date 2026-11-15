package io.github.linuxkernel44.opensoundboard.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stroke icons on a 24x24 grid (Lucide-style paths). */
enum class Ico(val path: String, val filled: Boolean = false) {
    Sounds("M4 10v4M8 6v12M12 3v18M16 8v8M20 11v2"),
    Grid("M6 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM15 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM6 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM15 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"),
    Heart("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"),
    HeartFilled("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", true),
    Record("M12 5a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z"),
    Sliders("M4 7h10M18 7h2M4 17h2M10 17h10M14 4v6M6 14v6"),
    Plus("M12 5v14M5 12h14"),
    Stop("M7 7h10v10H7z", true),
    Play("M8 5v14l11-7z", true),
    Share("M12 3v12M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"),
    Trash("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"),
    Close("M6 6l12 12M18 6L6 18"),
    Check("M5 12l5 5 9-10"),
    Edit("M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4"),
    Upload("M12 16V4M7 9l5-5 5 5M5 20h14"),
    Download("M12 4v12M7 11l5 5 5-5M5 20h14"),
    Info("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v6M12 7.6v.1"),
    Loop("M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3"),
}

private val cache = HashMap<Ico, ImageVector>()

fun Ico.vector(): ImageVector = cache.getOrPut(this) {
    val nodes = PathParser().parsePathString(path).toNodes()
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = nodes,
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = if (filled) null else SolidColor(Color.Black),
        strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}

@Composable
fun Icon(ico: Ico, modifier: Modifier = Modifier, tint: Color = LocalAurora.current.text, size: Dp = 22.dp) {
    val v = remember(ico) { ico.vector() }
    Image(v, null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
