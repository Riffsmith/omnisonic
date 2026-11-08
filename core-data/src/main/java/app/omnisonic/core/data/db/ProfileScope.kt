package app.omnisonic.core.data.db

/**
 * Visibility filter threaded through every library, search, playlist and
 * download read (`reference/ROADMAP.md` §Phase 5 / R-13). Phase 5 carries only
 * the provider exclusion set, computed from the hidden-provider preference and
 * the provider status tracker; Phase 10 adds profile visibility semantics here
 * without changing DAO signatures.
 */
data class ProfileScope(
    val excludedProviderIds: List<Long> = emptyList(),
) {

    fun excluding(providerIds: Collection<Long>): ProfileScope =
        copy(excludedProviderIds = (excludedProviderIds + providerIds).distinct())

    companion object {
        val Unrestricted = ProfileScope()
    }
}
