package app.omnisonic.core.data.db.dao

/**
 * Android 10 ships SQLite 3.22, which rejects statements bound with more than
 * 999 variables (SQLITE_MAX_VARIABLE_NUMBER). DAO queries named `...Chunk`
 * take exactly one pre-sized batch of a parameterized IN list; their
 * plain-named default-method wrappers feed them batches of this size.
 */
const val SQL_IN_CHUNK_SIZE = 500

suspend fun <T> List<T>.forEachSqlChunk(action: suspend (List<T>) -> Unit) {
    for (batch in chunked(SQL_IN_CHUNK_SIZE)) action(batch)
}
