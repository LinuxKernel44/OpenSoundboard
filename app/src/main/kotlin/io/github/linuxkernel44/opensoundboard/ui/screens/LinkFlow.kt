package io.github.linuxkernel44.opensoundboard.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.data.WaveformCache
import io.github.linuxkernel44.opensoundboard.download.DownloadException
import io.github.linuxkernel44.opensoundboard.download.LinkDownloader
import io.github.linuxkernel44.opensoundboard.download.LinkInfo
import io.github.linuxkernel44.opensoundboard.download.PadMaker
import io.github.linuxkernel44.opensoundboard.download.Phase
import io.github.linuxkernel44.opensoundboard.download.Staged
import io.github.linuxkernel44.opensoundboard.download.Zone
import io.github.linuxkernel44.opensoundboard.ui.LocalBackdrop
import io.github.linuxkernel44.opensoundboard.ui.fmtDuration
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassChip
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.glass.LiquidToggle
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.abs

enum class LinkStep { Closed, Input, Probing, Preview, Downloading, Choose, Split, Saving }
enum class LinkError { None, Url, NotFound, Failed, Decode }

/** The whole "download from a link" journey. It lives above the screens, so a download goes on while the user browses. */
class LinkFlowState {
    var step by mutableStateOf(LinkStep.Closed)
    var url by mutableStateOf("")
    var phase by mutableStateOf(Phase.Probing)
    var info by mutableStateOf<LinkInfo?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf(LinkError.None)
    var staged by mutableStateOf<Staged?>(null)
    var downloaded: File? = null
    var zones by mutableStateOf<List<Zone>>(emptyList())
    var categoryId by mutableStateOf<String?>(null)
    var keepFull by mutableStateOf(false)
    var saved by mutableIntStateOf(0)
    var savingTotal by mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun open(link: String = "", categoryId: String? = null) {
        if (step != LinkStep.Closed) { if (link.isNotBlank() && (step == LinkStep.Input)) url = link; return }
        reset(); url = link; this.categoryId = categoryId; step = LinkStep.Input
    }

    private fun reset() { info = null; thumb = null; progress = 0f; error = LinkError.None; staged = null; downloaded = null; zones = emptyList(); keepFull = false; saved = 0 }

