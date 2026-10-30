package app.omnisonic

import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkPolicyManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun cleartextHttpIsPermittedUnderAppPolicy() {
        val resources = RuntimeEnvironment.getApplication().resources
        val id = resources.getIdentifier("network_security_config", "xml", "app.omnisonic")

        assertThat(id).isNotEqualTo(0)

        val parser: XmlResourceParser = resources.getXml(id)
        var sawBaseConfig = false
        var cleartextPermitted: String? = null
        var event = parser.eventType
        while (event != XmlResourceParser.END_DOCUMENT) {
            if (event == XmlResourceParser.START_TAG) {
                when (parser.name) {
                    "base-config" -> {
                        sawBaseConfig = true
                        cleartextPermitted =
                            parser.getAttributeValue(androidNs, "cleartextTrafficPermitted")
                                ?: parser.getAttributeValue(null, "cleartextTrafficPermitted")
                    }
                }
            }
            event = parser.next()
        }
        parser.close()

        assertThat(sawBaseConfig).isTrue()
        assertThat(cleartextPermitted).isEqualTo("true")
    }

    @Test
    fun manifestPointsAtTheCleartextPolicy() {
        val manifest = File("src/main/AndroidManifest.xml")

        assertThat(manifest.isFile).isTrue()

        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = builder.newDocumentBuilder().parse(manifest)
        val application = document.getElementsByTagName("application").item(0)

        val attr = application.attributes.getNamedItemNS(androidNs, "networkSecurityConfig")

        assertThat(attr?.nodeValue).isEqualTo("@xml/network_security_config")
    }

    @Test
    fun foregroundServicePermissionIsDeclared() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)

        val requested = info.requestedPermissions?.toList().orEmpty()

        assertThat(requested).contains("android.permission.FOREGROUND_SERVICE")
    }
}
