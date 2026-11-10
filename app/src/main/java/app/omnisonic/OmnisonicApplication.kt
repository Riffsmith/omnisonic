package app.omnisonic

import android.app.Application
import android.content.Context
import app.omnisonic.core.data.providers.LibrarySyncLauncher
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class OmnisonicApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var imageLoader: Provider<ImageLoader>

    @Inject
    lateinit var librarySyncLauncher: Provider<LibrarySyncLauncher>

    override fun onCreate() {
        super.onCreate()
        librarySyncLauncher.get().resumeIncomplete()
    }

    override fun newImageLoader(context: Context): ImageLoader = imageLoader.get()
}
