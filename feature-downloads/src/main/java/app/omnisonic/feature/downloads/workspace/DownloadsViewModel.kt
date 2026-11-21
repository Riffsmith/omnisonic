package app.omnisonic.feature.downloads.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.DownloadDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Downloads workspace state and commands (task 9a/9b). Reads are rebuilt for
 * the current hidden-provider scope, so hiding a provider removes its rows from
 * this surface without cancelling or pruning anything — internal rule workers
 * keep their own unrestricted reads. Per-row commands are single-flight, and
 * global commands have their own gate; a double tap cannot issue two cancels,
 * retries or resignations of the same row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val port: DownloadsPort,
) : ViewModel() {

    private data class Reads(
        val queue: List<DownloadItemEntity>,
        val offline: List<OfflineFileEntity>,
        val rules: List<DownloadRuleEntity>,
    )

    private data class Chrome(
        val providers: List<ProviderIdentityRow>,
        val hidden: Set<Long>,
        val globallyPaused: Boolean,
        val preferredDestination: DownloadDestination,
    )

    private data class Extras(
        val tab: DownloadsTab = DownloadsTab.QUEUE,
        val busyRowIds: Set<Long> = emptySet(),
        val busyGlobal: Boolean = false,
        val notice: DownloadsNotice? = null,
        val candidates: DownloadsRuleCandidatesState = DownloadsRuleCandidatesState(),
    )

    private val extras = MutableStateFlow(Extras())
    private val retrySignal = MutableStateFlow(0)
    private val loadFailed = MutableStateFlow(false)
    private val inFlightRows = mutableSetOf<Long>()
    private val globalInFlight = AtomicBoolean(false)

    private val scopeFlow = port.hiddenProviderIds
        .map { ProfileScope(it.toList()) }
        .distinctUntilChanged()

    private val reads = combine(scopeFlow, retrySignal) { scope, _ -> scope }.flatMapLatest { scope ->
        combine(port.queue(scope), port.offline(scope), port.rules(scope)) { queue, offline, rules ->
            Reads(queue, offline, rules)
        }.catch {
            loadFailed.value = true
            emit(Reads(emptyList(), emptyList(), emptyList()))
        }
    }

    private val chrome = combine(
        port.providers,
        port.hiddenProviderIds,
        port.globallyPaused,
        port.preferredDestination,
    ) { providers, hidden, paused, preferred ->
        Chrome(providers, hidden, paused, preferred)
    }.catch {
        loadFailed.value = true
        emit(Chrome(emptyList(), emptySet(), false, DownloadDestination.OFFLINE))
    }

    val state: StateFlow<DownloadsUiState> = combine(
        reads,
        chrome,
        extras,
        loadFailed,
    ) { reads, chrome, extras, failed ->
        buildState(reads, chrome, extras).copy(failed = failed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsUiState())

    fun selectTab(tab: DownloadsTab) {
        extras.update { it.copy(tab = tab) }
    }

    /** Re-subscribes the scoped reads after a terminal flow failure. */
    fun reload() {
        loadFailed.value = false
        retrySignal.update { it + 1 }
    }

    fun pauseAll() = globalAction { port.pauseAll() }

    fun resumeAll() = globalAction { port.resumeAll() }

    fun retryAll() = globalAction { port.retryAll() }

    fun pauseItem(rowId: Long) = rowAction(rowId) { port.pauseItem(rowId) }

    fun resumeItem(rowId: Long) = rowAction(rowId) { port.resumeItem(rowId) }

    fun retryItem(rowId: Long) = rowAction(rowId) { port.retry(rowId) }

    fun cancelItem(rowId: Long) = rowAction(rowId) { port.cancel(rowId) }

    fun deleteOffline(offlineId: Long) = rowAction(offlineId) {
        postNotice(
            if (port.deleteOffline(offlineId)) {
                DownloadsNotice.OFFLINE_DELETED
            } else {
                DownloadsNotice.OFFLINE_DELETE_FAILED
            },
        )
    }

    fun setOfflinePermanent(offlineId: Long, permanent: Boolean) = rowAction(offlineId) {
        if (!port.setOfflinePermanent(offlineId, permanent)) {
            postNotice(DownloadsNotice.OWNERSHIP_FAILED)
        }
    }

    fun addRule(providerId: Long, kind: AutomaticRuleKind, targetExternalId: String) = globalAction {
        postNotice(
            if (port.addRule(providerId, kind, targetExternalId)) {
                DownloadsNotice.RULE_ADDED
            } else {
                DownloadsNotice.RULE_FAILED
            },
        )
    }

    fun removeRule(ruleId: Long) = rowAction(ruleId) {
        postNotice(
            if (port.removeRule(ruleId)) {
                DownloadsNotice.RULE_REMOVED
            } else {
                DownloadsNotice.RULE_FAILED
            },
        )
    }

    fun setPreferredDestination(destination: DownloadDestination) {
        viewModelScope.launch {
            try {
                port.setPreferredDestination(destination)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A preference write failure leaves the previous choice authoritative.
            }
        }
    }

    fun dismissNotice() {
        extras.update { it.copy(notice = null) }
    }

    fun loadRuleCandidates() {
        if (extras.value.candidates.loading) return
        extras.update { it.copy(candidates = DownloadsRuleCandidatesState(loading = true)) }
        viewModelScope.launch {
            try {
                val hidden = port.hiddenProviderIds.first()
                val candidates = port.ruleCandidates(ProfileScope(hidden.toList()))
                extras.update {
                    it.copy(candidates = DownloadsRuleCandidatesState(candidates = candidates))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                extras.update {
                    it.copy(candidates = DownloadsRuleCandidatesState(failed = true))
                }
            }
        }
    }

    private fun rowAction(rowId: Long, block: suspend () -> Unit) {
        if (!beginRow(rowId)) return
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Every command is a conditional DB/runtime write; a failure
                // leaves the row visible with its current persisted state.
            } finally {
                endRow(rowId)
            }
        }
    }

    private fun globalAction(block: suspend () -> Unit) {
        if (!globalInFlight.compareAndSet(false, true)) return
        extras.update { it.copy(busyGlobal = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // See rowAction: the persisted gate/row state is the source of truth.
            } finally {
                globalInFlight.set(false)
                extras.update { it.copy(busyGlobal = false) }
            }
        }
    }

    private fun beginRow(rowId: Long): Boolean = synchronized(inFlightRows) {
        if (!inFlightRows.add(rowId)) {
            false
        } else {
            extras.update { it.copy(busyRowIds = inFlightRows.toSet()) }
            true
        }
    }

    private fun endRow(rowId: Long) = synchronized(inFlightRows) {
        inFlightRows.remove(rowId)
        extras.update { it.copy(busyRowIds = inFlightRows.toSet()) }
    }

    private fun postNotice(notice: DownloadsNotice) {
        extras.update { it.copy(notice = notice) }
    }

    private fun buildState(reads: Reads, chrome: Chrome, extras: Extras): DownloadsUiState {
        val names = chrome.providers
            .filter { it.id !in chrome.hidden }
            .associate { it.id to it.name }
        val candidates = extras.candidates.candidates
        return DownloadsUiState(
            loading = false,
            failed = false,
            tab = extras.tab,
            queue = reads.queue.map { it.toQueueRow(names[it.providerId]) },
            offline = reads.offline
                .sortedWith(
                    compareByDescending<OfflineFileEntity> { it.downloadedAt ?: 0L }
                        .thenByDescending { it.id },
                )
                .map { it.toOfflineRow(names[it.providerId]) },
            rules = reads.rules.mapNotNull { it.toRuleRow(names[it.providerId], candidates) },
            providerNames = names,
            globallyPaused = chrome.globallyPaused,
            preferredDestination = chrome.preferredDestination,
            busyRowIds = extras.busyRowIds,
            busyGlobal = extras.busyGlobal,
            notice = extras.notice,
            candidates = extras.candidates,
        )
    }

    private fun DownloadItemEntity.toQueueRow(providerName: String?): DownloadQueueRowUi =
        DownloadQueueRowUi(
            id = id,
            title = title,
            providerName = providerName,
            rawStatus = status,
            status = DownloadStatus.entries.firstOrNull { it.name == status },
            progressPercent = progressPercent,
            errorCount = errorCount,
            lastError = lastError,
            actions = DownloadQueueRowRules.actionsFor(status),
        )

    private fun OfflineFileEntity.toOfflineRow(providerName: String?): DownloadOfflineRowUi =
        DownloadOfflineRowUi(
            id = id,
            title = title,
            providerName = providerName,
            permanent = !tempCache,
            automatic = autoRule != null,
            sizeBytes = sizeBytes,
            downloadedAtMs = downloadedAt,
            bitrateKbps = bitrateKbps,
        )

    private fun DownloadRuleEntity.toRuleRow(
        providerName: String?,
        candidates: DownloadRuleCandidatesUi?,
    ): DownloadRuleRowUi? {
        val ruleKind = AutomaticRuleKind.entries.firstOrNull { it.name == kind } ?: return null
        val label = when (ruleKind) {
            AutomaticRuleKind.PLAYLIST -> candidates?.playlists
                ?.firstOrNull { it.providerId == providerId && it.externalId == targetExternalId }
                ?.title
            AutomaticRuleKind.ALBUM -> candidates?.albums
                ?.firstOrNull { it.providerId == providerId && it.externalId == targetExternalId }
                ?.title
            AutomaticRuleKind.FAVORITES -> null
        }
        return DownloadRuleRowUi(
            id = id,
            providerId = providerId,
            providerName = providerName,
            kind = ruleKind,
            targetExternalId = targetExternalId,
            targetLabel = label,
        )
    }
}
