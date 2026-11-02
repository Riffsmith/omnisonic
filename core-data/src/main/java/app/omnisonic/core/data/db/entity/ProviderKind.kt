package app.omnisonic.core.data.db.entity

/**
 * Stored by name in providers.kind. Unknown or null names fall back to
 * REMOTE_SERVER, matching the column default applied to pre-existing rows.
 */
enum class ProviderKind {
    REMOTE_SERVER,
    DEVICE,
    VIRTUAL;

    companion object {
        fun fromName(name: String?): ProviderKind =
            entries.firstOrNull { it.name == name } ?: REMOTE_SERVER
    }
}
