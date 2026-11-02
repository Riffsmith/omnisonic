package app.omnisonic.core.data.providers

import app.omnisonic.provider.api.ProviderTraits

/**
 * Runtime traits for a persisted provider's api. The full factory list is
 * assembled above core-data, so the queue and enqueuers resolve traits
 * through this seam instead of holding concrete factories.
 */
fun interface ProviderTraitsSource {
    fun traitsFor(api: String): ProviderTraits
}

/** Defaults to the Subsonic family so unknown apis keep today's behaviour. */
val DefaultTraitsSource: ProviderTraitsSource = ProviderTraitsSource { ProviderTraits() }
