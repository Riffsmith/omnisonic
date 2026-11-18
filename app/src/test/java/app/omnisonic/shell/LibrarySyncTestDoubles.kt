package app.omnisonic.shell

import app.omnisonic.core.data.sync.LibrarySyncControl
import app.omnisonic.core.data.sync.LibrarySyncLastResult
import app.omnisonic.core.data.sync.LibrarySyncOutcome
import app.omnisonic.core.data.sync.LibrarySyncPhase
import app.omnisonic.core.data.sync.LibrarySyncProgress
import app.omnisonic.core.data.sync.LibrarySyncProvider
import app.omnisonic.core.data.sync.OutboxPendingProvider
import app.omnisonic.core.data.sync.OutboxPendingRepository
import app.omnisonic.core.data.sync.OutboxSyncControl
import app.omnisonic.core.data.sync.OutboxSyncReport
import app.omnisonic.core.data.sync.OutboxSyncState
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.sync.LibrarySyncManagerViewModel
import app.omnisonic.feature.settings.sync.LibrarySyncStepViewModel
import app.omnisonic.feature.settings.sync.PendingSyncViewModel
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Shell tests only need the sync view models because the shell holds them;
 * walk behavior lives in `:core-data` and step/manager transitions in
 * `:feature-settings`.
 */
internal class FakeLibrarySyncControl(
    initialProgress: LibrarySyncProgress? = null,
    initialProviders: List<LibrarySyncProvider> = emptyList(),
) : LibrarySyncControl {

    private val _progress = MutableStateFlow(initialProgress)
    override val progress: StateFlow<LibrarySyncProgress?> = _progress

    var providersResult: List<LibrarySyncProvider> = initialProviders
    val fullSyncCalls = mutableListOf<Long>()
    val diffSyncCalls = mutableListOf<Long>()

    override suspend fun providers(): List<LibrarySyncProvider> = providersResult

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

internal fun syncDoneProvider(id: Long = 1): LibrarySyncProvider = LibrarySyncProvider(
    providerId = id,
    name = "Home",
    supportsLibrarySync = true,
    lastFullSyncAt = 1_000,
    lastResult = LibrarySyncLastResult.COMPLETED,
)

/** Fresh-install shape: no completed walk, no live progress (step stays put). */
internal fun syncPendingProvider(id: Long = 1): LibrarySyncProvider = LibrarySyncProvider(
    providerId = id,
    name = "Home",
    supportsLibrarySync = true,
    lastFullSyncAt = null,
    lastResult = LibrarySyncLastResult.NEVER,
)

internal fun syncCompletedProgress(providerId: Long = 1): LibrarySyncProgress =
    LibrarySyncProgress(
        providerId = providerId,
        phase = LibrarySyncPhase.DONE,
        startedAt = 100,
        finishedAt = 200,
        outcome = LibrarySyncOutcome.COMPLETED,
    )

internal fun testLibrarySyncStepViewModel(
    // Inert default: a completed fake would auto-advance off the route during
    // composition and break tests that assert the destination itself.
    control: LibrarySyncControl = FakeLibrarySyncControl(
        initialProgress = null,
        initialProviders = listOf(syncPendingProvider()),
    ),
): LibrarySyncStepViewModel = LibrarySyncStepViewModel(
    sync = control,
    shellPreferences = mockk(relaxed = true),
)

internal fun testLibrarySyncManagerViewModel(
    control: LibrarySyncControl = FakeLibrarySyncControl(),
): LibrarySyncManagerViewModel = LibrarySyncManagerViewModel(control)

/** Shell tests never trigger real network work; requests are counted, not run. */
internal class FakeOutboxSyncControl : OutboxSyncControl {

    private val _state = MutableStateFlow(OutboxSyncState())
    override val state: StateFlow<OutboxSyncState> = _state

    var drainRequests = 0
    var retryRequests = 0

    override fun requestDrain() {
        drainRequests++
    }

    override fun retryNow() {
        retryRequests++
    }

    override suspend fun drainNow(): OutboxSyncReport? = null

    override suspend fun retryAllNow(): OutboxSyncReport? = null

    fun publish(state: OutboxSyncState) {
        _state.value = state
    }
}

internal fun testPendingSyncViewModel(
    providers: List<OutboxPendingProvider> = emptyList(),
    control: OutboxSyncControl = FakeOutboxSyncControl(),
): PendingSyncViewModel {
    val repository = mockk<OutboxPendingRepository>()
    every { repository.providers } returns flowOf(providers)
    return PendingSyncViewModel(repository, control)
}

internal fun tempShellStore(tag: String): ShellPreferencesStore =
    ShellPreferencesStore.createAt(File("/tmp/opencode", "$tag-${System.nanoTime()}.preferences_pb"))
