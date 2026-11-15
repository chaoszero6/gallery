package app.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalMedia::class, RemoteMedia::class, RemoteDay::class, LocalFavorite::class,
        UploadRecord::class, PendingOp::class, SearchEntry::class, RecentSearch::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GalleryDb : RoomDatabase() {
    abstract fun local(): LocalMediaDao
    abstract fun remote(): RemoteMediaDao
    abstract fun days(): RemoteDayDao
    abstract fun favorites(): FavoriteDao
    abstract fun uploads(): UploadDao
    abstract fun pendingOps(): PendingOpDao
    abstract fun search(): SearchDao

    companion object {
        fun create(context: Context): GalleryDb =
            Room.databaseBuilder(context, GalleryDb::class.java, "gallery.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
