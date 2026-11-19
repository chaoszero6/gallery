package app.gallery

import android.content.Context
import app.gallery.backup.BackupRepository
import app.gallery.backup.UploadEngine
import app.gallery.data.db.GalleryDb
import app.gallery.data.images.ImageSources
import app.gallery.data.images.buildImageLoader
import app.gallery.data.local.MediaStoreSource
import app.gallery.data.nextcloud.AccountStore
import app.gallery.data.nextcloud.DavClient
import app.gallery.data.nextcloud.LoginFlow
import app.gallery.data.nextcloud.MemoriesApi
import app.gallery.data.nextcloud.baseHttpClient
import app.gallery.data.repo.ActionsRepository
import app.gallery.data.repo.MediaRepository
import app.gallery.data.repo.NextcloudRepository
import app.gallery.data.settings.Settings
import app.gallery.search.SearchEngine
import app.gallery.search.SearchIndexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependencies (one instance per process). */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = GalleryDb.create(context)
    val settings = Settings(context)
    val accounts = AccountStore(context)
    val http = baseHttpClient { accounts.account.value }
    val api = MemoriesApi(http) { accounts.account.value }
    val dav = DavClient(http) { accounts.account.value }
    val login = LoginFlow(http, api)
    val mediaStore = MediaStoreSource(context)
    val media = MediaRepository(db, mediaStore, api, accounts, scope)
    val nextcloud = NextcloudRepository(accounts, api, dav, login, db, scope)
    val actions = ActionsRepository(context, db, api, dav, nextcloud, media)
    val uploadEngine = UploadEngine(dav, db.uploads())
    val backup = BackupRepository(context, db, settings, dav, accounts, media)
    val settingsBackup = app.gallery.data.settings.SettingsBackup(settings, dav)
    val imageLoader = buildImageLoader(context, http)
    val images = ImageSources(api)
    val searchIndexer = SearchIndexer(context, db, http, images, settings)
    val search = SearchEngine(db, media, nextcloud, api, searchIndexer)

    /** Random id per app session for Memories video transcoding (must be 8-128 hex chars). */
    val videoClientId: String = (1..32).map { "0123456789abcdef".random() }.joinToString("")
}
