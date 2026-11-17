package app.omnisonic.feature.settings.ui

import android.content.Intent
import android.provider.Settings

/**
 * The app's only notification control surface: Android's own per-app
 * notification settings, where channel toggles and the app-level switch live.
 * The app deliberately offers no local switch, which would desync from it.
 */
object NotificationSettingsIntent {

    fun create(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
}
