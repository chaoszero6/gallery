package app.gallery.data.nextcloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class DavEntry(
    /** Path relative to the user's files root, starting with "/", decoded. */
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val fileId: Long?,
    val etag: String?,
    val lastModified: String?,
    val favorite: Boolean,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** Minimal WebDAV client for the Nextcloud endpoints the app uses. */
class DavClient(private val http: OkHttpClient, private val account: () -> Account?) {
    private val xml = "application/xml; charset=utf-8".toMediaType()
    private fun acc() = account() ?: throw IllegalStateException("Not signed in to Nextcloud")

    fun fileUrl(path: String): String = acc().davFiles + encodePath(path)

    // --- files ------------------------------------------------------------------------------------

    suspend fun list(path: String, depth: Int = 1): List<DavEntry> {
        val req = Request.Builder().url(fileUrl(path.trimEnd('/') + "/"))
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xml))
            .build()
        return try {
            parseMultistatus(http.text(req), acc().uid)
        } catch (e: HttpException) {
            if (e.code == 404) emptyList() else throw e
        }
    }

    /** Lists every file under [root] by walking folders one level at a time (Depth: infinity is often disabled). */
    suspend fun listRecursive(root: String, maxFolders: Int = 5000): List<DavEntry> {
        val out = ArrayList<DavEntry>()
        val queue = ArrayDeque(listOf(root.trimEnd('/')))
        var seen = 0
        while (queue.isNotEmpty() && seen < maxFolders) {
            val dir = queue.removeFirst()
            seen++
            for (e in list(dir)) {
                if (e.path.trimEnd('/') == dir.trimEnd('/')) continue
                if (e.isDir) queue += e.path.trimEnd('/') else out += e
            }
        }
        return out
    }

    suspend fun exists(path: String): Boolean = try {
        http.text(Request.Builder().url(fileUrl(path)).header("Depth", "0")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xml)).build())
        true
    } catch (e: HttpException) {
        if (e.code == 404) false else throw e
    }

    suspend fun mkcol(path: String) {
        try {
            http.text(Request.Builder().url(fileUrl(path)).method("MKCOL", null).build())
        } catch (e: HttpException) {
            if (e.code != 405) throw e // 405 = already exists
        }
    }

    /** Creates every missing folder in [path]. */
    suspend fun mkdirs(path: String, known: MutableSet<String> = HashSet()) {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var cur = ""
        for (p in parts) {
            cur += "/$p"
            if (cur in known) continue
            mkcol(cur)
            known += cur
        }
    }

    /** Reads a small text file; null if it does not exist. */
    suspend fun getText(path: String): String? = try {
        http.text(Request.Builder().url(fileUrl(path)).get().build())
    } catch (e: HttpException) {
        if (e.code == 404) null else throw e
    }

    /** Simple upload. Returns the new file id from the oc-fileid header, if present. */
    suspend fun put(path: String, body: RequestBody, mtimeSec: Long?): Long? {
        val b = Request.Builder().url(fileUrl(path)).put(body)
        if (mtimeSec != null && mtimeSec > 0) b.header("X-OC-MTime", mtimeSec.toString())
        http.send(b.build()).use { r -> return parseFileId(r.header("OC-FileId")) }
    }

    // Chunked upload v2: MKCOL session, PUT numbered chunks, MOVE .file into place.
    fun uploadSessionUrl(session: String) = acc().davRoot + "/uploads/" + Account.enc(acc().uid) + "/" + session

    suspend fun chunkStart(session: String, destPath: String) {
        try {
            http.text(Request.Builder().url(uploadSessionUrl(session)).method("MKCOL", null)
                .header("Destination", fileUrl(destPath)).build())
        } catch (e: HttpException) {
            if (e.code != 405) throw e
        }
    }

    suspend fun chunkPut(session: String, index: Int, destPath: String, total: Long, body: RequestBody) {
        http.send(Request.Builder().url(uploadSessionUrl(session) + "/" + index).put(body)
            .header("Destination", fileUrl(destPath))
            .header("OC-Total-Length", total.toString())
            .build()).close()
    }

    suspend fun chunkFinish(session: String, destPath: String, total: Long, mtimeSec: Long?): Long? {
        val b = Request.Builder().url(uploadSessionUrl(session) + "/.file").method("MOVE", null)
            .header("Destination", fileUrl(destPath))
            .header("OC-Total-Length", total.toString())
        if (mtimeSec != null && mtimeSec > 0) b.header("X-OC-Mtime", mtimeSec.toString())
        http.send(b.build()).use { r -> return parseFileId(r.header("OC-FileId")) }
    }

    suspend fun chunkAbort(session: String) {
        runCatching { http.text(Request.Builder().url(uploadSessionUrl(session)).delete().build()) }
    }

    suspend fun fileId(path: String): Long? = list(path, 0).firstOrNull()?.fileId

    suspend fun delete(path: String) {
        http.text(Request.Builder().url(fileUrl(path)).delete().build())
    }

    suspend fun setFavorite(path: String, favorite: Boolean) {
        val body = """<?xml version="1.0"?>
            <d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            <d:set><d:prop><oc:favorite>${if (favorite) 1 else 0}</oc:favorite></d:prop></d:set>
            </d:propertyupdate>""".trimIndent()
        http.text(Request.Builder().url(fileUrl(path)).method("PROPPATCH", body.toRequestBody(xml)).build())
    }

    // --- albums (Photos app DAV) ------------------------------------------------------------------

    private fun albumUrl(name: String) = acc().davRoot + "/photos/" + Account.enc(acc().uid) + "/albums/" + Account.enc(name)

    suspend fun createAlbum(name: String) {
        http.text(Request.Builder().url(albumUrl(name)).method("MKCOL", null).build())
    }

    suspend fun renameAlbum(old: String, new: String) {
        http.text(Request.Builder().url(albumUrl(old)).method("MOVE", null).header("Destination", albumUrl(new)).build())
    }

    suspend fun deleteAlbum(name: String) {
        http.text(Request.Builder().url(albumUrl(name)).delete().build())
    }

    suspend fun addToAlbum(album: String, filePath: String) {
        val name = filePath.substringAfterLast('/')
        try {
            http.text(Request.Builder().url(fileUrl(filePath)).method("COPY", null)
                .header("Destination", albumUrl(album) + "/" + Account.enc(name)).build())
        } catch (e: HttpException) {
            if (e.code != 409) throw e // 409 = already in the album
        }
    }

    suspend fun removeFromAlbum(album: String, fileId: Long, basename: String) {
        http.text(Request.Builder().url(albumUrl(album) + "/" + Account.enc("$fileId-$basename")).delete().build())
    }

    // --- tags -------------------------------------------------------------------------------------

    /** Creates a collaborative tag and returns its id (or null if it could not be read back). */
    suspend fun createTag(name: String): Long? {
        val body = """{"name":${NcJson.encodeToString(kotlinx.serialization.serializer<String>(), name)},"userVisible":true,"userAssignable":true,"canAssign":true}"""
        val req = Request.Builder().url(acc().davRoot + "/systemtags")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.send(req).use { r -> return r.header("Content-Location")?.trimEnd('/')?.substringAfterLast('/')?.toLongOrNull() }
    }

    companion object {
        private val PROPFIND_BODY = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            <d:prop><d:getlastmodified/><d:getcontentlength/><d:resourcetype/><d:getetag/><oc:fileid/><oc:favorite/></d:prop>
            </d:propfind>""".trimIndent()

        private fun parseFileId(h: String?): Long? = h?.takeWhile { it.isDigit() }?.toLongOrNull()

        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { if (it.isEmpty()) it else Account.enc(it) }.let { if (it.startsWith("/")) it else "/$it" }

        fun percentDecode(s: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3; continue }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** Parses a PROPFIND multistatus. Paths are made relative to /remote.php/dav/files/{uid}. */
        fun parseMultistatus(body: String, uid: String): List<DavEntry> {
            val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val marker = "/remote.php/dav/files/$uid"
            val out = ArrayList<DavEntry>(responses.length)
            for (i in 0 until responses.length) {
                val r = responses.item(i) as Element
                val href = percentDecode(r.child("DAV:", "href")?.textContent ?: continue)
                val idx = href.indexOf(marker)
                val path = if (idx >= 0) href.substring(idx + marker.length).ifEmpty { "/" } else href
                var size = 0L
                var fileId: Long? = null
                var etag: String? = null
                var mod: String? = null
                var dir = false
                var fav = false
                val propstats = r.getElementsByTagNameNS("DAV:", "propstat")
                for (j in 0 until propstats.length) {
                    val ps = propstats.item(j) as Element
                    val status = ps.child("DAV:", "status")?.textContent ?: ""
                    if (!status.contains(" 200")) continue
                    val prop = ps.child("DAV:", "prop") ?: continue
                    prop.child("DAV:", "getcontentlength")?.textContent?.trim()?.toLongOrNull()?.let { size = it }
                    prop.child("http://owncloud.org/ns", "fileid")?.textContent?.trim()?.toLongOrNull()?.let { fileId = it }
                    prop.child("DAV:", "getetag")?.textContent?.trim('"', ' ')?.let { etag = it }
                    prop.child("DAV:", "getlastmodified")?.textContent?.let { mod = it }
                    prop.child("http://owncloud.org/ns", "favorite")?.textContent?.trim()?.let { fav = it == "1" }
                    prop.child("DAV:", "resourcetype")?.let { rt -> dir = rt.getElementsByTagNameNS("DAV:", "collection").length > 0 }
                }
                out += DavEntry(path, dir, size, fileId, etag, mod, fav)
            }
            return out
        }

        private fun Element.child(ns: String, name: String): Element? {
            val l = getElementsByTagNameNS(ns, name)
            return if (l.length > 0) l.item(0) as Element else null
        }
    }
}
