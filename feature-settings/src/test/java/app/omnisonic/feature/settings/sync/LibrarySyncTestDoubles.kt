package app.omnisonic.feature.settings.sync

import app.omnisonic.core.data.sync.LibrarySyncControl
import app.omnisonic.core.data.sync.LibrarySyncLastResult
import app.omnisonic.core.data.sync.LibrarySyncOutcome
import app.omnisonic.core.data.sync.LibrarySyncPhase
import app.omnisonic.core.data.sync.LibrarySyncProgress
import app.omnisonic.core.data.sync.LibrarySyncProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records the calls the screens make; publishes progress on demand. */
internal class FakeLibrarySyncControl(
    initialProgress: LibrarySyncProgress? = null,
    initialProviders: List<LibrarySyncProvider> = emptyList(),
) : LibrarySyncControl {

    private val _progress = MutableStateFlow(initialProgress)
    override val progress: StateFlow<LibrarySyncProgress?> = _progress

    var providersResult: List<LibrarySyncProvider> = initialProviders
    var providersRequests: Int = 0
        private set
    val fullSyncCalls = mutableListOf<Long>()
    val diffSyncCalls = mutableListOf<Long>()

    override suspend fun providers(): List<LibrarySyncProvider> {
        providersRequests += 1
        return providersResult
    }

    override fun startFullSync(providerId: Long) {
        fullSyncCalls += providerId
    }

    override fun startDiffSync(providerId: Long) {
        diffSyncCalls += providerId
    }

    fun publish(progress: LibrarySyncProgress) {
        _progress.value = progress
    }
}

internal fun provider(
    id: Long = 1,
    name: String = "Home",
    supportsLibrarySync: Boolean = true,
    lastFullSyncAt: Long? = null,
    lastDiffSyncAt: Long? = null,
    lastResult: LibrarySyncLastResult = LibrarySyncLastResult.NEVER,
    lastMessage: String? = null,
): LibrarySyncProvider = LibrarySyncProvider(
    providerId = id,
    name = name,
    supportsLibrarySync = supportsLibrarySync,
    lastFullSyncAt = lastFullSyncAt,
    lastDiffSyncAt = lastDiffSyncAt,
    lastResult = lastResult,
    lastMessage = lastMessage,
)

internal fun runningProgress(
    providerId: Long = 1,
    phase: LibrarySyncPhase = LibrarySyncPhase.ALBUMS_SONGS,
    itemsDone: Int = 3,
    itemsTotal: Int = 10,
): LibrarySyncProgress = LibrarySyncProgress(
    providerId = providerId,
    phase = phase,
    startedAt = 100,
    itemsDone = itemsDone,
    itemsTotal = itemsTotal,
)

internal fun terminalProgress(
    phase: LibrarySyncPhase,
    providerId: Long = 1,
): LibrarySyncProgress = LibrarySyncProgress(
    providerId = providerId,
    phase = phase,
    startedAt = 100,
    finishedAt = 200,
    outcome = when (phase) {
        LibrarySyncPhase.DONE -> LibrarySyncOutcome.COMPLETED
        LibrarySyncPhase.FAILED -> LibrarySyncOutcome.FAILED
        LibrarySyncPhase.BLOCKED -> LibrarySyncOutcome.BLOCKED
        else -> LibrarySyncOutcome.SKIPPED
    },
)
