package app.omnisonic.feature.settings.providers

import app.omnisonic.core.data.providers.ProviderSetupIssue
import app.omnisonic.core.data.providers.parseServerUrl

/** What the setup form is currently doing; controls disabled outside [NONE]. */
enum class ProviderSetupBusy { NONE, TESTING, SAVING }

/** Result of the last connection test, mapped for presentation. */
enum class ProviderConnectionStatus {
    NONE,
    OK,
    NETWORK_ERROR,
    AUTH_ERROR,
    SERVER_ERROR,
    UNSUPPORTED,
    UNEXPECTED,
    INVALID_INPUT,
}

data class ProviderSetupUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val secondaryUrl: String = "",
    val useSecondary: Boolean = false,
    val busy: ProviderSetupBusy = ProviderSetupBusy.NONE,
    val issues: Set<ProviderSetupIssue> = emptySet(),
    val connectionStatus: ProviderConnectionStatus = ProviderConnectionStatus.NONE,
) {
    /** Cleartext to a non-private host is allowed but flagged before saving. */
    val cleartextWarning: Boolean
        get() = parseServerUrl(serverUrl)?.let { it.isCleartext && !it.isPrivateHost } == true
}

sealed interface ProviderSetupEvent {
    data object Completed : ProviderSetupEvent
}
