package app.omnisonic.core.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omnisonic.core.data.db.Migrations
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.ProviderCascadeTriggers
import app.omnisonic.core.data.db.entity.ProviderEntity
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
    fun createdV1SchemaThenRealRoomOpenValidatesAndServesData() {
        val name = "migration-harness-v1.db"
        val provider = ProviderEntity(
            id = 42,
            name = "storage",
            api = "subsonic",
            primaryScheme = "https",
            primaryHost = "server.example",
            primaryPort = 443,
            primaryPath = "/",
            username = "u",
            createdAt = 1,
            updatedAt = 1,
        )

        helper.createDatabase(name, OmnisonicDatabase.SCHEMA_VERSION).use { v1 ->
            v1.execSQL("DELETE FROM providers")
            v1.execSQL("INSERT INTO providers (id, name, api, isEnabled, displayOrder, primaryScheme, primaryHost, primaryPort, primaryPath, useSecondary, username, createdAt, updatedAt) VALUES (42, 'storage', 'subsonic', 1, 0, 'https', 'server.example', 443, '/', 0, 'u', 1, 1)")
        }

        val db = Room.databaseBuilder(appContext, OmnisonicDatabase::class.java, name)
            .addMigrations(*Migrations.ALL.toTypedArray())
            .build()
        try {
            val loaded = runBlocking { db.providerDao().selectById(42) }
            assertThat(loaded).isEqualTo(provider)
        } finally {
            db.close()
        }
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
