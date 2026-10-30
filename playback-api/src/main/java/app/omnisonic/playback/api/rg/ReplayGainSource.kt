package app.omnisonic.playback.api.rg

interface ReplayGainSource {
    suspend fun gainFor(providerId: Long, externalId: String): ReplayGainValues?
}

/** Always absent; playback runs unnormalised when no source reports values. */
object NoReplayGainSource : ReplayGainSource {
    override suspend fun gainFor(providerId: Long, externalId: String): ReplayGainValues? = null
}
