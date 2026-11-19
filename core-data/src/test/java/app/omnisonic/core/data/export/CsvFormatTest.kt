package app.omnisonic.core.data.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CsvFormatTest {

    @Test
    fun `fields without special characters are written as-is`() {
        assertThat(CsvFormat.field("song-1")).isEqualTo("song-1")
        assertThat(CsvFormat.field("1970-01-01T00:00:01Z")).isEqualTo("1970-01-01T00:00:01Z")
        assertThat(CsvFormat.field("")).isEqualTo("")
    }

    @Test
    fun `comma in a field quotes the whole field`() {
        assertThat(CsvFormat.field("a,b")).isEqualTo("\"a,b\"")
    }

    @Test
    fun `quote in a field is doubled and the field is quoted`() {
        assertThat(CsvFormat.field("a\"b")).isEqualTo("\"a\"\"b\"")
    }

    @Test
    fun `newline and carriage return in a field quote the whole field`() {
        assertThat(CsvFormat.field("a\nb")).isEqualTo("\"a\nb\"")
        assertThat(CsvFormat.field("a\rb")).isEqualTo("\"a\rb\"")
        assertThat(CsvFormat.field("a\r\nb")).isEqualTo("\"a\r\nb\"")
    }

    @Test
    fun `records join fields with commas and end in crlf`() {
        assertThat(CsvFormat.record("1", "song-1", "true")).isEqualTo("1,song-1,true\r\n")
        assertThat(CsvFormat.record("1", "a,b")).isEqualTo("1,\"a,b\"\r\n")
    }

    @Test
    fun `header pins the column order`() {
        assertThat(CsvFormat.HEADER)
            .isEqualTo("providerId,externalId,playedAtUtc,playedAtMs,played,skipped,durationMs")
    }
}
