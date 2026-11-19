package app.omnisonic.core.data.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow

data class HistoryPreferences(
    val playThresholdPercent: Int = HistoryPreferencesStore.DEFAULT_PLAY_THRESHOLD_PERCENT,
    val resumeMinPositionMs: Long = HistoryPreferencesStore.DEFAULT_RESUME_MIN_POSITION_MS,
    val pausedProviderIds: Set<Long> = emptySet(),
    val albumCountRule: AlbumCountRule = AlbumCountRule.AVERAGE,
    val includeSupportingArtists: Boolean = false,
)

/**
 * Persisted listen-history user choices (Phase 6 task 9): the play-count
 * threshold, the per-provider recording pause, the minimum resume position and
 * the aggregate options. The recorder reads the threshold/resume floors through
 * this store; the recording gate and the aggregate holder write through to it,
 * so a backup-restore of the settings snapshot lands here too. Values are
 * sanitized on write, and an unknown stored enum name decodes to the default.
 */
class HistoryPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<HistoryPreferences> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            HistoryPreferences(
                playThresholdPercent = prefs[Keys.PLAY_THRESHOLD_PERCENT]
                    ?: DEFAULT_PLAY_THRESHOLD_PERCENT,
                resumeMinPositionMs = prefs[Keys.RESUME_MIN_POSITION_MS]
                    ?: DEFAULT_RESUME_MIN_POSITION_MS,
                pausedProviderIds = prefs[Keys.PAUSED_PROVIDER_IDS]
                    .orEmpty()
                    .mapNotNull { it.toLongOrNull() }
                    .toSet(),
                albumCountRule = prefs[Keys.ALBUM_COUNT_RULE]
                    ?.let { raw -> AlbumCountRule.entries.firstOrNull { it.name == raw } }
                    ?: AlbumCountRule.AVERAGE,
                includeSupportingArtists = prefs[Keys.INCLUDE_SUPPORTING_ARTISTS] ?: false,
            )
        }

    suspend fun current(): HistoryPreferences = preferences.first()

    suspend fun setPlayThresholdPercent(percent: Int) {
        dataStore.edit { it[Keys.PLAY_THRESHOLD_PERCENT] = sanitizePlayThresholdPercent(percent) }
    }

    suspend fun setResumeMinPositionMs(ms: Long) {
        dataStore.edit { it[Keys.RESUME_MIN_POSITION_MS] = sanitizeResumeMinPositionMs(ms) }
    }

    suspend fun setPausedProviderIds(providerIds: Set<Long>) {
        dataStore.edit {
            it[Keys.PAUSED_PROVIDER_IDS] = providerIds.mapTo(mutableSetOf()) { id -> id.toString() }
        }
    }

    suspend fun setPaused(providerId: Long, paused: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PAUSED_PROVIDER_IDS].orEmpty().toMutableSet()
            if (paused) current += providerId.toString() else current -= providerId.toString()
            prefs[Keys.PAUSED_PROVIDER_IDS] = current
        }
    }

    suspend fun setAlbumCountRule(rule: AlbumCountRule) {
        dataStore.edit { it[Keys.ALBUM_COUNT_RULE] = rule.name }
    }

    suspend fun setIncludeSupportingArtists(include: Boolean) {
        dataStore.edit { it[Keys.INCLUDE_SUPPORTING_ARTISTS] = include }
    }

    suspend fun setAggregateOptions(rule: AlbumCountRule, includeSupportingArtists: Boolean) {
        dataStore.edit {
            it[Keys.ALBUM_COUNT_RULE] = rule.name
            it[Keys.INCLUDE_SUPPORTING_ARTISTS] = includeSupportingArtists
        }
    }

    private object Keys {
        val PLAY_THRESHOLD_PERCENT = intPreferencesKey("history.play_threshold_percent")
        val RESUME_MIN_POSITION_MS = longPreferencesKey("history.resume_min_position_ms")
        val PAUSED_PROVIDER_IDS = stringSetPreferencesKey("history.paused_provider_ids")
        val ALBUM_COUNT_RULE = stringPreferencesKey("history.album_count_rule")
        val INCLUDE_SUPPORTING_ARTISTS = booleanPreferencesKey("history.include_supporting_artists")
    }

    companion object {
        const val DEFAULT_PLAY_THRESHOLD_PERCENT = 95
        const val MIN_PLAY_THRESHOLD_PERCENT = 1
        const val MAX_PLAY_THRESHOLD_PERCENT = 100
        const val DEFAULT_RESUME_MIN_POSITION_MS = 10_000L
        const val MAX_RESUME_MIN_POSITION_MS = 600_000L

        fun sanitizePlayThresholdPercent(percent: Int): Int =
            percent.coerceIn(MIN_PLAY_THRESHOLD_PERCENT, MAX_PLAY_THRESHOLD_PERCENT)

        fun sanitizeResumeMinPositionMs(ms: Long): Long =
            ms.coerceIn(0L, MAX_RESUME_MIN_POSITION_MS)

        fun create(context: Context): HistoryPreferencesStore = HistoryPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("omnisonic_history") },
            ),
        )

        fun createAt(file: File): HistoryPreferencesStore = HistoryPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
        )

        /** Non-persisted store for in-memory holders used outside the app graph (tests). */
        fun inMemory(): HistoryPreferencesStore = HistoryPreferencesStore(InMemoryDataStore())
    }

    private class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}
