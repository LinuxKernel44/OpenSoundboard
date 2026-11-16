package io.github.linuxkernel44.opensoundboard.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import io.github.linuxkernel44.opensoundboard.data.Settings

/** User-tunable Liquid Glass look (Settings > Liquid Glass). Every glass surface reads from here. */
object GlassStyle {
    /** Opacity setting at which the palette alphas apply unchanged (the design reference, not the default setting). */
    const val DEFAULT_OPACITY = 0.4f
    /** 1.0 at the reference opacity; 0 gives fully clear glass. */
    val opacityFactor: Float get() = Settings.glassOpacity / DEFAULT_OPACITY

    fun tint(c: Color): Color = c.copy(alpha = (c.alpha * opacityFactor).coerceIn(0f, 0.95f))

    fun BackdropEffectScope.glassEffects(strong: Boolean, lightMode: Boolean) {
        colorControls(brightness = if (strong && lightMode) 0.15f else 0f, saturation = Settings.glassSaturation)
        val b = Settings.glassBlur * (if (strong) 1.8f else 1f)
        if (b > 0.5f) blur(b.dp.toPx())
        val r = Settings.glassRefraction
        if (r > 0.01f) lens(16f.dp.toPx() * r, 32f.dp.toPx() * r, depthEffect = strong, chromaticAberration = Settings.glassChromatic)
    }

    fun highlight(strong: Boolean): Highlight =
        (if (strong) Highlight.Plain else Highlight.Default).copy(alpha = Settings.glassHighlight)

    /** Scale for the larger, interactive elements (buttons, tab bar) that carry their own refraction. */
    val interactiveLens: Float get() = (Settings.glassRefraction / 0.5f).coerceIn(0f, 2f)
}
