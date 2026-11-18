package app.omnisonic.core.data.sync

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.provider.api.OutboxActions
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxPendingRepositoryTest : DatabaseTestBase() {

    @Test
    fun `counts errors and provider names aggregate per provider`() = runDbTest {
        newProvider(id = 1, name = "Home")
        newProvider(id = 2, name = "On device")
        outboxDao.upsert(row(providerId = 1, externalId = "a", attempts = 2, nextAttemptAt = 17_000, error = "server said no"))
        outboxDao.upsert(row(providerId = 1, externalId = "b"))
        outboxDao.upsert(row(providerId = 2, externalId = "c"))
        outboxDao.upsert(row(providerId = 9, externalId = "orphan"))

        val providers = OutboxPendingRepository(db).providers.first()

        assertThat(providers.map { it.providerId }).containsExactly(1L, 2L, 9L).inOrder()
        val home = providers[0]
        assertThat(home.providerName).isEqualTo("Home")
        assertThat(home.pendingCount).isEqualTo(2)
        assertThat(home.failingCount).isEqualTo(1)
        assertThat(home.lastError).isEqualTo("server said no")
        assertThat(providers[1].providerName).isEqualTo("On device")
        assertThat(providers[1].failingCount).isEqualTo(0)
        assertThat(providers[1].lastError).isNull()
        assertThat(providers[2].providerName).isNull()
    }

    @Test
    fun `the most backed-off failure supplies the provider error`() = runDbTest {
        newProvider(id = 1)
        outboxDao.upsert(row(providerId = 1, externalId = "a", attempts = 1, nextAttemptAt = 5_000, error = "older"))
        outboxDao.upsert(row(providerId = 1, externalId = "b", attempts = 4, nextAttemptAt = 30_000, error = "newer"))

        val provider = OutboxPendingRepository(db).providers.first().single()

        assertThat(provider.pendingCount).isEqualTo(2)
        assertThat(provider.failingCount).isEqualTo(2)
        assertThat(provider.lastError).isEqualTo("newer")
    }

    @Test
    fun `clearing the back-off makes parked rows due and keeps the error`() = runDbTest {
        newProvider(id = 1)
        outboxDao.upsert(row(providerId = 1, externalId = "a", attempts = 3, nextAttemptAt = 60_000, error = "boom"))

        OutboxPendingRepository(db).clearBackoff()

        val stored = outboxDao.selectByProvider(1).single()
        assertThat(stored.attempts).isEqualTo(0)
        assertThat(stored.nextAttemptAt).isEqualTo(0)
        assertThat(stored.lastError).isEqualTo("boom")
    }

    private fun row(
        providerId: Long,
        externalId: String,
        attempts: Int = 0,
        nextAttemptAt: Long = 0,
        error: String? = null,
    ): OutboxItemEntity = OutboxItemEntity(
        providerId = providerId,
        mediaType = "SONG",
        externalId = externalId,
        action = OutboxActions.PLAY_COUNT,
        payloadJson = "{\"playCountDelta\":1}",
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = error,
        createdAt = 1_000,
    )
}
