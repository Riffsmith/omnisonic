package app.omnisonic.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

object ProviderCascadeTriggers {

    val CREATE_STATEMENTS: List<String> = listOf(
        "library_sources",
        "artists",
        "albums",
        "genres",
        "songs",
        "album_artists",
        "song_artists",
        "chapters",
        "playlists",
        "playlists_entries",
        "internet_radios",
        "playback_events",
        "outbox_items",
        "offline_files",
        "sync_status",
    ).map { table ->
        "CREATE TRIGGER IF NOT EXISTS trg_provider_delete_$table " +
            "AFTER DELETE ON providers " +
            "BEGIN " +
            "DELETE FROM $table WHERE providerId = old.id; " +
            "END"
    } + listOf(
        "CREATE TRIGGER IF NOT EXISTS trg_playlist_delete_playlists_entries " +
            "AFTER DELETE ON playlists " +
            "BEGIN " +
            "DELETE FROM playlists_entries WHERE playlistId = old.id; " +
            "END",
    )

    fun install(db: SupportSQLiteDatabase) {
        CREATE_STATEMENTS.forEach(db::execSQL)
    }
}
