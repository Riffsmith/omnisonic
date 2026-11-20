package app.omnisonic.feature.downloads.service

import android.content.Context
import app.omnisonic.core.model.log.OmnisonicLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the download service after a committed queue mutation. A background
 * start denial leaves rows `PENDING` (never a crash loop); the next foreground
 * trigger or connectivity change starts the same work.
 */
@Singleton
class DownloadServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: OmnisonicLogger,
) : DownloadWorkStarter {

    override fun start() {
        try {
            context.startForegroundService(DownloadService.startIntent(context))
        } catch (denied: IllegalStateException) {
            logger.warn(TAG, "foreground download start denied; work stays pending", denied)
        } catch (denied: SecurityException) {
            logger.warn(TAG, "foreground download start not permitted; work stays pending", denied)
        }
    }

    private companion object {
        const val TAG = "DownloadStarter"
    }
}
