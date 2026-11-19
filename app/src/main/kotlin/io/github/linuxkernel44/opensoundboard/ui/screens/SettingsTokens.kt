package io.github.linuxkernel44.opensoundboard.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.download.CookieRow
import io.github.linuxkernel44.opensoundboard.data.Settings
import io.github.linuxkernel44.opensoundboard.download.LinkDownloader
import io.github.linuxkernel44.opensoundboard.download.PoTokenAuto
import io.github.linuxkernel44.opensoundboard.download.TokenException
import io.github.linuxkernel44.opensoundboard.download.TokenStore
import io.github.linuxkernel44.opensoundboard.ui.GlassDialog
import io.github.linuxkernel44.opensoundboard.ui.GlassField
import io.github.linuxkernel44.opensoundboard.ui.KeyValue
import io.github.linuxkernel44.opensoundboard.ui.LocalBackdrop
import io.github.linuxkernel44.opensoundboard.ui.LocalToast
import io.github.linuxkernel44.opensoundboard.ui.Section
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.glass.Segmented
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A cookie file waiting for its label and scope; the dialog is drawn above everything by the app root. */
object CookieImport {
    var text by mutableStateOf<String?>(null)
    var label by mutableStateOf("")
    var global by mutableStateOf(false)
    var error by mutableStateOf(false)
    fun start(text: String, name: String) { this.text = text; label = name.substringBeforeLast('.'); global = false; error = false }
    fun clear() { text = null; error = false }
}

