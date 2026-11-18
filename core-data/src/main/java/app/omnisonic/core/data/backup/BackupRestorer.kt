package app.omnisonic.core.data.backup

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.PlaybackDay
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.aggregation.recomputeAlbumCountsForProvider
import app.omnisonic.core.data.db.aggregation.recomputeArtistCountsForProvider
import app.omnisonic.core.data.db.entity.PlayEventEntity
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.history.HistoryRecordingGate
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.provider.api.OutboxActions
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restore side of the backup container (Phase 6 task 7). [readBackup]
 * parses and validates the whole ZIP — manifest entry first, known
 * [BackupFormat.FORMAT_VERSION], every referenced entry present and
 * decodable — and rejects anything malformed with a
 * [BackupRestoreException] before the database is touched. [preview]
 * reports per-provider counts and conflicts read-only; [restore] applies one
 * parsed backup in a single transaction.
 *
 * REPLACE wipes the matched provider's event log, play state and pending
 * play-count outbox rows first, then applies the backup. MERGE only inserts
 * events whose (`externalId`, `playedAtMs`) is not already local. In both
 * modes the backup wins user-data conflicts, which are reported either way.
 * Provider sections resolve by id first, then by name; unmatched sections
 * are reported and skipped. Events are keyed on (`externalId`,
 * `playedAtMs`); user data attaches only to an existing local song row, so
 * events for songs the catalog has not synced yet are kept while their user
 * data is reported as skipped.
 */
