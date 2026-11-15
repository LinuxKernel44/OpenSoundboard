package io.github.linuxkernel44.opensoundboard.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.linuxkernel44.opensoundboard.R
import io.github.linuxkernel44.opensoundboard.data.Category
import io.github.linuxkernel44.opensoundboard.data.Library
import io.github.linuxkernel44.opensoundboard.ui.EmptyState
import io.github.linuxkernel44.opensoundboard.ui.LocalBackdrop
import io.github.linuxkernel44.opensoundboard.ui.NameDialog
import io.github.linuxkernel44.opensoundboard.ui.ScreenTitle
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassAction
import io.github.linuxkernel44.opensoundboard.ui.glass.GlassPanel
import io.github.linuxkernel44.opensoundboard.ui.theme.AText
import io.github.linuxkernel44.opensoundboard.ui.theme.Ico
import io.github.linuxkernel44.opensoundboard.ui.theme.LocalAurora

@Composable
fun CategoriesScreen(onOpen: (String) -> Unit, bottomInset: Dp) {
    val backdrop = LocalBackdrop.current
    val p = LocalAurora.current
    val data = Library.data
    var dialog by remember { mutableStateOf<Category?>(null) }   // id "" = new
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.categories_title)) {
            GlassAction(backdrop, { dialog = Category("", "") }, icon = Ico.Plus, label = stringResource(R.string.new_category))
        }
        if (data.categories.isEmpty()) EmptyState(stringResource(R.string.categories_title), stringResource(R.string.empty_categories_body))
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, bottomInset + 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(data.categories, key = { it.id }) { c ->
                val count = data.sounds.count { it.categoryId == c.id }
                GlassPanel(backdrop, Modifier.fillMaxWidth().clickable { onOpen(c.id) }, radius = 22.dp) {
                    Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            AText(c.name, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
                            AText(stringResource(R.string.sounds_count, count), size = 13.sp, color = p.muted)
                        }
                        GlassAction(backdrop, { dialog = c }, icon = Ico.Edit)
                        GlassAction(backdrop, { Library.deleteCategory(c.id) }, icon = Ico.Trash, danger = true)
                    }
                }
            }
        }
    }
    dialog?.let { c ->
        NameDialog(stringResource(if (c.id.isEmpty()) R.string.new_category else R.string.rename), c.name, { dialog = null }) { n ->
            if (c.id.isEmpty()) Library.addCategory(n) else Library.renameCategory(c.id, n)
            dialog = null
        }
    }
}
