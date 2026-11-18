package app.omnisonic.core.data.backup

/** A fully parsed and validated backup; safe to [BackupRestorer.preview] or apply. */
data class ParsedBackup(
    val manifest: BackupManifest,
    val sections: List<ParsedProviderSection>,
    val settings: BackupSettingsSnapshot,
    val styleProfileJson: String?,
)

data class ParsedProviderSection(
    val section: ProviderBackupSection,
    val userData: List<SongUserData>,
    val events: List<PlayEventRecord>,
)

enum class RestoreMode { MERGE, REPLACE }

/** One song whose locally-owned values differed between the local row and the backup. */
data class RestoreConflict(
    val providerId: Long,
    val externalId: String?,
    val filePath: String?,
    val fields: List<String>,
)

data class ProviderRestorePreview(
    val section: ProviderBackupSection,
    val localProviderId: Long?,
    val songCount: Int,
    val eventCount: Int,
    val newEvents: Int,
    val duplicateEvents: Int,
    val songsMatched: Int,
    val songsUnmatched: Int,
    val localEventCount: Int,
    val conflicts: List<RestoreConflict>,
)

data class RestorePreview(
    val manifest: BackupManifest,
    val providers: List<ProviderRestorePreview>,
    val settings: BackupSettingsSnapshot,
    val styleProfileJson: String?,
)

data class ProviderRestoreResult(
    val section: ProviderBackupSection,
    val localProviderId: Long?,
    val applied: Boolean,
    val eventsInserted: Int,
    val eventsRemoved: Int,
    val songsUpdated: Int,
    val songsSkipped: Int,
    val conflicts: List<RestoreConflict>,
)

data class RestoreResult(
    val mode: RestoreMode,
    val providers: List<ProviderRestoreResult>,
    val settingsApplied: Boolean,
    val styleProfileJson: String?,
)
