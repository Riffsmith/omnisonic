package app.omnisonic.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The single v1 -> v2 schema migration. It carries every T16 schema change:
 * provider kinds/endpoints, Wi-Fi and opt-in mTLS columns, download ownership
 * and metadata, queue snapshots and the per-play history design (T22 option b).
 * It also applies the T24 clean-room table renames (own vocabulary):
 * playlists_entries -> playlist_items, internet_radios -> radio_stations,
 * download_items -> download_queue, offline_files -> offline_media,
 * sync_status -> sync_state.
 *
 * Orphan download policy: `download_queue.providerId` is derived from the
 * `stream:<providerId>:...` media key (see [DownloadKeys]). Rows whose key
 * carries no reliable provider id, or whose provider row no longer exists,
 * are deleted. No owner is ever invented for a download row.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        dropCascadeTriggers(db)
        rebuildProviders(db)
        rebuildDownloadQueue(db)
        renameLegacyTables(db)
        createQueueTables(db)
        migratePlaybackHistory(db)
        ProviderCascadeTriggers.install(db)
    }
}

/**
 * Cascade triggers carry old table names in their own names and bodies. They
 * are torn down here and reinstalled (with the v2 names) at the end, so both
 * the fresh-database path (no triggers yet) and the production path (v1
 * triggers installed on open) end with exactly one trigger per statement.
 */
private fun dropCascadeTriggers(db: SupportSQLiteDatabase) {
    val v1Tables = listOf(
        "library_sources", "artists", "albums", "genres", "songs", "album_artists",
        "song_artists", "chapters", "playlists", "playlists_entries", "internet_radios",
        "playback_events", "outbox_items", "offline_files", "download_items",
        "sync_status", "queue_entries",
    )
    v1Tables.forEach { db.execSQL("DROP TRIGGER IF EXISTS trg_provider_delete_$it") }
    db.execSQL("DROP TRIGGER IF EXISTS trg_playlist_delete_playlists_entries")
}

private fun rebuildProviders(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `providers_new` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `api` TEXT NOT NULL,
            `isEnabled` INTEGER NOT NULL,
            `displayOrder` INTEGER NOT NULL,
            `kind` TEXT NOT NULL DEFAULT 'REMOTE_SERVER',
            `primaryScheme` TEXT,
            `primaryHost` TEXT,
            `primaryPort` INTEGER,
            `primaryPath` TEXT,
            `secondaryScheme` TEXT,
            `secondaryHost` TEXT,
            `secondaryPort` INTEGER,
            `secondaryPath` TEXT,
            `useSecondary` INTEGER NOT NULL,
            `lastWorkingBlock` INTEGER,
            `wifiOnly` INTEGER NOT NULL DEFAULT 0,
            `mtlsEnabled` INTEGER NOT NULL DEFAULT 0,
            `mtlsKeystoreBlob` TEXT,
            `mtlsAlias` TEXT,
            `trustedCertsBlob` TEXT,
            `username` TEXT NOT NULL,
            `secretBlob` TEXT,
            `authParametersJson` TEXT,
            `optionsJson` TEXT,
            `customHeadersJson` TEXT,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO `providers_new` (
            `id`, `name`, `api`, `isEnabled`, `displayOrder`, `kind`,
            `primaryScheme`, `primaryHost`, `primaryPort`, `primaryPath`,
            `secondaryScheme`, `secondaryHost`, `secondaryPort`, `secondaryPath`,
            `useSecondary`, `lastWorkingBlock`, `wifiOnly`, `mtlsEnabled`,
            `username`, `secretBlob`, `authParametersJson`, `optionsJson`,
            `customHeadersJson`, `createdAt`, `updatedAt`
        )
        SELECT
            `id`, `name`, `api`, `isEnabled`, `displayOrder`, 'REMOTE_SERVER',
            `primaryScheme`, `primaryHost`, `primaryPort`, `primaryPath`,
            `secondaryScheme`, `secondaryHost`, `secondaryPort`, `secondaryPath`,
            `useSecondary`, `lastWorkingBlock`, 0, 0,
            `username`, `secretBlob`, `authParametersJson`, `optionsJson`,
            `customHeadersJson`, `createdAt`, `updatedAt`
        FROM `providers`
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE `providers`")
    db.execSQL("ALTER TABLE `providers_new` RENAME TO `providers`")
}

