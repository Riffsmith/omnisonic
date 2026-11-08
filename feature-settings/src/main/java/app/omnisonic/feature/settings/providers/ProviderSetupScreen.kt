package app.omnisonic.feature.settings.providers

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.providers.ProviderSetupIssue
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * First-connection destination (D-12, UI_SPEC.md §3.2). There is no skip in
 * v1: setup completes before home. Reached later from settings as "add another
 * provider" with the same form and no flag semantics (completion is idempotent).
 */
@Composable
fun ProviderSetupRoute(
    viewModel: ProviderSetupViewModel,
    onComplete: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is ProviderSetupEvent.Completed) onComplete()
        }
    }
    ProviderSetupScreen(
        state = state,
        onServerUrlChange = viewModel::onServerUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSecondaryUrlChange = viewModel::onSecondaryUrlChange,
        onUseSecondaryChange = viewModel::onUseSecondaryChange,
        onTestConnection = viewModel::testConnection,
        onSave = viewModel::save,
        onCancel = onCancel,
    )
}

@Composable
fun ProviderSetupScreen(
    state: ProviderSetupUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSecondaryUrlChange: (String) -> Unit,
    onUseSecondaryChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    val idle = state.busy == ProviderSetupBusy.NONE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.provider_setup_back),
                    )
                }
            }
            Text(
                text = stringResource(R.string.provider_setup_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            text = stringResource(R.string.provider_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionField(
            value = state.serverUrl,
            onValueChange = onServerUrlChange,
            label = stringResource(R.string.provider_setup_server_url),
            error = issueMessage(state.issues, ProviderSetupIssue.SERVER_URL_REQUIRED, ProviderSetupIssue.SERVER_URL_INVALID),
            keyboardType = KeyboardType.Uri,
            enabled = idle,
        )
        if (state.cleartextWarning) {
            Text(
                text = stringResource(R.string.provider_setup_cleartext_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        ConnectionField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = stringResource(R.string.provider_setup_username),
            error = issueMessage(state.issues, ProviderSetupIssue.USERNAME_REQUIRED),
            keyboardType = KeyboardType.Text,
            enabled = idle,
        )
        ConnectionField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.provider_setup_password),
            error = issueMessage(state.issues, ProviderSetupIssue.PASSWORD_REQUIRED),
            keyboardType = KeyboardType.Password,
            password = true,
            enabled = idle,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.useSecondary,
                    enabled = idle,
                    role = Role.Checkbox,
                    onValueChange = onUseSecondaryChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.useSecondary, onCheckedChange = null, enabled = idle)
            Text(
                text = stringResource(R.string.provider_setup_secondary_toggle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = spacing.xs),
            )
        }
        if (state.useSecondary) {
            ConnectionField(
                value = state.secondaryUrl,
                onValueChange = onSecondaryUrlChange,
                label = stringResource(R.string.provider_setup_secondary_url),
                error = issueMessage(state.issues, ProviderSetupIssue.SECONDARY_URL_INVALID),
                keyboardType = KeyboardType.Uri,
                enabled = idle,
            )
        }

        ConnectionStatusRow(state = state)

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedButton(onClick = onTestConnection, enabled = idle) {
                Text(stringResource(R.string.provider_setup_test))
            }
            Button(onClick = onSave, enabled = idle) {
                Text(
                    stringResource(
                        if (state.busy == ProviderSetupBusy.SAVING) {
                            R.string.provider_setup_saving
                        } else {
                            R.string.provider_setup_save
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ConnectionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType,
    password: Boolean = false,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
    )
}

@Composable
private fun ConnectionStatusRow(state: ProviderSetupUiState) {
    val spacing = OmnisonicTheme.tokens.spacing
    when (state.busy) {
        ProviderSetupBusy.TESTING -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(spacing.md))
            Text(stringResource(R.string.provider_setup_testing), style = MaterialTheme.typography.bodyMedium)
        }

        ProviderSetupBusy.SAVING -> Text(
            text = stringResource(R.string.provider_setup_saving),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ProviderSetupBusy.NONE -> statusMessage(state.connectionStatus)?.let { message ->
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private data class StatusMessage(val text: String, val ok: Boolean)

@Composable
private fun statusMessage(status: ProviderConnectionStatus): StatusMessage? = when (status) {
    ProviderConnectionStatus.NONE -> null
    ProviderConnectionStatus.OK -> StatusMessage(stringResource(R.string.provider_setup_test_ok), ok = true)
    ProviderConnectionStatus.NETWORK_ERROR ->
        StatusMessage(stringResource(R.string.provider_setup_test_failed_network), ok = false)
    ProviderConnectionStatus.AUTH_ERROR ->
        StatusMessage(stringResource(R.string.provider_setup_test_failed_auth), ok = false)
    ProviderConnectionStatus.SERVER_ERROR ->
        StatusMessage(stringResource(R.string.provider_setup_test_failed_server), ok = false)
    ProviderConnectionStatus.UNSUPPORTED ->
        StatusMessage(stringResource(R.string.provider_setup_test_failed_unsupported), ok = false)
    ProviderConnectionStatus.UNEXPECTED, ProviderConnectionStatus.INVALID_INPUT ->
        StatusMessage(stringResource(R.string.provider_setup_test_failed_unexpected), ok = false)
}

@Composable
private fun issueMessage(issues: Set<ProviderSetupIssue>, vararg candidates: ProviderSetupIssue): String? =
    candidates.firstOrNull { it in issues }?.let { stringResource(it.message()) }

@StringRes
private fun ProviderSetupIssue.message(): Int = when (this) {
    ProviderSetupIssue.SERVER_URL_REQUIRED -> R.string.provider_setup_error_url_required
    ProviderSetupIssue.SERVER_URL_INVALID -> R.string.provider_setup_error_url_invalid
    ProviderSetupIssue.SECONDARY_URL_INVALID -> R.string.provider_setup_error_secondary_invalid
    ProviderSetupIssue.USERNAME_REQUIRED -> R.string.provider_setup_error_username
    ProviderSetupIssue.PASSWORD_REQUIRED -> R.string.provider_setup_error_password
}
