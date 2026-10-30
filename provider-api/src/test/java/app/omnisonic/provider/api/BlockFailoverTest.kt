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
        val failover = BlockFailover(connection.copy(lastWorkingBlock = null)) { reported = it }
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
    fun `transport error flips to secondary and reports new preference`() = runTest {
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(connection) { reported += it }
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            if (block.host == "primary.test") BlockAttempt.TransportError(IllegalStateException("refused")) else BlockAttempt.Success("ok")
        }

        assertEquals(listOf("primary.test", "secondary.test"), visited)
        assertEquals(ProviderConnection.SECONDARY_BLOCK, failover.preferredBlock)
        assertEquals(listOf(ProviderConnection.SECONDARY_BLOCK), reported)
        assertTrue(outcome is BlockAttempt.Success)
    }

    @Test
    fun `remembered secondary is tried first`() = runTest {
        var reported = false
        val failover = BlockFailover(connection.copy(lastWorkingBlock = ProviderConnection.SECONDARY_BLOCK)) { reported = true }
        val visited = mutableListOf<String>()

        failover.attempt { block ->
            visited += block.host
            BlockAttempt.Success("ok")
        }

        assertEquals(listOf("secondary.test"), visited)
        assertEquals(ProviderConnection.SECONDARY_BLOCK, failover.preferredBlock)
        assertEquals(false, reported)
    }

    @Test
    fun `remembered secondary falls back to primary when it breaks`() = runTest {
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(connection.copy(lastWorkingBlock = ProviderConnection.SECONDARY_BLOCK)) { reported += it }
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            if (block.host == "secondary.test") BlockAttempt.TransportError(IllegalStateException("dead")) else BlockAttempt.Success("ok")
        }

        assertEquals(listOf("secondary.test", "primary.test"), visited)
        assertEquals(listOf(ProviderConnection.PRIMARY_BLOCK), reported)
        assertTrue(outcome is BlockAttempt.Success)
    }

    @Test
    fun `both transport errors surface the last failure`() = runTest {
        val failover = BlockFailover(connection)

        val outcome = failover.attempt { BlockAttempt.TransportError(IllegalStateException("network down")) }

        assertTrue(outcome is BlockAttempt.TransportError)
    }

    @Test
    fun `server error flips when another block exists but stops when none left`() = runTest {
        val reported = mutableListOf<Int>()
        val failover = BlockFailover(connection) { reported += it }
        val visited = mutableListOf<String>()

        val outcome = failover.attempt { block ->
            visited += block.host
            BlockAttempt.StatusError(503, "unavailable")
        }

        assertEquals(listOf("primary.test", "secondary.test"), visited)
        assertTrue(outcome is BlockAttempt.StatusError)
        assertEquals(0, reported.size)
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
        val failover = BlockFailover(connection.copy(secondary = null)) { reported = true }

        val outcome = failover.attempt { BlockAttempt.TransportError(IllegalStateException("down")) }

        assertTrue(outcome is BlockAttempt.TransportError)
        assertEquals(ProviderConnection.PRIMARY_BLOCK, failover.preferredBlock)
        assertEquals(false, reported)
    }
}
