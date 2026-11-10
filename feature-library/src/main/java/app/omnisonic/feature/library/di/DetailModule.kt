package app.omnisonic.feature.library.di

import app.omnisonic.feature.library.detail.DetailSource
import app.omnisonic.feature.library.detail.RepositoryDetailSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DetailModule {

    @Binds
    @Singleton
    abstract fun detailSource(impl: RepositoryDetailSource): DetailSource
}
