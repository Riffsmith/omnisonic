package app.omnisonic.downloads

import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ServiceInfo
import app.omnisonic.MainActivity
import app.omnisonic.feature.downloads.service.AndroidDownloadNotificationCenter
import app.omnisonic.feature.downloads.service.DownloadNotificationRules
import app.omnisonic.feature.downloads.service.DownloadRunBlock
import app.omnisonic.feature.downloads.service.DownloadService
import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadServiceManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun `merged debug and release manifests export the dataSync service`() {
        for (variant in listOf("debug", "release")) {
            val document = mergedManifest(variant)
            val service = serviceElement(document)
            assertThat(service).isNotNull()
            assertThat(service!!.getAttributeNS(androidNs, "exported")).isEqualTo("false")
            assertThat(service.getAttributeNS(androidNs, "foregroundServiceType"))
                .isEqualTo("dataSync")
        }
    }

    @Test
    fun `the merged manifest declares the foreground and wifi permissions`() {
        for (variant in listOf("debug", "release")) {
            val permissions = permissionElements(mergedManifest(variant))
            assertThat(permissions).contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
            assertThat(permissions).contains("android.permission.WAKE_LOCK")
            assertThat(permissions).contains("android.permission.ACCESS_WIFI_STATE")
            assertThat(permissions).contains("android.permission.ACCESS_NETWORK_STATE")
        }
    }

    @Test
    fun `the merged package exposes the service as non exported dataSync`() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getServiceInfo(
            ComponentName(app.packageName, DownloadService::class.java.name),
            0,
        )

        assertThat(info.exported).isFalse()
        assertThat(info.foregroundServiceType)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    @Test
    fun `the notification channel is low importance and command intents are immutable`() {
        val app = RuntimeEnvironment.getApplication()
        val center = AndroidDownloadNotificationCenter(app)
        center.ensureChannel()

        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AndroidDownloadNotificationCenter.CHANNEL_ID)
        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(channel.sound).isNull()

        val notification = center.build(
            DownloadNotificationRules.state(
                progressPercents = listOf(25, 75),
                pendingCount = 1,
                paused = false,
                blocked = null,
            ),
        )
        val actions = notification.actions.orEmpty()
        assertThat(actions.size).isEqualTo(2)
        for (action in actions) {
            val pending = shadowOf(action.actionIntent)
            assertThat(pending.isImmutable).isTrue()
            assertThat(pending.isService).isTrue()
            assertThat(pending.savedIntent.component?.className)
                .isEqualTo(DownloadService::class.java.name)
        }

        val content = shadowOf(notification.contentIntent)
        assertThat(content.isImmutable).isTrue()
        assertThat(content.isActivity).isTrue()
        assertThat(content.savedIntent.component?.className)
            .isEqualTo(MainActivity::class.java.name)
    }

    @Test
    fun `blocked states keep their own notification title`() {
        val app = RuntimeEnvironment.getApplication()
        val center = AndroidDownloadNotificationCenter(app)

        val wifi = center.build(
            DownloadNotificationRules.state(
                progressPercents = emptyList(),
                pendingCount = 1,
                paused = false,
                blocked = DownloadRunBlock.WIFI_REQUIRED,
            ),
        )
        assertThat(wifi.extras.getCharSequence("android.title").toString())
            .isEqualTo(app.getString(app.resources.getIdentifier(
                "download_notification_wifi",
                "string",
                app.packageName,
            )))

        val paused = center.build(
            DownloadNotificationRules.state(
                progressPercents = listOf(10),
                pendingCount = 1,
                paused = true,
                blocked = null,
            ),
        )
        assertThat(paused.extras.getCharSequence("android.title").toString())
            .isEqualTo(app.getString(app.resources.getIdentifier(
                "download_notification_paused",
                "string",
                app.packageName,
            )))
    }

    private fun mergedManifest(variant: String): Document {
        val capitalized = variant.replaceFirstChar { it.uppercase() }
        val file = File(
            "build/intermediates/merged_manifest/$variant/process${capitalized}MainManifest/AndroidManifest.xml",
        )
        assertThat(file.isFile).isTrue()
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    private fun serviceElement(document: Document): Element? {
        val services = document.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val service = services.item(index) as Element
            if (service.getAttributeNS(androidNs, "name") == DownloadService::class.java.name) {
                return service
            }
        }
        return null
    }

    private fun permissionElements(document: Document): List<String> {
        val permissions = document.getElementsByTagName("uses-permission")
        return (0 until permissions.length).map { index ->
            (permissions.item(index) as Element).getAttributeNS(androidNs, "name")
        }
    }
}
