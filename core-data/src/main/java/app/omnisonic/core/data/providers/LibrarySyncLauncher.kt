package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.sync.SubsonicLibrarySyncer
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.AllowAllRunGate
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderTraits
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starts a provider's first library walk without blocking the screen that
 * saved it. Kept as a seam so setup tests can assert the trigger without a
 * network walk.
 */
fun interface InitialLibrarySync {
    fun start(providerId: Long)
}

/**
 * Long-lived trigger over the existing library walker. The walk reuses the
 * registry-cached provider (and its client), consults the status tracker's run
 * gate, and reports auth outcomes so the provider row's status stays accurate.
 * A walk failure is logged, never thrown: setup has already completed.
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
) : InitialLibrarySync {

    override fun start(providerId: Long) {
        scope.launch { run(providerId) }
    }

    suspend fun run(providerId: Long) {
        val row = database.providerDao().selectById(providerId) ?: return
        val provider = clients.providerFor(providerId) ?: run {
            logger.warn(TAG, "initial sync skipped: provider $providerId is not loadable")
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
        )
        try {
            val outcome = syncer.runFullSync()
            logger.info(
                TAG,
                "initial sync for provider $providerId: ${outcome.message} (${outcome.albumsProcessed} albums)",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn(TAG, "initial sync for provider $providerId failed", failure)
        }
    }

    private companion object {
        const val TAG = "LibrarySync"
    }
}
