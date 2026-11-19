package app.gallery.data.settings

import android.os.Build
import app.gallery.data.nextcloud.DavClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Backs up and restores the app's settings (everything except the Nextcloud sign-in), to a file
 * the user picks or to their Nextcloud at [REMOTE_PATH].
 */
class SettingsBackup(private val settings: Settings, private val dav: DavClient) {
    data class Info(val exportedAtMs: Long?, val device: String?)

    suspend fun toJson(): String = buildJsonObject {
        put("app", JsonPrimitive(APP_ID))
        put("format", JsonPrimitive(1))
        put("exportedAt", JsonPrimitive(System.currentTimeMillis()))
        put("device", JsonPrimitive(listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ")))
        put("settings", settings.exportAll())
    }.toString()

    /** Applies a backup. Returns the number of settings restored; throws if it is not a Gallery backup. */
    suspend fun restore(json: String): Int {
        val o = runCatching { app.gallery.data.nextcloud.NcJson.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("This file is not a Gallery settings backup")
        if ((o["app"] as? JsonPrimitive)?.content != APP_ID) throw IllegalArgumentException("This file is not a Gallery settings backup")
        val s = o["settings"] as? JsonObject ?: throw IllegalArgumentException("The backup has no settings in it")
        return settings.importAll(s)
    }

    fun info(json: String): Info? = runCatching {
        val o = app.gallery.data.nextcloud.NcJson.parseToJsonElement(json).jsonObject
        if ((o["app"] as? JsonPrimitive)?.content != APP_ID) return null
        Info((o["exportedAt"] as? JsonPrimitive)?.longOrNull, (o["device"] as? JsonPrimitive)?.content)
    }.getOrNull()

    suspend fun saveToNextcloud() {
        dav.mkdirs(REMOTE_PATH.substringBeforeLast('/'))
        dav.put(REMOTE_PATH, toJson().toRequestBody("application/json".toMediaType()), null)
    }

    /** The backup stored on Nextcloud, or null if there is none. */
    suspend fun fromNextcloud(): String? = dav.getText(REMOTE_PATH)

    companion object {
        const val APP_ID = "app.gallery"
        /** In the user's Nextcloud Files; the dot keeps it out of the way. */
        const val REMOTE_PATH = "/.gallery/settings.json"
    }
}
