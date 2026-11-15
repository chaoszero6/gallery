package app.gallery.data.nextcloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class DescribeDto(
    val version: String? = null,
    val baseUrl: String? = null,
    val loginFlowUrl: String? = null,
    val uid: String? = null,
)

@Serializable
data class DayDto(val dayid: Int, val count: Int)

@Serializable
data class PhotoDto(
    val fileid: Long,
    val dayid: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val etag: String = "",
    val indexed_at: Long = 0,
    val basename: String = "",
    val size: Long = 0,
    val epoch: Long = 0,
    val mimetype: String = "",
    val isvideo: Int = 0,
    val video_duration: Int = 0,
    val liveid: String? = null,
    val isfavorite: Int = 0,
    val dayid_real: Int? = null,
)

@Serializable
data class MapPreviewDto(val fileid: Long, val etag: String? = null, val w: Int = 0, val h: Int = 0)

@Serializable
data class MapClusterDto(
    val id: Long,
    val center: List<Double>,
    val count: Int,
    val preview: MapPreviewDto? = null,
)

@Serializable
data class LensResultDto(val fileid: Long, val score: Double = 0.0, val dayid: Int = 0, val etag: String? = null)

/** Feature flags and user settings from /api/config. */
data class ServerConfig(
    val version: String,
    val albums: Boolean,
    val tags: Boolean,
    val recognize: Boolean,
    val faceRecognition: Boolean,
    val places: Boolean,
    val transcoding: Boolean,
    val lens: Boolean,
    val tileUrl: String,
    val tileAttribution: String,
    val timelinePath: String,
    val onThisDayRange: Int,
    val onThisDayPerYear: Int,
    val mapDensity: Int = 1,
) {
    companion object {
        val Default = ServerConfig("", false, false, false, false, false, false, false,
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors", "", 3, 10, 1)

        fun parse(o: JsonObject): ServerConfig {
            val tileUrl = o.str("map_tile_server_url")?.takeIf { it.isNotBlank() } ?: Default.tileUrl
            var attribution = Default.tileAttribution
            (o["map_tile_servers"] as? JsonArray)?.forEach { s ->
                val so = s as? JsonObject ?: return@forEach
                if (so.str("url") == tileUrl) so.str("attribution")?.let { attribution = it.replace(Regex("<[^>]+>"), "") }
            }
            return ServerConfig(
                version = o.str("version") ?: "",
                albums = o.bool("albums_enabled"),
                tags = o.bool("systemtags_enabled"),
                recognize = o.bool("recognize_enabled"),
                faceRecognition = o.bool("facerecognition_enabled"),
                places = (o.num("places_gis") ?: 0.0) > 0,
                transcoding = !o.bool("vod_disable"),
                lens = o.bool("lens_enabled"),
                tileUrl = tileUrl,
                tileAttribution = attribution,
                timelinePath = o.str("timeline_path")?.takeIf { it != "_empty_" } ?: "",
                onThisDayRange = o.num("onthisday_day_range")?.toInt() ?: 3,
                onThisDayPerYear = o.num("onthisday_photos_per_year")?.toInt() ?: 10,
                mapDensity = (o.num("map_cluster_density")?.toInt() ?: 1).coerceIn(1, 16),
            )
        }
    }
}

/** An entry from /api/clusters/{backend}: an album, tag, place or person. */
data class Cluster(
    val backend: String,
    /** The value used in the days filter. */
    val clusterId: String,
    val name: String,
    val count: Int,
    val coverFileId: Long?,
    val coverEtag: String?,
    val user: String?,
    val numericId: Long?,
    val shared: Boolean = false,
    val created: Long = 0,
    val location: String? = null,
) {
    val isFace: Boolean get() = backend == "recognize" || backend == "facerecognition"
    /** Filter parameter to list this cluster's photos with /api/days. */
    val daysFilter: Pair<String, String>
        get() = when (backend) {
            "albums" -> "albums" to clusterId
            "tags" -> "tags" to clusterId
            "places" -> "places" to clusterId
            else -> backend to "${user ?: ""}/$clusterId"
        }

    companion object {
        fun parse(backend: String, o: JsonObject): Cluster? {
            val clusterId = o.str("cluster_id") ?: when (backend) {
                "albums" -> "${o.str("user")}/${o.str("name")}"
                "places" -> o.str("osm_id")
                "tags" -> o.str("name")
                else -> o.str("id")
            } ?: return null
            val name = when (backend) {
                "albums", "tags", "places" -> o.str("name") ?: clusterId
                else -> o.str("name")?.takeIf { it.isNotBlank() } ?: ""
            }
            val cover = o.long("cover")?.takeIf { it > 0 } ?: o.long("last_added_photo")?.takeIf { it > 0 }
            return Cluster(
                backend = backend,
                clusterId = clusterId,
                name = name,
                count = o.num("count")?.toInt() ?: 0,
                coverFileId = cover,
                coverEtag = o.str("cover_etag") ?: o.str("last_added_photo_etag"),
                user = o.str("user_id") ?: o.str("user"),
                numericId = o.long("id") ?: o.long("album_id") ?: o.long("osm_id"),
                shared = o.bool("shared"),
                created = o.long("created") ?: 0,
                location = o.str("location"),
            )
        }
    }
}

data class ImageInfo(
    val fileId: Long,
    val path: String?,
    val basename: String,
    val mime: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val epoch: Long?,
    val address: String?,
    val lat: Double?,
    val lon: Double?,
    val make: String?,
    val model: String?,
    val lens: String?,
    val fNumber: String?,
    val exposure: String?,
    val iso: String?,
    val focal: String?,
    val title: String?,
    val description: String?,
    val tz: String?,
    val hasDateTag: Boolean,
    val permissions: String,
    val tags: Map<Long, String>,
    val albums: List<String>,
    val people: List<String>,
    val owner: String?,
) {
    val canEdit: Boolean get() = 'U' in permissions
    val canDelete: Boolean get() = 'D' in permissions

    companion object {
        fun parse(o: JsonObject): ImageInfo {
            val exif = o["exif"] as? JsonObject ?: JsonObject(emptyMap())
            val tags = HashMap<Long, String>()
            when (val t = o["tags"]) {
                is JsonObject -> t.forEach { (k, v) -> k.toLongOrNull()?.let { tags[it] = v.jsonPrimitive.content } }
                is JsonArray -> t.forEachIndexed { i, v -> tags[i.toLong()] = v.jsonPrimitive.content }
                else -> {}
            }
            val clusters = o["clusters"] as? JsonObject
            fun clusterNames(b: String) = (clusters?.get(b) as? JsonArray)?.mapNotNull {
                (it as? JsonObject)?.let { c -> c.str("name")?.takeIf { n -> n.isNotBlank() } }
            } ?: emptyList()
            fun ex(k: String) = exif.str(k)?.takeIf { it.isNotBlank() }
            return ImageInfo(
                fileId = o.long("fileid") ?: 0,
                path = o.str("filename"),
                basename = o.str("basename") ?: "",
                mime = o.str("mimetype") ?: "",
                size = o.long("size") ?: 0,
                width = o.num("w")?.toInt() ?: 0,
                height = o.num("h")?.toInt() ?: 0,
                epoch = exif.long("DateTimeEpoch"),
                address = o.str("address"),
                lat = exif.num("GPSLatitude"),
                lon = exif.num("GPSLongitude"),
                make = ex("Make"),
                model = ex("Model"),
                lens = ex("LensModel"),
                fNumber = ex("FNumber"),
                exposure = ex("ExposureTime"),
                iso = ex("ISO"),
                focal = ex("FocalLength"),
                title = ex("Title"),
                description = ex("Description") ?: ex("ImageDescription"),
                tz = ex("LocationTZID") ?: ex("OffsetTimeOriginal"),
                hasDateTag = ex("DateTimeOriginal") != null || ex("CreateDate") != null,
                permissions = o.str("permissions") ?: "",
                tags = tags,
                albums = clusterNames("albums"),
                people = clusterNames("recognize") + clusterNames("facerecognition"),
                owner = o.str("ownername"),
            )
        }
    }
}

internal fun JsonObject.prim(k: String): JsonPrimitive? = this[k] as? JsonPrimitive
internal fun JsonObject.str(k: String): String? = prim(k)?.contentOrNull
internal fun JsonObject.num(k: String): Double? = prim(k)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.long(k: String): Long? = prim(k)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toLongOrNull() }
internal fun JsonObject.bool(k: String): Boolean = prim(k)?.let {
    it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } ?: (it.contentOrNull == "true")
} ?: false

internal fun JsonElement.asObjects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
