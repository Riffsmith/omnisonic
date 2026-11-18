package app.omnisonic.core.data.backup

/**
 * Restore rejection reasons. Every subclass is thrown by
 * [BackupRestorer.readBackup] before the database is touched, so a rejected
 * backup never leaves partial writes behind.
 */
sealed class BackupRestoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MalformedBackup(message: String, cause: Throwable? = null) : BackupRestoreException(message, cause)

class MissingManifest : BackupRestoreException("backup has no ${BackupFormat.MANIFEST_ENTRY} entry")

class UnsupportedFormatVersion(val version: Int) : BackupRestoreException(
    "unsupported backup formatVersion $version (expected ${BackupFormat.FORMAT_VERSION})",
)

class MissingEntry(val entryName: String) : BackupRestoreException("backup entry missing: $entryName")

class MalformedEntry(val entryName: String, detail: String) :
    BackupRestoreException("backup entry $entryName is malformed: $detail")
