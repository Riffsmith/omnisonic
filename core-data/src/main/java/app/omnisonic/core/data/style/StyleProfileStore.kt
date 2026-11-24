package app.omnisonic.core.data.style

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.StyleIssue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface StyleSwitchResult {

    data class Switched(val id: String) : StyleSwitchResult

    data class Rejected(val id: String, val errors: List<StyleIssue>) : StyleSwitchResult {
        val firstMessage: String get() = errors.first().message
    }
}

/**
 * A mutation-time snapshot of the active id: a raw DataStore read that reports
 * [Unreadable] when the pointer cannot be read instead of falling back the way
 * the [StyleProfileStore.activeId] observation flow does (D-113). A mutation
 * that would delete data must not proceed on unknown ownership.
 */
sealed interface ActiveIdSnapshot {

    /** The pointer was read; [id] is the persisted value (null = none set). */
    data class Known(val id: String?) : ActiveIdSnapshot

    /** The pointer could not be read; the active id is unknown, not absent. */
    data class Unreadable(val detail: String) : ActiveIdSnapshot
}

/**
 * Active-profile pointer over the `omnisonic_style` DataStore
 * (`docs/omnisonic/style/style-loader.md` §4, D-73).
 *
 * [activeProfile] is [StyleProfileResolver.resolve] over the persisted id and
 * re-resolves after a repository write or delete, so an edited active file is
 * observed; a DataStore read failure resolves the default instead of failing
 * collection. [switch] validates the candidate through
 * [StyleProfileResolver.resolveCandidate], which merges it over the built-in
 * exactly as observation does, and rejects a candidate that cannot be applied.
 * [switch] and [delete] share [operationMutex], so a candidate can never turn
 * up active after its file was deleted.
 *
 * Observation and mutation read the pointer differently (D-113): [activeId]
 * converts a read failure to null so observers fall back to the default, while
 * [snapshotActiveId] is the mutation-only raw read that reports
 * [ActiveIdSnapshot.Unreadable]. [delete] with an explicit [activeAtStart]
 * snapshot uses the value captured before the repository deletion, so a read
 * failure can never be mistaken for "not active" after the file is gone.
 */
class StyleProfileStore(
    private val dataStore: DataStore<Preferences>,
    private val repository: StyleRepository,
    private val resolver: StyleProfileResolver,
    private val logger: OmnisonicLogger,
) {

    private val operationMutex = Mutex()

    val activeId: Flow<String?> = dataStore.data
        .catch { failure ->
            logger.warn(TAG, "the active style id could not be read; using the default style", failure)
            emit(emptyPreferences())
        }
        .map { prefs -> prefs[Keys.ACTIVE_STYLE_ID] }

    val activeProfile: Flow<StyleProfileState> =
        combine(activeId, repository.changes) { id, _ -> resolver.resolve(id) }
            .distinctUntilChanged()

    suspend fun switch(id: String): StyleSwitchResult = operationMutex.withLock {
        when (val candidate = resolver.resolveCandidate(id)) {
            is StyleProfileState.Resolved -> {
                dataStore.edit { prefs -> prefs[Keys.ACTIVE_STYLE_ID] = id }
                StyleSwitchResult.Switched(id)
            }

            is StyleProfileState.Unavailable -> StyleSwitchResult.Rejected(id, candidate.errors)
        }
    }

    /**
     * Mutation-only snapshot of the active id: a raw read of the DataStore that
     * never applies the [activeId] observation fallback. A delete (or any
     * destructive mutation) must capture this **before** it deletes anything and
     * refuse to proceed on [ActiveIdSnapshot.Unreadable] (D-113).
     */
    suspend fun snapshotActiveId(): ActiveIdSnapshot = operationMutex.withLock {
        try {
            ActiveIdSnapshot.Known(dataStore.data.first()[Keys.ACTIVE_STYLE_ID])
        } catch (failure: IOException) {
            ActiveIdSnapshot.Unreadable(
                failure.message ?: "the active style pointer could not be read",
            )
        } catch (failure: SecurityException) {
            ActiveIdSnapshot.Unreadable(
                failure.message ?: "the active style pointer could not be read",
            )
        }
    }

    suspend fun delete(id: String): StyleDeleteOutcome = operationMutex.withLock {
        deleteLocked(id, activeId.first())
    }

    /**
     * Deletes [id] using [activeAtStart] — a snapshot captured by
     * [snapshotActiveId] before the repository deletion — to decide whether the
     * pointer must be cleared. The observation fallback is never used here.
     */
    suspend fun delete(id: String, activeAtStart: String?): StyleDeleteOutcome =
        operationMutex.withLock {
            deleteLocked(id, activeAtStart)
        }

    private suspend fun deleteLocked(id: String, activeAtStart: String?): StyleDeleteOutcome {
        val outcome = repository.delete(id)
        val gone = outcome is StyleDeleteOutcome.Deleted || outcome is StyleDeleteOutcome.NotFound
        if (gone && activeAtStart == id) {
            dataStore.edit { prefs -> prefs.remove(Keys.ACTIVE_STYLE_ID) }
        }
        return outcome
    }

    private object Keys {
        val ACTIVE_STYLE_ID = stringPreferencesKey("style.active_id")
    }

    companion object {
        const val DATASTORE_NAME: String = "omnisonic_style"

        private const val TAG = "styleProfileStore"

        fun create(
            context: Context,
            repository: StyleRepository,
            resolver: StyleProfileResolver,
            logger: OmnisonicLogger,
        ): StyleProfileStore =
            createAt(context.preferencesDataStoreFile(DATASTORE_NAME), repository, resolver, logger)

        fun createAt(
            file: File,
            repository: StyleRepository,
            resolver: StyleProfileResolver,
            logger: OmnisonicLogger,
        ): StyleProfileStore = StyleProfileStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
            repository,
            resolver,
            logger,
        )

        fun inMemory(
            repository: StyleRepository,
            resolver: StyleProfileResolver,
            logger: OmnisonicLogger,
        ): StyleProfileStore = StyleProfileStore(InMemoryDataStore(), repository, resolver, logger)
    }

    private class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}
