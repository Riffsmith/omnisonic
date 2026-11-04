package app.omnisonic.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omnisonic.core.data.db.MIGRATION_1_2
import app.omnisonic.core.data.db.MIGRATION_2_3
import app.omnisonic.core.data.db.Migrations
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.ProviderCascadeTriggers
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationHarnessTest {

    private lateinit var helper: MigrationTestHelper
    private lateinit var appContext: android.content.Context

    @Before
    fun prepareHarness() {
        appContext = ApplicationProvider.getApplicationContext()
        helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OmnisonicDatabase::class.java,
        )
    }

    @Test
    fun migrationsContainTheExplicitUpgradeStepsInOrder() {
        assertThat(Migrations.ALL).hasSize(2)
        val (v1ToV2, v2ToV3) = Migrations.ALL
        assertThat(v1ToV2.startVersion).isEqualTo(1)
        assertThat(v1ToV2.endVersion).isEqualTo(2)
        assertThat(v1ToV2).isSameInstanceAs(MIGRATION_1_2)
        assertThat(v2ToV3.startVersion).isEqualTo(2)
        assertThat(v2ToV3.endVersion).isEqualTo(3)
        assertThat(v2ToV3).isSameInstanceAs(MIGRATION_2_3)
    }

    @Test
    fun v2SchemaMigratesToV3AddingTheQueuePosition() {
        val name = "migration-harness-v2-to-v3.db"
        appContext.deleteDatabase(name)

        helper.createDatabase(name, 2).use { v2 ->
            v2.execSQL(
                """
                INSERT INTO queue_state (id, playIndex, repeatMode, speed, pitch)
                VALUES (1, 2, 'ALL', 1.25, 0.75)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT playIndex, repeatMode, speed, pitch, positionMs FROM queue_state WHERE id = 1").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(2)
                assertThat(cursor.getString(1)).isEqualTo("ALL")
                assertThat(cursor.getDouble(2)).isEqualTo(1.25)
                assertThat(cursor.getDouble(3)).isEqualTo(0.75)
                assertThat(cursor.isNull(4)).isTrue()
            }
        }
    }

    @Test
    fun v1SchemaMigratesToV2WithDefaultsBackfillHistoryAndCascade() {
        val name = "migration-harness-v1-to-v2.db"
        appContext.deleteDatabase(name)

        helper.createDatabase(name, 1).use { v1 ->
            seedV1(v1)
        }

        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { db ->
            assertThat(recursiveTriggers(db)).isEqualTo(0)
            assertProviderDefaults(db)
            assertCleanRoomRenames(db)
            assertDownloadBackfill(db)
            assertHistoryMigration(db)
            assertQueueRestoreWithoutSong(db)
            assertProviderEditKeepsChildren(db)
            assertNewProviderUsesDefaults(db)
            assertProviderDeleteCascades(db)
        }
    }

    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO providers (
                id, name, api, isEnabled, displayOrder,
                primaryScheme, primaryHost, primaryPort, primaryPath,
                secondaryScheme, secondaryHost, secondaryPort, secondaryPath,
                useSecondary, lastWorkingBlock, username, secretBlob,
                authParametersJson, optionsJson, customHeadersJson, createdAt, updatedAt
            ) VALUES (
                42, 'storage', 'subsonic', 1, 0,
                'https', 'server.example', 443, '/rest',
                'http', 'backup.example', 8080, '/backup',
                1, 1, 'alice', NULL,
                NULL, '{"software":"navidrome"}', NULL, 1000, 2000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO providers (
                id, name, api, isEnabled, displayOrder,
                primaryScheme, primaryHost, primaryPort, primaryPath,
                useSecondary, username, createdAt, updatedAt
            ) VALUES (
                43, 'second', 'subsonic', 1, 1,
                'http', 'second.example', 80, '/',
                0, 'bob', 1000, 2000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO songs (
                id, providerId, externalId, title, playCount, isFavorite,
                skipped, missingOnServer, isAudiobook, updatedAt
            ) VALUES (7, 42, 'song-1', 'Track One', 5, 1, 0, 0, 0, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO outbox_items (
                id, providerId, mediaType, externalId, action, payloadJson,
                attempts, nextAttemptAt, lastError, createdAt
            ) VALUES (1, 42, 'SONG', 'song-1', 'scrobble', NULL, 0, 0, NULL, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO download_items (
                id, mediaKey, settingsJson, status, title, imagePath,
                progressPercent, errorCount, lastError, transcodeBitrateKbps,
                createdAt, updatedAt
            ) VALUES (
                5, 'stream:42:song-1:auto:auto', NULL, 'completed', 'Track One', NULL,
                100, 0, NULL, NULL, 1000, 2000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO download_items (
                id, mediaKey, settingsJson, status, title, imagePath,
                progressPercent, errorCount, lastError, transcodeBitrateKbps,
                createdAt, updatedAt
            ) VALUES (6, 'legacy-key', NULL, 'pending', 'Old', NULL, 0, 0, NULL, NULL, 1000, 2000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO download_items (
                id, mediaKey, settingsJson, status, title, imagePath,
                progressPercent, errorCount, lastError, transcodeBitrateKbps,
                createdAt, updatedAt
            ) VALUES (8, 'stream:99:ghost:auto:auto', NULL, 'pending', 'Ghost', NULL, 0, 0, NULL, NULL, 1000, 2000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO playlists (
                id, providerId, externalId, title, type, isFavorite, autoSync,
                autoOffline, entryCount, hidden, updatedAt
            ) VALUES (9, 42, 'pl-1', 'Road music', 'manual', 0, 0, 0, 1, 0, 2000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO playlists_entries (
                id, playlistId, providerId, entryOrder, externalId, songId, filePath,
                title, displayArtist, durationMs, imagePath, addedAt
            ) VALUES (11, 9, 42, 0, 'song-1', 7, NULL, 'Track One', 'Artist One', 300, NULL, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO internet_radios (
                id, providerId, externalId, title, streamUrl, isFavorite, updatedAt
            ) VALUES (13, 42, 'radio-1', 'Static FM', 'http://radio.example/stream', 1, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO offline_files (
                id, providerId, mediaType, sourceKey, offlinePath, downloadedAt
            ) VALUES (15, 42, 'SONG', 'source-key-1', '/offline/track-1.mp3', 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO sync_status (
                id, providerId, lastFullSyncAt, lastDiffSyncAt, lastSyncResult, lastSyncCount
            ) VALUES (17, 42, 1000, 1500, 1, 12)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO playback_events (
                id, providerId, externalId, date, played, skipped, durationMs
            ) VALUES (3, 42, 'song-1', 1736100000000, 1, 0, 300)
            """.trimIndent(),
        )
    }

    private fun recursiveTriggers(db: SupportSQLiteDatabase): Int =
        db.query("PRAGMA recursive_triggers").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun assertProviderDefaults(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT id, name, kind, primaryScheme, primaryHost, primaryPort, primaryPath,
                   secondaryScheme, secondaryHost, secondaryPort, secondaryPath,
                   useSecondary, lastWorkingBlock, wifiOnly, mtlsEnabled,
                   mtlsKeystoreBlob, mtlsAlias, trustedCertsBlob, username,
                   secretBlob, authParametersJson, optionsJson, customHeadersJson,
                   createdAt, updatedAt
            FROM providers WHERE id = 42
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(1)).isEqualTo("storage")
            assertThat(cursor.getString(2)).isEqualTo("REMOTE_SERVER")
            assertThat(cursor.getString(3)).isEqualTo("https")
            assertThat(cursor.getString(4)).isEqualTo("server.example")
            assertThat(cursor.getInt(5)).isEqualTo(443)
            assertThat(cursor.getString(6)).isEqualTo("/rest")
            assertThat(cursor.getString(7)).isEqualTo("http")
            assertThat(cursor.getString(8)).isEqualTo("backup.example")
            assertThat(cursor.getInt(9)).isEqualTo(8080)
            assertThat(cursor.getString(10)).isEqualTo("/backup")
            assertThat(cursor.getInt(11)).isEqualTo(1)
            assertThat(cursor.getInt(12)).isEqualTo(1)
            assertThat(cursor.getInt(13)).isEqualTo(0)
            assertThat(cursor.getInt(14)).isEqualTo(0)
            assertThat(cursor.isNull(15)).isTrue()
            assertThat(cursor.isNull(16)).isTrue()
            assertThat(cursor.isNull(17)).isTrue()
            assertThat(cursor.getString(18)).isEqualTo("alice")
            assertThat(cursor.isNull(20)).isTrue()
            assertThat(cursor.getString(21)).isEqualTo("""{"software":"navidrome"}""")
            assertThat(cursor.getLong(23)).isEqualTo(1000)
            assertThat(cursor.getLong(24)).isEqualTo(2000)
        }
        assertThat(count(db, "songs")).isEqualTo(1)
        assertThat(count(db, "outbox_items")).isEqualTo(1)
    }

    private fun assertCleanRoomRenames(db: SupportSQLiteDatabase) {
        listOf(
            "playlists_entries", "internet_radios", "download_items",
            "offline_files", "sync_status",
        ).forEach { assertThat(tableExists(db, it)).isFalse() }

        db.query(
            "SELECT playlistId, providerId, entryOrder, externalId, songId, title, displayArtist, durationMs, addedAt FROM playlist_items WHERE id = 11",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(9)
            assertThat(cursor.getLong(1)).isEqualTo(42)
            assertThat(cursor.getInt(2)).isEqualTo(0)
            assertThat(cursor.getString(3)).isEqualTo("song-1")
            assertThat(cursor.getLong(4)).isEqualTo(7)
            assertThat(cursor.getString(5)).isEqualTo("Track One")
            assertThat(cursor.getString(6)).isEqualTo("Artist One")
            assertThat(cursor.getLong(7)).isEqualTo(300)
            assertThat(cursor.getLong(8)).isEqualTo(1000)
        }
        db.query(
            "SELECT providerId, externalId, title, streamUrl, isFavorite FROM radio_stations WHERE id = 13",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(42)
            assertThat(cursor.getString(1)).isEqualTo("radio-1")
            assertThat(cursor.getString(2)).isEqualTo("Static FM")
            assertThat(cursor.getString(3)).isEqualTo("http://radio.example/stream")
            assertThat(cursor.getInt(4)).isEqualTo(1)
        }
        db.query(
            "SELECT providerId, mediaType, sourceKey, offlinePath FROM offline_media WHERE id = 15",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(42)
            assertThat(cursor.getString(1)).isEqualTo("SONG")
            assertThat(cursor.getString(2)).isEqualTo("source-key-1")
            assertThat(cursor.getString(3)).isEqualTo("/offline/track-1.mp3")
        }
        db.query(
            "SELECT providerId, lastFullSyncAt, lastDiffSyncAt, lastSyncResult, lastSyncCount FROM sync_state WHERE id = 17",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(42)
            assertThat(cursor.getLong(1)).isEqualTo(1000)
            assertThat(cursor.getLong(2)).isEqualTo(1500)
            assertThat(cursor.getInt(3)).isEqualTo(1)
            assertThat(cursor.getInt(4)).isEqualTo(12)
        }

        val indexNames = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%' ORDER BY name",
        ).use { cursor ->
            mutableListOf<String>().apply { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertThat(indexNames).containsAtLeast(
            "index_playlist_items_playlistId_entryOrder",
            "index_playlist_items_providerId",
            "index_radio_stations_providerId_externalId",
            "index_radio_stations_providerId",
            "index_download_queue_mediaKey",
            "index_download_queue_status",
            "index_download_queue_providerId",
            "index_offline_media_sourceKey",
            "index_offline_media_offlinePath",
            "index_offline_media_providerId",
            "index_sync_state_providerId",
        )
        listOf(
            "index_playlists_entries_playlistId_entryOrder",
            "index_playlists_entries_providerId",
            "index_internet_radios_providerId_externalId",
            "index_internet_radios_providerId",
            "index_download_items_mediaKey",
            "index_download_items_status",
            "index_offline_files_sourceKey",
            "index_offline_files_offlinePath",
            "index_offline_files_providerId",
            "index_sync_status_providerId",
        ).forEach { assertThat(indexNames).doesNotContain(it) }
    }

    private fun assertDownloadBackfill(db: SupportSQLiteDatabase) {
        db.query("SELECT id, providerId, tempCache, exportTarget, autoRuleId FROM download_queue").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(5)
            assertThat(cursor.getLong(1)).isEqualTo(42)
            assertThat(cursor.getInt(2)).isEqualTo(0)
            assertThat(cursor.isNull(3)).isTrue()
            assertThat(cursor.isNull(4)).isTrue()
            assertThat(cursor.moveToNext()).isFalse()
        }
    }

    private fun assertHistoryMigration(db: SupportSQLiteDatabase) {
        assertThat(tableExists(db, "playback_events")).isFalse()
        db.query(
            "SELECT providerId, externalId, playedAtMs, dayStartMs, played, skipped, durationMs FROM play_events",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(42)
            assertThat(cursor.getString(1)).isEqualTo("song-1")
            assertThat(cursor.getLong(2)).isEqualTo(1_736_100_000_000)
            assertThat(cursor.getLong(3)).isEqualTo(1_736_100_000_000)
            assertThat(cursor.getInt(4)).isEqualTo(1)
            assertThat(cursor.getInt(5)).isEqualTo(0)
            assertThat(cursor.getInt(6)).isEqualTo(300)
            assertThat(cursor.moveToNext()).isFalse()
        }
        db.query("SELECT date, played, skipped, durationMs, playCount FROM play_events_daily").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1_736_100_000_000)
            assertThat(cursor.getInt(1)).isEqualTo(1)
            assertThat(cursor.getInt(2)).isEqualTo(0)
            assertThat(cursor.getInt(3)).isEqualTo(300)
            assertThat(cursor.getInt(4)).isEqualTo(1)
        }
    }

    private fun assertQueueRestoreWithoutSong(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO queue_entries (
                queueItemId, position, providerId, externalId, songRowId,
                title, artistName, albumExternalId, coverImageId, durationMs,
                isRadioStation
            ) VALUES (10, 0, 43, 'missing-song', NULL, 'Ghost', 'Nobody', 'album-1', 'cover-1', 1234, 0)
            """.trimIndent(),
        )
        db.query(
            "SELECT title, artistName, albumExternalId, coverImageId, durationMs FROM queue_entries WHERE queueItemId = 10",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Ghost")
            assertThat(cursor.getString(1)).isEqualTo("Nobody")
            assertThat(cursor.getString(2)).isEqualTo("album-1")
            assertThat(cursor.getString(3)).isEqualTo("cover-1")
            assertThat(cursor.getInt(4)).isEqualTo(1234)
        }
    }

    private fun assertProviderEditKeepsChildren(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE providers SET name = 'renamed', primaryHost = 'new.example' WHERE id = 42")
        assertThat(count(db, "songs")).isEqualTo(1)
        assertThat(count(db, "download_queue")).isEqualTo(1)
        assertThat(count(db, "play_events")).isEqualTo(1)
        assertThat(count(db, "outbox_items")).isEqualTo(1)
        assertThat(count(db, "playlist_items")).isEqualTo(1)
        assertThat(count(db, "radio_stations")).isEqualTo(1)
        assertThat(count(db, "offline_media")).isEqualTo(1)
        assertThat(count(db, "sync_state")).isEqualTo(1)
    }

    private fun assertNewProviderUsesDefaults(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO providers (id, name, api, isEnabled, displayOrder, useSecondary, username, createdAt, updatedAt)
            VALUES (44, 'fresh', 'subsonic', 1, 2, 0, 'carol', 3000, 3000)
            """.trimIndent(),
        )
        db.query("SELECT kind, wifiOnly, mtlsEnabled FROM providers WHERE id = 44").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("REMOTE_SERVER")
            assertThat(cursor.getInt(1)).isEqualTo(0)
            assertThat(cursor.getInt(2)).isEqualTo(0)
        }
    }

    private fun assertProviderDeleteCascades(db: SupportSQLiteDatabase) {
        val triggerNames = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name",
        ).use { cursor ->
            mutableListOf<String>().apply { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertThat(triggerNames).hasSize(ProviderCascadeTriggers.CREATE_STATEMENTS.size)
        assertThat(triggerNames).containsNoDuplicates()
        assertThat(triggerNames).containsAtLeast(
            "trg_provider_delete_playlist_items",
            "trg_provider_delete_radio_stations",
            "trg_provider_delete_download_queue",
            "trg_provider_delete_offline_media",
            "trg_provider_delete_sync_state",
            "trg_playlist_delete_playlist_items",
        )
        listOf(
            "trg_provider_delete_playlists_entries",
            "trg_provider_delete_internet_radios",
            "trg_provider_delete_download_items",
            "trg_provider_delete_offline_files",
            "trg_provider_delete_sync_status",
            "trg_playlist_delete_playlists_entries",
        ).forEach { assertThat(triggerNames).doesNotContain(it) }

        db.execSQL("DELETE FROM providers WHERE id = 42")
        assertThat(count(db, "songs")).isEqualTo(0)
        assertThat(count(db, "download_queue")).isEqualTo(0)
        assertThat(count(db, "play_events")).isEqualTo(0)
        assertThat(count(db, "outbox_items")).isEqualTo(0)
        assertThat(count(db, "playlist_items")).isEqualTo(0)
        assertThat(count(db, "radio_stations")).isEqualTo(0)
        assertThat(count(db, "offline_media")).isEqualTo(0)
        assertThat(count(db, "sync_state")).isEqualTo(0)

        db.execSQL("DELETE FROM providers WHERE id = 43")
        assertThat(count(db, "queue_entries")).isEqualTo(0)
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
            it.moveToFirst()
        }

    @Test
    fun fileBuilderInstallsCascadeTriggersOnOpen() {
        val name = "migration-harness-triggers.db"

        val db = OmnisonicDatabaseFactory(appContext).open(name)
        try {
            val triggerNames = runBlocking {
                db.withTransaction {
                    db.openHelper.writableDatabase.query(
                        "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name",
                    ).use { cursor ->
                        mutableListOf<String>().apply { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
                }
            }
            assertThat(triggerNames).hasSize(ProviderCascadeTriggers.CREATE_STATEMENTS.size)
            assertThat(triggerNames).containsNoDuplicates()
        } finally {
            db.close()
        }
    }
}
