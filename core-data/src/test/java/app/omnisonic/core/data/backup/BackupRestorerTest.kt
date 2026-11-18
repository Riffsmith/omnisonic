package app.omnisonic.core.data.backup

import android.content.ContentResolver
import android.net.Uri
import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.history.HistoryRecordingGate
import app.omnisonic.core.data.json.OmnisonicJson
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Types
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestorerTest : DatabaseTestBase() {

    private val manifestAdapter = OmnisonicJson.moshi.adapter(BackupManifest::class.java)
    private val songListAdapter = OmnisonicJson.moshi.adapter<List<SongUserData>>(
        Types.newParameterizedType(List::class.java, SongUserData::class.java),
    )
    private val eventListAdapter = OmnisonicJson.moshi.adapter<List<PlayEventRecord>>(
        Types.newParameterizedType(List::class.java, PlayEventRecord::class.java),
    )
    private val settingsAdapter = OmnisonicJson.moshi.adapter(BackupSettingsSnapshot::class.java)

    private fun restorer(
        settings: PlayAggregateSettings = PlayAggregateSettings(),
        gate: HistoryRecordingGate = HistoryRecordingGate(),
    ) = BackupRestorer(db, settings, gate)

    private suspend fun expectRestoreFailure(
        bytes: ByteArray,
        mode: RestoreMode = RestoreMode.MERGE,
    ): BackupRestoreException =
        try {
            restorer().restore(ByteArrayInputStream(bytes), mode)
            throw AssertionError("expected BackupRestoreException")
        } catch (e: BackupRestoreException) {
            e
        }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, json) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifestJson(
        formatVersion: Int = BackupFormat.FORMAT_VERSION,
        providers: List<ProviderBackupSection> = emptyList(),
        settingsEntry: String = BackupFormat.SETTINGS_ENTRY,
        styleEntry: String? = null,
    ): String = manifestAdapter.toJson(
        BackupManifest(
            formatVersion = formatVersion,
            appVersion = "9.9.9",
            createdAtMs = 42,
            providers = providers,
            settingsEntry = settingsEntry,
            styleEntry = styleEntry,
        ),
    )

    private fun section(
        providerId: Long,
        name: String = "Server $providerId",
        songs: Int,
        events: Int,
    ) = ProviderBackupSection(
        providerId = providerId,
        name = name,
        userDataEntry = BackupFormat.userDataEntry(providerId),
        eventsEntry = BackupFormat.eventsEntry(providerId),
        songCount = songs,
        eventCount = events,
    )

    private fun settingsJson(
        rule: String = "AVERAGE",
        supporting: Boolean = false,
        paused: List<Long> = emptyList(),
    ) = settingsAdapter.toJson(BackupSettingsSnapshot(rule, supporting, paused))

    private fun songData(
        externalId: String? = "song-1",
        filePath: String? = null,
        playCount: Int = 0,
        lastPlayed: Long? = null,
        skipped: Boolean = false,
        lastSkipped: Long? = null,
        userRating: Int? = null,
        isFavorite: Boolean = false,
        favoriteAt: Long? = null,
        resumeMs: Long? = null,
        resumeUpdatedAt: Long? = null,
    ) = SongUserData(
        externalId, filePath, playCount, lastPlayed, skipped, lastSkipped,
        userRating, isFavorite, favoriteAt, resumeMs, resumeUpdatedAt,
    )

    private fun event(
        externalId: String = "song-1",
        playedAtMs: Long,
        played: Boolean = true,
        skipped: Boolean = false,
        durationMs: Long = 100_000,
    ) = PlayEventRecord(externalId, playedAtMs, dayStartMs = 1, played, skipped, durationMs)

    private fun validZip(
        providerId: Long = 1,
        name: String = "Server one",
        userData: List<SongUserData> = emptyList(),
        events: List<PlayEventRecord> = emptyList(),
        settings: String = settingsJson(),
        style: String? = null,
        formatVersion: Int = BackupFormat.FORMAT_VERSION,
    ): ByteArray {
        val entries = mutableListOf<Pair<String, String>>()
        entries += BackupFormat.MANIFEST_ENTRY to manifestJson(
            formatVersion = formatVersion,
            providers = listOf(section(providerId, name, userData.size, events.size)),
            styleEntry = if (style != null) BackupFormat.STYLE_ENTRY else null,
        )
        entries += BackupFormat.userDataEntry(providerId) to songListAdapter.toJson(userData)
        entries += BackupFormat.eventsEntry(providerId) to eventListAdapter.toJson(events)
        entries += BackupFormat.SETTINGS_ENTRY to settings
        if (style != null) entries += BackupFormat.STYLE_ENTRY to style
        return zipBytes(*entries.toTypedArray())
    }

    private suspend fun seedLocalState() {
        newProvider(id = 1, name = "Server one")
        newSong(id = 1, providerId = 1, playCount = 3, lastPlayed = 50)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 10, durationMs = 100)
    }

    private suspend fun assertNoWrites() {
        assertThat(providerDao.count()).isEqualTo(1)
        assertThat(songDao.selectAll()).hasSize(1)
        assertThat(songDao.selectById(1)!!.playCount).isEqualTo(3)
        assertThat(playEventDao.count()).isEqualTo(1)
    }

    @Test
    fun `garbage bytes are rejected as malformed before any write`() = runDbTest {
        seedLocalState()

        val e = expectRestoreFailure("this is not a zip".toByteArray())

        assertThat(e).isInstanceOf(MalformedBackup::class.java)
        assertNoWrites()
    }

    @Test
    fun `zip without manifest entry is rejected with no writes`() = runDbTest {
        seedLocalState()

        val e = expectRestoreFailure(zipBytes(BackupFormat.SETTINGS_ENTRY to settingsJson()))

        assertThat(e).isInstanceOf(MissingManifest::class.java)
        assertNoWrites()
    }

    @Test
    fun `manifest that is not the first entry is rejected`() = runDbTest {
        seedLocalState()
        val bytes = zipBytes(
            BackupFormat.SETTINGS_ENTRY to settingsJson(),
            BackupFormat.MANIFEST_ENTRY to manifestJson(),
        )

        val e = expectRestoreFailure(bytes)

        assertThat(e).isInstanceOf(MalformedBackup::class.java)
        assertNoWrites()
    }

    @Test
    fun `unknown format version is rejected before any write`() = runDbTest {
        seedLocalState()

        val e = expectRestoreFailure(validZip(formatVersion = 99))

        assertThat(e).isInstanceOf(UnsupportedFormatVersion::class.java)
        assertThat((e as UnsupportedFormatVersion).version).isEqualTo(99)
        assertNoWrites()
    }

    @Test
    fun `manifest without format version field is rejected as malformed`() = runDbTest {
        seedLocalState()
        val manifestWithoutVersion = """
            {"appVersion":"9.9.9","createdAtMs":42,"providers":[],"settingsEntry":"settings.json"}
        """.trimIndent()
        val bytes = zipBytes(
            BackupFormat.MANIFEST_ENTRY to manifestWithoutVersion,
            BackupFormat.SETTINGS_ENTRY to settingsJson(),
        )

        val e = expectRestoreFailure(bytes)

        assertThat(e).isInstanceOf(MalformedEntry::class.java)
        assertThat((e as MalformedEntry).entryName).isEqualTo(BackupFormat.MANIFEST_ENTRY)
        assertNoWrites()
    }

    @Test
    fun `missing referenced provider entry is rejected with no writes`() = runDbTest {
        seedLocalState()
        val bytes = zipBytes(
            BackupFormat.MANIFEST_ENTRY to manifestJson(providers = listOf(section(1, songs = 1, events = 1))),
            BackupFormat.SETTINGS_ENTRY to settingsJson(),
        )

        val e = expectRestoreFailure(bytes)

        assertThat(e).isInstanceOf(MissingEntry::class.java)
        assertThat((e as MissingEntry).entryName).isEqualTo(BackupFormat.userDataEntry(1))
        assertNoWrites()
    }

    @Test
    fun `undecodable provider entry is rejected with no writes`() = runDbTest {
        seedLocalState()
        val bytes = zipBytes(
            BackupFormat.MANIFEST_ENTRY to manifestJson(providers = listOf(section(1, songs = 1, events = 0))),
            BackupFormat.userDataEntry(1) to "not json at all",
            BackupFormat.eventsEntry(1) to "[]",
            BackupFormat.SETTINGS_ENTRY to settingsJson(),
        )

        val e = expectRestoreFailure(bytes)

        assertThat(e).isInstanceOf(MalformedEntry::class.java)
        assertThat((e as MalformedEntry).entryName).isEqualTo(BackupFormat.userDataEntry(1))
        assertNoWrites()
    }

    @Test
    fun `section counts that do not match the entries are rejected`() = runDbTest {
        seedLocalState()
        val bytes = zipBytes(
            BackupFormat.MANIFEST_ENTRY to manifestJson(providers = listOf(section(1, songs = 5, events = 0))),
            BackupFormat.userDataEntry(1) to "[]",
            BackupFormat.eventsEntry(1) to "[]",
            BackupFormat.SETTINGS_ENTRY to settingsJson(),
        )

        val e = expectRestoreFailure(bytes)

        assertThat(e).isInstanceOf(MalformedEntry::class.java)
        assertThat((e as MalformedEntry).entryName).isEqualTo(BackupFormat.MANIFEST_ENTRY)
        assertNoWrites()
    }

    @Test
    fun `unknown album count rule in the settings entry is rejected`() = runDbTest {
        seedLocalState()

        val e = expectRestoreFailure(validZip(settings = settingsJson(rule = "WEIRD")))

        assertThat(e).isInstanceOf(MalformedEntry::class.java)
        assertThat((e as MalformedEntry).entryName).isEqualTo(BackupFormat.SETTINGS_ENTRY)
        assertNoWrites()
    }

    @Test
    fun `merge inserts only new events and lets the backup win reported conflicts`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        newSong(id = 1, providerId = 1, playCount = 2, lastPlayed = 50)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 100, durationMs = 1_000)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 300, durationMs = 1_000)
        val backup = validZip(
            userData = listOf(songData(playCount = 7, lastPlayed = 200, userRating = 5)),
            events = listOf(
                event(playedAtMs = 100),
                event(playedAtMs = 200, played = false, skipped = true),
            ),
        )

        val parsed = restorer().readBackup(ByteArrayInputStream(backup))
        val preview = restorer().preview(parsed)
        val p = preview.providers.single()
        assertThat(p.localProviderId).isEqualTo(1)
        assertThat(p.songCount).isEqualTo(1)
        assertThat(p.eventCount).isEqualTo(2)
        assertThat(p.newEvents).isEqualTo(1)
        assertThat(p.duplicateEvents).isEqualTo(1)
        assertThat(p.localEventCount).isEqualTo(2)
        assertThat(p.songsMatched).isEqualTo(1)
        assertThat(p.songsUnmatched).isEqualTo(0)
        assertThat(p.conflicts.single().fields).containsExactly("playCount", "lastPlayed", "userRating")
        assertThat(p.conflicts.single().externalId).isEqualTo("song-1")
        // preview wrote nothing
        assertThat(playEventDao.count()).isEqualTo(2)
        assertThat(songDao.selectById(1)!!.playCount).isEqualTo(2)

        val result = restorer().restore(parsed, RestoreMode.MERGE)
        val r = result.providers.single()
        assertThat(r.applied).isTrue()
        assertThat(r.eventsInserted).isEqualTo(1)
        assertThat(r.eventsRemoved).isEqualTo(0)
        assertThat(r.songsUpdated).isEqualTo(1)
        assertThat(r.songsSkipped).isEqualTo(0)
        assertThat(r.conflicts).hasSize(1)

        val song = songDao.selectById(1)!!
        assertThat(song.playCount).isEqualTo(7)
        assertThat(song.lastPlayed).isEqualTo(200)
        assertThat(song.userRating).isEqualTo(5)
        val events = playEventDao.selectByProvider(1)
        assertThat(events.map { it.playedAtMs }).containsExactly(100L, 200L, 300L).inOrder()
        assertThat(events.single { it.playedAtMs == 200L }.skipped).isTrue()
    }

    @Test
    fun `replace wipes the provider history then applies the backup`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        newSong(id = 1, providerId = 1, playCount = 9, lastPlayed = 50)
        songDao.upsert(
            SongEntity(
                id = 2,
                providerId = 1,
                externalId = "song-2",
                title = "Second",
                playCount = 4,
                userRating = 3,
                updatedAt = 1_000,
            ),
        )
        playEventDao.recordPlay(1, "song-1", playedAtMs = 50, durationMs = 1_000)
        playEventDao.recordPlay(1, "song-2", playedAtMs = 60, durationMs = 1_000)
        seedOutboxItem(1)
        val backup = validZip(
            userData = listOf(songData(playCount = 7, lastPlayed = 200)),
            events = listOf(event(playedAtMs = 100)),
        )

        val result = restorer().restore(restorer().readBackup(ByteArrayInputStream(backup)), RestoreMode.REPLACE)
        val r = result.providers.single()
        assertThat(r.eventsRemoved).isEqualTo(2)
        assertThat(r.eventsInserted).isEqualTo(1)
        assertThat(r.songsUpdated).isEqualTo(1)
        assertThat(r.songsSkipped).isEqualTo(0)
        assertThat(r.conflicts.single().fields).containsExactly("playCount", "lastPlayed")

        assertThat(playEventDao.selectByProvider(1).map { it.playedAtMs }).containsExactly(100L)
        val song1 = songDao.selectById(1)!!
        assertThat(song1.playCount).isEqualTo(7)
        assertThat(song1.lastPlayed).isEqualTo(200)
        val song2 = songDao.selectById(2)!!
        assertThat(song2.playCount).isEqualTo(0)
        assertThat(song2.userRating).isEqualTo(3)
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun `unmatched provider section is skipped and reported`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        val backup = validZip(
            providerId = 99,
            name = "Ghost",
            userData = listOf(songData()),
            events = listOf(event(playedAtMs = 100)),
        )

        val parsed = restorer().readBackup(ByteArrayInputStream(backup))
        val preview = restorer().preview(parsed)
        assertThat(preview.providers.single().localProviderId).isNull()
        assertThat(preview.providers.single().songsUnmatched).isEqualTo(1)

        val result = restorer().restore(parsed, RestoreMode.MERGE)
        val r = result.providers.single()
        assertThat(r.applied).isFalse()
        assertThat(r.localProviderId).isNull()
        assertThat(r.songsSkipped).isEqualTo(1)
        assertThat(r.eventsInserted).isEqualTo(0)
        assertThat(playEventDao.count()).isEqualTo(0)
        assertThat(songDao.selectAll()).isEmpty()
    }

    @Test
    fun `provider resolves by name when the backup id is not local`() = runDbTest {
        newProvider(id = 7, name = "Server one")
        newSong(id = 1, providerId = 7, playCount = 1)
        val backup = validZip(
            providerId = 1,
            name = "Server one",
            userData = listOf(songData(playCount = 7)),
            events = listOf(event(playedAtMs = 100)),
        )

        val result = restorer().restore(restorer().readBackup(ByteArrayInputStream(backup)), RestoreMode.MERGE)

        val r = result.providers.single()
        assertThat(r.localProviderId).isEqualTo(7)
        assertThat(r.songsUpdated).isEqualTo(1)
        assertThat(songDao.selectById(1)!!.playCount).isEqualTo(7)
        assertThat(playEventDao.selectByProvider(7)).hasSize(1)
    }

    @Test
    fun `events for songs without a local row are kept and their user data is skipped`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        val backup = validZip(
            userData = listOf(songData(externalId = "ghost", playCount = 3)),
            events = listOf(event(externalId = "ghost", playedAtMs = 100)),
        )

        val result = restorer().restore(restorer().readBackup(ByteArrayInputStream(backup)), RestoreMode.MERGE)

        val r = result.providers.single()
        assertThat(r.songsSkipped).isEqualTo(1)
        assertThat(r.songsUpdated).isEqualTo(0)
        assertThat(r.eventsInserted).isEqualTo(1)
        assertThat(playEventDao.selectByProvider(1).map { it.externalId }).containsExactly("ghost")
    }

    @Test
    fun `settings snapshot is applied and the style profile is returned`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        newProvider(id = 2, name = "Two")
        val settings = PlayAggregateSettings()
        val gate = HistoryRecordingGate()
        gate.setPaused(2, true)
        val style = """{"accent":"#123456"}"""
        val backup = validZip(
            settings = settingsJson(rule = "SUM", supporting = true, paused = listOf(1)),
            style = style,
        )

        val result = restorer(settings, gate)
            .restore(restorer(settings, gate).readBackup(ByteArrayInputStream(backup)), RestoreMode.MERGE)

        assertThat(result.settingsApplied).isTrue()
        assertThat(result.styleProfileJson).isEqualTo(style)
        assertThat(settings.options().albumCountRule).isEqualTo(AlbumCountRule.SUM)
        assertThat(settings.options().includeSupportingArtists).isTrue()
        assertThat(gate.pausedProviders.value).containsExactly(1L)
    }

    @Test
    fun `replace recomputes album counts with the snapshot rule`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        newAlbum(id = 1, providerId = 1)
        newSong(id = 1, providerId = 1, albumId = 1, playCount = 9)
        newSong(id = 2, providerId = 1, albumId = 1, playCount = 4)
        val backup = validZip(
            settings = settingsJson(rule = "SUM"),
            userData = listOf(songData(playCount = 3), songData(externalId = "song-2", playCount = 5)),
        )

        restorer().restore(restorer().readBackup(ByteArrayInputStream(backup)), RestoreMode.REPLACE)

        assertThat(albumDao.selectById(1)!!.playCount).isEqualTo(8)
    }

    @Test
    fun `saf source reads the zip through the content resolver`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        newSong(id = 1, providerId = 1)
        val bytes = validZip(
            userData = listOf(songData(playCount = 7)),
            events = listOf(event(playedAtMs = 100)),
        )
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Arestore.zip")
        val resolver = mockk<ContentResolver> {
            every { openInputStream(uri) } returns ByteArrayInputStream(bytes)
        }

        val result = restorer().restore(SafBackupDocumentSource(resolver, uri), RestoreMode.MERGE)

        verify(exactly = 1) { resolver.openInputStream(uri) }
        assertThat(result.providers.single().songsUpdated).isEqualTo(1)
        assertThat(songDao.selectById(1)!!.playCount).isEqualTo(7)
        assertThat(playEventDao.count()).isEqualTo(1)
    }

    @Test
    fun `writer round trip restores every value into a fresh database`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        songDao.upsert(
            SongEntity(
                id = 1,
                providerId = 1,
                externalId = "song-1",
                title = "Song one",
                playCount = 7,
                lastPlayed = 111_000,
                skipped = true,
                lastSkipped = 222_000,
                userRating = 4,
                isFavorite = true,
                favoriteAt = 333_000,
                resumeMs = 444_000,
                resumeUpdatedAt = 555_000,
                updatedAt = 1_000,
            ),
        )
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 200_000)
        playEventDao.recordSkip(1, "song-2", playedAtMs = 500, durationMs = 100_000)
        val settings = PlayAggregateSettings()
        settings.update(PlayAggregateSettings.Options(albumCountRule = AlbumCountRule.SUM, includeSupportingArtists = true))
        val gate = HistoryRecordingGate()
        gate.setPaused(1, true)
        val style = """{"accent":"#aabbcc"}"""
        val out = ByteArrayOutputStream()
        BackupWriter(db, settings, gate).writeBackup(out, "1.2.3", style)
        val bytes = out.toByteArray()

        val fresh = OmnisonicDatabaseFactory(appContext).openInMemory()
        try {
            fresh.providerDao().upsert(
                ProviderEntity(
                    id = 1,
                    name = "Server one",
                    api = "api-test",
                    primaryScheme = "https",
                    primaryHost = "host.example",
                    primaryPort = 443,
                    primaryPath = "/",
                    username = "user",
                    createdAt = 1_000,
                    updatedAt = 1_000,
                ),
            )
            fresh.songDao().upsert(
                SongEntity(id = 1, providerId = 1, externalId = "song-1", title = "Song one", updatedAt = 1_000),
            )
            val freshRestorer = BackupRestorer(fresh, PlayAggregateSettings(), HistoryRecordingGate())
            val parsed = freshRestorer.readBackup(ByteArrayInputStream(bytes))
            assertThat(parsed.manifest.appVersion).isEqualTo("1.2.3")
            assertThat(parsed.styleProfileJson).isEqualTo(style)

            val result = freshRestorer.restore(parsed, RestoreMode.REPLACE)

            assertThat(result.providers.single().localProviderId).isEqualTo(1)
            val song = fresh.songDao().selectById(1)!!
            assertThat(song.playCount).isEqualTo(7)
            assertThat(song.lastPlayed).isEqualTo(111_000)
            assertThat(song.skipped).isTrue()
            assertThat(song.lastSkipped).isEqualTo(222_000)
            assertThat(song.userRating).isEqualTo(4)
            assertThat(song.isFavorite).isTrue()
            assertThat(song.favoriteAt).isEqualTo(333_000)
            assertThat(song.resumeMs).isEqualTo(444_000)
            assertThat(song.resumeUpdatedAt).isEqualTo(555_000)
            val events = fresh.playEventDao().selectByProvider(1)
            assertThat(events.map { it.externalId to it.playedAtMs }).containsExactly(
                "song-2" to 500L,
                "song-1" to 1_000L,
            ).inOrder()
            assertThat(events[0].skipped).isTrue()
            assertThat(events[1].played).isTrue()
            assertThat(events[1].durationMs).isEqualTo(200_000)
        } finally {
            fresh.close()
        }
    }
}
