package app.omnisonic.core.data.security

import app.omnisonic.core.data.db.entity.ProviderEntity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

class BackupExcludesTest {

    @Test
    fun `sealed credential and mTLS blobs are excluded from backups`() {
        assertThat(BackupExcludes.providerColumns).containsAtLeast(
            "secretBlob",
            "mtlsKeystoreBlob",
            "trustedCertsBlob",
        )
    }

    @Test
    fun `every excluded name is a real provider column`() {
        val declaredColumns: Set<String> = ProviderEntity::class.memberProperties
            .map(KProperty1<ProviderEntity, *>::name)
            .toSet()

        assertThat(BackupExcludes.providerColumns).isNotEmpty()
        assertThat(declaredColumns).containsAtLeastElementsIn(BackupExcludes.providerColumns)
    }
}
