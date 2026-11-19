package app.omnisonic.core.data.history

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HistoryWiringModule {

    @Provides
    @Singleton
    fun historyPreferencesStore(@ApplicationContext context: Context): HistoryPreferencesStore =
        HistoryPreferencesStore.create(context)
}
