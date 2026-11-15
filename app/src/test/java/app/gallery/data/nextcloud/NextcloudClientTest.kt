package app.gallery.data.nextcloud

import app.gallery.backup.ByteSource
import app.gallery.backup.UploadEngine
import app.gallery.data.db.UploadDao
import app.gallery.data.db.UploadRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class NextcloudClientTest {
    private lateinit var server: MockWebServer
    private lateinit var account: Account
    private val http by lazy { baseHttpClient { account } }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        account = Account(base, "alice", "app-pass", "alice", "$base/apps/memories/", "11.1.4")
    }

    @After fun tearDown() = server.close()

    private fun ok(body: String = "", code: Int = 200, headers: Map<String, String> = emptyMap()) =
        MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    private fun RecordedRequest.text() = body?.utf8() ?: ""

    @Test fun `every request carries basic auth and the CSRF header`() = runTest {
        server.enqueue(ok("""[{"dayid":20407,"count":3},{"dayid":20400,"count":1}]"""))
        val days = MemoriesApi(http) { account }.days(listOf("fav" to "1"))
        assertThat(days.map { it.dayid }).containsExactly(20407, 20400)
        val r = server.takeRequest()
        assertThat(r.headers["Authorization"]).startsWith("Basic ")
        assertThat(r.headers["OCS-APIRequest"]).isEqualTo("true")
        assertThat(r.url.encodedPath).isEqualTo("/apps/memories/api/days")
        assertThat(r.url.queryParameter("fav")).isEqualTo("1")
        assertThat(r.url.queryParameter("nopreload")).isEqualTo("1")
    }

    @Test fun `no auth is sent to other hosts`() = runTest {
        val other = MockWebServer().apply { start() }
        other.enqueue(ok("tile"))
        // Same host name but a different port is still treated as the server host by OkHttp's host check,
        // so use a different loopback name for the "other" host.
        val url = other.url("/tile.png").newBuilder().host("127.0.0.1").build()
        val acc2 = account.copy(server = account.server.replace("127.0.0.1", "localhost"))
        account = acc2
        http.text(okhttp3.Request.Builder().url(url).build())
        assertThat(other.takeRequest().headers["Authorization"]).isNull()
        other.close()
    }

    @Test fun `photo list parses Memories fields`() = runTest {
        server.enqueue(ok("""[{"fileid":5,"dayid":20407,"w":4000,"h":3000,"etag":"abc","basename":"IMG.jpg","size":12345,"epoch":1763200000,"mimetype":"image/jpeg","isfavorite":1,"liveid":"self__trailer","unknown":"x"},
            {"fileid":6,"dayid":20407,"isvideo":1,"video_duration":12,"basename":"V.mp4","mimetype":"video/mp4"}]"""))
        val p = MemoriesApi(http) { account }.day(listOf(20407, 20406))
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/apps/memories/api/days/20407,20406")
        assertThat(p[0].isfavorite).isEqualTo(1)
        assertThat(p[0].liveid).isEqualTo("self__trailer")
        assertThat(p[1].isvideo).isEqualTo(1)
        assertThat(p[1].video_duration).isEqualTo(12)
    }

    @Test fun `config flags and clusters`() = runTest {
        server.enqueue(ok("""{"version":"11.1.4","albums_enabled":true,"systemtags_enabled":false,"recognize_enabled":true,"facerecognition_enabled":false,
            "places_gis":2,"vod_disable":false,"lens_enabled":false,"map_tile_server_url":"https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "map_tile_servers":[{"url":"https://tile.openstreetmap.org/{z}/{x}/{y}.png","attribution":"<a href='x'>OSM</a>"}],"timeline_path":"/Photos","onthisday_day_range":2}"""))
        server.enqueue(ok("""[{"album_id":3,"name":"Trip","user":"alice","count":4,"cover":77,"cover_etag":"e","cluster_id":"alice/Trip"},
            {"album_id":4,"name":"Shared","user":"bob","count":1,"last_added_photo":88}]"""))
        server.enqueue(ok("""[{"id":12,"user_id":"alice","name":"Anna","count":30,"cover":9,"cover_etag":"x"}]"""))
        val api = MemoriesApi(http) { account }
        val cfg = api.config()
        assertThat(cfg.albums).isTrue(); assertThat(cfg.tags).isFalse(); assertThat(cfg.places).isTrue()
        assertThat(cfg.transcoding).isTrue(); assertThat(cfg.tileAttribution).isEqualTo("OSM"); assertThat(cfg.onThisDayRange).isEqualTo(2)
        val albums = api.clusters("albums")
        assertThat(albums.map { it.daysFilter }).containsExactly("albums" to "alice/Trip", "albums" to "bob/Shared")
        assertThat(albums[1].coverFileId).isEqualTo(88)
        val people = api.clusters("recognize")
        assertThat(people.single().daysFilter).isEqualTo("recognize" to "alice/12")
        assertThat(api.clusterPreviewUrl(people.single())).contains("clusters/recognize/preview")
    }

    @Test fun `set-exif, archive and on this day send the documented bodies`() = runTest {
        server.enqueue(ok("{}")); server.enqueue(ok("[]")); server.enqueue(ok("[]"))
        val api = MemoriesApi(http) { account }
        api.setExif(5, mapOf("AllDates" to "2024:01:02 03:04:05"))
        api.archive(5, false)
        api.daysByIds(listOf(1, 2))
        val r1 = server.takeRequest(); val r2 = server.takeRequest(); val r3 = server.takeRequest()
        assertThat(r1.method).isEqualTo("PATCH"); assertThat(r1.url.encodedPath).endsWith("/api/image/set-exif/5")
        assertThat(r1.text()).isEqualTo("""{"raw":{"AllDates":"2024:01:02 03:04:05"}}""")
        assertThat(r2.method).isEqualTo("PATCH"); assertThat(r2.text()).isEqualTo("""{"archive":false}""")
        assertThat(r3.method).isEqualTo("POST"); assertThat(r3.text()).isEqualTo("""{"dayIds":[1,2]}""")
    }

    @Test fun `image info parses exif, tags and clusters`() = runTest {
        server.enqueue(ok("""{"fileid":5,"filename":"/Photos/a b.jpg","basename":"a b.jpg","permissions":"RGDNVW U","w":10,"h":20,
            "exif":{"DateTimeEpoch":1700000000,"GPSLatitude":12.5,"GPSLongitude":"80.25","Make":"Samsung","Model":"S25","FNumber":1.8,"DateTimeOriginal":"2023:11:14 22:13:20"},
            "address":"Chennai","tags":{"7":"beach"},"clusters":{"albums":[{"name":"Trip"}],"recognize":[{"name":"Anna"},{"name":""}]}}"""))
        val i = MemoriesApi(http) { account }.info(5)
        assertThat(i.path).isEqualTo("/Photos/a b.jpg")
        assertThat(i.lat).isEqualTo(12.5); assertThat(i.lon).isEqualTo(80.25)
        assertThat(i.tags).containsExactly(7L, "beach")
        assertThat(i.albums).containsExactly("Trip"); assertThat(i.people).containsExactly("Anna")
        assertThat(i.hasDateTag).isTrue(); assertThat(i.canEdit).isTrue()
    }

    @Test fun `webdav listing is decoded relative to the user root`() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
             <d:response><d:href>/nc/remote.php/dav/files/alice/Photos/</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>1</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
             <d:response><d:href>/nc/remote.php/dav/files/alice/Photos/My%20Trip%20%281%29.jpg</d:href>
              <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength><oc:fileid>99</oc:fileid><oc:favorite>1</oc:favorite><d:getetag>"et"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              <d:propstat><d:prop><d:getcontenttype/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
            </d:multistatus>"""
        val e = DavClient.parseMultistatus(xml, "alice")
        assertThat(e[0].isDir).isTrue(); assertThat(e[0].path).isEqualTo("/Photos/")
        assertThat(e[1].path).isEqualTo("/Photos/My Trip (1).jpg")
        assertThat(e[1].size).isEqualTo(42); assertThat(e[1].fileId).isEqualTo(99); assertThat(e[1].favorite).isTrue(); assertThat(e[1].etag).isEqualTo("et")
        assertThat(DavClient.encodePath("/Photos/My Trip #1/ä.jpg")).isEqualTo("/Photos/My%20Trip%20%231/%C3%A4.jpg")
        assertThat(DavClient.percentDecode("%C3%A4%20x")).isEqualTo("ä x")
    }

    @Test fun `favourite proppatch body`() = runTest {
        server.enqueue(ok("", 207))
        DavClient(http) { account }.setFavorite("/Photos/a.jpg", true)
        val r = server.takeRequest()
        assertThat(r.method).isEqualTo("PROPPATCH")
        assertThat(r.url.encodedPath).isEqualTo("/remote.php/dav/files/alice/Photos/a.jpg")
        assertThat(r.text()).contains("<oc:favorite>1</oc:favorite>")
    }

    @Test fun `login flow v2 polls until approved then reads the user id`() = runTest {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(ok("""{"poll":{"token":"T","endpoint":"$base/login/v2/poll"},"login":"$base/login/v2/flow/X"}"""))
        server.enqueue(ok("", 404))
        server.enqueue(ok("""{"server":"$base","loginName":"alice@example.com","appPassword":"secret"}"""))
        server.enqueue(ok("""{"version":"11.1.4","baseUrl":"/apps/memories/","uid":"alice"}"""))
        val flow = LoginFlow(http, MemoriesApi(http) { null })
        val started = flow.start(base)
        assertThat(started.loginUrl).endsWith("/login/v2/flow/X")
        val creds = flow.poll(started)!!
        val acc = flow.finish(creds)
        assertThat(acc.uid).isEqualTo("alice")
        assertThat(acc.loginName).isEqualTo("alice@example.com")
        assertThat(acc.memoriesBase).isEqualTo("$base/apps/memories/")
        server.takeRequest() // start
        assertThat(server.takeRequest().text()).isEqualTo("token=T")
        server.takeRequest()
        val describe = server.takeRequest()
        assertThat(describe.headers["Authorization"]).isNotNull()
        assertThat(LoginFlow.majorVersion("9.0.1")).isLessThan(LoginFlow.MIN_MEMORIES_MAJOR)
    }

    @Test fun `upload uses single PUT for small files with mtime`() = runTest {
        server.enqueue(ok("", 201)) // MKCOL /Photos
        server.enqueue(ok("", 201)) // MKCOL /Photos/Camera
        server.enqueue(ok("", 201, mapOf("OC-FileId" to "00000123ocabc")))
        val dao = FakeUploads()
        val engine = UploadEngine(DavClient(http) { account }, dao, chunkThreshold = 1000, chunkSize = 400)
        val data = ByteArray(10) { it.toByte() }
        val id = engine.upload(UploadRecord(1, UploadRecord.UPLOADING, "/Photos/Camera/a.jpg", size = 10), { ByteArrayInputStream(data) }, "image/jpeg", 1700000000)
        assertThat(id).isEqualTo(123)
        server.takeRequest(); server.takeRequest()
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.headers["X-OC-MTime"]).isEqualTo("1700000000")
        assertThat(put.body?.size).isEqualTo(10)
    }

    @Test fun `chunked upload resumes from the stored chunk`() = runTest {
        repeat(2) { server.enqueue(ok("", 201)) }           // MKCOL folders
        server.enqueue(ok("", 201))                         // MKCOL session
        server.enqueue(ok("", 201))                         // PUT chunk 2
        server.enqueue(ok("", 201))                         // PUT chunk 3
        server.enqueue(ok("", 201, mapOf("OC-FileId" to "456"))) // MOVE .file
        val dao = FakeUploads()
        val engine = UploadEngine(DavClient(http) { account }, dao, chunkThreshold = 100, chunkSize = 400)
        val data = ByteArray(1000) { (it % 251).toByte() }
        val rec = UploadRecord(1, UploadRecord.UPLOADING, "/Photos/Camera/v.mp4", size = 1000, chunkSession = "gallery-s1", nextChunk = 2)
        val id = engine.upload(rec, ByteSource { ByteArrayInputStream(data) }, "video/mp4", 5)
        assertThat(id).isEqualTo(456)
        val reqs = (1..6).map { server.takeRequest() }
        assertThat(reqs.map { it.method }).containsExactly("MKCOL", "MKCOL", "MKCOL", "PUT", "PUT", "MOVE").inOrder()
        assertThat(reqs[3].url.encodedPath).endsWith("/uploads/alice/gallery-s1/2")
        assertThat(reqs[3].body!!.toByteArray().toList()).isEqualTo(data.copyOfRange(400, 800).toList())
        assertThat(reqs[4].body!!.size).isEqualTo(200)
        assertThat(reqs[3].headers["OC-Total-Length"]).isEqualTo("1000")
        assertThat(reqs[5].url.encodedPath).endsWith("/gallery-s1/.file")
        assertThat(reqs[5].headers["Destination"]).endsWith("/remote.php/dav/files/alice/Photos/Camera/v.mp4")
        assertThat(dao.last?.chunkSession).isNull()
    }

    @Test fun `quota errors surface as 507`() = runTest {
        repeat(2) { server.enqueue(ok("", 201)) }
        server.enqueue(ok("Insufficient Storage", 507))
        val engine = UploadEngine(DavClient(http) { account }, FakeUploads(), chunkThreshold = 1000)
        val e = runCatching {
            engine.upload(UploadRecord(1, UploadRecord.UPLOADING, "/P/C/a.jpg", size = 3), { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, "image/jpeg", 0)
        }.exceptionOrNull()
        assertThat((e as HttpException).code).isEqualTo(507)
    }

    @Test fun `put body sizes`() {
        assertThat("abc".toRequestBody().contentLength()).isEqualTo(3)
    }

    private class FakeUploads : UploadDao {
        var last: UploadRecord? = null
        override fun observeAll(): Flow<List<UploadRecord>> = flowOf(emptyList())
        override suspend fun all() = listOfNotNull(last)
        override suspend fun pending() = emptyList<UploadRecord>()
        override suspend fun byId(id: Long) = last
        override suspend fun upsert(r: UploadRecord) { last = r }
        override suspend fun upsertAll(r: List<UploadRecord>) { last = r.lastOrNull() }
        override suspend fun retryFailed() {}
        override suspend fun clear() {}
    }
}
