package app.omnisonic.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit upgrade steps only. The pre-release database started at one clean
 * v1 baseline (FIX-15 / D-11 option B); the approved additive steps are the
 * offline read model (Phase 7 task 5a, D-43) and the automatic-rule table
 * (task 7a, D-47). The migration harness seeds each exported baseline and
 * validates the result against the newest export. Destructive fallback is
 * intentionally absent: an unknown schema must fail loudly instead of dropping
 * user data.
 */
object Migrations {

    /**
     * Additive `offline_media` read model: `tempCache` (rolling intent, v1 rows
     * stay permanent), a nullable `externalId` with a `(providerId, externalId)`
     * lookup index, and a tolerant backfill from the stream `sourceKey`. Rows
     * whose key does not parse keep NULL — no path, source id or row is ever
     * rewritten or deleted by this step.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE offline_media ADD COLUMN tempCache INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE offline_media ADD COLUMN externalId TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_offline_media_providerId_externalId` " +
                    "ON `offline_media` (`providerId`, `externalId`)",
            )
            backfillExternalIds(db)
        }
    }

    /**
     * Additive provider-owned rule table (Phase 7 task 7a, D-47): no existing
     * table or row is touched. The unique `(providerId, kind, targetExternalId)`
     * index is the deterministic duplicate rule; FAVORITES uses the empty-string
     * target sentinel because SQLite UNIQUE treats NULLs as distinct.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_rules` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`providerId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                    "`targetExternalId` TEXT NOT NULL, `transcodeBitrateKbps` INTEGER, " +
                    "`format` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_download_rules_providerId_kind_targetExternalId` " +
                    "ON `download_rules` (`providerId`, `kind`, `targetExternalId`)",
            )
        }
    }

    val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)

    private fun backfillExternalIds(db: SupportSQLiteDatabase) {
        val updates = mutableListOf<Pair<String, Long>>()
        db.query("SELECT id, sourceKey FROM offline_media").use { cursor ->
            val idIndex = cursor.getColumnIndex("id")
            val keyIndex = cursor.getColumnIndex("sourceKey")
            while (cursor.moveToNext()) {
                val externalId = DownloadKeys.parseMediaKey(cursor.getString(keyIndex))?.externalId ?: continue
                updates += externalId to cursor.getLong(idIndex)
            }
        }
        updates.forEach { (externalId, id) ->
            db.execSQL("UPDATE offline_media SET externalId = ? WHERE id = ?", arrayOf<Any>(externalId, id))
        }
    }
}
