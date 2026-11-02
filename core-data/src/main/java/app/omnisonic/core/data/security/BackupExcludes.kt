package app.omnisonic.core.data.security

import app.omnisonic.core.data.db.entity.ProviderEntity

/**
 * Columns that must never leave the device through Android backup, export or
 * share: their payloads are sealed with the Android Keystore key, which is
 * device-bound, so a restored copy is unreadable anyway — and exporting sealed
 * credentials widens the attack surface for no benefit. Phase 6 backup rules
 * and any future export feature must consult this list.
 */
object BackupExcludes {

    val providerColumns: Set<String> = setOf(
        ProviderEntity::secretBlob.name,
        ProviderEntity::mtlsKeystoreBlob.name,
        ProviderEntity::trustedCertsBlob.name,
    )
}
