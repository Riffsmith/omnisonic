package app.omnisonic.feature.library.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.AutomaticRuleRepository
import app.omnisonic.core.data.downloads.DownloadDestination
import app.omnisonic.core.data.downloads.LibraryDownloadActions
import app.omnisonic.core.data.downloads.LibraryDownloadResult
import app.omnisonic.core.data.downloads.LibraryRuleToggle
import app.omnisonic.core.data.search.ProviderVisibilityStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryDownloadNotice {
    QUEUED,
    PARTIAL,
    BLOCKED,
    UNSUPPORTED,
    DESTINATION_CONFLICT,
    EMPTY,
    RULE_ENABLED,
    RULE_DISABLED,
    RULE_FAILED,
}

data class LibraryDownloadUiState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val preferredDestination: DownloadDestination = DownloadDestination.OFFLINE,
    val ruleEnabled: Boolean = false,
    val ruleSupported: Boolean = false,
    val notice: LibraryDownloadNotice? = null,
)

/**
 * One album/playlist detail's download entry points (task 9c). The actions are
 * core-data's [LibraryDownloadActions], so enqueueing follows the same
 * coordinator, gates and strict idempotence as every other download. Binding a
 * header refreshes the automatic-rule state under the real UI scope; toggling a
 * rule is a separate command and never doubles as an enqueue.
 */
@HiltViewModel
class LibraryDownloadActionsViewModel @Inject constructor(
    private val actions: LibraryDownloadActions,
    private val rules: AutomaticRuleRepository,
    private val visibility: ProviderVisibilityStore,
) : ViewModel() {

    private data class Target(
        val kind: AutomaticRuleKind,
        val providerId: Long,
        val externalId: String?,
        val albumId: Long? = null,
        val playlistId: Long? = null,
    )

    private val _state = MutableStateFlow(LibraryDownloadUiState())
    val state: StateFlow<LibraryDownloadUiState> = _state.asStateFlow()

    private var target: Target? = null
    private val ruleInFlight = AtomicBoolean(false)
    private val downloadInFlight = AtomicBoolean(false)

    fun bindAlbum(providerId: Long, externalId: String?, albumId: Long) {
        bind(Target(AutomaticRuleKind.ALBUM, providerId, externalId, albumId = albumId))
    }

    fun bindPlaylist(providerId: Long, externalId: String?, playlistId: Long) {
        bind(Target(AutomaticRuleKind.PLAYLIST, providerId, externalId, playlistId = playlistId))
    }

    fun downloadPreferred() {
        val current = target ?: return
        viewModelScope.launch {
            runDownload(null) { destination ->
                when (current.kind) {
                    AutomaticRuleKind.ALBUM ->
                        actions.enqueueAlbum(current.albumId!!, destination)
                    AutomaticRuleKind.PLAYLIST ->
                        actions.enqueuePlaylist(current.playlistId!!, destination)
                    AutomaticRuleKind.FAVORITES -> LibraryDownloadResult()
                }
            }
        }
    }

    fun download(destination: DownloadDestination) {
        val current = target ?: return
        viewModelScope.launch {
            runDownload(destination) {
                when (current.kind) {
                    AutomaticRuleKind.ALBUM -> actions.enqueueAlbum(current.albumId!!, destination)
                    AutomaticRuleKind.PLAYLIST ->
                        actions.enqueuePlaylist(current.playlistId!!, destination)
                    AutomaticRuleKind.FAVORITES -> LibraryDownloadResult()
                }
            }
        }
    }

    fun enqueueSong(songId: Long, destination: DownloadDestination) {
        viewModelScope.launch {
            try {
                val result = actions.enqueueSong(songId, destination)
                _state.update { it.copy(notice = result.notice(), preferredDestination = destination) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(notice = LibraryDownloadNotice.EMPTY) }
            }
        }
    }

    fun toggleRule() {
        val current = target ?: return
        val externalId = current.externalId?.takeIf { it.isNotBlank() }
        if (externalId == null || !ruleInFlight.compareAndSet(false, true)) {
            _state.update { it.copy(notice = LibraryDownloadNotice.RULE_FAILED) }
            return
        }
        viewModelScope.launch {
            try {
                val outcome = when (current.kind) {
                    AutomaticRuleKind.ALBUM ->
                        actions.toggleAlbumRule(current.providerId, externalId)
                    AutomaticRuleKind.PLAYLIST ->
                        actions.togglePlaylistRule(current.providerId, externalId)
                    AutomaticRuleKind.FAVORITES -> LibraryRuleToggle.INVALID
                }
                _state.update {
                    when (outcome) {
                        LibraryRuleToggle.ADDED -> it.copy(
                            ruleEnabled = true,
                            notice = LibraryDownloadNotice.RULE_ENABLED,
                        )
                        LibraryRuleToggle.REMOVED -> it.copy(
                            ruleEnabled = false,
                            notice = LibraryDownloadNotice.RULE_DISABLED,
                        )
                        LibraryRuleToggle.INVALID -> it.copy(
                            notice = LibraryDownloadNotice.RULE_FAILED,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(notice = LibraryDownloadNotice.RULE_FAILED) }
            } finally {
                ruleInFlight.set(false)
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun bind(next: Target) {
        target = next
        _state.update {
            it.copy(
                ready = true,
                ruleSupported = next.externalId?.isNotBlank() == true,
                ruleEnabled = false,
            )
        }
        viewModelScope.launch {
            val preferred = runCatching { actions.preferredDestination() }
                .getOrDefault(DownloadDestination.OFFLINE)
            val enabled = runCatching { isRuleEnabled(next) }.getOrDefault(false)
            _state.update {
                it.copy(
                    preferredDestination = preferred,
                    ruleEnabled = enabled,
                    ruleSupported = next.externalId?.isNotBlank() == true,
                )
            }
        }
    }

    private suspend fun isRuleEnabled(next: Target): Boolean {
        val externalId = next.externalId?.takeIf { it.isNotBlank() } ?: return false
        val hidden = visibility.hiddenProviderIds.first()
        return rules.list(ProfileScope(hidden.toList())).any {
            it.providerId == next.providerId &&
                it.kind == next.kind &&
                it.targetExternalId == externalId
        }
    }

    private suspend fun runDownload(
        explicit: DownloadDestination?,
        block: suspend (DownloadDestination) -> LibraryDownloadResult,
    ) {
        if (!downloadInFlight.compareAndSet(false, true)) return
        _state.update { it.copy(busy = true) }
        try {
            val destination = explicit ?: actions.preferredDestination()
            val result = block(destination)
            _state.update {
                it.copy(
                    notice = result.notice(),
                    preferredDestination = destination,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(notice = LibraryDownloadNotice.EMPTY) }
        } finally {
            downloadInFlight.set(false)
            _state.update { it.copy(busy = false) }
        }
    }

    private fun LibraryDownloadResult.notice(): LibraryDownloadNotice = when {
        requested == 0 -> LibraryDownloadNotice.EMPTY
        unsupported > 0 && queued == 0 -> LibraryDownloadNotice.UNSUPPORTED
        destinationConflicts > 0 && queued == 0 -> LibraryDownloadNotice.DESTINATION_CONFLICT
        blocked > 0 && queued == 0 -> LibraryDownloadNotice.BLOCKED
        failed > 0 -> LibraryDownloadNotice.PARTIAL
        else -> LibraryDownloadNotice.QUEUED
    }
}
