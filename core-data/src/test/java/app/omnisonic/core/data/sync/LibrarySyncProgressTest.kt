package app.omnisonic.core.data.sync

import app.omnisonic.core.data.db.entity.SyncStatusEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LibrarySyncProgressTest {

    @Test
    fun `running progress reports a fraction when the phase has a total`() {
        val progress = LibrarySyncProgress(
            providerId = 1,
            phase = LibrarySyncPhase.ALBUMS_SONGS,
            startedAt = 10,
            itemsDone = 25,
            itemsTotal = 100,
        )

        assertThat(progress.isRunning).isTrue()
        assertThat(progress.fraction).isEqualTo(0.25f)
        assertThat(progress.finishedAt).isNull()
    }

    @Test
    fun `phases without counts stay indeterminate`() {
        val progress = LibrarySyncProgress(
            providerId = 1,
            phase = LibrarySyncPhase.ARTISTS,
            startedAt = 10,
        )

        assertThat(progress.fraction).isNull()
    }

    @Test
    fun `terminal progress closes the walk`() {
        val progress = LibrarySyncProgress(
            providerId = 1,
            phase = LibrarySyncPhase.DONE,
            startedAt = 10,
            finishedAt = 20,
            outcome = LibrarySyncOutcome.COMPLETED,
            message = "ok",
        )

        assertThat(progress.isRunning).isFalse()
        assertThat(progress.phase.isTerminal).isTrue()
    }

    @Test
    fun `terminal phase requires an outcome and a finish time`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibrarySyncProgress(
                providerId = 1,
                phase = LibrarySyncPhase.FAILED,
                startedAt = 10,
            )
        }
    }

    @Test
    fun `running phase rejects terminal markers`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibrarySyncProgress(
                providerId = 1,
                phase = LibrarySyncPhase.GENRES,
                startedAt = 10,
                finishedAt = 20,
                outcome = LibrarySyncOutcome.COMPLETED,
            )
        }
    }

    @Test
    fun `status repository publishes the latest value and guards double starts`() {
        val statuses = LibrarySyncStatusRepository()
        assertThat(statuses.progress.value).isNull()

        assertThat(statuses.tryBegin(7)).isTrue()
        assertThat(statuses.tryBegin(7)).isFalse()
        assertThat(statuses.isRunning(7)).isTrue()
        assertThat(statuses.isRunning(8)).isFalse()

        statuses.publish(
            LibrarySyncProgress(
                providerId = 7,
                phase = LibrarySyncPhase.PLAYLISTS,
                startedAt = 1,
            ),
        )
        assertThat(statuses.progress.value?.phase).isEqualTo(LibrarySyncPhase.PLAYLISTS)

        statuses.finish(7)
        assertThat(statuses.isRunning(7)).isFalse()
        assertThat(statuses.tryBegin(7)).isTrue()
    }

    @Test
    fun `resume policy only restarts a provider whose full sync never completed`() {
        assertThat(LibrarySyncResumePolicy.shouldResume(status = null, hasLibrarySync = true)).isTrue()
        assertThat(
            LibrarySyncResumePolicy.shouldResume(
                status = SyncStatusEntity(providerId = 1, lastSyncResult = 2),
                hasLibrarySync = true,
            ),
        ).isTrue()
        assertThat(
            LibrarySyncResumePolicy.shouldResume(
                status = SyncStatusEntity(providerId = 1, lastFullSyncAt = 5_000),
                hasLibrarySync = true,
            ),
        ).isFalse()
        assertThat(
            LibrarySyncResumePolicy.shouldResume(
                status = SyncStatusEntity(providerId = 1, lastDiffSyncAt = 5_000),
                hasLibrarySync = true,
            ),
        ).isTrue()
        assertThat(LibrarySyncResumePolicy.shouldResume(status = null, hasLibrarySync = false)).isFalse()
    }
}
