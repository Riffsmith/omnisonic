package app.omnisonic.core.data.backup

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.entity.PlayEventEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.history.HistoryPreferencesStore
import app.omnisonic.core.data.history.HistoryRecordingGate
import app.omnisonic.core.data.json.OmnisonicJson
import com.squareup.moshi.Types
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup ZIP writer (Phase 6 task 6; restore is task 7). The manifest entry
 * is written first so a restore can validate it before reading the rest.
 * Only songs that carry locally-owned state are exported — the catalog stays
 * with the server sync, so the ZIP never duplicates the library. Provider
 * credential columns are never read here (see
 * [app.omnisonic.core.data.security.BackupExcludes]).
 */
@Singleton
class BackupWriter @Inject constructor(
    private val db: OmnisonicDatabase,
    private val aggregateSettings: PlayAggregateSettings,
    private val recordingGate: HistoryRecordingGate,
    private val historyPreferences: HistoryPreferencesStore,
) {

    suspend fun writeBackup(
        sink: BackupDocumentSink,
        appVersionName: String,
        styleProfileJson: String? = null,
    ): BackupManifest = sink.open().use { writeBackup(it, appVersionName, styleProfileJson) }

    suspend fun writeBackup(
        output: OutputStream,
        appVersionName: String,
        styleProfileJson: String? = null,
    ): BackupManifest {
        val snapshot = collectSnapshot(appVersionName, styleProfileJson)
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            for (entry in snapshot.entries) {
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return snapshot.manifest
    }

    suspend fun collectSnapshot(appVersionName: String, styleProfileJson: String? = null): BackupSnapshot {
        recordingGate.awaitReady()
        aggregateSettings.awaitReady()
        val history = historyPreferences.current()
        return db.withTransaction {
            val sections = mutableListOf<ProviderBackupSection>()
            val entries = mutableListOf<BackupEntry>()
            for (provider in db.providerDao().selectAll()) {
                val songs = db.songDao().selectByProvider(provider.id).filter { it.carriesUserData() }
                val events = db.playEventDao().selectByProvider(provider.id)
                val userDataEntry = BackupFormat.userDataEntry(provider.id)
                val eventsEntry = BackupFormat.eventsEntry(provider.id)
                entries += BackupEntry(userDataEntry, songListAdapter.toJson(songs.map { it.toUserData() }))
                entries += BackupEntry(eventsEntry, eventListAdapter.toJson(events.map { it.toRecord() }))
                sections += ProviderBackupSection(
                    providerId = provider.id,
                    name = provider.name,
                    userDataEntry = userDataEntry,
                    eventsEntry = eventsEntry,
                    songCount = songs.size,
                    eventCount = events.size,
                )
            }
            val options = aggregateSettings.options()
            entries += BackupEntry(
                BackupFormat.SETTINGS_ENTRY,
                settingsAdapter.toJson(
                    BackupSettingsSnapshot(
                        albumCountRule = options.albumCountRule.name,
                        includeSupportingArtists = options.includeSupportingArtists,
                        pausedProviderIds = recordingGate.pausedProviders.value.toList().sorted(),
                        playThresholdPercent = history.playThresholdPercent,
                        resumeMinPositionMs = history.resumeMinPositionMs,
                    ),
                ),
            )
            if (styleProfileJson != null) {
                entries += BackupEntry(BackupFormat.STYLE_ENTRY, styleProfileJson)
            }
            val manifest = BackupManifest(
                formatVersion = BackupFormat.FORMAT_VERSION,
                appVersion = appVersionName,
                createdAtMs = System.currentTimeMillis(),
                providers = sections,
                styleEntry = if (styleProfileJson != null) BackupFormat.STYLE_ENTRY else null,
            )
            BackupSnapshot(
                manifest,
                listOf(BackupEntry(BackupFormat.MANIFEST_ENTRY, manifestAdapter.toJson(manifest))) + entries,
            )
        }
    }

    private fun SongEntity.carriesUserData(): Boolean =
        playCount > 0 || lastPlayed != null || skipped || lastSkipped != null ||
            userRating != null || isFavorite || favoriteAt != null ||
            resumeMs != null || resumeUpdatedAt != null

    private fun SongEntity.toUserData() = SongUserData(
        externalId = externalId,
        filePath = filePath,
        playCount = playCount,
        lastPlayed = lastPlayed,
        skipped = skipped,
        lastSkipped = lastSkipped,
        userRating = userRating,
        isFavorite = isFavorite,
        favoriteAt = favoriteAt,
        resumeMs = resumeMs,
        resumeUpdatedAt = resumeUpdatedAt,
    )

    private fun PlayEventEntity.toRecord() = PlayEventRecord(
        externalId = externalId,
        playedAtMs = playedAtMs,
        dayStartMs = dayStartMs,
        played = played,
        skipped = skipped,
        durationMs = durationMs,
    )

    private companion object {
        private val manifestAdapter =
            OmnisonicJson.moshi.adapter(BackupManifest::class.java).indent("  ")
        private val songListAdapter = OmnisonicJson.moshi
            .adapter<List<SongUserData>>(Types.newParameterizedType(List::class.java, SongUserData::class.java))
            .indent("  ")
        private val eventListAdapter = OmnisonicJson.moshi
            .adapter<List<PlayEventRecord>>(Types.newParameterizedType(List::class.java, PlayEventRecord::class.java))
            .indent("  ")
        private val settingsAdapter =
            OmnisonicJson.moshi.adapter(BackupSettingsSnapshot::class.java).indent("  ")
    }
}
