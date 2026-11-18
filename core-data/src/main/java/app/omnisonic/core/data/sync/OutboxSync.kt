package app.omnisonic.core.data.sync

import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.AllowAllRunGate
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.OutboxDrainer
import app.omnisonic.provider.api.OutboxStore
import app.omnisonic.provider.api.ProviderRunGate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Coarse drain state the settings view and triggers observe. */
data class OutboxSyncState(
    val running: Boolean = false,
    val lastRunAt: Long? = null,
    val lastCompleted: Int = 0,
    val lastDeferred: Int = 0,
)

/** One drain outcome in core-data terms; `blocked` means the run gate refused it. */
data class OutboxSyncReport(
    val completed: Int,
    val progressed: Int,
    val deferred: Int,
    val blocked: Boolean,
)

/**
 * Trigger seam for the outbox drain. Writes request a drain without waiting for
 * it; the settings view watches [state] and can trigger a manual retry. The run
 * gate (provider status) still decides whether anything is sent.
 */
interface OutboxSyncControl {

    val state: StateFlow<OutboxSyncState>

    /** Coalescing fire-and-forget request; safe to call from any write path. */
    fun requestDrain()

    /** Clears the retry back-off and drains, so parked rows are retried at once. */
    fun retryNow()

    /** Runs one drain now; null when another drain already holds the runner. */
    suspend fun drainNow(): OutboxSyncReport?

    /** [drainNow] after clearing the retry back-off; null when a drain is running. */
    suspend fun retryAllNow(): OutboxSyncReport?
}

/**
 * Long-lived drain coordinator. Requests land in a conflated channel drained by
 * one coroutine, so a burst of writes produces one drain and a request that
 * arrives mid-drain produces exactly one follow-up. A manual retry clears
 * `nextAttemptAt` first; without that, parked rows would stay invisible to the
 * due query. Failures are logged, never thrown: a trigger is best-effort.
 */
class OutboxSync(
    store: OutboxStore,
    providerLocator: suspend (Long) -> MediaProvider?,
    private val pending: OutboxPendingRepository,
    private val scope: CoroutineScope,
    private val logger: OmnisonicLogger,
    runGate: ProviderRunGate = AllowAllRunGate,
    onAuthFailed: suspend (Long) -> Unit = {},
    onAuthSucceeded: suspend (Long) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : OutboxSyncControl {

    private val drainer = OutboxDrainer(
        store = store,
        providerLocator = providerLocator,
        clock = clock,
        runGate = runGate,
        logger = logger,
        onAuthFailed = onAuthFailed,
        onAuthSucceeded = onAuthSucceeded,
    )

    private val _state = MutableStateFlow(OutboxSyncState())
    override val state: StateFlow<OutboxSyncState> = _state.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val runner = Mutex()

    init {
        scope.launch {
            requests.consumeAsFlow().collect { drainNow() }
        }
    }

    override fun requestDrain() {
        requests.trySend(Unit)
    }

    override fun retryNow() {
        scope.launch { retryAllNow() }
    }

    override suspend fun drainNow(): OutboxSyncReport? {
        if (!runner.tryLock()) return null
        return try {
            _state.update { it.copy(running = true) }
            val report = drainer.drain()
            _state.update {
                it.copy(
                    lastRunAt = clock(),
                    lastCompleted = report.completed,
                    lastDeferred = report.deferred,
                )
            }
            OutboxSyncReport(
                completed = report.completed,
                progressed = report.progressed,
                deferred = report.deferred,
                blocked = report.blockedBy != null,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn(TAG, "outbox drain failed", failure)
            _state.update { it.copy(lastRunAt = clock()) }
            null
        } finally {
            _state.update { it.copy(running = false) }
            runner.unlock()
        }
    }

    override suspend fun retryAllNow(): OutboxSyncReport? {
        pending.clearBackoff()
        return drainNow() ?: run {
            requestDrain()
            null
        }
    }

    private companion object {
        const val TAG = "OutboxSync"
    }
}
