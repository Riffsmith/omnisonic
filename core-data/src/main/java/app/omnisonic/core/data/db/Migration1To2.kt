package app.omnisonic.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The single v1 -> v2 schema migration. It carries every T16 schema change:
 * provider kinds/endpoints, Wi-Fi and opt-in mTLS columns, download ownership
 * and metadata, queue snapshots and the per-play history design (T22 option b).
 *
 * Orphan download policy: `download_items.providerId` is derived from the
 * `stream:<providerId>:...` media key (see [DownloadKeys]). Rows whose key
 * carries no reliable provider id, or whose provider row no longer exists,
 * are deleted. No owner is ever invented for a download row.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildProviders(db)
        rebuildDownloadItems(db)
        createQueueTables(db)
        migratePlaybackHistory(db)
        ProviderCascadeTriggers.install(db)
    }
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

private fun rebuildDownloadItems(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `download_items_new` (
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
        INSERT INTO `download_items_new` (
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
    db.execSQL("ALTER TABLE `download_items_new` RENAME TO `download_items`")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_download_items_mediaKey` ON `download_items` (`mediaKey`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_items_status` ON `download_items` (`status`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_items_providerId` ON `download_items` (`providerId`)")
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
