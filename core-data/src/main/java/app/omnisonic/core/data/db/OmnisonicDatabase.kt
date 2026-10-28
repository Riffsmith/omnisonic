package app.omnisonic.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import app.omnisonic.core.data.db.dao.AggregateDao
import app.omnisonic.core.data.db.dao.AlbumArtistDao
import app.omnisonic.core.data.db.dao.AlbumDao
import app.omnisonic.core.data.db.dao.ArtistDao
import app.omnisonic.core.data.db.dao.ChapterDao
import app.omnisonic.core.data.db.dao.DownloadDao
import app.omnisonic.core.data.db.dao.GenreDao
import app.omnisonic.core.data.db.dao.InternetRadioDao
import app.omnisonic.core.data.db.dao.LibrarySourceDao
import app.omnisonic.core.data.db.dao.OfflineFileDao
import app.omnisonic.core.data.db.dao.OutboxDao
import app.omnisonic.core.data.db.dao.PlaybackEventDao
import app.omnisonic.core.data.db.dao.PlaylistDao
import app.omnisonic.core.data.db.dao.PlaylistEntryDao
import app.omnisonic.core.data.db.dao.ProviderDao
import app.omnisonic.core.data.db.dao.SavedFilterDao
import app.omnisonic.core.data.db.dao.SongArtistDao
import app.omnisonic.core.data.db.dao.SongDao
import app.omnisonic.core.data.db.dao.SyncStatusDao
import app.omnisonic.core.data.db.entity.AlbumArtistEntity
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.ChapterEntity
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.InternetRadioEntity
import app.omnisonic.core.data.db.entity.LibrarySourceEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.core.data.db.entity.PlaybackEventEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.SavedFilterEntity
import app.omnisonic.core.data.db.entity.SongArtistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity

@Database(
    entities = [
        ProviderEntity::class,
        LibrarySourceEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        SongEntity::class,
        GenreEntity::class,
        AlbumArtistEntity::class,
        SongArtistEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        InternetRadioEntity::class,
        ChapterEntity::class,
        PlaybackEventEntity::class,
        OutboxItemEntity::class,
        DownloadItemEntity::class,
        OfflineFileEntity::class,
        SavedFilterEntity::class,
        SyncStatusEntity::class,
    ],
    version = OmnisonicDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class OmnisonicDatabase : RoomDatabase() {

    abstract fun providerDao(): ProviderDao
    abstract fun librarySourceDao(): LibrarySourceDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun songDao(): SongDao
    abstract fun genreDao(): GenreDao
    abstract fun albumArtistDao(): AlbumArtistDao
    abstract fun songArtistDao(): SongArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistEntryDao(): PlaylistEntryDao
    abstract fun internetRadioDao(): InternetRadioDao
    abstract fun chapterDao(): ChapterDao
    abstract fun playbackEventDao(): PlaybackEventDao
    abstract fun outboxDao(): OutboxDao
    abstract fun downloadDao(): DownloadDao
    abstract fun offlineFileDao(): OfflineFileDao
    abstract fun savedFilterDao(): SavedFilterDao
    abstract fun syncStatusDao(): SyncStatusDao
    abstract fun aggregateDao(): AggregateDao

    companion object {
        const val SCHEMA_VERSION = 1
        const val DATABASE_FILE = "omnisonic.db"

        fun migrationsForSteps(): Array<Migration> = Migrations.ALL.toTypedArray()
    }
}
