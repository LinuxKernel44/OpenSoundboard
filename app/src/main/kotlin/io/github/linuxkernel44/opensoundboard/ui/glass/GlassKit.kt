package io.github.linuxkernel44.opensoundboard.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.Icon
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora

/** A frosted glass surface that samples [backdrop]. [strong] is used for sheets and dialogs. */
@Composable
fun GlassPanel(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    strong: Boolean = false,
    refract: Boolean = false,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val p = LocalAurora.current
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedRectangle(radius) },
            effects = { with(GlassStyle) { glassEffects(strong, !p.dark) } },
            highlight = { GlassStyle.highlight(strong) },
            onDrawSurface = {
                drawRect(GlassStyle.tint(if (strong) p.glassStrong else p.glass))
                if (tint != Color.Unspecified) drawRect(tint)
            },
        ),
        content = content,
    )
}

@Composable
fun GlassChip(backdrop: Backdrop, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalAurora.current
    Box(
        modifier
            .drawBackdrop(
                backdrop, { Capsule() },
                effects = { with(GlassStyle) { glassEffects(false, !p.dark) } },
                highlight = { GlassStyle.highlight(false) },
                onDrawSurface = { drawRect(if (selected) p.accent else GlassStyle.tint(p.glass)) },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .height(34.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { AText(label, size = 13.sp, color = if (selected) p.onAccent else p.text, weight = FontWeight.Medium, maxLines = 1) }
}

/** Round or pill shaped glass action button with an optional icon and label. */
@Composable
fun GlassAction(
    backdrop: Backdrop, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Ico? = null, label: String? = null,
    accent: Boolean = false, danger: Boolean = false,
) {
    val p = LocalAurora.current
    val fg = when { accent -> p.onAccent; danger -> p.danger; else -> p.text }
    LiquidButton(
        onClick = onClick, backdrop = backdrop, modifier = modifier,
        tint = if (accent) p.accent else Color.Unspecified, surfaceColor = if (accent) Color.Unspecified else GlassStyle.tint(p.glass),
    ) {
        if (icon != null) Icon(icon, tint = fg, size = 20.dp)
        if (label != null) AText(label, color = fg, weight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun Segmented(backdrop: Backdrop, options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> GlassChip(backdrop, o, i == selected, { onSelect(i) }, Modifier.weight(1f)) }
    }
}

