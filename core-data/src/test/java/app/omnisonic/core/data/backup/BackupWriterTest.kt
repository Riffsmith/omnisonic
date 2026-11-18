package app.omnisonic.core.data.backup

import android.content.ContentResolver
import android.net.Uri
import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.PlaybackDay
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
import java.util.zip.ZipInputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWriterTest : DatabaseTestBase() {

    private val manifestAdapter = OmnisonicJson.moshi.adapter(BackupManifest::class.java)
    private val songListAdapter = OmnisonicJson.moshi.adapter<List<SongUserData>>(
        Types.newParameterizedType(List::class.java, SongUserData::class.java),
    )
    private val eventListAdapter = OmnisonicJson.moshi.adapter<List<PlayEventRecord>>(
        Types.newParameterizedType(List::class.java, PlayEventRecord::class.java),
    )
    private val settingsAdapter = OmnisonicJson.moshi.adapter(BackupSettingsSnapshot::class.java)

    private fun writer(
        settings: PlayAggregateSettings = PlayAggregateSettings(),
        gate: HistoryRecordingGate = HistoryRecordingGate(),
    ) = BackupWriter(db = db, aggregateSettings = settings, recordingGate = gate)

    private suspend fun writeToEntries(
        appVersion: String = "1.2.3",
        styleProfileJson: String? = null,
        settings: PlayAggregateSettings = PlayAggregateSettings(),
        gate: HistoryRecordingGate = HistoryRecordingGate(),
    ): Pair<BackupManifest, Map<String, String>> {
        val out = ByteArrayOutputStream()
        val manifest = writer(settings, gate).writeBackup(out, appVersion, styleProfileJson)
        return manifest to unzip(out.toByteArray())
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return result
    }

    private suspend fun seedSongWithUserData() {
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
    }

    @Test
    fun `manifest is the first entry and carries version app version and per provider sections`() = runDbTest {
        newProvider(id = 1, name = "Server one")
        seedSongWithUserData()
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 200_000)

        val (manifest, entries) = writeToEntries(appVersion = "9.9.9")

        assertThat(entries.keys.first()).isEqualTo(BackupFormat.MANIFEST_ENTRY)
        assertThat(manifest.formatVersion).isEqualTo(BackupFormat.FORMAT_VERSION)
        assertThat(manifest.appVersion).isEqualTo("9.9.9")
        assertThat(manifest.createdAtMs).isGreaterThan(0)
        assertThat(manifest.settingsEntry).isEqualTo(BackupFormat.SETTINGS_ENTRY)
        assertThat(manifest.styleEntry).isNull()
        assertThat(manifest.providers).hasSize(1)
        val section = manifest.providers.single()
        assertThat(section.providerId).isEqualTo(1)
        assertThat(section.name).isEqualTo("Server one")
        assertThat(section.userDataEntry).isEqualTo(BackupFormat.userDataEntry(1))
        assertThat(section.eventsEntry).isEqualTo(BackupFormat.eventsEntry(1))
        assertThat(section.songCount).isEqualTo(1)
        assertThat(section.eventCount).isEqualTo(1)

        val reparsed = manifestAdapter.fromJson(entries.getValue(BackupFormat.MANIFEST_ENTRY))!!
        assertThat(reparsed).isEqualTo(manifest)
    }

    @Test
    fun `per provider entries hold only that provider rows`() = runDbTest {
        newProvider(id = 1, name = "One")
        newProvider(id = 2, name = "Two")
        newSong(id = 1, providerId = 1, playCount = 2)
        newSong(id = 2, providerId = 2, playCount = 5)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 10, durationMs = 100)
        playEventDao.recordSkip(2, "song-2", playedAtMs = 20, durationMs = 100)

        val (manifest, entries) = writeToEntries()

        val songsOne = songListAdapter.fromJson(entries.getValue(BackupFormat.userDataEntry(1)))!!
        val songsTwo = songListAdapter.fromJson(entries.getValue(BackupFormat.userDataEntry(2)))!!
        assertThat(songsOne.map { it.externalId }).containsExactly("song-1")
        assertThat(songsTwo.map { it.externalId }).containsExactly("song-2")
        val eventsOne = eventListAdapter.fromJson(entries.getValue(BackupFormat.eventsEntry(1)))!!
        val eventsTwo = eventListAdapter.fromJson(entries.getValue(BackupFormat.eventsEntry(2)))!!
        assertThat(eventsOne.map { it.externalId }).containsExactly("song-1")
        assertThat(eventsTwo.map { it.externalId }).containsExactly("song-2")
        assertThat(manifest.providers.map { it.providerId }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `empty provider still gets its entries with zero counts`() = runDbTest {
        newProvider(id = 3, name = "Empty")

        val (manifest, entries) = writeToEntries()

        val section = manifest.providers.single()
        assertThat(section.providerId).isEqualTo(3)
        assertThat(section.songCount).isEqualTo(0)
        assertThat(section.eventCount).isEqualTo(0)
        assertThat(songListAdapter.fromJson(entries.getValue(BackupFormat.userDataEntry(3)))).isEmpty()
        assertThat(eventListAdapter.fromJson(entries.getValue(BackupFormat.eventsEntry(3)))).isEmpty()
    }

    @Test
    fun `song user data values round trip through the zip`() = runDbTest {
        newProvider(id = 1)
        seedSongWithUserData()
        songDao.upsert(
            SongEntity(
                id = 2,
                providerId = 1,
                externalId = null,
                filePath = "/music/local.mp3",
                title = "Local file",
                playCount = 1,
                updatedAt = 1_000,
            ),
        )

        val (_, entries) = writeToEntries()

        val songs = songListAdapter.fromJson(entries.getValue(BackupFormat.userDataEntry(1)))!!
        assertThat(songs).hasSize(2)
        val remote = songs.single { it.externalId == "song-1" }
        assertThat(remote.playCount).isEqualTo(7)
        assertThat(remote.lastPlayed).isEqualTo(111_000)
        assertThat(remote.skipped).isTrue()
        assertThat(remote.lastSkipped).isEqualTo(222_000)
        assertThat(remote.userRating).isEqualTo(4)
        assertThat(remote.isFavorite).isTrue()
        assertThat(remote.favoriteAt).isEqualTo(333_000)
        assertThat(remote.resumeMs).isEqualTo(444_000)
        assertThat(remote.resumeUpdatedAt).isEqualTo(555_000)
        val local = songs.single { it.filePath == "/music/local.mp3" }
        assertThat(local.externalId).isNull()
        assertThat(local.playCount).isEqualTo(1)
    }

    @Test
    fun `event log round trips in played at order with played and skipped flags`() = runDbTest {
        newProvider(id = 1)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 200_000)
        playEventDao.recordSkip(1, "song-2", playedAtMs = 500, durationMs = 100_000)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 3_000, durationMs = 200_000)

        val (_, entries) = writeToEntries()

        val events = eventListAdapter.fromJson(entries.getValue(BackupFormat.eventsEntry(1)))!!
        assertThat(events.map { it.playedAtMs }).containsExactly(500L, 1_000L, 3_000L).inOrder()
        val skip = events[0]
        assertThat(skip.externalId).isEqualTo("song-2")
        assertThat(skip.played).isFalse()
        assertThat(skip.skipped).isTrue()
        assertThat(skip.durationMs).isEqualTo(100_000)
        assertThat(skip.dayStartMs).isEqualTo(PlaybackDay.dayStart(500))
        val play = events[1]
        assertThat(play.played).isTrue()
        assertThat(play.skipped).isFalse()
        assertThat(play.dayStartMs).isEqualTo(PlaybackDay.dayStart(1_000))
    }

    @Test
    fun `songs without user data are not exported`() = runDbTest {
        newProvider(id = 1)
        newSong(id = 1, providerId = 1)
        songDao.upsert(
            SongEntity(
                id = 2,
                providerId = 1,
                externalId = "song-2",
                title = "Rated",
                userRating = 5,
                updatedAt = 1_000,
            ),
        )

        val (manifest, entries) = writeToEntries()

        val songs = songListAdapter.fromJson(entries.getValue(BackupFormat.userDataEntry(1)))!!
        assertThat(songs.map { it.externalId }).containsExactly("song-2")
        assertThat(manifest.providers.single().songCount).isEqualTo(1)
    }

    @Test
    fun `settings snapshot carries the aggregate options and paused providers`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        val settings = PlayAggregateSettings()
        settings.update(PlayAggregateSettings.Options(albumCountRule = AlbumCountRule.SUM, includeSupportingArtists = true))
        val gate = HistoryRecordingGate()
        gate.setPaused(2, true)

        val (_, entries) = writeToEntries(settings = settings, gate = gate)

        val snapshot = settingsAdapter.fromJson(entries.getValue(BackupFormat.SETTINGS_ENTRY))!!
        assertThat(snapshot.albumCountRule).isEqualTo("SUM")
        assertThat(snapshot.includeSupportingArtists).isTrue()
        assertThat(snapshot.pausedProviderIds).containsExactly(2L)
    }

    @Test
    fun `style profile entry is written only when provided`() = runDbTest {
        newProvider(id = 1)
        val styleJson = """{"accent":"#123456"}"""

        val withStyle = writeToEntries(styleProfileJson = styleJson)
        assertThat(withStyle.first.styleEntry).isEqualTo(BackupFormat.STYLE_ENTRY)
        assertThat(withStyle.second.getValue(BackupFormat.STYLE_ENTRY)).isEqualTo(styleJson)

        val withoutStyle = writeToEntries()
        assertThat(withoutStyle.first.styleEntry).isNull()
        assertThat(withoutStyle.second).doesNotContainKey(BackupFormat.STYLE_ENTRY)
    }

    @Test
    fun `saf document sink writes the zip through the content resolver`() = runDbTest {
        newProvider(id = 1)
        seedSongWithUserData()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Abackup.zip")
        val bytes = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver> {
            every { openOutputStream(uri, "w") } returns bytes
        }

        val manifest = writer().writeBackup(SafBackupDocumentSink(resolver, uri), appVersionName = "1.0.0")

        verify(exactly = 1) { resolver.openOutputStream(uri, "w") }
        assertThat(manifest.formatVersion).isEqualTo(BackupFormat.FORMAT_VERSION)
        val zipBytes = bytes.toByteArray()
        assertThat(zipBytes[0]).isEqualTo('P'.code.toByte())
        assertThat(zipBytes[1]).isEqualTo('K'.code.toByte())
        assertThat(unzip(zipBytes).keys).contains(BackupFormat.MANIFEST_ENTRY)
    }

    @Test
    fun `sealed provider credential blobs never enter the backup`() = runDbTest {
        providerDao.upsert(
            ProviderEntity(
                id = 1,
                name = "Sealed",
                api = "api-test",
                primaryScheme = "https",
                primaryHost = "host.example",
                primaryPort = 443,
                primaryPath = "/",
                username = "user",
                secretBlob = "sealed-secret-blob",
                mtlsKeystoreBlob = "sealed-keystore-blob",
                trustedCertsBlob = "sealed-certs-blob",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )

        val (_, entries) = writeToEntries()

        assertThat(entries.values.none { "sealed-".toRegex().containsMatchIn(it) }).isTrue()
    }
}
