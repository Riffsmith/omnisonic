package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticRuleExpanderTest : DatabaseTestBase() {

    private var now = 10_000L
    private val subsonicTraits = ProviderTraits()
    private val localTraits = ProviderTraits(
        hasLibrarySync = true,
        supportsDownload = false,
        supportsServerTranscode = false,
        reportsUserData = false,
    )

    private fun rules() = AutomaticRuleRepository(db, nowMs = { now })

    private fun enqueuer(
        traits: ProviderTraits = subsonicTraits,
        runGate: ProviderRunGate = ProviderRunGate { ProviderStatus.OK },
    ): DownloadEnqueuer {
        val settings = DownloadQueueSettingsStore.inMemory()
        val coordinator = DownloadWorkerCoordinator(
            repository = DownloadQueueRepository(downloadDao),
            settings = settings,
            runGate = runGate,
            workerCount = 2,
            nowMs = { now },
        )
        val traitsSource = ProviderTraitsSource { traits }
        return DownloadEnqueuer(db, coordinator, traitsSource, runGate, nowMs = { now })
    }

    private fun expander(
        source: AutomaticRuleTrackSource = DatabaseAutomaticRuleTrackSource(db.automaticRuleCatalogDao()),
        enqueuer: DownloadEnqueuer = enqueuer(),
    ) = AutomaticRuleExpander(rules(), source, enqueuer)

    private suspend fun seedSong(
        id: Long,
        providerId: Long,
        albumId: Long? = null,
        externalId: String = "song-$id",
        title: String = "Song $id",
        favorite: Boolean = false,
        missing: Boolean = false,
        imagePath: String? = null,
        discNo: Int? = null,
        trackNo: Int? = null,
    ): SongEntity {
        val song = SongEntity(
            id = id,
            providerId = providerId,
            externalId = externalId,
            albumId = albumId,
            title = title,
            imagePath = imagePath,
            isFavorite = favorite,
            missingOnServer = missing,
            discNo = discNo,
            trackNo = trackNo,
            updatedAt = now,
        )
        songDao.upsert(song)
        return songDao.selectById(id) ?: error("song row missing")
    }

    private suspend fun playlistWithCount(providerId: Long, count: Int): PlaylistEntity {
        val playlist = seedPlaylist(providerId)
        playlistDao.upsert(playlist.copy(entryCount = count))
        return playlistDao.selectById(playlist.id) ?: error("playlist row missing")
    }

    private suspend fun addEntry(
        playlistId: Long,
        providerId: Long,
        position: Int,
        song: SongEntity?,
        externalId: String = song?.externalId ?: error("entry needs an external id"),
    ) {
        playlistEntryDao.upsert(
            app.omnisonic.core.data.db.entity.PlaylistEntryEntity(
                playlistId = playlistId,
                providerId = providerId,
                position = position,
                externalId = externalId,
                songId = song?.id,
                title = song?.title ?: externalId,
                addedAt = now,
            ),
        )
    }

    private suspend fun externalIdsOf(providerId: Long): List<String> =
        downloadDao.selectByProvider(providerId).map { DownloadKeys.parseMediaKey(it.mediaKey)!!.externalId }

    @Test
    fun `favorites expand only favorite songs with the rule variant and rolling intent`() = runDbTest {
        newProvider(id = 1)
        seedSong(1, 1, favorite = true, imagePath = "/one")
        seedSong(2, 1, favorite = true, title = "Favorite two")
        seedSong(3, 1, favorite = false)
        seedSong(4, 1, favorite = true, missing = true)
        rules().add(1, AutomaticRuleKind.FAVORITES, transcodeBitrateKbps = 192, format = "mp3")
        val rule = rules().list(ProfileScope.Unrestricted).single()

        val result = expander().expand(ProfileScope.Unrestricted).single()

        assertThat(result.outcome).isEqualTo(AutomaticRuleExpansionOutcome.EXPANDED)
        assertThat(result.wantedCount).isEqualTo(2)
        assertThat(result.acceptedCount).isEqualTo(2)
        val rows = downloadDao.selectByProvider(1)
        assertThat(externalIdsOf(1)).containsExactly("song-1", "song-2")
        assertThat(rows.map { it.autoRuleId }).containsExactly(rule.id, rule.id)
        assertThat(rows.map { it.transcodeBitrateKbps }).containsExactly(192, 192)
        assertThat(rows.map { it.tempCache }).containsExactly(true, true)
        assertThat(rows.map { it.title }).containsExactly("Song 1", "Favorite two")
        assertThat(rows.first().imagePath).isEqualTo("/one")
    }

    @Test
    fun `playlist entries expand in position order once each`() = runDbTest {
        newProvider(id = 1)
        val first = seedSong(1, 1)
        val second = seedSong(2, 1)
        val playlist = playlistWithCount(1, 3)
        addEntry(playlist.id, 1, 0, second)
        addEntry(playlist.id, 1, 1, first)
        addEntry(playlist.id, 1, 2, first)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)

        val result = expander().expand(ProfileScope.Unrestricted).single()

        assertThat(result.outcome).isEqualTo(AutomaticRuleExpansionOutcome.EXPANDED)
        assertThat(result.wantedCount).isEqualTo(2)
        assertThat(externalIdsOf(1)).containsExactly("song-2", "song-1").inOrder()
    }

    @Test
    fun `album tracks expand in disc and track order`() = runDbTest {
        newProvider(id = 1)
        val album = newAlbum(1, 1).let { albumDao.upsert(it.copy(songCount = 3)); albumDao.selectById(1)!! }
        seedSong(1, 1, albumId = album.id, discNo = 1, trackNo = 2)
        seedSong(2, 1, albumId = album.id, discNo = 1, trackNo = 1)
        seedSong(3, 1, albumId = album.id, discNo = 2, trackNo = 1)
        rules().add(1, AutomaticRuleKind.ALBUM, album.externalId!!)

        val result = expander().expand(ProfileScope.Unrestricted).single()

        assertThat(result.outcome).isEqualTo(AutomaticRuleExpansionOutcome.EXPANDED)
        assertThat(result.wantedCount).isEqualTo(3)
        assertThat(externalIdsOf(1)).containsExactly("song-2", "song-1", "song-3").inOrder()
    }

    @Test
    fun `genuinely empty collections are expanded with an empty wanted set and no writes`() = runDbTest {
        newProvider(id = 1)
        val playlist = playlistWithCount(1, 0)
        val album = newAlbum(2, 1).let { albumDao.upsert(it.copy(songCount = 0)); albumDao.selectById(2)!! }
        rules().add(1, AutomaticRuleKind.FAVORITES)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
        rules().add(1, AutomaticRuleKind.ALBUM, album.externalId!!)

        val results = expander().expand(ProfileScope.Unrestricted)

        assertThat(results.map { it.outcome })
            .containsExactly(
                AutomaticRuleExpansionOutcome.EXPANDED,
                AutomaticRuleExpansionOutcome.EXPANDED,
                AutomaticRuleExpansionOutcome.EXPANDED,
            )
        assertThat(results.map { it.wantedCount }).containsExactly(0, 0, 0)
        assertThat(downloadDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun `a missing provider playlist or album is a typed stale skip`() = runDbTest {
        newProvider(id = 1)
        rules().add(1, AutomaticRuleKind.PLAYLIST, "missing-playlist")
        rules().add(1, AutomaticRuleKind.ALBUM, "missing-album")
        rules().add(99, AutomaticRuleKind.FAVORITES)

        val results = expander().expand(ProfileScope.Unrestricted)

        assertThat(results.map { it.outcome }).containsExactly(
            AutomaticRuleExpansionOutcome.STALE_TARGET,
            AutomaticRuleExpansionOutcome.STALE_TARGET,
            AutomaticRuleExpansionOutcome.STALE_TARGET,
        )
        assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()
    }

    @Test
    fun `an incomplete playlist or album is a typed unsynced skip with no partial enqueue`() = runDbTest {
        newProvider(id = 1)
        val visible = seedSong(1, 1)
        val playlist = playlistWithCount(1, 2)
        addEntry(playlist.id, 1, 0, visible)
        addEntry(playlist.id, 1, 1, null, externalId = "ghost")
        val album = newAlbum(2, 1).let { albumDao.upsert(it.copy(songCount = 2)); albumDao.selectById(2)!! }
        seedSong(2, 1, albumId = album.id)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
        rules().add(1, AutomaticRuleKind.ALBUM, album.externalId!!)

        val results = expander().expand(ProfileScope.Unrestricted)

        assertThat(results.map { it.outcome }).containsExactly(
            AutomaticRuleExpansionOutcome.UNSYNCED,
            AutomaticRuleExpansionOutcome.UNSYNCED,
        )
        assertThat(results.map { it.wantedCount }).containsExactly(0, 0)
        assertThat(downloadDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun `overlapping rules insert one row and a repeated pass stays a no-op`() = runDbTest {
        newProvider(id = 1)
        seedSong(1, 1, favorite = true)
        val playlist = playlistWithCount(1, 1)
        addEntry(playlist.id, 1, 0, songDao.selectById(1)!!)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
        val favoriteRule = rules().list(ProfileScope.Unrestricted)
            .first { it.kind == AutomaticRuleKind.FAVORITES }

        val first = expander().expand(ProfileScope.Unrestricted)
        val inserted = downloadDao.selectByProvider(1).single()

        assertThat(first.map { it.acceptedCount }).containsExactly(1, 1)
        assertThat(inserted.autoRuleId).isEqualTo(favoriteRule.id)
        assertThat(inserted.tempCache).isTrue()

        val second = expander().expand(ProfileScope.Unrestricted)
        assertThat(second.map { it.acceptedCount }).containsExactly(1, 1)
        assertThat(downloadDao.selectByProvider(1)).containsExactly(inserted)
    }

    @Test
    fun `an existing manual active row keeps ownership and progress`() = runDbTest {
        newProvider(id = 1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES, transcodeBitrateKbps = 192, format = "mp3")
        val seeded = DownloadItemEntity(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", 192, "mp3"),
            providerId = 1,
            status = DownloadStatus.ACTIVE.name,
            title = "manual",
            progressPercent = 63,
            tempCache = false,
            autoRuleId = null,
            createdAt = 500,
            updatedAt = 900,
        )
        downloadDao.upsert(seeded)
        val before = downloadDao.selectByMediaKey(seeded.mediaKey)!!

        val result = expander().expand(ProfileScope.Unrestricted).single()

        assertThat(result.acceptedCount).isEqualTo(1)
        assertThat(downloadDao.selectByMediaKey(seeded.mediaKey)).isEqualTo(before)
        assertThat(before.autoRuleId).isNull()
        assertThat(before.progressPercent).isEqualTo(63)
    }

    @Test
    fun `an existing export destination is a typed conflict and is not rewritten`() = runDbTest {
        newProvider(id = 1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val seeded = DownloadItemEntity(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null),
            providerId = 1,
            status = DownloadStatus.PENDING.name,
            title = "export request",
            progressPercent = 77,
            exportTarget = "MEDIASTORE_DOWNLOADS",
            createdAt = 500,
            updatedAt = 900,
        )
        downloadDao.upsert(seeded)
        val before = downloadDao.selectByMediaKey(seeded.mediaKey)!!

        val result = expander().expand(ProfileScope.Unrestricted).single()

        assertThat(result.destinationConflictCount).isEqualTo(1)
        assertThat(result.acceptedCount).isEqualTo(0)
        assertThat(downloadDao.selectByMediaKey(seeded.mediaKey)).isEqualTo(before)
    }

    @Test
    fun `unsupported download providers are typed and write nothing`() = runDbTest {
        newProvider(id = 1).let { providerDao.upsert(it.copy(api = "local")) }
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)

        val result = expander(enqueuer = enqueuer(traits = localTraits))
            .expand(ProfileScope.Unrestricted)
            .single()

        assertThat(result.outcome).isEqualTo(AutomaticRuleExpansionOutcome.EXPANDED)
        assertThat(result.wantedCount).isEqualTo(1)
        assertThat(result.unsupportedCount).isEqualTo(1)
        assertThat(result.acceptedCount).isEqualTo(0)
        assertThat(downloadDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun `offline and auth run gates are typed and write nothing`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        seedSong(1, 1, favorite = true)
        seedSong(2, 2, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        rules().add(2, AutomaticRuleKind.FAVORITES)
        val gate = ProviderRunGate { providerId ->
            if (providerId == 1L) ProviderStatus.OFFLINE else ProviderStatus.AUTH_FAILED
        }

        val results = expander(enqueuer = enqueuer(runGate = gate)).expand(ProfileScope.Unrestricted)

        val byProvider = results.associateBy { it.rule.providerId }
        assertThat(byProvider.getValue(1).blockedBy).containsExactly(ProviderStatus.OFFLINE, 1)
        assertThat(byProvider.getValue(2).blockedBy).containsExactly(ProviderStatus.AUTH_FAILED, 1)
        assertThat(results.map { it.acceptedCount }).containsExactly(0, 0)
        assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()
    }

    @Test
    fun `a catalog read failure is typed and leaves the prior queue intact`() = runDbTest {
        newProvider(id = 1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        expander().expand(ProfileScope.Unrestricted)
        val prior = downloadDao.selectByProvider(1).single()

        val failure = object : AutomaticRuleTrackSource {
            override suspend fun resolve(
                rule: AutomaticRule,
                scope: ProfileScope,
            ): AutomaticRuleTargetResolution = error("catalog unavailable")
        }
        val result = expander(source = failure).expand(ProfileScope.Unrestricted).single()

        assertThat(result.outcome).isEqualTo(AutomaticRuleExpansionOutcome.READ_FAILED)
        assertThat(downloadDao.selectByProvider(1)).containsExactly(prior)
    }

    @Test
    fun `a changed playlist adds new rows without deleting the previous snapshot`() = runDbTest {
        newProvider(id = 1)
        val first = seedSong(1, 1)
        val second = seedSong(2, 1)
        val playlist = playlistWithCount(1, 1)
        addEntry(playlist.id, 1, 0, first)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
        expander().expand(ProfileScope.Unrestricted)
        val prior = downloadDao.selectByProvider(1).single()

        playlistDao.upsert(playlist.copy(entryCount = 2))
        addEntry(playlist.id, 1, 1, second)
        val result = expander().expand(ProfileScope.Unrestricted).single()

        assertThat(result.wantedCount).isEqualTo(2)
        assertThat(result.acceptedCount).isEqualTo(2)
        assertThat(externalIdsOf(1)).containsExactly("song-1", "song-2").inOrder()
        assertThat(downloadDao.selectById(prior.id)).isEqualTo(prior)
    }

    @Test
    fun `different songs keep their own variant identities`() = runDbTest {
        newProvider(id = 1)
        seedSong(1, 1, favorite = true)
        val second = seedSong(2, 1)
        val playlist = playlistWithCount(1, 1)
        addEntry(playlist.id, 1, 0, second)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!, 192, "mp3")

        val results = expander().expand(ProfileScope.Unrestricted)

        assertThat(results.map { it.acceptedCount }).containsExactly(1, 1)
        val keys = downloadDao.selectByProvider(1).map { it.mediaKey }.toSet()
        assertThat(keys).containsExactly(
            DownloadKeys.mediaKey(1, "song-1", null, null),
            DownloadKeys.mediaKey(1, "song-2", 192, "mp3"),
        )
    }

    @Test
    fun `a hidden provider is excluded from scoped rules and catalog reads`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        seedSong(1, 1, favorite = true)
        seedSong(2, 2, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        rules().add(2, AutomaticRuleKind.FAVORITES)
        val hidden = ProfileScope().excluding(listOf(2L))
        val hiddenRule = rules().list(ProfileScope.Unrestricted)
            .first { it.providerId == 2L }

        val results = expander().expand(hidden)
        val direct = DatabaseAutomaticRuleTrackSource(db.automaticRuleCatalogDao())
            .resolve(hiddenRule, hidden)

        assertThat(results.map { it.rule.providerId }).containsExactly(1L)
        assertThat(externalIdsOf(1)).containsExactly("song-1")
        assertThat(downloadDao.selectByProvider(2)).isEmpty()
        assertThat(direct).isEqualTo(AutomaticRuleTargetResolution.StaleTarget)
    }

    @Test
    fun `a large album snapshot stays below the bind limit and enqueues every track`() = runDbTest {
        newProvider(id = 1)
        val total = 505
        val album = newAlbum(1, 1).let {
            albumDao.upsert(it.copy(songCount = total))
            albumDao.selectById(1)!!
        }
        songDao.upsertRows(
            (1..total).map { id ->
                SongEntity(
                    id = id.toLong(),
                    providerId = 1,
                    externalId = "song-$id",
                    albumId = album.id,
                    title = "Song $id",
                    trackNo = id,
                    updatedAt = now,
                )
            },
        )
        rules().add(1, AutomaticRuleKind.ALBUM, album.externalId!!)

        val result = expander().expand(ProfileScope.Unrestricted).single()

        assertThat(result.wantedCount).isEqualTo(total)
        assertThat(result.acceptedCount).isEqualTo(total)
        assertThat(downloadDao.selectByProvider(1)).hasSize(total)
    }

    @Test
    fun `the newest rule owns a conflicting automatic variant`() = runDbTest {
        newProvider(id = 1)
        val song = seedSong(1, 1, favorite = true)
        val playlist = playlistWithCount(1, 1)
        addEntry(playlist.id, 1, 0, song)
        now = 10_000
        rules().add(1, AutomaticRuleKind.FAVORITES, transcodeBitrateKbps = 128)
        now = 20_000
        rules().add(
            1,
            AutomaticRuleKind.PLAYLIST,
            playlist.externalId!!,
            transcodeBitrateKbps = 320,
        )

        val first = expander().expand(ProfileScope.Unrestricted)

        assertThat(downloadDao.selectByProvider(1)).hasSize(1)
        assertThat(downloadDao.selectByProvider(1).single().transcodeBitrateKbps).isEqualTo(320)
        assertThat(first.single { it.rule.kind == AutomaticRuleKind.FAVORITES }.wantedMediaKeys).isEmpty()
        assertThat(first.single { it.rule.kind == AutomaticRuleKind.PLAYLIST }.wantedMediaKeys)
            .containsExactly(DownloadKeys.mediaKey(1, "song-1", 320, null))

        val second = expander().expand(ProfileScope.Unrestricted)
        assertThat(downloadDao.selectByProvider(1)).hasSize(1)
        assertThat(second.single { it.rule.kind == AutomaticRuleKind.PLAYLIST }.acceptedCount)
            .isEqualTo(1)

        now = 30_000
        rules().setVariant(
            providerId = 1,
            kind = AutomaticRuleKind.FAVORITES,
            targetExternalId = "",
            transcodeBitrateKbps = 192,
            format = null,
            scope = ProfileScope.Unrestricted,
        )
        val changed = expander().expand(ProfileScope.Unrestricted)

        assertThat(downloadDao.selectByProvider(1)).hasSize(2)
        assertThat(changed.single { it.rule.kind == AutomaticRuleKind.FAVORITES }.wantedMediaKeys)
            .containsExactly(DownloadKeys.mediaKey(1, "song-1", 192, null))
        assertThat(changed.single { it.rule.kind == AutomaticRuleKind.PLAYLIST }.wantedMediaKeys)
            .isEmpty()
    }
}
