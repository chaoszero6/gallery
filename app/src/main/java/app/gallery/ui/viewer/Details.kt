package app.gallery.ui.viewer

import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import app.gallery.data.model.MediaItem
import app.gallery.data.nextcloud.ImageInfo
import app.gallery.data.nextcloud.ServerConfig
import app.gallery.ui.LocalContainer
import app.gallery.ui.actions.MediaActions
import app.gallery.ui.theme.G
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** What we know about one item, from the phone's EXIF and/or Memories. */
data class Details(
    val lat: Double? = null,
    val lon: Double? = null,
    val address: String? = null,
    val camera: String? = null,
    val exposure: String? = null,
    val remote: ImageInfo? = null,
)

private fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}

@Composable
private fun rememberDetails(item: MediaItem): Details {
    val c = LocalContainer.current
    val context = LocalContext.current
    val d by produceState(Details(), item.key, item.remoteEtag) {
        var out = Details()
        if (item.localUri != null && !item.isVideo) out = withContext(Dispatchers.IO) {
            runCatching {
                val uri = if (Build.VERSION.SDK_INT >= 29) runCatching { MediaStore.setRequireOriginal(item.localUri) }.getOrDefault(item.localUri) else item.localUri
                context.contentResolver.openInputStream(uri)!!.use { s ->
                    val e = ExifInterface(s)
                    val ll = e.latLong
                    val model = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL))
                        .distinct().joinToString(" ").takeIf { it.isNotBlank() }
                    val f = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/%.1f".format(it) }
                    val t = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { if (it < 1) "1/${(1 / it).toInt()}" else "%.1fs".format(it) }
                    val iso = e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }
                    val fl = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "%.1f mm".format(it) }
                    Details(ll?.getOrNull(0), ll?.getOrNull(1), null, model, listOfNotNull(f, t, iso, fl).joinToString("  |  ").ifBlank { null })
                }
            }.getOrDefault(Details())
        }
        value = out
        if (item.remoteFileId != null && c.accounts.account.value != null) {
            runCatching { c.api.info(item.remoteFileId) }.onSuccess { r ->
                value = out.copy(
                    lat = out.lat ?: r.lat, lon = out.lon ?: r.lon, address = r.address,
                    camera = out.camera ?: listOfNotNull(r.make, r.model).distinct().joinToString(" ").ifBlank { null },
                    exposure = out.exposure ?: listOfNotNull(r.fNumber?.let { "f/$it" }, r.exposure?.let { "${it}s" }, r.iso?.let { "ISO $it" }, r.focal?.let { "$it mm" }).joinToString("  |  ").ifBlank { null },
                    remote = r,
                )
                r.path?.let { p -> c.db.remote().setPath(item.remoteFileId, p) }
            }
        }
    }
    return d
}

