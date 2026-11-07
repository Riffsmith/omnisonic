package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["providerId", "externalId"], unique = true),
        Index(value = ["providerId", "filePath"]),
        Index(value = ["albumId"]),
        Index(value = ["providerId", "playCount", "lastPlayed"]),
        Index(value = ["providerId", "isFavorite"]),
    ],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String? = null,
    val providerPayloadJson: String? = null,
    val albumId: Long? = null,
    val title: String,
    val latinTitle: String? = null,
    val sortTitle: String? = null,
    val displayArtist: String? = null,
    val filePath: String? = null,
    val durationMs: Long? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
    val genresJson: String? = null,
    val imagePath: String? = null,
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    val skipped: Boolean = false,
    val lastSkipped: Long? = null,
    val userRating: Int? = null,
    val isFavorite: Boolean = false,
    val favoriteAt: Long? = null,
    val serverRating: Double? = null,
    val resumeMs: Long? = null,
    val resumeUpdatedAt: Long? = null,
    val missingOnServer: Boolean = false,
    val replayGainTrack: Double? = null,
    val replayGainAlbum: Double? = null,
    val replayGainType: Int? = null,
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val isAudiobook: Boolean = false,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    /** Sync-run stamp (D-10); NULL means never seen by a stamping sync run. */
    val lastSeenSyncId: Long? = null,
    val updatedAt: Long,
)
