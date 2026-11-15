package app.gallery.data.nextcloud

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Nextcloud Login Flow v2: the user approves the app in a browser, and the app polls for an
 * app password. The user's real password never passes through the app.
 */
class LoginFlow(private val http: OkHttpClient, private val api: MemoriesApi) {

    data class Started(val loginUrl: String, val pollEndpoint: String, val token: String, val server: String)
    data class Credentials2(val server: String, val loginName: String, val appPassword: String)

    fun normalizeServer(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        s = s.removeSuffix("/index.php").removeSuffix("/apps/memories")
        return s
    }

    suspend fun start(serverInput: String): Started {
        val server = normalizeServer(serverInput)
        val req = Request.Builder().url("$server/index.php/login/v2").post(FormBody.Builder().build()).build()
        val o = NcJson.parseToJsonElement(http.text(req)) as JsonObject
        val poll = o["poll"] as JsonObject
        return Started(o.str("login")!!, poll.str("endpoint")!!, poll.str("token")!!, server)
    }

    /** Polls every 2 s for up to 10 minutes. Returns null if the user never approved. */
    suspend fun poll(s: Started, timeoutMs: Long = 600_000): Credentials2? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val req = Request.Builder().url(s.pollEndpoint).post(FormBody.Builder().add("token", s.token).build()).build()
            try {
                val o = NcJson.parseToJsonElement(http.text(req)) as JsonObject
                return Credentials2(o.str("server") ?: s.server, o.str("loginName")!!, o.str("appPassword")!!)
            } catch (e: HttpException) {
                if (e.code != 404) throw e
            }
            delay(2000)
        }
        return null
    }

    /**
     * Turns credentials into a full Account: finds the Memories base URL (with or without
     * index.php), checks the credentials, and reads the real user id and version.
     */
    suspend fun finish(c: Credentials2): Account {
        val server = c.server.trimEnd('/')
        val auth = Credentials.basic(c.loginName, c.appPassword, Charsets.UTF_8)
        var lastError: Exception? = null
        for (base in listOf("$server/apps/memories/", "$server/index.php/apps/memories/")) {
            try {
                val d = api.describe(base, auth)
                val uid = d.uid ?: throw IllegalStateException("Nextcloud rejected the app password")
                val memoriesBase = d.baseUrl?.let { if (it.startsWith("http")) it else server + it }?.let { if (it.endsWith("/")) it else "$it/" } ?: base
                return Account(server, c.loginName, c.appPassword, uid, memoriesBase, d.version ?: "")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("Memories app not found on this server: ${lastError?.message}", lastError)
    }

    companion object {
        /** Major version from a string like "11.1.4". */
        fun majorVersion(v: String): Int = v.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        const val MIN_MEMORIES_MAJOR = 10
    }
}
