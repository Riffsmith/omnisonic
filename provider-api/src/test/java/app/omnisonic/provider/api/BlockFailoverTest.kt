package app.omnisonic.provider.api

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BlockFailoverTest {

    private val connection = ProviderConnection(
        providerId = 1L,
        primary = ConnectionBlock("https", "primary.test", 443),
        secondary = ConnectionBlock("https", "secondary.test", 8443),
    )

    @Test
    fun `primary success keeps preference and skips callback`() = runTest {
        var reported: Int? = null
        val failover = BlockFailover(connection.copy(lastWorkingBlock = null), onPreferredBlockChange = { reported = it })
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            BlockAttempt.Success("ok-primary")
        }

        assertEquals(listOf("primary.test"), visited)
        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
        assertEquals(null, reported)
        assertTrue(outcome is BlockAttempt.Success)
    }

    @Test
    fun `a single primary failure surfaces without flipping`() = runTest {
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(connection, onPreferredBlockChange = { reported += it })
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            BlockAttempt.TransportError(IllegalStateException("refused"))
        }

        assertEquals(listOf("primary.test"), visited)
        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
        assertEquals(0, reported.size)
        assertTrue(outcome is BlockAttempt.TransportError)
    }

    @Test
    fun `two consecutive primary failures flip to secondary and report the preference`() = runTest {
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(connection, onPreferredBlockChange = { reported += it })
        val visited = mutableListOf<String>()

        failover.attempt { block ->
            visited += block.host
            BlockAttempt.TransportError(IllegalStateException("refused"))
        }
        val outcome = failover.attempt { block ->
            visited += block.host
            if (block.host == "primary.test") {
                BlockAttempt.TransportError(IllegalStateException("refused"))
            } else {
                BlockAttempt.Success("ok-secondary")
            }
        }

        assertEquals(listOf("primary.test", "primary.test", "secondary.test"), visited)
        assertEquals(ProviderConnection.SECONDARY_BLOCK, failover.preferredBlock)
        assertEquals(listOf(ProviderConnection.SECONDARY_BLOCK), reported)
        assertTrue(outcome is BlockAttempt.Success)
    }

    @Test
    fun `hysteresis resets after a primary success`() = runTest {
        val failover = BlockFailover(connection)

        failover.attempt { BlockAttempt.TransportError(IllegalStateException("down")) }
        failover.attempt { BlockAttempt.Success("ok") }
        failover.attempt { BlockAttempt.TransportError(IllegalStateException("down")) }

        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
    }

    @Test
    fun `remembered secondary stays preferred before the retry interval elapses`() = runTest {
        var now = 0L
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(
            connection.copy(lastWorkingBlock = ProviderConnection.SECONDARY_BLOCK),
            onPreferredBlockChange = { reported += it },
            clock = { now },
        )
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            BlockAttempt.TransportError(IllegalStateException("secondary dead"))
        }

        assertEquals(listOf("secondary.test"), visited)
        assertEquals(ProviderConnection.SECONDARY_BLOCK, failover.preferredBlock)
        assertEquals(0, reported.size)
        assertTrue(outcome is BlockAttempt.TransportError)
    }

    @Test
    fun `after the interval a healthy primary reclaims the preference`() = runTest {
        var now = 0L
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(
            connection.copy(lastWorkingBlock = ProviderConnection.SECONDARY_BLOCK),
            onPreferredBlockChange = { reported += it },
            clock = { now },
        )
        val visited = mutableListOf<String>()

        now = BlockFailover.DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS + 1
        val outcome = failover.attempt { block ->
            visited += block.host
            if (block.host == "primary.test") BlockAttempt.Success("ok-primary") else BlockAttempt.Success("ok-secondary")
        }

        assertEquals(listOf("primary.test"), visited)
        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
        assertEquals(listOf(ProviderConnection.PRIMARY_BLOCK), reported)
        assertTrue(outcome is BlockAttempt.Success)
    }

    @Test
    fun `after the interval a failing primary defers back to the secondary`() = runTest {
        var now = 0L
        val failover = BlockFailover(
            connection.copy(lastWorkingBlock = ProviderConnection.SECONDARY_BLOCK),
            clock = { now },
        )
        val visited = mutableListOf<String>()

        now = BlockFailover.DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS + 1
        val outcome = failover.attempt { block ->
            visited += block.host
            if (block.host == "primary.test") {
                BlockAttempt.TransportError(IllegalStateException("lan down"))
            } else {
                BlockAttempt.Success("ok-secondary")
            }
        }

        assertEquals(listOf("primary.test", "secondary.test"), visited)
        assertEquals(ProviderConnection.SECONDARY_BLOCK, failover.preferredBlock)
        assertTrue(outcome is BlockAttempt.Success)

        // The failed probe re-arms the timer: no primary retry before the next interval.
        visited.clear()
        now += 1_000
        failover.attempt { block ->
            visited += block.host
            BlockAttempt.Success("ok")
        }
        assertEquals(listOf("secondary.test"), visited)
    }

    @Test
    fun `remembered primary is tried first`() = runTest {
        var reported = false
        val failover = BlockFailover(
            connection.copy(lastWorkingBlock = ProviderConnection.PRIMARY_BLOCK),
            onPreferredBlockChange = { reported = true },
        )
        val visited = mutableListOf<String>()

        failover.attempt { block ->
            visited += block.host
            BlockAttempt.Success("ok")
        }

        assertEquals(listOf("primary.test"), visited)
        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
        assertEquals(false, reported)
    }

    @Test
    fun `both transport errors surface the last failure`() = runTest {
        val failover = BlockFailover(connection)

        val outcome = failover.attempt { BlockAttempt.TransportError(IllegalStateException("network down")) }

        assertTrue(outcome is BlockAttempt.TransportError)
    }

    @Test
    fun `server errors flip after hysteresis when another block exists`() = runTest {
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(connection, onPreferredBlockChange = { reported += it })
        val visited = mutableListOf<String>()

        failover.attempt { block ->
            visited += block.host
            BlockAttempt.StatusError(503, "unavailable")
        }
        val outcome = failover.attempt { block ->
            visited += block.host
            BlockAttempt.StatusError(503, "unavailable")
        }

        assertEquals(listOf("primary.test", "primary.test", "secondary.test"), visited)
        assertTrue(outcome is BlockAttempt.StatusError)
        assertEquals(ProviderConnection.SECONDARY_BLOCK, failover.preferredBlock)
        assertEquals(1, reported.size)
    }

    @Test
    fun `client error like 404 is terminal without flip`() = runTest {
        val failover = BlockFailover(connection)
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            BlockAttempt.StatusError(404, "missing")
        }

        assertEquals(listOf("primary.test"), visited)
        assertTrue(outcome is BlockAttempt.StatusError)
        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
    }

    @Test
    fun `fatal error returns immediately without flip`() = runTest {
        val failover = BlockFailover(connection)
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            BlockAttempt.FatalError(IllegalArgumentException("bad envelope"))
        }

        assertEquals(listOf("primary.test"), visited)
        assertTrue(outcome is BlockAttempt.FatalError)
    }

    @Test
    fun `single block never flips`() = runTest {
        var reported = false
        val failover = BlockFailover(connection.copy(secondary = null), onPreferredBlockChange = { reported = true })

        val outcome = failover.attempt { BlockAttempt.TransportError(IllegalStateException("down")) }
        val second = failover.attempt { BlockAttempt.TransportError(IllegalStateException("down")) }

        assertTrue(outcome is BlockAttempt.TransportError)
        assertTrue(second is BlockAttempt.TransportError)
        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
        assertEquals(false, reported)
    }

    @Test
    fun `default retry interval is five minutes`() {
        assertEquals(5 * 60_000L, BlockFailover.DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS)
    }
}
