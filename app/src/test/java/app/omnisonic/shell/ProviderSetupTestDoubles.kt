package app.omnisonic.shell

import app.omnisonic.core.data.providers.ProviderSetupService
import app.omnisonic.feature.settings.providers.ProviderSetupViewModel
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import io.mockk.mockk
import java.io.File

/**
 * Shell tests only need a real setup view model because the nav host holds one;
 * the connection work itself is exercised in `:feature-settings`. Each call gets
 * its own preference file so parallel tests cannot share onboarding state.
 */
internal fun testProviderSetupViewModel(
    service: ProviderSetupService = mockk(relaxed = true),
    shellPreferences: ShellPreferencesStore = ShellPreferencesStore.createAt(
        File("/tmp/opencode", "provider-setup-${System.nanoTime()}.preferences_pb"),
    ),
): ProviderSetupViewModel = ProviderSetupViewModel(
    service = service,
    shellPreferences = shellPreferences,
)
