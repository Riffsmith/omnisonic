package app.omnisonic.core.data.repository

import app.omnisonic.core.data.DatabaseTestBase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueSnapshotRepositoryTest : DatabaseTestBase() {

    private val repository get() = QueueSnapshotRepository(db)

    @Test
    fun restoresAnEntryThatHasNoSongsRow() = runDbTest {
        newProvider(id = 1)
        repository.save(
            entries = listOf(
                QueueSnapshotRepository.EntrySnapshot(
                    queueItemId = 10,
                    position = 0,
                    providerId = 1,
                    externalId = "radio-1",
                    title = "Late night radio",
                    artistName = "DJ",
                    albumExternalId = "album-9",
                    coverImageId = "cover-9",
                    durationMs = 3_600_000,
                    isRadioStation = true,
                ),
            ),
            state = null,
        )

        assertThat(songDao.selectByProviderAndExternal(1, "radio-1")).isNull()

        val restored = repository.restore()
        val entry = restored.entries.single()
        assertThat(entry.title).isEqualTo("Late night radio")
        assertThat(entry.artistName).isEqualTo("DJ")
        assertThat(entry.albumExternalId).isEqualTo("album-9")
        assertThat(entry.coverImageId).isEqualTo("cover-9")
        assertThat(entry.durationMs).isEqualTo(3_600_000)
        assertThat(entry.isRadioStation).isTrue()
        assertThat(entry.songRowId).isNull()
    }

    @Test
    fun restoresShuffleAndTransportState() = runDbTest {
        newProvider(id = 1)
        repository.save(
            entries = listOf(
                QueueSnapshotRepository.EntrySnapshot(
                    queueItemId = 10,
                    position = 0,
                    providerId = 1,
                    externalId = "song-1",
                    title = "One",
                ),
                QueueSnapshotRepository.EntrySnapshot(
                    queueItemId = 11,
                    position = 1,
                    providerId = 1,
                    externalId = "song-2",
                    title = "Two",
                ),
            ),
            state = QueueSnapshotRepository.StateSnapshot(
                playIndex = 1,
                shuffleOrder = listOf(1, 0),
                shuffleSeed = 99,
                repeatMode = "ALL",
                speed = 1.25f,
                pitch = 0.75f,
                sourceType = "album",
                sourceData = "alb-1",
                positionMs = 543_210L,
            ),
        )

        val restored = repository.restore()
        assertThat(restored.entries.map { it.queueItemId }).containsExactly(10L, 11L).inOrder()
        assertThat(restored.state).isEqualTo(
            QueueSnapshotRepository.StateSnapshot(
                playIndex = 1,
                shuffleOrder = listOf(1, 0),
                shuffleSeed = 99,
                repeatMode = "ALL",
                speed = 1.25f,
                pitch = 0.75f,
                sourceType = "album",
                sourceData = "alb-1",
                positionMs = 543_210L,
            ),
        )
    }

    @Test
    fun saveReplacesThePreviousQueue() = runDbTest {
        newProvider(id = 1)
        repository.save(
            entries = listOf(
                QueueSnapshotRepository.EntrySnapshot(
                    queueItemId = 10,
                    position = 0,
                    providerId = 1,
                    externalId = "song-1",
                    title = "One",
                ),
            ),
            state = null,
        )

        repository.save(
            entries = listOf(
                QueueSnapshotRepository.EntrySnapshot(
                    queueItemId = 20,
                    position = 0,
                    providerId = 1,
                    externalId = "song-2",
                    title = "Two",
                ),
            ),
            state = null,
        )

        assertThat(repository.restore().entries.single().queueItemId).isEqualTo(20)
        repository.clear()
        assertThat(repository.restore().entries).isEmpty()
    }
}
