package app.omnisonic.provider.api

import com.squareup.moshi.Moshi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Golden round-trip for the outbox wire payload: the JSON field names are part
 * of the persisted contract, so a rename (or a drift between writers and
 * readers) must fail here instead of silently nulling fields at drain time.
 */
class OutboxActionPayloadJsonTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(OutboxActionPayload::class.java)

    @Test
    fun `encoding every field produces the pinned golden json`() {
        val payload = OutboxActionPayload(
            playCountDelta = 3,
            isFavorite = true,
            userRating = 5,
            resumeMs = 42_000L,
            lastPlayedAt = 1_700_000_000_000L,
            playTimestamps = listOf(1_700_000_000_000L, 1_700_000_000_500L),
        )

        val encoded = adapter.toJson(payload)

        assertEquals(
            """{"playCountDelta":3,"isFavorite":true,"userRating":5,""" +
                """"resumeMs":42000,"lastPlayedAt":1700000000000,""" +
                """"playTimestamps":[1700000000000,1700000000500]}""",
            encoded,
        )
    }

    @Test
    fun `decoding the golden json reproduces every field`() {
        val golden = "{\"playCountDelta\":2,\"isFavorite\":false,\"userRating\":1," +
            "\"resumeMs\":7,\"lastPlayedAt\":9,\"playTimestamps\":[7,8]}"

        val decoded = adapter.fromJson(golden)

        assertEquals(
            OutboxActionPayload(
                playCountDelta = 2,
                isFavorite = false,
                userRating = 1,
                resumeMs = 7L,
                lastPlayedAt = 9L,
                playTimestamps = listOf(7L, 8L),
            ),
            decoded,
        )
    }

    @Test
    fun `decoding a legacy payload without the timestamp list still decodes`() {
        val decoded = adapter.fromJson("""{"playCountDelta":1,"lastPlayedAt":5}""")

        assertEquals(OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 5L), decoded)
    }

    @Test
    fun `submitting one of two identical timestamps keeps the other`() {
        val payload = OutboxActionPayload(
            playCountDelta = 2,
            lastPlayedAt = 1_000L,
            playTimestamps = listOf(1_000L, 1_000L),
        )

        assertEquals(
            OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 1_000L, playTimestamps = listOf(1_000L)),
            payload.afterSubmitting(listOf(1_000L)),
        )
    }

    @Test
    fun `submitting part of a legacy timestampless payload keeps the rest`() {
        val payload = OutboxActionPayload(playCountDelta = 3, lastPlayedAt = 5_000L)

        assertEquals(
            OutboxActionPayload(playCountDelta = 2, lastPlayedAt = 5_001L, playTimestamps = listOf(5_001L, 5_002L)),
            payload.afterSubmitting(listOf(5_000L)),
        )
    }

    @Test
    fun `round trip through encode and decode is lossless`() {
        val payload = OutboxActionPayload(
            playCountDelta = 1,
            resumeMs = 12_345L,
            playTimestamps = listOf(1L, 2L, 3L),
        )

        assertEquals(payload, adapter.fromJson(adapter.toJson(payload)))
    }
}
