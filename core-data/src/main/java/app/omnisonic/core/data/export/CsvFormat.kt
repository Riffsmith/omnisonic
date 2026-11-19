package app.omnisonic.core.data.export

/**
 * RFC 4180 record formatting: a field containing a comma, quote, CR or LF is
 * wrapped in quotes with embedded quotes doubled; every record ends in CRLF.
 */
internal object CsvFormat {

    const val HEADER = "providerId,externalId,playedAtUtc,playedAtMs,played,skipped,durationMs"
    const val LINE_BREAK = "\r\n"
    const val BOM = '\uFEFF'

    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun record(vararg fields: String): String =
        fields.joinToString(separator = ",", transform = ::field) + LINE_BREAK
}
