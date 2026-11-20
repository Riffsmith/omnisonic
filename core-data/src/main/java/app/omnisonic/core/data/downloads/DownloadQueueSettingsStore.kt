package app.omnisonic.core.data.downloads

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persisted download settings and root state (`.agents/plans/phase-7-tasks.md`
 * §Task 2). The global pause gate lives in the same file so pausing an empty
 * queue is not forgotten; worker count and Wi-Fi-only are user-facing values;
 * the root fields are the publication and in-flight-move journal used by
 * [OfflineStorage].
 *
 * Every write is serialized through one mutex so a multi-key root transition is
 * not interleaved with a settings write. Reads that build or start workers must
 * call [awaitReady] first: defaults are only a fallback for a fresh file, never
 * a value to start a run with.
 */
class DownloadQueueSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {

    private val writeMutex = Mutex()

    val globallyPaused: Flow<Boolean> = dataStore.data
        .map { it[Keys.GLOBALLY_PAUSED] ?: false }

    val settings: Flow<DownloadSettings> = dataStore.data
        .map { prefs ->
            DownloadSettings(
                workerCount = DownloadSettingsRules.clampWorkerCount(
                    prefs[Keys.WORKER_COUNT] ?: DownloadSettingsRules.DEFAULT_WORKER_COUNT,
                ),
                wifiOnly = prefs[Keys.WIFI_ONLY] ?: DownloadSettingsRules.DEFAULT_WIFI_ONLY,
            )
        }

    val rootState: Flow<DownloadRootState> = dataStore.data
        .map { prefs ->
            DownloadRootState(
                selection = OfflineRootSelection.fromStored(prefs[Keys.ROOT_SELECTION]),
                activeVolumeId = prefs[Keys.ROOT_ACTIVE_VOLUME],
                generation = (prefs[Keys.ROOT_GENERATION] ?: 0L).coerceAtLeast(0L),
                pendingVolumeId = prefs[Keys.ROOT_PENDING_VOLUME],
                pendingSelection = prefs[Keys.ROOT_PENDING_SELECTION]?.let(OfflineRootSelection::fromStored),
                pendingGeneration = (prefs[Keys.ROOT_PENDING_GENERATION] ?: 0L).coerceAtLeast(0L),
                cleanupVolumeId = prefs[Keys.ROOT_CLEANUP_VOLUME],
            )
        }

    /** Waits for the first persisted emission; defaults never start a worker run. */
    suspend fun awaitReady(): DownloadSettings = settings.first()

    suspend fun currentSettings(): DownloadSettings = settings.first()

    suspend fun currentRootState(): DownloadRootState = rootState.first()

    suspend fun isGloballyPaused(): Boolean = globallyPaused.first()

    suspend fun setGloballyPaused(paused: Boolean) = write {
        it[Keys.GLOBALLY_PAUSED] = paused
    }

    suspend fun setWorkerCount(requested: Int) = write {
        it[Keys.WORKER_COUNT] = DownloadSettingsRules.clampWorkerCount(requested)
    }

    suspend fun setWifiOnly(enabled: Boolean) = write {
        it[Keys.WIFI_ONLY] = enabled
    }

    suspend fun setRootSelection(selection: OfflineRootSelection) = write {
        it[Keys.ROOT_SELECTION] = selection.storedValue()
    }

    /**
     * Publishes a usable root. Called on the first successful [OfflineStorage.prepare]
     * and when a selected root is already active; a change with files in flight
     * commits through [commitRootMove] instead.
     */
    suspend fun publishRoot(
        selection: OfflineRootSelection,
        activeVolumeId: String,
        generation: Long,
    ) = write {
        it[Keys.ROOT_SELECTION] = selection.storedValue()
        it[Keys.ROOT_ACTIVE_VOLUME] = activeVolumeId
        it[Keys.ROOT_GENERATION] = generation.coerceAtLeast(0L)
    }

    /** Journals the target before any file is copied, so a crash can resume or roll back. */
    suspend fun beginRootMove(
        selection: OfflineRootSelection,
        pendingVolumeId: String,
        pendingGeneration: Long,
    ) = write {
        it[Keys.ROOT_PENDING_VOLUME] = pendingVolumeId
        it[Keys.ROOT_PENDING_SELECTION] = selection.storedValue()
        it[Keys.ROOT_PENDING_GENERATION] = pendingGeneration.coerceAtLeast(0L)
    }

    /** The single publication point: the new root becomes authoritative here. */
    suspend fun commitRootMove(
        selection: OfflineRootSelection,
        activeVolumeId: String,
        generation: Long,
        cleanupVolumeId: String?,
    ) = write {
        it[Keys.ROOT_SELECTION] = selection.storedValue()
        it[Keys.ROOT_ACTIVE_VOLUME] = activeVolumeId
        it[Keys.ROOT_GENERATION] = generation.coerceAtLeast(0L)
        it.remove(Keys.ROOT_PENDING_VOLUME)
        it.remove(Keys.ROOT_PENDING_SELECTION)
        it.remove(Keys.ROOT_PENDING_GENERATION)
        if (cleanupVolumeId == null) {
            it.remove(Keys.ROOT_CLEANUP_VOLUME)
        } else {
            it[Keys.ROOT_CLEANUP_VOLUME] = cleanupVolumeId
        }
    }

    /** Drops an interrupted attempt; the old published root stays authoritative. */
    suspend fun rollbackRootMove() = write {
        it.remove(Keys.ROOT_PENDING_VOLUME)
        it.remove(Keys.ROOT_PENDING_SELECTION)
        it.remove(Keys.ROOT_PENDING_GENERATION)
    }

    suspend fun finishRootCleanup() = write {
        it.remove(Keys.ROOT_CLEANUP_VOLUME)
    }

    private suspend fun write(transform: suspend (MutablePreferences) -> Unit) =
        writeMutex.withLock { dataStore.edit(transform) }

    private object Keys {
        val GLOBALLY_PAUSED = booleanPreferencesKey("downloads.globally_paused")
        val WORKER_COUNT = intPreferencesKey("downloads.worker_count")
        val WIFI_ONLY = booleanPreferencesKey("downloads.wifi_only")
        val ROOT_SELECTION = stringPreferencesKey("downloads.root_selection")
        val ROOT_ACTIVE_VOLUME = stringPreferencesKey("downloads.root_active_volume")
        val ROOT_GENERATION = longPreferencesKey("downloads.root_generation")
        val ROOT_PENDING_VOLUME = stringPreferencesKey("downloads.root_pending_volume")
        val ROOT_PENDING_SELECTION = stringPreferencesKey("downloads.root_pending_selection")
        val ROOT_PENDING_GENERATION = longPreferencesKey("downloads.root_pending_generation")
        val ROOT_CLEANUP_VOLUME = stringPreferencesKey("downloads.root_cleanup_volume")
    }

    companion object {
        fun create(context: Context): DownloadQueueSettingsStore = DownloadQueueSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("omnisonic_downloads") },
            ),
        )

        fun createAt(file: File): DownloadQueueSettingsStore = DownloadQueueSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
        )

        fun inMemory(): DownloadQueueSettingsStore = DownloadQueueSettingsStore(InMemoryDataStore())
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
