package app.omnisonic.core.data.db

import androidx.room.migration.Migration

object Migrations {

    /**
     * Explicit upgrade steps only: v1 (pre-release) -> v2 (T16) -> v3 (queue
     * resume position). Destructive fallback is intentionally absent; an
     * unknown schema must fail loudly instead of dropping user data.
     */
    val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
}