private fun rebuildDownloadQueue(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `download_queue_new` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `mediaKey` TEXT NOT NULL,
            `providerId` INTEGER NOT NULL,
            `settingsJson` TEXT,
            `status` TEXT NOT NULL,
            `title` TEXT,
            `imagePath` TEXT,
            `progressPercent` INTEGER NOT NULL,
            `errorCount` INTEGER NOT NULL,
            `lastError` TEXT,
            `transcodeBitrateKbps` INTEGER,
            `tempCache` INTEGER NOT NULL,
            `exportTarget` TEXT,
            `autoRuleId` INTEGER,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL
        )
        """.trimIndent(),
    )

    val insertSql =
        """
        INSERT INTO `download_queue_new` (
            `id`, `mediaKey`, `providerId`, `settingsJson`, `status`, `title`,
            `imagePath`, `progressPercent`, `errorCount`, `lastError`,
            `transcodeBitrateKbps`, `tempCache`, `exportTarget`, `autoRuleId`,
            `createdAt`, `updatedAt`
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL, ?, ?)
        """.trimIndent()

    db.query(
        """
        SELECT `id`, `mediaKey`, `settingsJson`, `status`, `title`, `imagePath`,
               `progressPercent`, `errorCount`, `lastError`, `transcodeBitrateKbps`,
               `createdAt`, `updatedAt`
        FROM `download_items`
        """.trimIndent(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val mediaKey = cursor.getString(1)
            val providerId = DownloadKeys.providerIdFromMediaKey(mediaKey)
                ?.takeIf { providerExists(db, it) }
                ?: continue
            db.execSQL(
                insertSql,
                arrayOf(
                    cursor.getLong(0),
                    mediaKey,
                    providerId,
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getString(5),
                    cursor.getInt(6),
                    cursor.getInt(7),
                    cursor.getString(8),
                    if (cursor.isNull(9)) null else cursor.getInt(9),
                    cursor.getLong(10),
                    cursor.getLong(11),
                ),
            )
        }
    }

    db.execSQL("DROP TABLE `download_items`")
    db.execSQL("ALTER TABLE `download_queue_new` RENAME TO `download_queue`")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_download_queue_mediaKey` ON `download_queue` (`mediaKey`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_queue_status` ON `download_queue` (`status`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_queue_providerId` ON `download_queue` (`providerId`)")
}

/**
 * T24 renames: SQLite keeps table data and its indices attached through
 * ALTER TABLE RENAME, but index names keep their old table prefix. Each rename
 * therefore recreates the indices under the v2 names Room expects.
 */
private fun renameLegacyTables(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `playlists_entries` RENAME TO `playlist_items`")
    db.execSQL("DROP INDEX IF EXISTS `index_playlists_entries_playlistId_entryOrder`")
    db.execSQL("DROP INDEX IF EXISTS `index_playlists_entries_providerId`")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId_entryOrder` ON `playlist_items` (`playlistId`, `entryOrder`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_items_providerId` ON `playlist_items` (`providerId`)")

    db.execSQL("ALTER TABLE `internet_radios` RENAME TO `radio_stations`")
    db.execSQL("DROP INDEX IF EXISTS `index_internet_radios_providerId_externalId`")
    db.execSQL("DROP INDEX IF EXISTS `index_internet_radios_providerId`")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_radio_stations_providerId_externalId` ON `radio_stations` (`providerId`, `externalId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_radio_stations_providerId` ON `radio_stations` (`providerId`)")

    db.execSQL("ALTER TABLE `offline_files` RENAME TO `offline_media`")
    db.execSQL("DROP INDEX IF EXISTS `index_offline_files_sourceKey`")
    db.execSQL("DROP INDEX IF EXISTS `index_offline_files_offlinePath`")
    db.execSQL("DROP INDEX IF EXISTS `index_offline_files_providerId`")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_media_sourceKey` ON `offline_media` (`sourceKey`)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_media_offlinePath` ON `offline_media` (`offlinePath`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_media_providerId` ON `offline_media` (`providerId`)")

    db.execSQL("ALTER TABLE `sync_status` RENAME TO `sync_state`")
    db.execSQL("DROP INDEX IF EXISTS `index_sync_status_providerId`")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_state_providerId` ON `sync_state` (`providerId`)")
}

private fun providerExists(db: SupportSQLiteDatabase, providerId: Long): Boolean =
    db.query("SELECT 1 FROM `providers` WHERE `id` = ?", arrayOf(providerId)).use { it.moveToFirst() }

private fun createQueueTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `queue_entries` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `queueItemId` INTEGER NOT NULL,
            `position` INTEGER NOT NULL,
            `providerId` INTEGER NOT NULL,
            `externalId` TEXT NOT NULL,
            `songRowId` INTEGER,
            `title` TEXT NOT NULL,
            `artistName` TEXT,
            `albumExternalId` TEXT,
            `coverImageId` TEXT,
            `durationMs` INTEGER,
            `isRadioStation` INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_entries_queueItemId` ON `queue_entries` (`queueItemId`)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_entries_position` ON `queue_entries` (`position`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_entries_providerId` ON `queue_entries` (`providerId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_entries_providerId_externalId` ON `queue_entries` (`providerId`, `externalId`)")

    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `queue_state` (
            `id` INTEGER NOT NULL,
            `playIndex` INTEGER NOT NULL,
            `shuffleOrderJson` TEXT,
            `shuffleSeed` INTEGER,
            `repeatMode` TEXT NOT NULL,
            `speed` REAL NOT NULL,
            `pitch` REAL NOT NULL,
            `sourceType` TEXT,
            `sourceData` TEXT,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}

private fun migratePlaybackHistory(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `play_events` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `providerId` INTEGER NOT NULL,
            `externalId` TEXT NOT NULL,
            `playedAtMs` INTEGER NOT NULL,
            `dayStartMs` INTEGER NOT NULL,
            `played` INTEGER NOT NULL,
            `skipped` INTEGER NOT NULL,
            `durationMs` INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_events_providerId_externalId_playedAtMs` ON `play_events` (`providerId`, `externalId`, `playedAtMs`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_events_providerId_dayStartMs` ON `play_events` (`providerId`, `dayStartMs`)")

    db.execSQL(
        """
        INSERT INTO `play_events` (
            `id`, `providerId`, `externalId`, `playedAtMs`, `dayStartMs`,
            `played`, `skipped`, `durationMs`
        )
        SELECT `id`, `providerId`, `externalId`, `date`, `date`,
               `played`, `skipped`, `durationMs`
        FROM `playback_events`
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE `playback_events`")

    db.execSQL(
        "CREATE VIEW `play_events_daily` AS SELECT providerId,\n" +
            "               externalId,\n" +
            "               dayStartMs AS date,\n" +
            "               MAX(played) AS played,\n" +
            "               MAX(skipped) AS skipped,\n" +
            "               SUM(durationMs) AS durationMs,\n" +
            "               COUNT(*) AS playCount\n" +
            "        FROM play_events\n" +
            "        GROUP BY providerId, externalId, dayStartMs",
    )
}
