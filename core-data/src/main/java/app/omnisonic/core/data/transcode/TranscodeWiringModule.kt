package app.omnisonic.core.data.transcode

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TranscodeWiringModule {
    @Provides
    @Singleton
    fun transcodeSettingsStore(@ApplicationContext context: Context): TranscodeSettingsStore =
        TranscodeSettingsStore.create(context)
}
