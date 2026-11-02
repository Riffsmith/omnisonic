package app.omnisonic.core.data.db

import androidx.room.migration.Migration

object Migrations {

    /**
     * Exactly one explicit upgrade step: v1 (pre-release) -> v2 (T16).
     * Destructive fallback is intentionally absent; an unknown schema must
     * fail loudly instead of dropping user data.
     */
    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}