/** Swipe-up details: date, file, camera, location map, people, albums, tags, backup state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsPanel(item: MediaItem, actions: MediaActions, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val account by c.accounts.account.collectAsState()
    val cfg by c.nextcloud.config.collectAsState()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val d = rememberDetails(item)
    val dt = Instant.ofEpochMilli(item.takenAtMs).atZone(ZoneId.systemDefault())
    Column(modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dt.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · h:mm a", locale)),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text, modifier = Modifier.weight(1f),
            )
            Text("Edit", color = G.colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { actions.editDate(listOf(item)) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, fontSize = 12.sp, color = G.colors.textSecondary)
        val path = item.relativePath?.let { "/Internal storage/$it" } ?: d.remote?.path?.substringBeforeLast('/')?.let { "Nextcloud: $it" }
        if (path != null) Text(path, fontSize = 12.sp, color = G.colors.textSecondary)

        Spacer(Modifier.height(18.dp))
        Text(if (item.isVideo) "Video info" else "Image info", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
        Spacer(Modifier.height(4.dp))
        val mp = if (item.width > 0 && item.height > 0) "${(item.width.toLong() * item.height / 1_000_000).coerceAtLeast(1)}MP" else null
        Text(
            listOfNotNull(fmtSize(item.size), if (item.width > 0) "${item.width}x${item.height}" else null, if (item.isVideo) null else mp).joinToString("  |  "),
            fontSize = 12.sp, color = G.colors.textSecondary,
        )
        d.camera?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
            d.exposure?.let { e -> Text(e, fontSize = 12.sp, color = G.colors.textSecondary) }
        }

        // Backup state (the new Nextcloud part).
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, text) = when {
                item.isLocal && item.isRemote -> Icons.Rounded.CloudDone to "On this phone and Nextcloud"
                item.isRemoteOnly -> Icons.Rounded.Cloud to "Only on Nextcloud"
                else -> Icons.Rounded.PhoneAndroid to "Only on this phone"
            }
            Icon(icon, null, Modifier.size(18.dp), tint = G.colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 12.sp, color = G.colors.textSecondary)
            if (item.isLocalOnly && account != null) {
                Spacer(Modifier.width(12.dp))
                Text("Back up", color = G.colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { actions.backUp(listOf(item)) })
            }
        }

        // Location
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Location", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text, modifier = Modifier.weight(1f))
            Text(if (d.lat != null) "Edit" else "Add", color = G.colors.accent, fontSize = 13.sp, modifier = Modifier.clickable { actions.editLocation(listOf(item)) })
        }
        if (d.lat != null && d.lon != null) {
            Text(d.address ?: "%.5f, %.5f".format(d.lat, d.lon), fontSize = 12.sp, color = G.colors.textSecondary)
            Spacer(Modifier.height(10.dp))
            MiniMap(d.lat, d.lon, cfg, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)))
        }

        val people = d.remote?.people.orEmpty()
        if (people.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("People", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { people.forEach { Chip(it) } }
        }
        val albums = d.remote?.albums.orEmpty()
        if (albums.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Albums", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = G.colors.text)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { albums.forEach { Chip(it) } }
        }
        Spacer(Modifier.height(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.remote?.tags?.values?.forEach { Chip("# $it") }
            if (item.isRemote && cfg?.tags == true) Chip("# Add tag") { actions.tag(listOf(item)) }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Chip(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text, fontSize = 12.sp, color = G.colors.text,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, G.colors.divider, RoundedCornerShape(20.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Static map: a 3x3 block of tiles positioned so the photo's spot is in the middle, with a pin. */
@Composable
fun MiniMap(lat: Double, lon: Double, cfg: ServerConfig?, modifier: Modifier = Modifier, zoom: Int = 15) {
    val url = (cfg ?: ServerConfig.Default).tileUrl.replace("{s}", "a")
    val n = 2.0.pow(zoom)
    val xf = (lon + 180) / 360 * n
    val latR = lat * PI / 180
    val yf = (1 - ln(tan(latR) + 1 / cos(latR)) / PI) / 2 * n
    val tx = floor(xf).toInt(); val ty = floor(yf).toInt()
    val fx = (xf - tx).toFloat(); val fy = (yf - ty).toFloat()
    BoxWithConstraints(modifier.background(G.colors.card)) {
        val t: Dp = 128.dp
        val ox = maxWidth / 2 - t * (1 + fx)
        val oy = maxHeight / 2 - t * (1 + fy)
        for (dx in -1..1) for (dy in -1..1) {
            val x = ((tx + dx) % n.toInt() + n.toInt()) % n.toInt()
            val tile = url.replace("{z}", zoom.toString()).replace("{x}", x.toString()).replace("{y}", (ty + dy).toString())
            AsyncImage(tile, null, contentScale = ContentScale.FillBounds, modifier = Modifier.offset(ox + t * (dx + 1), oy + t * (dy + 1)).size(t))
        }
        Icon(Icons.Rounded.Place, null, Modifier.align(Alignment.Center).offset(y = (-14).dp).size(34.dp), tint = Color(0xFFE5483D))
        Box(Modifier.align(Alignment.Center).size(6.dp).clip(CircleShape).background(Color(0xFFE5483D)))
    }
}