    fun probe(ctx: Context) {
        val link = url.trim()
        if (!link.startsWith("http://") && !link.startsWith("https://")) { error = LinkError.Url; return }
        error = LinkError.None; step = LinkStep.Probing; phase = Phase.Probing
        job = scope.launch {
            try {
                val i = LinkDownloader.probe(ctx, link) { phase = it }
                info = i; step = LinkStep.Preview
                if (i.thumbnail.isNotBlank()) launch { thumb = runCatching { URL(i.thumbnail).openStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
            } catch (e: DownloadException) {
                error = if (e.kind == DownloadException.Kind.NotFound) LinkError.NotFound else LinkError.Failed
                step = if (e.kind == DownloadException.Kind.Cancelled) LinkStep.Input else LinkStep.Input
                if (e.kind == DownloadException.Kind.Cancelled) error = LinkError.None
            } catch (e: Throwable) { android.util.Log.w("OsbLink", "probe failed", e); error = LinkError.Failed; step = LinkStep.Input }
        }
    }

    fun download(ctx: Context) {
        val i = info ?: return
        error = LinkError.None; progress = 0f; step = LinkStep.Downloading; phase = Phase.Downloading
        job = scope.launch {
            try {
                val f = LinkDownloader.download(ctx, i, { progress = it }, { phase = it })
                downloaded = f; step = LinkStep.Choose
            } catch (e: DownloadException) {
                error = if (e.kind == DownloadException.Kind.Cancelled) LinkError.None else LinkError.Failed
                step = LinkStep.Preview
            } catch (e: Throwable) { android.util.Log.w("OsbLink", "download failed", e); error = LinkError.Failed; step = LinkStep.Preview }
        }
    }

    /** Cancels the running lookup or download and goes back one step. */
    fun cancel() { LinkDownloader.cancel(); job?.cancel() }

    fun close(ctx: Context) {
        cancel()
        val st = staged
        scope.launch { PadMaker.discard(ctx, st) }
        staged = null; step = LinkStep.Closed; reset()
    }

    fun importWhole(ctx: Context, done: (Boolean) -> Unit) {
        val f = downloaded ?: return
        val i = info ?: return
        step = LinkStep.Saving; savingTotal = 1; saved = 0
        scope.launch {
            val ok = PadMaker.importWhole(f, i.title, categoryId) != null
            if (ok) { LinkDownloader.clearCache(ctx); step = LinkStep.Closed; reset() } else { error = LinkError.Decode; step = LinkStep.Choose }
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    fun startSplit() {
        val f = downloaded ?: return
        step = LinkStep.Saving; savingTotal = 0
        scope.launch {
            val st = PadMaker.stage(f)
            if (st == null) { error = LinkError.Decode; step = LinkStep.Choose; return@launch }
            staged = st
            zones = listOf(Zone(0f, 1f))
            step = LinkStep.Split
        }
    }

    fun save(ctx: Context, done: (Int) -> Unit) {
        val st = staged ?: return
        val title = info?.title ?: "Audio"
        val on = zones.filter { it.on }
        if (on.isEmpty()) return
        step = LinkStep.Saving; savingTotal = on.size + if (keepFull) 1 else 0; saved = 0
        scope.launch {
            var made = 0
            on.forEachIndexed { k, z ->
                val name = z.name.ifBlank { if (on.size == 1) title else "$title – ${k + 1}" }
                if (PadMaker.importZone(st, z, name, categoryId) != null) made++
                saved++
            }
            if (keepFull) { PadMaker.importWhole(st.file, title, categoryId); saved++ }
            PadMaker.discard(ctx, st)
            staged = null; step = LinkStep.Closed; reset()
            withContext(Dispatchers.Main) { done(made) }
        }
    }
}

fun clipboardText(ctx: Context): String = runCatching {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
}.getOrDefault("")

@Composable
private fun phaseText(p: Phase) = stringResource(when (p) {
    Phase.Probing -> R.string.link_phase_probe; Phase.UpdatingYtDlp -> R.string.link_phase_update
    Phase.Fallback -> R.string.link_phase_fallback; Phase.Downloading -> R.string.link_phase_download
})

@Composable
private fun errorText(e: LinkError) = when (e) {
    LinkError.None -> ""
    LinkError.Url -> stringResource(R.string.link_err_url)
    LinkError.NotFound -> stringResource(R.string.link_err_notfound)
    LinkError.Failed -> stringResource(R.string.link_err_failed)
    LinkError.Decode -> stringResource(R.string.link_err_decode)
}

@Composable
private fun ProgressBar(value: Float, indeterminate: Boolean = false) {
    val p = LocalAurora.current
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(p.muted.copy(0.25f), size = size, cornerRadius = r)
        if (!indeterminate) drawRoundRect(p.accent, size = Size(size.width * value.coerceIn(0f, 1f), size.height), cornerRadius = r)
        else drawRoundRect(p.accent.copy(0.6f), size = Size(size.width * 0.3f, size.height), cornerRadius = r)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkFlowOverlay(f: LinkFlowState, onToast: (String) -> Unit) {
    if (f.step == LinkStep.Closed) return
    if (f.step == LinkStep.Split) { SplitEditor(f, onToast); return }
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    val ctx = LocalContext.current
    val busy = f.step == LinkStep.Probing || f.step == LinkStep.Downloading || f.step == LinkStep.Saving
    Box(Modifier.fillMaxSize().background(p.scrim).clickable(MutableInteractionSource(), null) { if (!busy) f.close(ctx) }, contentAlignment = Alignment.Center) {
        GlassPanel(backdrop, Modifier.padding(24.dp).fillMaxWidth().clickable(MutableInteractionSource(), null) {}, radius = 32.dp, strong = true, refract = true) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AText(stringResource(R.string.link_title), size = 22.sp, weight = FontWeight.SemiBold)
                when (f.step) {
                    LinkStep.Input -> {
                        GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 16.dp) {
                            BasicTextField(f.url, { f.url = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), maxLines = 3,
                                textStyle = TextStyle(p.text, 15.sp), cursorBrush = SolidColor(p.accent),
                                decorationBox = { inner -> Box { if (f.url.isEmpty()) AText(stringResource(R.string.link_hint), color = p.muted, size = 15.sp); inner() } })
                        }
                        if (f.error != LinkError.None) AText(errorText(f.error), size = 13.sp, color = p.danger)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassAction(backdrop, { f.url = clipboardText(ctx).trim() }, Modifier.weight(1f), label = stringResource(R.string.link_paste))
                            GlassAction(backdrop, { f.probe(ctx) }, Modifier.weight(1f), label = stringResource(R.string.link_fetch), accent = true)
                        }
                        GlassAction(backdrop, { f.close(ctx) }, Modifier.fillMaxWidth(), label = stringResource(R.string.cancel))
                    }
                    LinkStep.Probing -> {
                        AText(phaseText(f.phase), color = p.muted)
                        ProgressBar(0f, indeterminate = true)
                        GlassAction(backdrop, { f.cancel() }, Modifier.fillMaxWidth(), icon = Ico.Close, label = stringResource(R.string.cancel))
                    }
                    LinkStep.Preview, LinkStep.Downloading, LinkStep.Choose -> {
                        val i = f.info
                        if (i != null) {
                            f.thumb?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
                            AText(i.title, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 3)
                            AText(listOfNotNull(i.uploader.ifBlank { null }, if (i.durationSec > 0) fmtDuration(i.durationSec * 1000L) else null,
                                if (i.chapters.size > 1) stringResource(R.string.link_chapters, i.chapters.size) else null,
                                stringResource(R.string.link_engine, if (i.engine == "yt-dlp") "yt-dlp" else "NewPipe")).joinToString(" · "), size = 13.sp, color = p.muted, maxLines = 2)
                        }
                        if (f.error != LinkError.None) AText(errorText(f.error), size = 13.sp, color = p.danger)
                        when (f.step) {
                            LinkStep.Preview -> {
                                GlassAction(backdrop, { f.download(ctx) }, Modifier.fillMaxWidth(), icon = Ico.Download, label = stringResource(R.string.link_download), accent = true)
                                GlassAction(backdrop, { f.close(ctx) }, Modifier.fillMaxWidth(), label = stringResource(R.string.cancel))
                            }
                            LinkStep.Downloading -> {
                                AText(phaseText(f.phase) + if (f.progress > 0f) " ${(f.progress * 100).toInt()}%" else "", color = p.muted, size = 14.sp)
                                ProgressBar(f.progress, indeterminate = f.progress <= 0f)
                                GlassAction(backdrop, { f.cancel() }, Modifier.fillMaxWidth(), icon = Ico.Close, label = stringResource(R.string.cancel))
                            }
                            else -> {
                                CategoryPicker(f)
                                GlassAction(backdrop, { f.startSplit() }, Modifier.fillMaxWidth(), icon = Ico.Edit, label = stringResource(R.string.link_split), accent = true)
                                GlassAction(backdrop, { f.importWhole(ctx) { ok -> if (ok) onToast(ctx.getString(R.string.link_imported)) } }, Modifier.fillMaxWidth(), icon = Ico.Plus, label = stringResource(R.string.link_import_whole))
                                GlassAction(backdrop, { f.close(ctx) }, Modifier.fillMaxWidth(), label = stringResource(R.string.cancel))
                            }
                        }
                    }
                    LinkStep.Saving -> {
                        AText(if (f.savingTotal > 0) stringResource(R.string.split_saving, f.saved, f.savingTotal) else stringResource(R.string.link_phase_probe), color = p.muted)
                        ProgressBar(if (f.savingTotal > 0) f.saved.toFloat() / f.savingTotal else 0f, indeterminate = f.savingTotal == 0)
                    }
                    else -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(f: LinkFlowState) {
    val backdrop = LocalBackdrop.current
    val cats = Library.data.categories
    if (cats.isEmpty()) return
    AText(stringResource(R.string.category), size = 13.sp, color = LocalAurora.current.muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassChip(backdrop, stringResource(R.string.all).let { "—" }, f.categoryId == null, { f.categoryId = null })
        cats.forEach { c -> GlassChip(backdrop, c.name, f.categoryId == c.id, { f.categoryId = c.id }) }
    }
}

private fun fmtTime(sec: Float): String {
    val m = (sec / 60).toInt(); val s = sec - m * 60
    return if (m > 0) String.format("%d:%04.1f", m, s) else String.format("%.1f s", s)
}

/** Full-screen editor that turns the downloaded audio into several pads with cuts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitEditor(f: LinkFlowState, onToast: (String) -> Unit) {
    val st = f.staged ?: return
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val zones = f.zones
    val zonesNow by rememberUpdatedState(zones)
    var zoom by remember { mutableFloatStateOf(1f) }
    var viewStart by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableIntStateOf(-1) }
    var playhead by remember { mutableFloatStateOf(-1f) }
    var dragging by remember { mutableIntStateOf(-1) }
    val durSec = st.durationMs / 1000f
    val title = f.info?.title ?: ""
    val zoomNow by rememberUpdatedState(zoom)
    val startNow by rememberUpdatedState(viewStart)
    fun span() = 1f / zoom

    LaunchedEffect(Unit) {
        while (true) {
            val on = NativeEngine.isPlaying(PadMaker.TEMP_ID)
            if (!on && playing >= 0) { playing = -1; playhead = -1f }
            if (on) playhead = NativeEngine.progress(PadMaker.TEMP_ID)
            delay(33)
        }
    }
    fun play(i: Int) {
        if (playing == i) { NativeEngine.stopSound(PadMaker.TEMP_ID); playing = -1; return }
        NativeEngine.stopAll()
        NativeEngine.play(PadMaker.TEMP_ID, 0, 1f, zones[i].start, zones[i].end)
        playing = i
    }
    fun setZones(z: List<Zone>) { f.zones = z; if (playing >= z.size) playing = -1 }

    Box(Modifier.fillMaxSize().background(p.scrim)) {
        GlassPanel(backdrop, Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(10.dp), radius = 28.dp, strong = true) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        AText(stringResource(R.string.split_title), size = 20.sp, weight = FontWeight.SemiBold)
                        AText(title, size = 12.sp, color = p.muted, maxLines = 1)
                    }
                    GlassAction(backdrop, { NativeEngine.stopSound(PadMaker.TEMP_ID); f.close(ctx) }, icon = Ico.Close)
                }
                // Waveform with cuts
                val barsN = 160
                val bars = remember(zoom, viewStart) { WaveformCache.resample(st.peaks, barsN, viewStart, (viewStart + span()).coerceAtMost(1f)) }
                val hitPx = with(density) { 28.dp.toPx() }
                Box(
                    Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(0.18f))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { o ->
                                val x = startNow + o.x / size.width * (1f / zoomNow)
                                val near = zonesNow.dropLast(1).any { abs((it.end - startNow) / (1f / zoomNow) * size.width - o.x) < hitPx }
                                if (!near) setZones(PadMaker.addCut(zonesNow, x.coerceIn(0f, 1f)))
                            })
                        }
                        .pointerInput(Unit) {
                            var pan = false
                            detectDragGestures(
                                onDragStart = { o ->
                                    val idx = zonesNow.dropLast(1).indices.minByOrNull { abs((zonesNow[it].end - startNow) / (1f / zoomNow) * size.width - o.x) }
                                    val close = idx != null && abs((zonesNow[idx].end - startNow) / (1f / zoomNow) * size.width - o.x) < hitPx
                                    dragging = if (close) idx!! else -1; pan = !close
                                },
                                onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 },
                            ) { change, drag ->
                                change.consume()
                                val span = 1f / zoomNow
                                if (pan) viewStart = (startNow - drag.x / size.width * span).coerceIn(0f, (1f - span).coerceAtLeast(0f))
                                else if (dragging >= 0) setZones(PadMaker.moveCut(zonesNow, dragging, startNow + change.position.x / size.width * span))
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width / barsN
                        val sp = span()
                        for (b in 0 until barsN) {
                            val fr = viewStart + (b + 0.5f) / barsN * sp
                            val z = zones.firstOrNull { fr >= it.start && fr < it.end }
                            val h = (bars[b].coerceIn(0f, 1f) * size.height * 0.92f).coerceAtLeast(2f)
                            drawRoundRect(if (z?.on == false) p.muted.copy(0.35f) else p.accent, Offset(b * w + w * 0.15f, (size.height - h) / 2f), Size(w * 0.7f, h), CornerRadius(w * 0.3f))
                        }
                        zones.dropLast(1).forEachIndexed { i, z ->
                            val x = (z.end - viewStart) / sp * size.width
                            if (x in 0f..size.width) {
                                drawLine(p.text, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (i == dragging) 5f else 3f)
                                drawCircle(p.text, if (i == dragging) 13f else 9f, Offset(x, 14f))
                            }
                        }
                        if (playhead >= 0f) {
                            val x = (playhead - viewStart) / sp * size.width
                            if (x in 0f..size.width) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AText(fmtTime(viewStart * durSec) + " – " + fmtTime(((viewStart + span()).coerceAtMost(1f)) * durSec), Modifier.weight(1f), size = 12.sp, mono = true, color = p.muted)
                    GlassAction(backdrop, { zoom = (zoom / 2f).coerceAtLeast(1f); viewStart = viewStart.coerceIn(0f, (1f - span()).coerceAtLeast(0f)) }, icon = Ico.Minus)
                    GlassAction(backdrop, { val c = viewStart + span() / 2; zoom = (zoom * 2f).coerceAtMost(256f); viewStart = (c - span() / 2).coerceIn(0f, (1f - span()).coerceAtLeast(0f)) }, icon = Ico.Plus)
                }
                AText(stringResource(R.string.split_hint), size = 12.sp, color = p.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(backdrop, stringResource(R.string.split_detect), false, { NativeEngine.stopSound(PadMaker.TEMP_ID); f.zones = PadMaker.detectSilences(st.peaks, st.durationMs) })
                    val ch = f.info?.chapters ?: emptyList()
                    if (ch.size > 1) GlassChip(backdrop, stringResource(R.string.split_chapters), false, { NativeEngine.stopSound(PadMaker.TEMP_ID); f.zones = PadMaker.fromChapters(ch, durSec) })
                    GlassChip(backdrop, stringResource(R.string.split_reset), false, { NativeEngine.stopSound(PadMaker.TEMP_ID); f.zones = listOf(Zone(0f, 1f)) })
                }
                // Zones
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    zones.forEachIndexed { i, z ->
                        GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 18.dp) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AText("${i + 1}", Modifier.size(26.dp).background(if (z.on) p.accent else p.muted.copy(0.4f), RoundedCornerShape(13.dp)).padding(top = 3.dp), size = 13.sp, color = p.onAccent, weight = FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Center)
                                    BasicTextField(z.name, { n -> setZones(zones.mapIndexed { k, v -> if (k == i) v.copy(name = n) else v }) }, Modifier.weight(1f), singleLine = true,
                                        textStyle = TextStyle(if (z.on) p.text else p.muted, 15.sp), cursorBrush = SolidColor(p.accent),
                                        decorationBox = { inner -> Box { if (z.name.isEmpty()) AText(stringResource(R.string.split_name_hint), color = p.muted, size = 15.sp); inner() } })
                                    LiquidToggle({ z.on }, { v -> setZones(zones.mapIndexed { k, q -> if (k == i) q.copy(on = v) else q }) }, backdrop)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AText(fmtTime(z.start * durSec) + " → " + fmtTime(z.end * durSec) + "  (" + String.format("%.1f s", (z.end - z.start) * durSec) + ")", Modifier.weight(1f), size = 12.sp, mono = true, color = p.muted, maxLines = 1)
                                    GlassAction(backdrop, { play(i) }, icon = if (playing == i) Ico.Stop else Ico.Play)
                                    if (zones.size > 1) GlassAction(backdrop, { setZones(PadMaker.mergeZone(zones, i)) }, icon = Ico.Trash, danger = true)
                                }
                            }
                        }
                    }
                }
                CategoryPicker(f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AText(stringResource(R.string.split_keep_full), Modifier.weight(1f), size = 14.sp)
                    LiquidToggle({ f.keepFull }, { f.keepFull = it }, backdrop)
                }
                val n = zones.count { it.on }
                GlassAction(
                    backdrop, {
                        if (n == 0) onToast(ctx.getString(R.string.split_none))
                        else { NativeEngine.stopSound(PadMaker.TEMP_ID); f.save(ctx) { made -> onToast(ctx.getString(R.string.split_created, made)) } }
                    },
                    Modifier.fillMaxWidth(), icon = Ico.Check,
                    label = if (n == 1) stringResource(R.string.split_create_one) else stringResource(R.string.split_create, n), accent = n > 0,
                )
            }
        }
    }
}
