package app.omnisonic.feature.settings.di

import android.content.Context
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ShellPreferencesModule {

    @Provides
    @Singleton
    fun shellPreferencesStore(@ApplicationContext context: Context): ShellPreferencesStore =
        ShellPreferencesStore.create(context)
}
