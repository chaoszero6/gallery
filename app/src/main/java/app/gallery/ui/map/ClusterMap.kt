package app.gallery.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gallery.data.nextcloud.MapClusterDto
import app.gallery.data.nextcloud.MemoriesApi
import app.gallery.data.nextcloud.ServerConfig
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.pow

/** Raster style for the tile server chosen in Memories (default OpenStreetMap). */
fun rasterStyle(cfg: ServerConfig?): String {
    val c = cfg ?: ServerConfig.Default
    val url = c.tileUrl.replace("{s}", "a")
    return JSONObject().apply {
        put("version", 8)
        put("sources", JSONObject().put("tiles", JSONObject().apply {
            put("type", "raster")
            put("tiles", JSONArray().put(url))
            put("tileSize", 256)
            put("maxzoom", 19)
            put("attribution", c.tileAttribution)
        }))
        put("layers", JSONArray().put(JSONObject().put("id", "tiles").put("type", "raster").put("source", "tiles")))
    }.toString()
}

/** Visible map area. */
data class Bounds(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

/** Area covered by one Memories cluster at a zoom level (grid cell around its centre). */
fun clusterBounds(c: MapClusterDto, zoom: Int, density: Int): Bounds {
    val cell = 180.0 / (2.0.pow(zoom) * density)
    val lat = c.center[0]; val lon = c.center[1]
    return Bounds(lat - cell, lat + cell, lon - cell, lon + cell)
}

/**
 * MapLibre map showing Memories photo clusters as Samsung-style thumbnail pins
 * (white frame, count, little tail; the selected one framed in blue).
 */
@Composable
fun ClusterMap(
    api: MemoriesApi,
    config: ServerConfig?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    initial: Pair<Double, Double>? = null,
    initialZoom: Double = 11.0,
    selectedId: Long? = null,
    onClusterClick: (MapClusterDto, zoom: Int) -> Unit = { _, _ -> },
    onMapClick: () -> Unit = {},
    onReady: (MapLibreMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var clusters by remember { mutableStateOf<List<MapClusterDto>>(emptyList()) }
    var zoomLevel by remember { mutableStateOf(initialZoom.toInt()) }
    var idleTick by remember { mutableStateOf(0) }
    val onClick by rememberUpdatedState(onClusterClick)
    val onBg by rememberUpdatedState(onMapClick)

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    LaunchedEffect(config?.tileUrl) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                setAllGesturesEnabled(interactive)
                isRotateGesturesEnabled = false
            }
            val start = initial ?: (20.0 to 0.0)
            m.cameraPosition = CameraPosition.Builder().target(LatLng(start.first, start.second)).zoom(if (initial == null) 1.5 else initialZoom).build()
            m.setStyle(Style.Builder().fromJson(rasterStyle(config))) { s ->
                s.addSource(GeoJsonSource(SRC))
                s.addLayer(SymbolLayer(LAYER, SRC).withProperties(
                    PropertyFactory.iconImage(Expression.get("img")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconAnchor("bottom"),
                    PropertyFactory.symbolSortKey(Expression.get("count")),
                ))
                style = s
            }
            m.addOnCameraIdleListener { idleTick++ }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                val f = m.queryRenderedFeatures(pt, LAYER).firstOrNull()
                val id = f?.getNumberProperty("id")?.toLong()
                val c = clusters.firstOrNull { it.id == id }
                if (c != null) onClick(c, zoomLevel) else onBg()
                true
            }
            onReady(m)
        }
    }

    // Reload clusters whenever the camera stops moving.
    LaunchedEffect(idleTick, style) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val vr = m.projection.visibleRegion.latLngBounds
        val padLat = (vr.latitudeNorth - vr.latitudeSouth) * 0.25
        val padLon = (vr.longitudeEast - vr.longitudeWest) * 0.25
        val z = m.cameraPosition.zoom.toInt().coerceIn(0, 20)
        zoomLevel = z
        clusters = runCatching {
            api.mapClusters(
                (vr.latitudeSouth - padLat).coerceAtLeast(-90.0), (vr.latitudeNorth + padLat).coerceAtMost(90.0),
                (vr.longitudeWest - padLon).coerceAtLeast(-180.0), (vr.longitudeEast + padLon).coerceAtMost(180.0), z,
            )
        }.getOrDefault(clusters)
    }

    // Draw pins (thumbnails loaded through Coil, so they share the image cache).
    LaunchedEffect(clusters, style, selectedId) {
        val s = style ?: return@LaunchedEffect
        val features = ArrayList<Feature>()
        for (c in clusters) {
            val name = "c${c.id}-${c.preview?.fileid}-${c.count}-${c.id == selectedId}"
            if (s.getImage(name) == null) {
                val thumb = c.preview?.let { p -> loadBitmap(context, api.previewUrl(p.fileid, 256, true, p.etag)) }
                s.addImage(name, pinBitmap(context, thumb, c.count, c.id == selectedId))
            }
            features += Feature.fromGeometry(Point.fromLngLat(c.center[1], c.center[0])).apply {
                addStringProperty("img", name)
                addNumberProperty("id", c.id)
                addNumberProperty("count", c.count)
            }
        }
        (s.getSource(SRC) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val SRC = "clusters"
private const val LAYER = "clusters-layer"

private suspend fun loadBitmap(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
    val r = context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).size(160).build())
    (r as? SuccessResult)?.image?.toBitmap()
}

/** Thumbnail pin: rounded photo in a white (or blue) frame with the count and a tail. */
private fun pinBitmap(context: Context, thumb: Bitmap?, count: Int, selected: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (54 * d).toInt()
    val tail = (7 * d)
    val border = 2.5f * d
    val bmp = Bitmap.createBitmap(size, (size + tail).toInt(), Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = 9 * d
    p.color = if (selected) 0xFF3E7BF6.toInt() else 0xFFFFFFFF.toInt()
    p.setShadowLayer(3 * d, 0f, d, 0x55000000)
    cv.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, p)
    val path = Path().apply {
        moveTo(size / 2f - tail, size.toFloat() - 1); lineTo(size / 2f, size + tail - 1); lineTo(size / 2f + tail, size.toFloat() - 1); close()
    }
    cv.drawPath(path, p)
    p.clearShadowLayer()
    val inner = RectF(border, border, size - border, size - border)
    if (thumb != null) {
        val save = cv.save()
        cv.clipPath(Path().apply { addRoundRect(inner, r - border, r - border, Path.Direction.CW) })
        cv.drawBitmap(thumb, null, inner, Paint(Paint.FILTER_BITMAP_FLAG))
        cv.restoreToCount(save)
    } else {
        p.color = 0xFFCCCCCC.toInt(); cv.drawRoundRect(inner, r - border, r - border, p)
    }
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3 * d, 0f, 0f, 0xAA000000.toInt()); textAlign = Paint.Align.CENTER
    }
    val label = if (count >= 1000) "${count / 1000}k" else count.toString()
    cv.drawText(label, size / 2f, size / 2f + 5 * d, t)
    return bmp
}
