package app.omnisonic.core.data.backup

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackupManifest(
    val formatVersion: Int = BackupFormat.FORMAT_VERSION,
    val appVersion: String,
    val createdAtMs: Long,
    val providers: List<ProviderBackupSection> = emptyList(),
    val settingsEntry: String = BackupFormat.SETTINGS_ENTRY,
    val styleEntry: String? = null,
)

@JsonClass(generateAdapter = true)
data class ProviderBackupSection(
    val providerId: Long,
    val name: String,
    val userDataEntry: String,
    val eventsEntry: String,
    val songCount: Int,
    val eventCount: Int,
)

/** Locally-owned song state; identity is [externalId], or [filePath] for local files. */
@JsonClass(generateAdapter = true)
data class SongUserData(
    val externalId: String?,
    val filePath: String?,
    val playCount: Int,
    val lastPlayed: Long?,
    val skipped: Boolean,
    val lastSkipped: Long?,
    val userRating: Int?,
    val isFavorite: Boolean,
    val favoriteAt: Long?,
    val resumeMs: Long?,
    val resumeUpdatedAt: Long?,
)

/** One `play_events` row without its local row id; restore keys on (`externalId`, `playedAtMs`). */
@JsonClass(generateAdapter = true)
data class PlayEventRecord(
    val externalId: String,
    val playedAtMs: Long,
    val dayStartMs: Long,
    val played: Boolean,
    val skipped: Boolean,
    val durationMs: Long,
)

@JsonClass(generateAdapter = true)
data class BackupSettingsSnapshot(
    val albumCountRule: String,
    val includeSupportingArtists: Boolean,
    val pausedProviderIds: List<Long>,
)

data class BackupEntry(
    val name: String,
    val json: String,
)

data class BackupSnapshot(
    val manifest: BackupManifest,
    val entries: List<BackupEntry>,
)
