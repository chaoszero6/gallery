package app.gallery.data.nextcloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, message: String, val body: String? = null) : IOException("HTTP $code: $message")

val NcJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Adds Basic auth and the OCS-APIRequest header (which passes Nextcloud's CSRF check) to every
 * request that goes to the signed-in server. Requests to other hosts (map tiles) are untouched.
 */
class AuthInterceptor(private val account: () -> Account?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val a = account()
        val host = a?.server?.toHttpUrlOrNull()?.host
        val b = req.newBuilder().header("User-Agent", USER_AGENT)
        if (a != null && host != null && req.url.host == host && req.header("Authorization") == null) {
            b.header("Authorization", Credentials.basic(a.loginName, a.appPassword, Charsets.UTF_8))
            b.header("OCS-APIRequest", "true")
        }
        return chain.proceed(b.build())
    }

    companion object {
        const val USER_AGENT = "Gallery-Android/0.1"
    }
}

fun baseHttpClient(account: () -> Account?): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .addInterceptor(AuthInterceptor(account))
    .build()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * Runs the request and returns the body as text, throwing HttpException for non-2xx answers.
 * The body is read on the IO dispatcher: `await()` resumes on the caller's thread (often Main),
 * and reading an HTTPS body there throws NetworkOnMainThreadException.
 */
suspend fun OkHttpClient.text(req: Request): String = withContext(Dispatchers.IO) {
    newCall(req).await().use { r ->
        val body = r.body.string()
        if (!r.isSuccessful) throw HttpException(r.code, r.message.ifBlank { req.url.encodedPath }, body.take(500))
        body
    }
}

/**
 * Runs a request whose answer only matters for its status and headers (PUT, MOVE, MKCOL...).
 * The body is drained and closed on the IO dispatcher; the returned Response keeps its headers.
 */
suspend fun OkHttpClient.send(req: Request): Response = withContext(Dispatchers.IO) {
    val r = newCall(req).await()
    r.use {
        val body = it.body.string()
        if (!it.isSuccessful) throw HttpException(it.code, it.message.ifBlank { req.url.encodedPath }, body.take(500))
    }
    r
}
