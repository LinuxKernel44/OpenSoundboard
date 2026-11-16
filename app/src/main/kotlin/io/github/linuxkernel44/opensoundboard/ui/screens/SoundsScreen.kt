package io.github.linuxkernel44.opensoundboard.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import io.github.linuxkernel44.opensoundboard.audio.Player
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.PlayMode
import io.github.linuxkernel44.opensoundboard.update.UpdateManager
import io.github.linuxkernel44.opensoundboard.data.SoundItem
import io.github.linuxkernel44.opensoundboard.ui.theme.Icon
import io.github.linuxkernel44.opensoundboard.ui.EmptyState
import io.github.linuxkernel44.opensoundboard.ui.LocalBackdrop
import io.github.linuxkernel44.opensoundboard.ui.LocalMeters
import io.github.linuxkernel44.opensoundboard.ui.LocalToast
import io.github.linuxkernel44.opensoundboard.ui.ScreenTitle
import io.github.linuxkernel44.opensoundboard.ui.SeekWaveform
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassChip
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassStyle
import io.github.linuxkernel44.opensoundboard.ui.khz
import io.github.linuxkernel44.opensoundboard.ui.shortInfo
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sounds and Favorites share this grid; [favoritesOnly] filters to pinned sounds. */
@Composable
fun SoundsScreen(
    favoritesOnly: Boolean, category: String?, onCategory: (String?) -> Unit, onEdit: (Int) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
    onUpdate: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val backdrop = LocalBackdrop.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val data = Library.data
    val sounds = data.sounds.filter { (!favoritesOnly || it.favorite) && (category == null || it.categoryId == category) }
    val failed = stringResource(R.string.import_failed)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val bad = withContext(Dispatchers.IO) { uris.count { Library.importUri(ctx, it, category) == null } }
            if (bad > 0) toast(failed)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(if (favoritesOnly) R.string.tab_favorites else R.string.tab_sounds)) {
            if (!favoritesOnly) GlassAction(backdrop, { picker.launch(arrayOf("audio/*", "application/ogg")) }, icon = Ico.Plus, label = stringResource(R.string.import_sounds))
        }
        InfoBar(Modifier.padding(horizontal = 16.dp))
        UpdateManager.pendingRelease()?.takeIf { !favoritesOnly }?.let { r ->
            GlassPanel(backdrop, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clickable(onClick = onUpdate), radius = 20.dp, tint = LocalAurora.current.accent.copy(0.30f)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Ico.Download, tint = LocalAurora.current.text, size = 18.dp)
                    AText(stringResource(R.string.update_banner, r.version), Modifier.weight(1f), size = 14.sp, weight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        if (!favoritesOnly && data.categories.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { GlassChip(backdrop, stringResource(R.string.all), category == null, { onCategory(null) }) }
                items(data.categories, key = { it.id }) { c -> GlassChip(backdrop, c.name, category == c.id, { onCategory(c.id) }) }
            }
        }
        if (sounds.isEmpty()) {
            if (favoritesOnly) EmptyState(stringResource(R.string.empty_favorites_title), stringResource(R.string.empty_favorites_body))
            else EmptyState(stringResource(R.string.empty_sounds_title), stringResource(R.string.empty_sounds_body))
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(104.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, bottomInset + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sounds, key = { it.id }) { s -> Pad(s, onEdit) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                        GlassAction(backdrop, { Player.stopAll() }, icon = Ico.Stop, label = stringResource(R.string.stop_all))
                    }
                }
            }
        }
    }
}

/** File info of the last triggered sound plus the live output route and a small spectrum meter. */
@Composable
fun InfoBar(modifier: Modifier = Modifier) {
    val p = LocalAurora.current
    val m = LocalMeters.current
    val out = Player.output
    val last = Library.data.sounds.firstOrNull { it.id == m.lastSoundId }
    GlassPanel(LocalBackdrop.current, modifier.fillMaxWidth(), radius = 20.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (out.running) p.ok else p.danger))
            val text = buildString {
                if (last != null) append(shortInfo(last.info)).append("  →  ")
                if (out.running) {
                    append(if (out.deviceName.isNotEmpty()) out.deviceName else "Audio"); append(" · ${khz(out.sampleRate)} kHz")
                    if (out.latencyMs > 0) append(" · ${String.format("%.1f", out.latencyMs)} ms")
                } else append(stringResource(R.string.out_stopped))
            }
            AText(text, Modifier.weight(1f), size = 12.sp, mono = true, maxLines = 1)
            MiniMeter(m.spectrum, Modifier.width(34.dp).height(18.dp))
        }
    }
}

@Composable
fun MiniMeter(spectrum: FloatArray, modifier: Modifier = Modifier) {
    val color = LocalAurora.current.accent
    Canvas(modifier) {
        val n = 9
        val w = size.width / n
        for (i in 0 until n) {
            val idx = (i * spectrum.size / n).coerceAtMost(spectrum.size - 1)
            val v = spectrum[idx].coerceIn(0.08f, 1f)
            val h = size.height * v
            drawRoundRect(color, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.55f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
        }
    }
}

@Composable
private fun Pad(s: SoundItem, onEdit: (Int) -> Unit) {
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    val m = LocalMeters.current
    // Gesture handlers outlive recompositions, so they must read the current sound (trim, volume, mode), not the first one.
    val cur by rememberUpdatedState(s)
    val playing = Player.playing.contains(s.id)
    val scale = if (playing) 0.96f else 1f
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBackdrop(
                backdrop, { RoundedRectangle(22.dp) },
                effects = { with(GlassStyle) { glassEffects(false, !p.dark) } },
                highlight = { GlassStyle.highlight(false) },
                onDrawSurface = {
                    drawRect(GlassStyle.tint(p.glass))
                    if (playing) drawRect(p.accent.copy(0.28f))
                },
            )
            .pointerInput(s.id) {
                // A tap plays on release; a long press does nothing. Hold-mode pads are the exception:
                // they play for as long as the finger stays down.
                detectTapGestures(
                    onPress = {
                        if (cur.mode == PlayMode.HOLD) {
                            // Wait a moment first, so a scroll that starts on this pad (which cancels the press) never plays it.
                            when (withTimeoutOrNull(120) { tryAwaitRelease() }) {
                                null -> { m.lastSoundId = cur.id; Player.press(cur); tryAwaitRelease(); Player.release(cur) }
                                true -> { m.lastSoundId = cur.id; Player.press(cur); Player.releaseLater(cur, 150) }
                                else -> Unit
                            }
                        }
                    },
                    // Setting onLongPress (even empty) makes detectTapGestures cancel onTap for long presses.
                    onLongPress = { },
                    onTap = {
                        if (cur.mode != PlayMode.HOLD) { m.lastSoundId = cur.id; Player.press(cur) }
                    },
                )
            },
    ) {
        SeekWaveform(s, 32, Modifier.align(Alignment.TopStart).fillMaxWidth().height(46.dp))
        if (s.favorite) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(7.dp).clip(CircleShape).background(p.accent))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(30.dp).clip(CircleShape)
                .background(p.text.copy(0.12f)).clickable(role = Role.Button) { onEdit(s.id) },
            contentAlignment = Alignment.Center,
        ) { Icon(Ico.Edit, tint = p.text.copy(0.8f), size = 15.dp) }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 38.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AText(s.name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 2)
            AText(shortInfo(s.info), size = 9.sp, color = p.muted, mono = true, maxLines = 1)
        }
    }
}

