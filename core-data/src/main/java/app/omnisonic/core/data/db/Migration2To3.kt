package app.omnisonic.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 -> v3: the queue snapshot stores the resume point of its current entry.
 * Null means "no restore point" (idle, stopped, or a track just started), so
 * existing rows keep their previous behaviour.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `queue_state` ADD COLUMN `positionMs` INTEGER")
    }
}
