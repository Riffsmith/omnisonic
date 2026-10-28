package app.omnisonic.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseDurabilityTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun abruptCloseKeepsCommittedDataOnlyAndReopensHealthyInWal() {
        val name = "durability.db"
        val committedProviderId = 42
        val danglingProviderId = 777

        val first = OmnisonicDatabaseFactory(appContext).open(name)
        val openHelper = first.openHelper.writableDatabase
        openHelper.execSQL(
            "INSERT INTO providers (id, name, api, isEnabled, displayOrder, primaryScheme, primaryHost, primaryPort, primaryPath, useSecondary, username, createdAt, updatedAt) VALUES ($committedProviderId, 'committed', 'subsonic', 1, 0, 'https', 'server.example', 443, '/', 0, 'user', 1, 1)",
        )

        openHelper.beginTransaction()
        openHelper.execSQL(
            "INSERT INTO providers (id, name, api, isEnabled, displayOrder, primaryScheme, primaryHost, primaryPort, primaryPath, useSecondary, username, createdAt, updatedAt) VALUES ($danglingProviderId, 'dangling', 'subsonic', 1, 0, 'https', 'dangling.example', 443, '/', 0, 'dangling', 1, 1)",
        )
        first.close()

        val second = OmnisonicDatabaseFactory(appContext).open(name)
        try {
            val ids = mutableListOf<Long>()
            second.openHelper.readableDatabase.query("SELECT id FROM providers ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
            assertThat(ids).containsExactly(committedProviderId.toLong())

            second.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                cursor.moveToFirst()
                assertThat(cursor.getString(0)).isEqualTo("ok")
            }

            second.openHelper.readableDatabase.query("PRAGMA journal_mode").use { cursor ->
                cursor.moveToFirst()
                assertThat(cursor.getString(0)).isEqualTo("wal")
            }
        } finally {
            second.close()
        }
    }
}
