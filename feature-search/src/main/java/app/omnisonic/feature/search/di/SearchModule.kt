package app.omnisonic.feature.search.di

import app.omnisonic.feature.search.search.RepositorySearchSource
import app.omnisonic.feature.search.search.SearchSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {

    @Binds
    @Singleton
    abstract fun searchSource(impl: RepositorySearchSource): SearchSource
}
