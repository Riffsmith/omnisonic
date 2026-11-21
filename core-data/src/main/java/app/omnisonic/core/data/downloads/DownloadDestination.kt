package app.omnisonic.core.data.downloads

/**
 * The two destinations a user download can target (`.agents/plans/phase-7-tasks.md`
 * §Task 9): app-private offline media or a public `MediaStore.Downloads` export.
 * The last explicit choice is persisted by NAME so the Settings/library primary
 * action keeps meaning the same thing across restarts.
 */
enum class DownloadDestination {

    OFFLINE,

    /** Public export-only publication; no offline row is written (D-46). */
    EXPORT;

    /** Non-null `exportTarget` value for an export row; offline rows store null. */
    fun exportTarget(): String? = when (this) {
        OFFLINE -> null
        EXPORT -> MEDIASTORE_DOWNLOADS
    }

    companion object {

        /** The one public collection the export publisher understands. */
        const val MEDIASTORE_DOWNLOADS = "MEDIASTORE_DOWNLOADS"

        fun fromStored(raw: String?): DownloadDestination =
            entries.firstOrNull { it.name == raw } ?: OFFLINE
    }
}
