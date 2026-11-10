package app.omnisonic.core.data.sync

import app.omnisonic.core.data.db.entity.SyncStatusEntity

/**
 * Resume-on-launch decision (task 4b / D-16). A full sync that never completed
 * leaves `lastFullSyncAt` null — force-stopping mid-walk looks identical to a
 * walk that never started, and retrying is safe because the walk merges rather
 * than duplicates. Providers without library sync are never resumed, and the
 * in-process run gate in [LibrarySyncStatusRepository] keeps a live walk from
 * being restarted.
 */
object LibrarySyncResumePolicy {

    fun shouldResume(status: SyncStatusEntity?, hasLibrarySync: Boolean): Boolean =
        hasLibrarySync && status?.lastFullSyncAt == null
}
