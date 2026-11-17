package app.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gallery.data.model.MediaItem
import app.gallery.ui.actions.MediaActions
import app.gallery.ui.theme.G
import app.gallery.ui.theme.Sym

/** Top of selection mode: "○ 3" pill (tap = select all) and a "Cancel" pill. */
@Composable
fun SelectionTopBar(selection: SelectionState, allKeys: List<String>, modifier: Modifier = Modifier) {
    val all = allKeys.isNotEmpty() && selection.keys.size == allKeys.size
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.shadow(8.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x18000000), spotColor = Color(0x22000000))
                .clip(RoundedCornerShape(30.dp)).background(G.colors.pill)
                .clickable { selection.set(if (all) emptySet() else allKeys.toSet()) }
                .height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectMark(all, dark = true)
            HGap(12)
            Text(if (selection.keys.isEmpty()) "Select items" else "${selection.keys.size}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
        }
        Box(Modifier.weight(1f))
        TextPill("Cancel") { selection.clear() }
    }
}

/** Bottom of selection mode: Share · Delete · Album · More. */
@Composable
fun SelectionBottomBar(selected: List<MediaItem>, actions: MediaActions, modifier: Modifier = Modifier) {
    val any = selected.isNotEmpty()
    FloatingActionPill(modifier) {
        PillAction(Sym.Share, "Share", any) { actions.share(selected) }
        PillAction(Sym.Trash, "Delete", any) { actions.delete(selected) }
        PillAction(Sym.AddPhoto, "Album", any) { actions.addToAlbum(selected) }
        PillAction(Sym.MoreVert, "More", any) { actions.more(selected) }
    }
}
