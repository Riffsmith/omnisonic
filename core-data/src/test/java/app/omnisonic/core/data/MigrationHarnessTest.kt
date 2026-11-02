package app.omnisonic.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omnisonic.core.data.db.MIGRATION_1_2
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
    fun migrationsContainExactlyOneExplicitV1ToV2Step() {
        assertThat(Migrations.ALL).hasSize(1)
        val step = Migrations.ALL.single()
        assertThat(step.startVersion).isEqualTo(1)
        assertThat(step.endVersion).isEqualTo(2)
        assertThat(step).isSameInstanceAs(MIGRATION_1_2)
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

    private fun assertDownloadBackfill(db: SupportSQLiteDatabase) {
        db.query("SELECT id, providerId, tempCache, exportTarget, autoRuleId FROM download_items").use { cursor ->
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
        assertThat(count(db, "download_items")).isEqualTo(1)
        assertThat(count(db, "play_events")).isEqualTo(1)
        assertThat(count(db, "outbox_items")).isEqualTo(1)
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

        db.execSQL("DELETE FROM providers WHERE id = 42")
        assertThat(count(db, "songs")).isEqualTo(0)
        assertThat(count(db, "download_items")).isEqualTo(0)
        assertThat(count(db, "play_events")).isEqualTo(0)
        assertThat(count(db, "outbox_items")).isEqualTo(0)

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
