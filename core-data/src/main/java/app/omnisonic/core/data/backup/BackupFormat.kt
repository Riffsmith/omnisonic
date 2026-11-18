package app.omnisonic.core.data.backup

/**
 * Versioned backup container layout. The ZIP holds the manifest entry first
 * (a restore validates it before reading anything else), then JSON entries:
 * per-provider user data and event log, the settings snapshot and an optional
 * style profile. Restore (Phase 6 task 7) rejects unknown [FORMAT_VERSION]
 * values before writing anything.
 */
object BackupFormat {

    const val FORMAT_VERSION = 1

    const val MANIFEST_ENTRY = "omnisonic-backup.json"
    const val SETTINGS_ENTRY = "settings.json"
    const val STYLE_ENTRY = "style.json"

    fun userDataEntry(providerId: Long): String = "providers/$providerId/user-data.json"

    fun eventsEntry(providerId: Long): String = "providers/$providerId/events.json"
}
