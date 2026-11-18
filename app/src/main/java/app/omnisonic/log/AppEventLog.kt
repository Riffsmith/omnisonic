package app.omnisonic.log

import app.omnisonic.core.model.log.OmnisonicLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shell-level events for the persistent runtime stream: screen transitions and
 * deliberate user actions. Both go through [OmnisonicLogger], so the file line
 * is scrubbed before it is written. Routes and action names only — never media
 * titles, search text or queue contents.
 */
@Singleton
class AppEventLog @Inject constructor(
    private val logger: OmnisonicLogger,
) {
    fun navigation(route: String) {
        logger.info(TAG, "navigation -> $route")
    }

    fun userAction(action: String) {
        logger.info(TAG, "user action: $action")
    }

    private companion object {
        const val TAG = "Shell"
    }
}
