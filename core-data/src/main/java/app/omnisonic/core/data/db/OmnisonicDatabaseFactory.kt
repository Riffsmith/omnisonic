package app.omnisonic.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

class OmnisonicDatabaseFactory(private val context: Context) {

    fun open(name: String = OmnisonicDatabase.DATABASE_FILE): OmnisonicDatabase =
        fileBuilder(name).build()

    fun openInMemory(): OmnisonicDatabase =
        inMemoryBuilder().build()

    fun fileBuilder(name: String = OmnisonicDatabase.DATABASE_FILE): RoomDatabase.Builder<OmnisonicDatabase> =
        Room.databaseBuilder(context, OmnisonicDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*OmnisonicDatabase.migrationsForSteps())
            .addCallback(OpenCallbacks)

    fun inMemoryBuilder(): RoomDatabase.Builder<OmnisonicDatabase> =
        Room.inMemoryDatabaseBuilder(context, OmnisonicDatabase::class.java)
            .addMigrations(*OmnisonicDatabase.migrationsForSteps())
            .addCallback(OpenCallbacks)

    private object OpenCallbacks : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            ProviderCascadeTriggers.install(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA synchronous = NORMAL")
            ProviderCascadeTriggers.install(db)
        }
    }
}
