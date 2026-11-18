package app.omnisonic

import android.app.Application
import android.content.Context
import app.omnisonic.core.data.providers.LibrarySyncLauncher
import app.omnisonic.log.CrashLogInstaller
import app.omnisonic.playback.service.PlaybackSessionHost
import app.omnisonic.playback.startup.PlaybackLaunchRestore
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
    lateinit var crashLogInstaller: CrashLogInstaller

    @Inject
    lateinit var librarySyncLauncher: Provider<LibrarySyncLauncher>

    @Inject
    lateinit var playbackLaunchRestore: Provider<PlaybackLaunchRestore>

    @Inject
    lateinit var playbackSessionHost: Provider<PlaybackSessionHost>

    override fun onCreate() {
        super.onCreate()
        // First, so a crash during sync or playback restore still reaches crash.log.
        crashLogInstaller.install()
        librarySyncLauncher.get().resumeIncomplete()
        playbackLaunchRestore.get().restore()
        playbackSessionHost.get().connect()
    }

    override fun newImageLoader(context: Context): ImageLoader = imageLoader.get()
}
