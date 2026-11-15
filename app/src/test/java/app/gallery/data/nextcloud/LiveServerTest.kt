package app.gallery.data.nextcloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Read-only checks against a real Nextcloud + Memories server.
 * Put nc.url, nc.user and nc.appPassword in local.properties to run; skipped otherwise.
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveServerTest*' -i
 */
class LiveServerTest {
    private val url = System.getProperty("nc.url")
    private val user = System.getProperty("nc.user")
    private val pass = System.getProperty("nc.appPassword")
    private lateinit var account: Account
    private val http by lazy { baseHttpClient { account } }
    private val api by lazy { MemoriesApi(http) { account } }

    @Before fun connect() = runBlocking {
        assumeTrue("no live server configured", !url.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        val flow = LoginFlow(baseHttpClient { null }, MemoriesApi(baseHttpClient { null }) { null })
        account = flow.finish(LoginFlow.Credentials2(flow.normalizeServer(url!!), user!!, pass!!))
        println("Connected as ${account.uid}, Memories ${account.memoriesVersion}, base ${account.memoriesBase}")
    }

    @Test fun timelineClustersMapAndPreviews() = runBlocking {
        val cfg = api.config()
        println("config: $cfg")
        val days = api.days()
        println("days: ${days.size}, photos: ${days.sumOf { it.count }}")
        assertThat(days).isNotEmpty()
        val photos = api.day(days.take(3).map { it.dayid })
        assertThat(photos).isNotEmpty()
        val p = photos.first()
        println("first photo: $p")
        val info = api.info(p.fileid)
        println("info: path=${info.path} lat=${info.lat} lon=${info.lon} address=${info.address} tags=${info.tags} albums=${info.albums} people=${info.people}")
        assertThat(info.path).isNotNull()

        http.newCall(Request.Builder().url(api.previewUrl(p.fileid, 256, true, p.etag)).build()).execute().use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.header("Content-Type")).startsWith("image/")
        }
        for (b in listOf("albums", "tags", "places", "recognize", "facerecognition")) {
            val r = runCatching { api.clusters(b) }
            println("clusters $b: ${r.map { it.size }.getOrElse { "n/a (${it.message})" }}")
        }
        println("map init: ${api.mapInit()}")
        val clusters = api.mapClusters(-90.0, 90.0, -180.0, 180.0, 2)
        println("map clusters at zoom 2: ${clusters.size}")
        val dav = DavClient(http) { account }
        val root = dav.list("/")
        println("dav root entries: ${root.size}")
        assertThat(root).isNotEmpty()
    }
}