@Singleton
class BackupRestorer @Inject constructor(
    private val db: OmnisonicDatabase,
    private val aggregateSettings: PlayAggregateSettings,
    private val recordingGate: HistoryRecordingGate,
) {

    suspend fun readBackup(source: BackupDocumentSource): ParsedBackup =
        source.open().use { readBackup(it) }

    suspend fun readBackup(input: InputStream): ParsedBackup {
        val entries = readEntries(input)
        val first = entries.keys.firstOrNull()
        if (first != BackupFormat.MANIFEST_ENTRY) {
            if (BackupFormat.MANIFEST_ENTRY !in entries) throw MissingManifest()
            throw MalformedBackup("${BackupFormat.MANIFEST_ENTRY} must be the first entry")
        }
        val manifest = decode(manifestAdapter, entries.getValue(BackupFormat.MANIFEST_ENTRY), BackupFormat.MANIFEST_ENTRY)
        if (manifest.formatVersion != BackupFormat.FORMAT_VERSION) {
            throw UnsupportedFormatVersion(manifest.formatVersion)
        }
        val providerIds = manifest.providers.map { it.providerId }
        if (providerIds.toSet().size != providerIds.size) {
            throw MalformedEntry(BackupFormat.MANIFEST_ENTRY, "duplicate provider section")
        }
        val sections = manifest.providers.map { section ->
            if (section.userDataEntry != BackupFormat.userDataEntry(section.providerId) ||
                section.eventsEntry != BackupFormat.eventsEntry(section.providerId)
            ) {
                throw MalformedEntry(BackupFormat.MANIFEST_ENTRY, "provider entries must follow the canonical layout")
            }
            val userData = decodeList(
                songListAdapter,
                entries[section.userDataEntry] ?: throw MissingEntry(section.userDataEntry),
                section.userDataEntry,
            )
            val events = decodeList(
                eventListAdapter,
                entries[section.eventsEntry] ?: throw MissingEntry(section.eventsEntry),
                section.eventsEntry,
            )
            if (userData.size != section.songCount || events.size != section.eventCount) {
                throw MalformedEntry(BackupFormat.MANIFEST_ENTRY, "section counts do not match its entries")
            }
            userData.forEach {
                if (it.externalId == null && it.filePath == null) {
                    throw MalformedEntry(section.userDataEntry, "song user data without externalId or filePath")
                }
            }
            events.forEach {
                if (it.externalId.isBlank() || it.playedAtMs <= 0) {
                    throw MalformedEntry(section.eventsEntry, "event without externalId or positive playedAtMs")
                }
            }
            ParsedProviderSection(section, userData, events)
        }
        val settings = decode(
            settingsAdapter,
            entries[manifest.settingsEntry] ?: throw MissingEntry(manifest.settingsEntry),
            manifest.settingsEntry,
        )
        if (runCatching { AlbumCountRule.valueOf(settings.albumCountRule) }.isFailure) {
            throw MalformedEntry(manifest.settingsEntry, "unknown albumCountRule ${settings.albumCountRule}")
        }
        val styleProfileJson = manifest.styleEntry?.let { name ->
            val json = entries[name] ?: throw MissingEntry(name)
            if (runCatching { anyAdapter.fromJson(json) }.getOrNull() == null) {
                throw MalformedEntry(name, "not a JSON value")
            }
            json
        }
        return ParsedBackup(manifest, sections, settings, styleProfileJson)
    }

    /** Read-only preview: merge counts, unmatched rows and the conflict list, no writes. */
    suspend fun preview(parsed: ParsedBackup): RestorePreview {
        val providers = parsed.sections.map { parsedSection ->
            val local = resolveProvider(parsedSection.section)
            val localId = local?.id
            val existingEvents = localId?.let {
                db.playEventDao().selectByProvider(it)
                    .map { event -> event.externalId to event.playedAtMs }
                    .toHashSet()
            } ?: emptySet()
            var newEvents = 0
            var duplicateEvents = 0
            for (event in parsedSection.events) {
                if ((event.externalId to event.playedAtMs) in existingEvents) duplicateEvents++ else newEvents++
            }
            var matched = 0
            var unmatched = 0
            val conflicts = mutableListOf<RestoreConflict>()
            if (local != null) {
                for (data in parsedSection.userData) {
                    val song = findSong(local.id, data)
                    if (song == null) {
                        unmatched++
                    } else {
                        matched++
                        val fields = differingFields(song, data)
                        if (fields.isNotEmpty()) {
                            conflicts += RestoreConflict(
                                parsedSection.section.providerId,
                                data.externalId,
                                data.filePath,
                                fields,
                            )
                        }
                    }
                }
            } else {
                unmatched = parsedSection.userData.size
            }
            ProviderRestorePreview(
                section = parsedSection.section,
                localProviderId = localId,
                songCount = parsedSection.userData.size,
                eventCount = parsedSection.events.size,
                newEvents = if (local == null) 0 else newEvents,
                duplicateEvents = duplicateEvents,
                songsMatched = matched,
                songsUnmatched = unmatched,
                localEventCount = localId?.let { db.playEventDao().countByProvider(it) } ?: 0,
                conflicts = conflicts,
            )
        }
        return RestorePreview(parsed.manifest, providers, parsed.settings, parsed.styleProfileJson)
    }

    suspend fun restore(source: BackupDocumentSource, mode: RestoreMode): RestoreResult =
        restore(readBackup(source), mode)

    suspend fun restore(input: InputStream, mode: RestoreMode): RestoreResult =
        restore(readBackup(input), mode)

    suspend fun restore(parsed: ParsedBackup, mode: RestoreMode): RestoreResult {
        val results = db.withTransaction {
            parsed.sections.map { applySection(it, mode, parsed.settings) }
        }
        applySettings(parsed.settings)
        return RestoreResult(mode, results, settingsApplied = true, parsed.styleProfileJson)
    }

    private suspend fun applySection(
        parsedSection: ParsedProviderSection,
        mode: RestoreMode,
        settings: BackupSettingsSnapshot,
    ): ProviderRestoreResult {
        val section = parsedSection.section
        val local = resolveProvider(section)
        if (local == null) {
            return ProviderRestoreResult(
                section = section,
                localProviderId = null,
                applied = false,
                eventsInserted = 0,
                eventsRemoved = 0,
                songsUpdated = 0,
                songsSkipped = parsedSection.userData.size,
                conflicts = emptyList(),
            )
        }
        val providerId = local.id
        val songDao = db.songDao()
        val eventDao = db.playEventDao()

        val userDataPairs = mutableListOf<Pair<Long, SongUserData>>()
        val conflicts = mutableListOf<RestoreConflict>()
        var skipped = 0
        for (data in parsedSection.userData) {
            val song = findSong(providerId, data)
            if (song == null) {
                skipped++
                continue
            }
            val fields = differingFields(song, data)
            if (fields.isNotEmpty()) {
                conflicts += RestoreConflict(section.providerId, data.externalId, data.filePath, fields)
            }
            userDataPairs += song.id to data
        }

        var eventsRemoved = 0
        if (mode == RestoreMode.REPLACE) {
            eventsRemoved = eventDao.countByProvider(providerId)
            eventDao.deleteByProvider(providerId)
            songDao.resetPlayStateByProvider(providerId)
            db.outboxDao().deleteByProviderAndAction(providerId, OutboxActions.PLAY_COUNT)
        }

        val knownEvents = if (mode == RestoreMode.MERGE) {
            eventDao.selectByProvider(providerId)
                .map { it.externalId to it.playedAtMs }
                .toHashSet()
        } else {
            hashSetOf()
        }
        var inserted = 0
        for (event in parsedSection.events) {
            val key = event.externalId to event.playedAtMs
            if (key in knownEvents) continue
            knownEvents += key
            eventDao.insert(
                PlayEventEntity(
                    providerId = providerId,
                    externalId = event.externalId,
                    playedAtMs = event.playedAtMs,
                    // the backup may come from another time zone, so the day bucket is local
                    dayStartMs = PlaybackDay.dayStart(event.playedAtMs),
                    played = event.played,
                    skipped = event.skipped,
                    durationMs = event.durationMs,
                ),
            )
            inserted++
        }

        for ((songId, data) in userDataPairs) {
            songDao.applyUserData(
                id = songId,
                playCount = data.playCount,
                lastPlayed = data.lastPlayed,
                skipped = data.skipped,
                lastSkipped = data.lastSkipped,
                userRating = data.userRating,
                isFavorite = data.isFavorite,
                favoriteAt = data.favoriteAt,
                resumeMs = data.resumeMs,
                resumeUpdatedAt = data.resumeUpdatedAt,
            )
        }

        val rule = AlbumCountRule.valueOf(settings.albumCountRule)
        db.aggregateDao().recomputeAlbumCountsForProvider(providerId, rule)
        db.aggregateDao().recomputeArtistCountsForProvider(providerId, settings.includeSupportingArtists)

        return ProviderRestoreResult(
            section = section,
            localProviderId = providerId,
            applied = true,
            eventsInserted = inserted,
            eventsRemoved = eventsRemoved,
            songsUpdated = userDataPairs.size,
            songsSkipped = skipped,
            conflicts = conflicts,
        )
    }

    private suspend fun resolveProvider(section: ProviderBackupSection): ProviderEntity? =
        db.providerDao().selectById(section.providerId)
            ?: db.providerDao().selectByName(section.name).firstOrNull()

    private suspend fun findSong(providerId: Long, data: SongUserData): SongEntity? =
        data.externalId?.let { db.songDao().selectByProviderAndExternal(providerId, it) }
            ?: data.filePath?.let { db.songDao().selectByProviderAndFilePath(providerId, it) }

    private fun applySettings(settings: BackupSettingsSnapshot) {
        aggregateSettings.update(
            PlayAggregateSettings.Options(
                albumCountRule = AlbumCountRule.valueOf(settings.albumCountRule),
                includeSupportingArtists = settings.includeSupportingArtists,
            ),
        )
        val target = settings.pausedProviderIds.toSet()
        val current = recordingGate.pausedProviders.value
        (current - target).forEach { recordingGate.setPaused(it, false) }
        (target - current).forEach { recordingGate.setPaused(it, true) }
    }

    private fun differingFields(song: SongEntity, data: SongUserData): List<String> {
        val fields = mutableListOf<String>()
        if (song.playCount != data.playCount) fields += "playCount"
        if (song.lastPlayed != data.lastPlayed) fields += "lastPlayed"
        if (song.skipped != data.skipped) fields += "skipped"
        if (song.lastSkipped != data.lastSkipped) fields += "lastSkipped"
        if (song.userRating != data.userRating) fields += "userRating"
        if (song.isFavorite != data.isFavorite) fields += "isFavorite"
        if (song.favoriteAt != data.favoriteAt) fields += "favoriteAt"
        if (song.resumeMs != data.resumeMs) fields += "resumeMs"
        if (song.resumeUpdatedAt != data.resumeUpdatedAt) fields += "resumeUpdatedAt"
        return fields
    }

    private fun readEntries(input: InputStream): LinkedHashMap<String, String> {
        val bytes = input.readBytes()
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw MalformedBackup("not a ZIP archive")
        }
        val entries = linkedMapOf<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name in entries) throw MalformedBackup("duplicate entry ${entry.name}")
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    entry = zip.nextEntry
                }
            }
        } catch (e: BackupRestoreException) {
            throw e
        } catch (e: ZipException) {
            throw MalformedBackup("not a readable ZIP", e)
        } catch (e: IOException) {
            throw MalformedBackup("could not read ZIP stream", e)
        }
        return entries
    }

    private fun <T> decode(adapter: JsonAdapter<T>, json: String, entryName: String): T =
        runCatching { adapter.fromJson(json) }.getOrNull()
            ?: throw MalformedEntry(entryName, "undecodable JSON")

    private fun <T> decodeList(adapter: JsonAdapter<List<T>>, json: String, entryName: String): List<T> =
        decode(adapter, json, entryName)

    private companion object {
        private val manifestAdapter = OmnisonicJson.moshi.adapter(BackupManifest::class.java)
        private val songListAdapter = OmnisonicJson.moshi
            .adapter<List<SongUserData>>(Types.newParameterizedType(List::class.java, SongUserData::class.java))
        private val eventListAdapter = OmnisonicJson.moshi
            .adapter<List<PlayEventRecord>>(Types.newParameterizedType(List::class.java, PlayEventRecord::class.java))
        private val settingsAdapter = OmnisonicJson.moshi.adapter(BackupSettingsSnapshot::class.java)
        private val anyAdapter = OmnisonicJson.moshi.adapter(Any::class.java)
    }
}