@Composable
fun CookieImportDialog() {
    val t = CookieImport.text ?: return
    val backdrop = LocalBackdrop.current
    val p = LocalAurora.current
    val toast = LocalToast.current
    val ok = stringResource(R.string.tok_imported)
    GlassDialog({ CookieImport.clear() }, stringResource(R.string.tok_import_file)) {
        GlassField(CookieImport.label, { CookieImport.label = it }, stringResource(R.string.tok_label_hint))
        Segmented(backdrop, listOf(stringResource(R.string.tok_scope_site), stringResource(R.string.tok_scope_global)), if (CookieImport.global) 1 else 0, { CookieImport.global = it == 1 })
        AText(stringResource(if (CookieImport.global) R.string.tok_scope_global_hint else R.string.tok_scope_site_hint), size = 12.sp, color = p.muted)
        if (CookieImport.error) AText(stringResource(R.string.tok_invalid), size = 13.sp, color = p.danger)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassAction(backdrop, { CookieImport.clear() }, Modifier.weight(1f), label = stringResource(R.string.cancel))
            GlassAction(backdrop, {
                try { TokenStore.addCookies(CookieImport.label, t, CookieImport.global); CookieImport.clear(); toast(ok) }
                catch (e: TokenException) { CookieImport.error = true }
            }, Modifier.weight(1f), label = stringResource(R.string.tok_import), accent = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkDownloadsSection() {
    val ctx = LocalContext.current
    val p = LocalAurora.current
    val backdrop = LocalBackdrop.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<CookieRow>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    var poStatus by remember { mutableStateOf("") }
    var po by remember { mutableStateOf(TokenStore.poToken) }
    var visitor by remember { mutableStateOf(TokenStore.visitorData) }
    LaunchedEffect(Unit) { version = withContext(Dispatchers.IO) { LinkDownloader.versionLabel(ctx) } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) } }.getOrNull() }
            if (text != null) CookieImport.start(text, uri.lastPathSegment?.substringAfterLast('/') ?: "cookies.txt")
        }
    }

    Section(stringResource(R.string.tok_title)) {
        GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeyValue(stringResource(R.string.tok_ytdlp), version.ifEmpty { "—" })
                val done = stringResource(R.string.tok_updated, "%s")
                val fail = stringResource(R.string.tok_update_failed)
                GlassAction(backdrop, {
                    if (!updating) { updating = true; scope.launch {
                        val v = withContext(Dispatchers.IO) { LinkDownloader.updateYtDlp(ctx) }
                        updating = false
                        if (v != null) { version = v; toast(done.replace("%s", v)) } else toast(fail)
                    } }
                }, Modifier.fillMaxWidth(), icon = Ico.Download, label = stringResource(if (updating) R.string.tok_updating else R.string.tok_update))
            }
        }
        GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AText(stringResource(R.string.tok_cookies), size = 16.sp, weight = FontWeight.Medium)
                AText(stringResource(R.string.tok_cookies_hint), size = 12.sp, color = p.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassAction(backdrop, { pick.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }, icon = Ico.Upload, label = stringResource(R.string.tok_import_file))
                    GlassAction(backdrop, { CookieImport.start(clipboardText(ctx), "cookies") }, icon = Ico.Link, label = stringResource(R.string.tok_import_paste))
                }
                if (TokenStore.sets.isEmpty()) AText(stringResource(R.string.tok_none), size = 13.sp, color = p.muted)
                TokenStore.sets.forEach { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                AText(s.label, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                                AText(stringResource(R.string.tok_cookie_count, s.count, s.domains.take(4).joinToString(", ") + if (s.domains.size > 4) " +${s.domains.size - 4}" else ""), size = 12.sp, color = p.muted, maxLines = 2)
                            }
                            AText(stringResource(if (s.global) R.string.tok_scope_global else R.string.tok_scope_site).uppercase(), size = 10.sp, color = p.muted, weight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassAction(backdrop, { if (open == s.id) open = null else scope.launch { rows = withContext(Dispatchers.IO) { runCatching { TokenStore.preview(s.id) }.getOrDefault(emptyList()) }; open = s.id } }, label = stringResource(if (open == s.id) R.string.tok_hide else R.string.tok_preview))
                            GlassAction(backdrop, { TokenStore.removeCookies(s.id); if (open == s.id) open = null }, icon = Ico.Trash, label = stringResource(R.string.delete), danger = true)
                        }
                        if (open == s.id) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            rows.take(40).forEach { AText("${it.domain} · ${it.name} = ${it.masked}", size = 11.sp, color = p.muted, mono = true, maxLines = 1) }
                        }
                    }
                }
            }
        }
        GlassPanel(backdrop, Modifier.fillMaxWidth(), radius = 22.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AText(stringResource(R.string.tok_po), size = 16.sp, weight = FontWeight.Medium)
                AText(stringResource(R.string.tok_po_hint), size = 12.sp, color = p.muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        AText(stringResource(R.string.tok_po_auto), size = 15.sp)
                        AText(stringResource(R.string.tok_po_auto_hint), size = 12.sp, color = p.muted)
                    }
                    io.github.linuxkernel44.opensoundboard.ui.glass.LiquidToggle({ Settings.autoPoToken }, { Settings.autoPoToken = it; Settings.save() }, backdrop)
                }
                GlassAction(backdrop, {
                    if (!testing) { testing = true; poStatus = ""; scope.launch {
                        poStatus = withContext(Dispatchers.IO) {
                            runCatching { PoTokenAuto.init(ctx); PoTokenAuto.generate() }.fold(
                                { (id, t) -> ctx.getString(R.string.tok_po_ok, t.take(8), (PoTokenAuto.secondsLeft() / 3600).toString()) + if (id.loggedIn) "" else "  (anonymous)" },
                                { ctx.getString(R.string.tok_po_fail, it.message ?: it.javaClass.simpleName) })
                        }
                        testing = false
                    } }
                }, Modifier.fillMaxWidth(), icon = Ico.Check, label = stringResource(if (testing) R.string.tok_po_testing else R.string.tok_po_test))
                if (poStatus.isNotEmpty()) AText(poStatus, size = 12.sp, color = p.muted)
                GlassField(po, { po = it }, stringResource(R.string.tok_po_field))
                GlassField(visitor, { visitor = it }, stringResource(R.string.tok_visitor_field))
                val saved = stringResource(R.string.tok_saved)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassAction(backdrop, { TokenStore.saveYoutube(po, visitor); toast(saved) }, Modifier.weight(1f), icon = Ico.Check, label = stringResource(R.string.save), accent = true)
                    GlassAction(backdrop, { po = ""; visitor = ""; TokenStore.saveYoutube("", "") }, Modifier.weight(1f), icon = Ico.Close, label = stringResource(R.string.tok_clear))
                }
            }
        }
    }
}
