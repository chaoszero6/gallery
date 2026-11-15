package app.gallery.data.nextcloud

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Filters accepted by /api/days, as query parameters. */
typealias DayFilters = List<Pair<String, String>>

/**
 * Client for the Memories app API (pulsejet/memories, checked against v11 source).
 * Auth and the OCS-APIRequest header are added by [AuthInterceptor].
 */
class MemoriesApi(private val http: OkHttpClient, private val account: () -> Account?) {
    private val jsonType = "application/json".toMediaType()

    private fun acc() = account() ?: throw IllegalStateException("Not signed in to Nextcloud")
    private fun api(path: String, params: DayFilters = emptyList()): HttpUrl {
        val b = (acc().apiBase + path).toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun getJson(url: HttpUrl): JsonElement =
        NcJson.parseToJsonElement(http.text(Request.Builder().url(url).get().build()))

    private suspend fun sendJson(method: String, url: HttpUrl, body: JsonElement?): String =
        http.text(Request.Builder().url(url).method(method, (body?.toString() ?: "{}").toRequestBody(jsonType)).build())

    // --- account / capabilities ---------------------------------------------------------------

    /** Checks credentials against a server before an Account exists. */
    suspend fun describe(memoriesBase: String, auth: String?): DescribeDto {
        val b = Request.Builder().url(memoriesBase + "api/describe")
        if (auth != null) b.header("Authorization", auth).header("OCS-APIRequest", "true")
        return NcJson.decodeFromString(DescribeDto.serializer(), http.text(b.build()))
    }

    suspend fun config(): ServerConfig = ServerConfig.parse(getJson(api("config")) as JsonObject)

    // --- timeline ---------------------------------------------------------------------------------

    suspend fun days(filters: DayFilters = emptyList()): List<DayDto> {
        val txt = http.text(Request.Builder().url(api("days", filters + ("nopreload" to "1"))).build())
        return NcJson.decodeFromString(ListSerializer(DayDto.serializer()), txt)
    }

    suspend fun day(dayIds: List<Int>, filters: DayFilters = emptyList()): List<PhotoDto> {
        if (dayIds.isEmpty()) return emptyList()
        val txt = http.text(Request.Builder().url(api("days/" + dayIds.joinToString(","), filters)).build())
        return NcJson.decodeFromString(ListSerializer(PhotoDto.serializer()), txt)
    }

    /** Photos for an explicit set of day ids (used for "On this day"). */
    suspend fun daysByIds(dayIds: List<Int>): List<PhotoDto> {
        val body = buildJsonObject { putJsonArray("dayIds") { dayIds.forEach { add(JsonPrimitive(it)) } } }
        val txt = sendJson("POST", api("days"), body)
        return NcJson.decodeFromString(ListSerializer(PhotoDto.serializer()), txt)
    }

    // --- images -----------------------------------------------------------------------------------

    fun previewUrl(fileId: Long, size: Int, square: Boolean, etag: String?): String {
        val b = api("image/preview/$fileId").newBuilder()
            .addQueryParameter("x", size.toString()).addQueryParameter("y", size.toString())
            .addQueryParameter("a", if (square) "0" else "1")
        if (!etag.isNullOrEmpty()) b.addQueryParameter("c", etag)
        return b.build().toString()
    }

    fun fullPreviewUrl(fileId: Long, etag: String?): String = api("image/preview/$fileId").newBuilder()
        .addQueryParameter("x", "-1").addQueryParameter("y", "-1").addQueryParameter("a", "1")
        .apply { if (!etag.isNullOrEmpty()) addQueryParameter("c", etag) }.build().toString()

    fun decodableUrl(fileId: Long, etag: String?): String = api("image/decodable/$fileId").newBuilder()
        .addQueryParameter("hevc", "1")
        .apply { if (!etag.isNullOrEmpty()) addQueryParameter("c", etag) }.build().toString()

    fun streamUrl(fileId: Long): String = api("stream/$fileId").toString()

    fun transcodeUrl(clientId: String, fileId: Long): String =
        api("video/transcode/$clientId/$fileId/index.m3u8").toString()

    fun livePhotoUrl(fileId: Long, liveId: String): String =
        api("video/livephoto/$fileId", listOf("liveid" to liveId)).toString()

    suspend fun info(fileId: Long, withClusters: Boolean = true): ImageInfo {
        val p = mutableListOf("tags" to "1")
        if (withClusters) p += "clusters" to "albums,recognize,facerecognition"
        return ImageInfo.parse(getJson(api("image/info/$fileId", p)) as JsonObject)
    }

    /** Writes metadata with exiftool on the server. Allowed keys are listed in the Memories source. */
    suspend fun setExif(fileId: Long, raw: Map<String, String>) {
        val body = buildJsonObject { put("raw", JsonObject(raw.mapValues { JsonPrimitive(it.value) })) }
        sendJson("PATCH", api("image/set-exif/$fileId"), body)
    }

    suspend fun reindex(fileId: Long) {
        runCatching { sendJson("POST", api("image/reindex/$fileId"), null) }
    }

    suspend fun delete(fileId: Long) {
        http.text(Request.Builder().url(api("image/delete/$fileId")).delete().build())
    }

    suspend fun archive(fileId: Long, archive: Boolean) {
        val body = if (archive) JsonObject(emptyMap()) else buildJsonObject { put("archive", false) }
        try {
            sendJson("PATCH", api("archive/$fileId"), body)
        } catch (e: HttpException) {
            // 400 means "already in that state", which is the result we wanted.
            if (e.code != 400) throw e
        }
    }

    suspend fun setTags(fileId: Long, add: List<Long>, remove: List<Long>) {
        val body = buildJsonObject {
            putJsonArray("add") { add.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("remove") { remove.forEach { add(JsonPrimitive(it)) } }
        }
        sendJson("PATCH", api("tags/set/$fileId"), body)
    }

    // --- clusters ---------------------------------------------------------------------------------

    suspend fun clusters(backend: String, params: DayFilters = emptyList()): List<Cluster> {
        val el = getJson(api("clusters/$backend", params))
        val list = (el as? JsonObject)?.get("list") ?: el
        return (list as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> Cluster.parse(backend, o) } } ?: emptyList()
    }

    fun clusterPreviewUrl(c: Cluster): String {
        if (!c.isFace && c.coverFileId != null) return previewUrl(c.coverFileId, 512, true, c.coverEtag)
        val b = api("clusters/${c.backend}/preview").newBuilder()
            .addQueryParameter("name", if (c.isFace) "${c.user}/${c.clusterId}" else c.clusterId)
        c.coverFileId?.let { b.addQueryParameter("cover", it.toString()) }
        c.coverEtag?.let { b.addQueryParameter("cover_etag", it) }
        return b.build().toString()
    }

    // --- map --------------------------------------------------------------------------------------

    suspend fun mapClusters(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, zoom: Int): List<MapClusterDto> {
        val bounds = listOf(minLat, maxLat, minLon, maxLon).joinToString(",") { "%.6f".format(java.util.Locale.US, it) }
        val txt = http.text(Request.Builder().url(api("map/clusters", listOf("bounds" to bounds, "zoom" to zoom.toString()))).build())
        return NcJson.decodeFromString(ListSerializer(MapClusterDto.serializer()), txt)
    }

    suspend fun mapInit(): Pair<Double, Double>? {
        val o = getJson(api("map/init")) as? JsonObject ?: return null
        val pos = o["pos"] as? JsonObject ?: return null
        val lat = pos.num("lat") ?: return null
        val lon = pos.num("lon") ?: return null
        return lat to lon
    }

    // --- search -----------------------------------------------------------------------------------

    suspend fun lensSearch(text: String, limit: Int = 100): List<LensResultDto> {
        val txt = http.text(Request.Builder().url(api("lens/search", listOf("text" to text, "limit" to limit.toString()))).build())
        return NcJson.decodeFromString(ListSerializer(LensResultDto.serializer()), txt)
    }
}
