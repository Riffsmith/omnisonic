package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.entity.DownloadStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadLifecycleRulesTest {

    @Test
    fun `the command table is legal only where the lifecycle allows it`() {
        val legal = mapOf(
            DownloadQueueCommand.CLAIM to setOf(DownloadStatus.PENDING),
            DownloadQueueCommand.PAUSE_ALL to setOf(DownloadStatus.PENDING, DownloadStatus.ACTIVE),
            DownloadQueueCommand.RESUME_ALL to setOf(DownloadStatus.PAUSED),
            DownloadQueueCommand.PAUSE_ONE to setOf(
                DownloadStatus.PENDING,
                DownloadStatus.PAUSED,
                DownloadStatus.ACTIVE,
            ),
            DownloadQueueCommand.RESUME_ONE to setOf(DownloadStatus.USER_PAUSED),
            DownloadQueueCommand.CANCEL to DownloadStatus.entries.toSet(),
            DownloadQueueCommand.RETRY to setOf(DownloadStatus.FAILED),
            DownloadQueueCommand.COMPLETE to setOf(DownloadStatus.ACTIVE),
        )

        for (command in DownloadQueueCommand.entries) {
            for (status in DownloadStatus.entries) {
                assertThat(DownloadLifecycleRules.canApply(command, status))
                    .isEqualTo(status in legal.getValue(command))
            }
        }
    }

    @Test
    fun `unknown or invalid raw statuses reject every command`() {
        for (raw in listOf(null, "", "RUNNING", "COMPLETED", "pending", "BOGUS")) {
            assertThat(DownloadLifecycleRules.parse(raw)).isNull()
            assertThat(DownloadLifecycleRules.isKnown(raw)).isFalse()
            for (command in DownloadQueueCommand.entries) {
                assertThat(DownloadLifecycleRules.canApply(command, raw)).isFalse()
            }
        }
    }

    @Test
    fun `every known status name parses back to its state`() {
        for (status in DownloadStatus.entries) {
            assertThat(DownloadLifecycleRules.parse(status.name)).isEqualTo(status)
            assertThat(DownloadLifecycleRules.isKnown(status.name)).isTrue()
        }
    }

    @Test
    fun `zero error count is due immediately even when the clock moved backwards`() {
        assertThat(DownloadLifecycleRules.isDue(updatedAt = 5_000, errorCount = 0, nowMs = 100)).isTrue()
        assertThat(DownloadLifecycleRules.isDue(updatedAt = 5_000, errorCount = 0, nowMs = 9_999)).isTrue()
    }

    @Test
    fun `retry delays use before exact and after boundaries for attempts one to five`() {
        for (count in 1..5) {
            val dueAt = 1_000L + count * DownloadLifecycleRules.BACKOFF_STEP_MS
            assertThat(DownloadLifecycleRules.isDue(1_000, count, dueAt - 1)).isFalse()
            assertThat(DownloadLifecycleRules.isDue(1_000, count, dueAt)).isTrue()
            assertThat(DownloadLifecycleRules.isDue(1_000, count, dueAt + 1)).isTrue()
        }
    }

    @Test
    fun `negative and excess error counts fail closed`() {
        assertThat(DownloadLifecycleRules.isDue(0, -1, Long.MAX_VALUE)).isFalse()
        assertThat(DownloadLifecycleRules.isDue(0, 6, Long.MAX_VALUE)).isFalse()
        assertThat(DownloadLifecycleRules.effectiveDueAtMs(0, -3)).isEqualTo(Long.MAX_VALUE)
        assertThat(DownloadLifecycleRules.effectiveDueAtMs(0, 7)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `an overflowing retry delay saturates instead of becoming due early`() {
        val updatedAt = Long.MAX_VALUE - 1_000

        assertThat(DownloadLifecycleRules.effectiveDueAtMs(updatedAt, 5)).isEqualTo(Long.MAX_VALUE)
        assertThat(DownloadLifecycleRules.isDue(updatedAt, 5, nowMs = Long.MAX_VALUE - 2_000)).isFalse()
        assertThat(DownloadLifecycleRules.isDue(updatedAt, 5, nowMs = Long.MAX_VALUE - 1)).isFalse()
    }

    @Test
    fun `attempts one to five return to pending and the sixth parks`() {
        assertThat((1..5).map(DownloadLifecycleRules::failureTarget))
            .containsExactly(
                DownloadStatus.PENDING,
                DownloadStatus.PENDING,
                DownloadStatus.PENDING,
                DownloadStatus.PENDING,
                DownloadStatus.PENDING,
            )
        assertThat(DownloadLifecycleRules.failureTarget(6)).isEqualTo(DownloadStatus.FAILED)
        assertThat(DownloadLifecycleRules.failureTarget(9)).isEqualTo(DownloadStatus.FAILED)
    }

    @Test
    fun `enqueue and resume targets follow the current global gate`() {
        assertThat(DownloadLifecycleRules.enqueueStatus(false)).isEqualTo(DownloadStatus.PENDING)
        assertThat(DownloadLifecycleRules.enqueueStatus(true)).isEqualTo(DownloadStatus.PAUSED)
        assertThat(DownloadLifecycleRules.resumeTarget(false)).isEqualTo(DownloadStatus.PENDING)
        assertThat(DownloadLifecycleRules.resumeTarget(true)).isEqualTo(DownloadStatus.PAUSED)
    }

    @Test
    fun `failure messages are collapsed trimmed and bounded`() {
        assertThat(DownloadLifecycleRules.sanitizeFailureMessage(null)).isNull()
        assertThat(DownloadLifecycleRules.sanitizeFailureMessage("  \n\t ")).isNull()
        assertThat(DownloadLifecycleRules.sanitizeFailureMessage("connect\nfailed\tagain"))
            .isEqualTo("connect failed again")
        assertThat(DownloadLifecycleRules.sanitizeFailureMessage("x".repeat(400)))
            .hasLength(DownloadLifecycleRules.MAX_ERROR_MESSAGE_LENGTH)
    }

    @Test
    fun `worker counts are sanitized at the boundary`() {
        assertThat(DownloadLifecycleRules.sanitizeWorkerCount(2)).isEqualTo(2)
        assertThat(DownloadLifecycleRules.sanitizeWorkerCount(0)).isEqualTo(1)
        assertThat(DownloadLifecycleRules.sanitizeWorkerCount(-4)).isEqualTo(1)
    }
}
