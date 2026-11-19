package app.omnisonic.core.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

internal class BlockingHistoryDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    val readStarted = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()
    private val state = MutableStateFlow(initial)

    val persisted: Preferences get() = state.value

    override val data: Flow<Preferences> = flow {
        val snapshot = state.value
        readStarted.complete(Unit)
        releaseRead.await()
        emit(snapshot)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}
