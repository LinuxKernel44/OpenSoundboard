package io.github.linuxkernel44.opensoundboard.ui

import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.audio.NativeEngine
import io.github.linuxkernel44.opensoundboard.audio.Player
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.glass.LiquidBottomTab
import io.github.linuxkernel44.opensoundboard.ui.glass.LiquidBottomTabs
import io.github.linuxkernel44.opensoundboard.ui.screens.CategoriesScreen
import io.github.linuxkernel44.opensoundboard.ui.screens.RecordScreen
import io.github.linuxkernel44.opensoundboard.ui.screens.SettingsScreen
import io.github.linuxkernel44.opensoundboard.ui.screens.SoundSheet
import io.github.linuxkernel44.opensoundboard.ui.screens.SoundsScreen
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.PhotoBackground
import io.github.linuxkernel44.opensoundboard.data.Settings
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.Icon
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora
import io.github.linuxkernel44.opensoundboard.ui.theme.auroraPalette
import kotlinx.coroutines.delay

@Composable
fun OpenSoundboardApp() {
    val palette = auroraPalette()
    val backdrop = rememberLayerBackdrop()
    val meters = remember { Meters() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var toastText by remember { mutableStateOf<String?>(null) }

    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(w, view)
        c.isAppearanceLightStatusBars = !palette.dark
        c.isAppearanceLightNavigationBars = !palette.dark
    }

    // Single polling loop for levels, spectrum, playing pads and the output route.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            val l = NativeEngine.levels()
            meters.peakL = l[0]; meters.peakR = l[1]; meters.rmsL = l[2]; meters.rmsR = l[3]
            meters.spectrum = NativeEngine.spectrum(32)
            Player.pollPlaying(Library.data.sounds.map { it.id })
            if (n++ % 30 == 0) Player.refreshOutput(Player.output.running)
            delay(33)
        }
    }
    BackHandler(enabled = editing != null || tab != 0) { if (editing != null) editing = null else tab = 0 }
    LaunchedEffect(toastText) { if (toastText != null) { delay(2600); toastText = null } }

    CompositionLocalProvider(LocalAurora provides palette, LocalBackdrop provides backdrop, LocalMeters provides meters, LocalToast provides { toastText = it }) {
        Box(Modifier.fillMaxSize()) {
            PhotoBackground(Settings.background, palette.dark, Modifier.layerBackdrop(backdrop))
            val bottomInset = 104.dp
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                Crossfade(tab, label = "tab") { t ->
                    when (t) {
                        0 -> SoundsScreen(false, category, { category = it }, { editing = it }, bottomInset + 24.dp)
                        1 -> CategoriesScreen({ category = it; tab = 0 }, bottomInset + 24.dp)
                        2 -> SoundsScreen(true, null, {}, { editing = it }, bottomInset + 24.dp)
                        3 -> RecordScreen(bottomInset + 24.dp)
                        else -> SettingsScreen(bottomInset + 24.dp)
                    }
                }
            }
            val tabs = listOf(Ico.Sounds to R.string.tab_sounds, Ico.Grid to R.string.tab_categories, Ico.Heart to R.string.tab_favorites, Ico.Record to R.string.tab_record, Ico.Sliders to R.string.tab_settings)
            LiquidBottomTabs(
                selectedTabIndex = { tab }, onTabSelected = { tab = it }, backdrop = backdrop, tabsCount = tabs.size,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                tabs.forEachIndexed { i, (ico, label) ->
                    LiquidBottomTab({ tab = i }) {
                        Icon(ico, tint = palette.text, size = 22.dp)
                        AText(stringResource(label), size = 10.sp, color = palette.text, maxLines = 1)
                    }
                }
            }
            toastText?.let { t ->
                GlassPanel(backdrop, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp, start = 24.dp, end = 24.dp), radius = 22.dp, strong = true) {
                    AText(t, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), size = 14.sp)
                }
            }
            editing?.let { id -> Box(Modifier.fillMaxSize().imePadding()) { SoundSheet(id) { editing = null } } }
        }
    }
}

