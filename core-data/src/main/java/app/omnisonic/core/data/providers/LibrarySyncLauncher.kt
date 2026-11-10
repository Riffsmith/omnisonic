package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.sync.LibrarySyncControl
import app.omnisonic.core.data.sync.LibrarySyncLastResult
import app.omnisonic.core.data.sync.LibrarySyncOutcome
import app.omnisonic.core.data.sync.LibrarySyncPhase
import app.omnisonic.core.data.sync.LibrarySyncProgress
import app.omnisonic.core.data.sync.LibrarySyncProvider
import app.omnisonic.core.data.sync.LibrarySyncResumePolicy
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.core.data.sync.SubsonicLibrarySyncer
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.AllowAllRunGate
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderTraits
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Which walk a trigger starts; full walks prune, diff walks follow the watermark. */
enum class LibrarySyncKind {
    FULL,
    DIFF,
}

/**
 * Starts a provider's library walk without blocking the screen that asked for
 * it. Kept as a seam so setup tests can assert the trigger without a network
 * walk.
 */
fun interface InitialLibrarySync {
    fun start(providerId: Long)
}

/**
 * Long-lived trigger over the existing library walker. The walk reuses the
 * registry-cached provider (and its client), consults the status tracker's run
 * gate, and reports auth outcomes so the provider row's status stays accurate.
 * A walk failure is logged, never thrown: setup has already completed.
 *
 * Live progress lands in [statuses] (D-16); [resumeIncompleteNow] restarts
 * initial walks a force-stop killed, and the run gate in [statuses] keeps a
 * live walk from being started twice. Task 4c's screens talk to this class
 * through [LibrarySyncControl] only.
 */
class LibrarySyncLauncher(
    private val database: OmnisonicDatabase,
    private val clients: ProviderClientRegistry,
    private val traitsFor: (String) -> ProviderTraits,
    private val scope: CoroutineScope,
    private val logger: OmnisonicLogger,
    private val runGate: ProviderRunGate = AllowAllRunGate,
    private val onAuthFailed: suspend (Long) -> Unit = {},
    private val onAuthSucceeded: suspend (Long) -> Unit = {},
    private val statuses: LibrarySyncStatusRepository = LibrarySyncStatusRepository(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : InitialLibrarySync, LibrarySyncControl {

    override val progress: StateFlow<LibrarySyncProgress?>
        get() = statuses.progress

    override fun start(providerId: Long) = start(providerId, LibrarySyncKind.FULL)

    override fun startFullSync(providerId: Long) = start(providerId, LibrarySyncKind.FULL)

    override fun startDiffSync(providerId: Long) = start(providerId, LibrarySyncKind.DIFF)

    fun start(providerId: Long, kind: LibrarySyncKind) {
        if (!statuses.tryBegin(providerId)) return
        scope.launch {
            try {
                run(providerId, kind)
            } finally {
                statuses.finish(providerId)
            }
        }
    }

    suspend fun run(providerId: Long, kind: LibrarySyncKind = LibrarySyncKind.FULL) {
        val row = database.providerDao().selectById(providerId) ?: return
        val provider = clients.providerFor(providerId) ?: run {
            logger.warn(TAG, "library sync skipped: provider $providerId is not loadable")
            publishFailure(providerId, "provider is not loadable")
            return
        }
        val syncer = SubsonicLibrarySyncer(
            database = database,
            provider = provider,
            providerId = providerId,
            traits = traitsFor(row.api),
            runGate = runGate,
            onAuthFailed = onAuthFailed,
            onAuthSucceeded = onAuthSucceeded,
            onProgress = statuses::publish,
        )
        try {
            val outcome = when (kind) {
                LibrarySyncKind.FULL -> syncer.runFullSync()
                LibrarySyncKind.DIFF -> syncer.runDiffSync()
            }
            logger.info(
                TAG,
                "library sync (${kind.name.lowercase()}) for provider $providerId: " +
                    "${outcome.message} (${outcome.albumsProcessed} albums)",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn(TAG, "library sync for provider $providerId failed", failure)
        }
    }

    override suspend fun providers(): List<LibrarySyncProvider> =
        database.providerDao().selectAll().map { row ->
            val status = database.syncStatusDao().selectByProvider(row.id)
            LibrarySyncProvider(
                providerId = row.id,
                name = row.name,
                supportsLibrarySync = traitsFor(row.api).hasLibrarySync,
                lastFullSyncAt = status?.lastFullSyncAt,
                lastDiffSyncAt = status?.lastDiffSyncAt,
                lastResult = when (status?.lastSyncResult) {
                    SubsonicLibrarySyncer.RESULT_OK -> LibrarySyncLastResult.COMPLETED
                    SubsonicLibrarySyncer.RESULT_FAILED -> LibrarySyncLastResult.FAILED
                    else -> LibrarySyncLastResult.NEVER
                },
                lastMessage = status?.lastSyncMessage,
                lastCount = status?.lastSyncCount ?: 0,
                lastDurationMs = status?.lastSyncDurationMs,
            )
        }

    /** Launches the resume check off the caller's thread (app start). */
    fun resumeIncomplete() {
        scope.launch { resumeIncompleteNow() }
    }

    /**
     * Restarts every provider whose initial walk never completed (D-16). The
     * trigger is "no full sync recorded", which covers force-stop mid-walk;
     * running walks are skipped by the [statuses] run gate.
     */
    suspend fun resumeIncompleteNow() {
        database.providerDao().selectAll().forEach { row ->
            val status = database.syncStatusDao().selectByProvider(row.id)
            if (LibrarySyncResumePolicy.shouldResume(status, traitsFor(row.api).hasLibrarySync)) {
                logger.info(TAG, "resuming the incomplete initial sync for provider ${row.id}")
                start(row.id)
            }
        }
    }

    private fun publishFailure(providerId: Long, message: String) {
        val started = nowMs()
        statuses.publish(
            LibrarySyncProgress(
                providerId = providerId,
                phase = LibrarySyncPhase.FAILED,
                startedAt = started,
                finishedAt = started,
                outcome = LibrarySyncOutcome.FAILED,
                message = message,
            ),
        )
    }

    private companion object {
        const val TAG = "LibrarySync"
    }
}
