package app.omnisonic.feature.library.di

import app.omnisonic.feature.library.history.HistoryControls
import app.omnisonic.feature.library.history.HistorySource
import app.omnisonic.feature.library.history.RepositoryHistoryControls
import app.omnisonic.feature.library.history.RepositoryHistorySource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HistoryModule {

    @Binds
    @Singleton
    abstract fun bindHistorySource(impl: RepositoryHistorySource): HistorySource

    @Binds
    @Singleton
    abstract fun bindHistoryControls(impl: RepositoryHistoryControls): HistoryControls
}
