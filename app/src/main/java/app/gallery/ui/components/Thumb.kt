package app.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gallery.data.model.MediaItem
import app.gallery.ui.LocalContainer
import app.gallery.ui.sharedMedia
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

private val overlayText = TextStyle(fontFamily = app.gallery.ui.theme.AppFont, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium, shadow = Shadow(Color(0x88000000), blurRadius = 4f))

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Square grid cell with video duration, cloud badges and selection marks. */
@Composable
fun MediaThumb(
    item: MediaItem,
    sizeDp: Dp,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    notBackedUp: Boolean = false,
    compact: Boolean = false,
    onExpand: (() -> Unit)? = null,
    sharedKey: String? = null,
) {
    val images = LocalContainer.current.images
    val px = with(LocalDensity.current) { sizeDp.toPx() }.toInt()
    val context = LocalContext.current
    val model = remember(item.key, item.remoteEtag, px) {
        val size = if (px <= 300) 256 else 512
        ImageRequest.Builder(context).data(images.thumb(item, size))
            .apply { if (size == 256) memoryCacheKey(images.thumbCacheKey(item)) }
            .build()
    }
    Box(modifier.background(Color(0x14888888))) {
        // Only the picture takes part in the grid <-> viewer animation, not the badges and labels on top.
        AsyncImage(model = model, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().let { if (sharedKey != null) it.sharedMedia(sharedKey) else it })
        if (!compact) {
            if (item.isVideo) {
                Row(Modifier.align(Alignment.BottomStart).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp), tint = Color.White)
                    Text(formatDuration(item.durationMs), style = overlayText)
                }
            } else if (item.isGif) {
                Text("GIF", style = overlayText, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            when {
                item.isRemoteOnly -> Icon(Icons.Rounded.Cloud, "On Nextcloud", Modifier.align(Alignment.TopEnd).padding(5.dp).size(15.dp), tint = Color.White.copy(alpha = 0.92f))
                notBackedUp -> Icon(Icons.Rounded.CloudOff, "Not backed up", Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp), tint = Color.White.copy(alpha = 0.85f))
            }
        } else if (item.isRemoteOnly) {
            Icon(Icons.Rounded.Cloud, null, Modifier.align(Alignment.TopEnd).padding(2.dp).size(10.dp), tint = Color.White.copy(alpha = 0.9f))
        }
        if (selecting) {
            if (selected) Box(Modifier.fillMaxSize().background(Color(0x22000000)))
            SelectMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp), small = compact)
            if (onExpand != null && !compact) {
                Icon(Icons.Rounded.OpenInFull, "Open", Modifier.align(Alignment.BottomEnd).padding(5.dp).size(18.dp), tint = Color.White)
            }
        }
    }
}

@Composable
fun SelectMark(checked: Boolean, modifier: Modifier = Modifier, small: Boolean = false, dark: Boolean = false) {
    val s = if (small) 16.dp else 22.dp
    if (checked) {
        Box(modifier.size(s).clip(CircleShape).background(Color(0xFF3E7BF6)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, Modifier.size(s - 6.dp), tint = Color.White)
        }
    } else {
        val c = if (dark) Color(0xFF8E8E93) else Color.White
        Box(modifier.size(s).clip(CircleShape).background(Color(0x33000000)).border(1.6.dp, c, CircleShape))
    }
}

/** Rounded image tile used by albums, people and places rows. */
@Composable
fun CoverImage(model: Any?, modifier: Modifier = Modifier, radius: Dp = 18.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Color(0x22888888))) {
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
