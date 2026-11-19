package app.omnisonic.core.data.export

import androidx.room.withTransaction
import app.omnisonic.core.data.backup.BackupDocumentSink
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.PlayEventEntity
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports the whole `play_events` log as one CSV document for outside tools
 * (Phase 6 task 8). UTF-8 with an optional BOM, RFC 4180 quoting, CRLF
 * records; each row carries the UTC ISO-8601 stamp next to the epoch millis.
 * Written through the same SAF document sink as the backup ZIP. The read runs
 * in one transaction so the file is a consistent point-in-time snapshot.
 */
@Singleton
class PlayEventCsvExporter @Inject constructor(
    private val db: OmnisonicDatabase,
) {

    suspend fun writeCsv(sink: BackupDocumentSink, includeBom: Boolean = false): Int =
        sink.open().use { writeCsv(it, includeBom) }

    suspend fun writeCsv(output: OutputStream, includeBom: Boolean = false): Int {
        val events = db.withTransaction { db.playEventDao().selectAllOrdered() }
        BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            if (includeBom) writer.write(CsvFormat.BOM.code)
            writer.write(CsvFormat.HEADER + CsvFormat.LINE_BREAK)
            for (event in events) writer.write(event.toCsvRecord())
        }
        return events.size
    }

    private fun PlayEventEntity.toCsvRecord(): String = CsvFormat.record(
        providerId.toString(),
        externalId,
        Instant.ofEpochMilli(playedAtMs).toString(),
        playedAtMs.toString(),
        played.toString(),
        skipped.toString(),
        durationMs.toString(),
    )
}
