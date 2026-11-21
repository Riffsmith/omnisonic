package app.omnisonic.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * Migration harness for the v1 baseline and the approved additive steps:
 * the offline read model (Phase 7 task 5a, D-43) and the automatic-rule table
 * (task 7a, D-47). The harness seeds exported baselines, runs the real
 * migration path, and validates against the newest export. The v2 case proves
 * existing rows survive and that the new table is writable and uniquely indexed;
 * the fresh-database case checks provider-delete trigger installation.
 */
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
    fun `the current baseline is v3 with two additive migration steps`() {
        assertThat(OmnisonicDatabase.SCHEMA_VERSION).isEqualTo(3)
        assertThat(Migrations.ALL.map { it.startVersion to it.endVersion })
            .containsExactly(1 to 2, 2 to 3)
            .inOrder()
        assertThat(OmnisonicDatabase.migrationsForSteps()).hasLength(2)
    }

    @Test
    fun `v1 baseline seeds through the harness and validates against the export`() {
        val name = "migration-harness-v1.db"
        appContext.deleteDatabase(name)

        helper.createDatabase(name, 1).use { v1 ->
            seedV1(v1)
        }

        helper.runMigrationsAndValidate(name, 3, true, *Migrations.ALL.toTypedArray()).use { db ->
            assertRenamedColumns(db)
            assertPlayEventsDailyView(db)
            assertQueueState(db)
            assertOfflineReadModelUpgrade(db)
            db.query("SELECT COUNT(*) FROM download_rules").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test
    fun `v2 baseline seeds through the harness and gains the rule table`() {
        val name = "migration-harness-v2.db"
        appContext.deleteDatabase(name)

        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO providers (id, name, api, isEnabled, displayOrder, useSecondary, username, createdAt, updatedAt) VALUES (42, 'fixture', 'subsonic', 1, 0, 0, 'alice', 1000, 2000)")
            db.execSQL(
                """
                INSERT INTO offline_media (
                    id, providerId, mediaType, sourceKey, offlinePath, sizeBytes, title,
                    downloadedAt, tempCache, externalId
                ) VALUES (21, 42, 'SONG', 'stream:42:song-1:auto:auto', 'OfflineMedias/a/hash.flac', 4096, 'Track One', 1000, 1, 'song-1')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO download_queue (
                    id, mediaKey, providerId, status, progressPercent, errorCount,
                    tempCache, autoRuleId, createdAt, updatedAt
                ) VALUES (31, 'stream:42:song-1:auto:auto', 42, 'PENDING', 0, 0, 1, 77, 1000, 2000)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(name, 3, true, *Migrations.ALL.toTypedArray()).use { db ->
            db.query("SELECT tempCache, externalId FROM offline_media WHERE id = 21").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(1)
                assertThat(cursor.getString(1)).isEqualTo("song-1")
            }
            db.query("SELECT autoRuleId FROM download_queue WHERE id = 31").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(77)
            }

            db.execSQL(
                """
                INSERT INTO download_rules (providerId, kind, targetExternalId, transcodeBitrateKbps, format, createdAt, updatedAt)
                VALUES (42, 'FAVORITES', '', NULL, NULL, 1000, 1000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO download_rules (providerId, kind, targetExternalId, transcodeBitrateKbps, format, createdAt, updatedAt)
                VALUES (42, 'PLAYLIST', 'pl-1', 192, 'mp3', 1000, 1000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO download_rules (providerId, kind, targetExternalId, transcodeBitrateKbps, format, createdAt, updatedAt)
                VALUES (42, 'PLAYLIST', 'pl-1', 320, 'flac', 2000, 2000)
                """.trimIndent(),
            )
            db.query("SELECT kind, targetExternalId, transcodeBitrateKbps FROM download_rules ORDER BY kind").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("FAVORITES")
                assertThat(cursor.getString(1)).isEqualTo("")
                assertThat(cursor.isNull(2)).isTrue()
                assertThat(cursor.moveToNext()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("PLAYLIST")
                assertThat(cursor.getString(1)).isEqualTo("pl-1")
                assertThat(cursor.getInt(2)).isEqualTo(192)
                assertThat(cursor.moveToNext()).isFalse()
            }
        }
    }

    @Test
    fun `v1 database opens through the production factory and keeps its rows`() {
        val name = "migration-harness-v1-factory.db"
        appContext.deleteDatabase(name)

        helper.createDatabase(name, 1).use { v1 ->
            seedV1(v1)
        }

        val db = OmnisonicDatabaseFactory(appContext).open(name)
        try {
            val song = runBlocking { db.songDao().selectById(7) }
            assertThat(song).isNotNull()
            assertThat(song!!.latinTitle).isEqualTo("Taitoru")
            assertThat(song.providerPayloadJson).isEqualTo("""{"server":"fixture"}""")
            assertThat(song.resumeMs).isEqualTo(42_000)
            assertThat(song.windowStartMs).isEqualTo(1_000)
            assertThat(song.windowEndMs).isEqualTo(2_000)
            assertThat(song.lastSeenSyncId).isEqualTo(3)
            val offline = runBlocking { db.offlineFileDao().selectBySourceKey("stream:42:song-1:auto:auto") }
            assertThat(offline).isNotNull()
            assertThat(offline!!.offlinePath).isEqualTo("OfflineMedias/a/hash.flac")
            assertThat(offline.tempCache).isFalse()
            assertThat(offline.externalId).isEqualTo("song-1")
            val queue = runBlocking { db.downloadDao().selectById(31) }
            assertThat(queue).isNotNull()
            assertThat(queue!!.status).isEqualTo("PENDING")
            assertThat(queue.tempCache).isTrue()
        } finally {
            db.close()
        }
    }

    @Test
    fun `fileBuilderInstallsCascadeTriggersOnOpen`() {
        val name = "migration-harness-triggers.db"
        appContext.deleteDatabase(name)

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
            assertThat(triggerNames).contains("trg_provider_delete_download_rules")
        } finally {
            db.close()
        }
    }

    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO providers (
                id, name, api, isEnabled, displayOrder, useSecondary,
                username, createdAt, updatedAt
            ) VALUES (42, 'fixture', 'subsonic', 1, 0, 0, 'alice', 1000, 2000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO albums (
                id, providerId, externalId, providerPayloadJson, title, latinTitle,
                sortTitle, displayArtist, year, isCompilation, imagePath, description,
                playCount, lastPlayed, userRating, isFavorite, favoriteAt, serverRating,
                resumeTrackPath, durationMs, songCount, isAudiobook, discNamesJson,
                genresJson, lastSeenSyncId, updatedAt
            ) VALUES (
                5, 42, 'album-1', '{"server":"fixture"}', 'Album One', 'Arubamu',
                'album one', 'Artist One', 2020, 0, NULL, NULL,
                1, 1000, NULL, 0, NULL, NULL,
                '/music/one.flac', 300000, 1, 0, '["Disc One"]',
                '["Rock"]', 3, 2000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO songs (
                id, providerId, externalId, providerPayloadJson, albumId, title,
                latinTitle, sortTitle, displayArtist, filePath, durationMs, trackNo,
                discNo, year, genresJson, imagePath, playCount, lastPlayed, skipped,
                lastSkipped, userRating, isFavorite, favoriteAt, serverRating,
                resumeMs, resumeUpdatedAt, missingOnServer, replayGainTrack,
                replayGainAlbum, replayGainType, windowStartMs, windowEndMs,
                isAudiobook, codec, bitrateKbps, sampleRate, bitDepth, channelCount,
                lastSeenSyncId, updatedAt
            ) VALUES (
                7, 42, 'song-1', '{"server":"fixture"}', 5, 'Track One',
                'Taitoru', 'track one', 'Artist One', '/music/one.flac', 300000, 1,
                1, 2020, '["Rock"]', NULL, 4, 1000, 0,
                NULL, NULL, 0, NULL, NULL,
                42000, 1500, 0, -1.5,
                -1.0, 1, 1000, 2000,
                0, 'flac', 900, 44100, 16, 2,
                3, 2000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO chapters (
                id, providerId, albumId, songId, externalId, chapterIndex, title,
                positionMs, endPositionMs, origin, rank, updatedAt
            ) VALUES (11, 42, 5, 7, 'chapter-1', 0, 'Intro', 0, 60000, 'embedded', 2, 2000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO playlists (
                id, providerId, externalId, providerPayloadJson, title, description,
                imagePath, type, ruleJson, isFavorite, favoriteAt, autoSync,
                autoOffline, entryCount, durationMs, lastModified, lastPlayed, hidden,
                lastSeenSyncId, updatedAt
            ) VALUES (
                9, 42, 'pl-1', NULL, 'Road music', NULL,
                NULL, 0, '{"field":"genre","value":"Rock"}', 0, NULL, 0,
                0, 1, 300000, 2000, NULL, 0,
                3, 2000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO playlist_items (
                id, playlistId, providerId, position, externalId, songId, filePath,
                title, displayArtist, durationMs, imagePath, addedAt
            ) VALUES (13, 9, 42, 0, 'song-1', 7, NULL, 'Track One', 'Artist One', 300000, NULL, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO queue_state (
                id, playIndex, shuffleOrderJson, shuffleSeed, repeatMode, speed,
                pitch, sourceType, sourceData, positionMs
            ) VALUES (1, 0, NULL, NULL, 'OFF', 1.0, 1.0, NULL, NULL, 12345)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO play_events (
                id, providerId, externalId, playedAtMs, dayStartMs, played, skipped, durationMs
            ) VALUES (17, 42, 'song-1', 1736100000000, 1736100000000, 1, 0, 300000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO outbox_items (
                id, providerId, mediaType, externalId, action, payloadJson, attempts,
                nextAttemptAt, lastError, createdAt
            ) VALUES (19, 42, 'SONG', 'song-1', 'resume_point', '{"resumeMs":42000}', 0, 0, NULL, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO offline_media (
                id, providerId, mediaType, sourceKey, offlinePath, sizeBytes, title,
                imagePath, bitrateKbps, autoRule, downloadedAt, syncedAt
            ) VALUES (
                21, 42, 'SONG', 'stream:42:song-1:auto:auto', 'OfflineMedias/a/hash.flac',
                4096, 'Track One', NULL, NULL, NULL, 1000, NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO offline_media (
                id, providerId, mediaType, sourceKey, offlinePath, sizeBytes, downloadedAt
            ) VALUES (
                22, 42, 'SONG', 'stream:42:disc:one:128:mp3', 'OfflineMedias/b/other.mp3',
                2048, 2000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO offline_media (
                id, providerId, mediaType, sourceKey, offlinePath, sizeBytes, downloadedAt
            ) VALUES (
                23, 42, 'SONG', 'legacy-key', 'OfflineMedias/c/legacy.bin', 512, 3000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO download_queue (
                id, mediaKey, providerId, status, progressPercent, errorCount,
                tempCache, createdAt, updatedAt
            ) VALUES (31, 'stream:42:song-1:auto:auto', 42, 'PENDING', 40, 2, 1, 1000, 2000)
            """.trimIndent(),
        )
    }

    private fun assertOfflineReadModelUpgrade(db: SupportSQLiteDatabase) {
        db.query("SELECT sourceKey, offlinePath, sizeBytes, title, downloadedAt, tempCache, externalId FROM offline_media WHERE id = 21").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("stream:42:song-1:auto:auto")
            assertThat(cursor.getString(1)).isEqualTo("OfflineMedias/a/hash.flac")
            assertThat(cursor.getLong(2)).isEqualTo(4_096)
            assertThat(cursor.getString(3)).isEqualTo("Track One")
            assertThat(cursor.getLong(4)).isEqualTo(1_000)
            assertThat(cursor.getInt(5)).isEqualTo(0)
            assertThat(cursor.getString(6)).isEqualTo("song-1")
        }
        db.query(
            "SELECT sourceKey, offlinePath, sizeBytes, externalId, tempCache FROM offline_media WHERE id = 22",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("stream:42:disc:one:128:mp3")
            assertThat(cursor.getString(1)).isEqualTo("OfflineMedias/b/other.mp3")
            assertThat(cursor.getLong(2)).isEqualTo(2_048)
            assertThat(cursor.getString(3)).isEqualTo("disc:one")
            assertThat(cursor.getInt(4)).isEqualTo(0)
        }
        db.query(
            "SELECT sourceKey, offlinePath, sizeBytes, externalId, tempCache FROM offline_media WHERE id = 23",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("legacy-key")
            assertThat(cursor.getString(1)).isEqualTo("OfflineMedias/c/legacy.bin")
            assertThat(cursor.getLong(2)).isEqualTo(512)
            assertThat(cursor.isNull(3)).isTrue()
            assertThat(cursor.getInt(4)).isEqualTo(0)
        }
        db.query("SELECT status, progressPercent, errorCount, tempCache FROM download_queue WHERE id = 31").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("PENDING")
            assertThat(cursor.getInt(1)).isEqualTo(40)
            assertThat(cursor.getInt(2)).isEqualTo(2)
            assertThat(cursor.getInt(3)).isEqualTo(1)
        }
    }

    private fun assertRenamedColumns(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT providerPayloadJson, latinTitle, resumeMs, windowStartMs, windowEndMs
            FROM songs WHERE id = 7
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("""{"server":"fixture"}""")
            assertThat(cursor.getString(1)).isEqualTo("Taitoru")
            assertThat(cursor.getLong(2)).isEqualTo(42_000)
            assertThat(cursor.getLong(3)).isEqualTo(1_000)
            assertThat(cursor.getLong(4)).isEqualTo(2_000)
        }
        db.query("SELECT resumeTrackPath, discNamesJson FROM albums WHERE id = 5").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("/music/one.flac")
            assertThat(cursor.getString(1)).isEqualTo("""["Disc One"]""")
        }
        db.query("SELECT origin, rank FROM chapters WHERE id = 11").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("embedded")
            assertThat(cursor.getInt(1)).isEqualTo(2)
        }
        db.query("SELECT ruleJson FROM playlists WHERE id = 9").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("""{"field":"genre","value":"Rock"}""")
        }
        db.query("SELECT position FROM playlist_items WHERE id = 13").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    private fun assertPlayEventsDailyView(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT date, played, skipped, durationMs, playCount FROM play_events_daily",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1_736_100_000_000)
            assertThat(cursor.getInt(1)).isEqualTo(1)
            assertThat(cursor.getInt(2)).isEqualTo(0)
            assertThat(cursor.getLong(3)).isEqualTo(300_000)
            assertThat(cursor.getInt(4)).isEqualTo(1)
        }
    }

    private fun assertQueueState(db: SupportSQLiteDatabase) {
        db.query("SELECT playIndex, positionMs FROM queue_state WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
            assertThat(cursor.getLong(1)).isEqualTo(12_345)
        }
    }
}
