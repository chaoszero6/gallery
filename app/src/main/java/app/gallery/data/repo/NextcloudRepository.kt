package app.gallery.data.repo

import android.util.Log
import app.gallery.data.db.GalleryDb
import app.gallery.data.nextcloud.Account
import app.gallery.data.nextcloud.AccountStore
import app.gallery.data.nextcloud.Cluster
import app.gallery.data.nextcloud.DavClient
import app.gallery.data.nextcloud.LoginFlow
import app.gallery.data.nextcloud.MemoriesApi
import app.gallery.data.nextcloud.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Account, server features and the cluster lists (albums, tags, places, people). */
class NextcloudRepository(
    private val accounts: AccountStore,
    private val api: MemoriesApi,
    private val dav: DavClient,
    private val login: LoginFlow,
    private val db: GalleryDb,
    private val scope: CoroutineScope,
) {
    val account: StateFlow<Account?> = accounts.account

    private val _config = MutableStateFlow<ServerConfig?>(null)
    val config: StateFlow<ServerConfig?> = _config.asStateFlow()

    private val clusterCache = ConcurrentHashMap<String, List<Cluster>>()
    private val _clusters = MutableStateFlow<Map<String, List<Cluster>>>(emptyMap())
    val clusters: StateFlow<Map<String, List<Cluster>>> = _clusters.asStateFlow()

    val signedIn: Boolean get() = accounts.account.value != null

    fun start() {
        if (signedIn) scope.launch { refreshConfig(); refreshClusters() }
    }

    suspend fun refreshConfig(): ServerConfig? {
        if (!signedIn) return null
        return runCatching { api.config() }
            .onSuccess { _config.value = it }
            .onFailure { Log.w(TAG, "config failed", it) }
            .getOrNull() ?: _config.value
    }

    suspend fun startLogin(server: String): LoginFlow.Started = login.start(server)

    /** Waits for browser approval, checks the server, and stores the account. */
    suspend fun completeLogin(started: LoginFlow.Started): Account {
        val creds = login.poll(started) ?: throw IllegalStateException("Sign-in timed out")
        return finishLogin(creds)
    }

    /** Sign-in with an app password typed by the user (Settings > Security > Devices & sessions). */
    suspend fun loginWithAppPassword(server: String, user: String, appPassword: String): Account =
        finishLogin(LoginFlow.Credentials2(login.normalizeServer(server), user.trim(), appPassword.trim()))

    private suspend fun finishLogin(creds: LoginFlow.Credentials2): Account {
        val account = login.finish(creds)
        val major = LoginFlow.majorVersion(account.memoriesVersion)
        if (major in 1 until LoginFlow.MIN_MEMORIES_MAJOR) {
            throw IllegalStateException("Memories ${account.memoriesVersion} is too old. Please update Memories to version ${LoginFlow.MIN_MEMORIES_MAJOR} or newer.")
        }
        accounts.save(account)
        refreshConfig()
        scope.launch { refreshClusters() }
        return account
    }

    suspend fun signOut() {
        accounts.clear()
        _config.value = null
        clusterCache.clear()
        _clusters.value = emptyMap()
        db.remote().clear()
        db.days().clear()
        db.pendingOps().clear()
        db.uploads().clear()
    }

    /** Backends available on this server, based on its feature flags. */
    fun backends(cfg: ServerConfig? = _config.value): List<String> = buildList {
        cfg ?: return@buildList
        if (cfg.albums) add("albums")
        if (cfg.recognize) add("recognize")
        if (cfg.faceRecognition) add("facerecognition")
        if (cfg.places) add("places")
        if (cfg.tags) add("tags")
    }

    suspend fun refreshClusters() {
        val cfg = _config.value ?: refreshConfig() ?: return
        for (b in backends(cfg)) {
            runCatching {
                val params = if (b == "places") listOf("covers" to "1") else emptyList()
                api.clusters(b, params)
            }.onSuccess { list ->
                clusterCache[b] = list
                _clusters.value = HashMap(clusterCache)
            }.onFailure { Log.w(TAG, "clusters $b failed", it) }
        }
    }

    suspend fun clusters(backend: String, refresh: Boolean = false): List<Cluster> {
        if (!refresh) clusterCache[backend]?.let { return it }
        val list = runCatching { api.clusters(backend, if (backend == "places") listOf("covers" to "1") else emptyList()) }
            .getOrElse { return clusterCache[backend] ?: emptyList() }
        clusterCache[backend] = list
        _clusters.value = HashMap(clusterCache)
        return list
    }

    fun people(): List<Cluster> = (clusterCache["recognize"].orEmpty() + clusterCache["facerecognition"].orEmpty())
        .sortedByDescending { it.count }

    /** Server path of a file (needed for WebDAV actions). Cached on the timeline row. */
    suspend fun pathOf(fileId: Long): String {
        db.remote().byId(fileId)?.path?.let { return it }
        val info = api.info(fileId, withClusters = false)
        val path = info.path ?: throw IllegalStateException("File path unavailable")
        db.remote().setPath(fileId, path)
        return path
    }

    fun previewUrl(fileId: Long, size: Int, square: Boolean, etag: String?) = api.previewUrl(fileId, size, square, etag)
    fun clusterPreviewUrl(c: Cluster) = api.clusterPreviewUrl(c)

    suspend fun createAlbum(name: String) {
        dav.createAlbum(name)
        clusters("albums", refresh = true)
    }

    suspend fun renameAlbum(old: String, new: String) {
        dav.renameAlbum(old, new)
        clusters("albums", refresh = true)
    }

    suspend fun deleteAlbum(name: String) {
        dav.deleteAlbum(name)
        clusters("albums", refresh = true)
    }

    private companion object { const val TAG = "NextcloudRepo" }
}
