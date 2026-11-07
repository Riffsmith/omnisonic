package app.omnisonic.core.data.db

import androidx.room.migration.Migration

/**
 * Explicit upgrade steps only. The pre-release database starts at one clean
 * v1 baseline (FIX-15 / D-11 option B), so there is nothing to upgrade today.
 * A future schema change appends one `Migration(1, 2)` here and exports
 * `2.json`; the migration harness seeds the released `1.json` and validates
 * the result against the new export. Destructive fallback is intentionally
 * absent: an unknown schema must fail loudly instead of dropping user data.
 */
object Migrations {

    val ALL: List<Migration> = emptyList()
}
