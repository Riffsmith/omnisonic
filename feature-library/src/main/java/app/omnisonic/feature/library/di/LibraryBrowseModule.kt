package app.omnisonic.feature.library.di

import app.omnisonic.feature.library.browse.LibraryBrowseSource
import app.omnisonic.feature.library.browse.RepositoryBrowseSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryBrowseModule {

    @Binds
    @Singleton
    abstract fun bindLibraryBrowseSource(impl: RepositoryBrowseSource): LibraryBrowseSource
}
