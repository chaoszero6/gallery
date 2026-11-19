package app.gallery.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gallery.ui.theme.G
import app.gallery.ui.theme.Sym

/** One tab of the floating bottom bar. */
/** [selectedIcon] is the filled version shown on the selected tab (One UI style). */
data class PillTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon, val badge: Boolean = false)

/**
 * The floating rounded bottom bar (icon over label, selected tab on a grey lozenge),
 * also used for selection actions.
 */
@Composable
fun FloatingPillBar(
    tabs: List<PillTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(horizontal = 28.dp) // One UI's bar stops ~28 dp from the screen edges
            .shadow(14.dp, RoundedCornerShape(40.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .clip(RoundedCornerShape(40.dp))
            .background(G.colors.pill)
            .height(G.pillHeight)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, t ->
            val bg by animateColorAsState(if (i == selected) G.colors.pillSelected else Color.Transparent, label = "tab")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(30.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Icon(if (i == selected) t.selectedIcon else t.icon, null, Modifier.size(24.dp), tint = G.colors.text)
                        if (t.badge) Box(Modifier.align(Alignment.TopEnd).padding(start = 18.dp).size(6.dp).clip(CircleShape).background(Color(0xFFFF6A3D)))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.label, fontSize = 10.sp, color = G.colors.text, maxLines = 1,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Action bar of the same shape as the tab bar (selection mode, viewer). */
@Composable
fun FloatingActionPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .padding(horizontal = 28.dp) // One UI's bar stops ~28 dp from the screen edges
            .shadow(14.dp, RoundedCornerShape(40.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .clip(RoundedCornerShape(40.dp))
            .background(G.colors.pill)
            .height(G.pillHeight)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun RowScope.PillAction(icon: ImageVector, label: String?, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(30.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val c = (tint ?: G.colors.text).copy(alpha = if (enabled) 1f else 0.35f)
        Icon(icon, label, Modifier.size(24.dp), tint = c)
        if (label != null) {
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, color = c, maxLines = 1)
        }
    }
}

/**
 * Icon buttons at the top right. On the main tabs One UI draws them straight on the page
 * ([plain]); over photos and maps they sit on a rounded pill.
 */
@Composable
fun TopIconPill(modifier: Modifier = Modifier, plain: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        if (plain) modifier.height(52.dp).padding(horizontal = 2.dp)
        else modifier
            .shadow(8.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x18000000), spotColor = Color(0x22000000))
            .clip(RoundedCornerShape(30.dp))
            .background(G.colors.pill)
            .height(52.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun PillIcon(icon: ImageVector, description: String, tint: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(24.dp), tint = tint ?: G.colors.text) }
}

/** Round floating back button. */
@Composable
fun CircleBackButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .shadow(8.dp, CircleShape, ambientColor = Color(0x18000000), spotColor = Color(0x22000000))
            .clip(CircleShape)
            .background(G.colors.pill)
            .size(52.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Sym.Back, "Back", Modifier.size(24.dp), tint = G.colors.text) }
}

/** White pill with text (e.g. "Cancel", "Get started"). */
@Composable
fun TextPill(text: String, modifier: Modifier = Modifier, dark: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .shadow(if (dark) 0.dp else 8.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x18000000), spotColor = Color(0x22000000))
            .clip(RoundedCornerShape(30.dp))
            .background(if (dark) Color(0xFF3A3A3C) else G.colors.pill)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = if (dark) Color.White else G.colors.text) }
}

/** Section title with a chevron on the right ("People  >"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, size: androidx.compose.ui.unit.TextUnit = 16.sp, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = size, fontWeight = FontWeight.SemiBold, color = G.colors.text, modifier = Modifier.weight(1f))
        when {
            action != null -> Text(action, fontSize = 14.5.sp, color = G.colors.accent, fontWeight = FontWeight.Medium)
            onClick != null -> Icon(Sym.ChevronRight, null, Modifier.size(24.dp), tint = G.colors.text)
        }
    }
}

/** Dismissible card at the top of Pictures (here: connect Nextcloud). */
@Composable
fun TipCard(title: String, body: String, button: String, onButton: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(G.cardRadius))
            .background(G.colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF7FB2FF), Color(0xFFA7D1FF), Color(0xFFFFD9A8), Color(0xFFFFB199)))),
        ) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
                listOf(Color(0xFFFFFFFF), Color(0xFFE8F1FF), Color(0xFFFFF3E3)).forEachIndexed { i, c ->
                    Box(Modifier.padding(top = (i * 8).dp).size(width = 64.dp, height = 64.dp).shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(c))
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(32.dp).clip(CircleShape).background(Color(0xCCFFFFFF))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp), tint = Color(0xFF333333)) }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, color = G.colors.text.copy(alpha = 0.85f), lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextPill(button, dark = true, onClick = onButton) }
        }
    }
}

/** Rounded grouped card (settings style). */
@Composable
fun RoundedSection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(G.cardRadius))
            .background(G.colors.surface)
            .padding(vertical = 6.dp),
    ) { content() }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, fontSize = 13.sp, color = G.colors.textSecondary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 17.sp, color = G.colors.text, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 13.sp, color = G.colors.textSecondary, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int = 8) = Spacer(Modifier.width(w.dp))

/**
 * One UI's sub-screen app bar: a thin back arrow in a 60 dp slot, then the title in bold on the
 * same line (64 dp tall).
 */
@Composable
fun OneUiTopBar(title: String, modifier: Modifier = Modifier, showTitle: Boolean = true, onBack: () -> Unit) {
    val titleAlpha by androidx.compose.animation.core.animateFloatAsState(if (showTitle) 1f else 0f, label = "title")
    Row(modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(60.dp).height(64.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Sym.Back, "Back", Modifier.size(24.dp), tint = G.colors.text) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = G.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { alpha = titleAlpha })
    }
}

/** One UI's compact switch (32 x 20 dp): filled accent track when on, outlined grey when off. */
@Composable
fun OneUiSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val pos by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    val accent = G.colors.accent
    val off = Color(0xFF8E8E93)
    val alpha = if (enabled) 1f else 0.4f
    androidx.compose.foundation.Canvas(
        modifier
            .size(width = 32.dp, height = 20.dp)
            .let {
                if (onCheckedChange != null) it.toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange)
                else it.semantics { role = androidx.compose.ui.semantics.Role.Switch; toggleableState = androidx.compose.ui.state.ToggleableState(checked) }
            },
    ) {
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(h / 2)
        if (pos > 0f) drawRoundRect(accent.copy(alpha = pos * alpha), cornerRadius = corner)
        if (pos < 1f) drawRoundRect(
            off.copy(alpha = (1 - pos) * alpha), topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, h - stroke), cornerRadius = corner,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        val r = h / 2 - 3.dp.toPx()
        val cx = h / 2 + (size.width - h) * pos
        drawCircle(androidx.compose.ui.graphics.lerp(off, Color.White, pos).copy(alpha = alpha), r, androidx.compose.ui.geometry.Offset(cx, h / 2))
    }
}
