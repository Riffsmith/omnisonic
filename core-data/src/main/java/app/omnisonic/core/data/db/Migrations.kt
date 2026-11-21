package app.omnisonic.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit upgrade steps only. The pre-release database started at one clean
 * v1 baseline (FIX-15 / D-11 option B); the first approved schema change is the
 * additive offline read-model step below (Phase 7 task 5a, D-43). The migration
 * harness seeds the released `1.json` and validates the result against `2.json`.
 * Destructive fallback is intentionally absent: an unknown schema must fail
 * loudly instead of dropping user data.
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

    val ALL: List<Migration> = listOf(MIGRATION_1_2)

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
