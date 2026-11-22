package app.omnisonic.core.data.transcode

data class TranscodeSettings(
    val wifiBitrateKbps: Int? = null,
    val cellularBitrateKbps: Int? = null,
    val downloadBitrateKbps: Int? = null,
    val mandatory: Boolean = false,
    val preferredFormat: String? = null,
) {
    init {
        TranscodeSettingsValidation.requireValidBitrate(wifiBitrateKbps)
        TranscodeSettingsValidation.requireValidBitrate(cellularBitrateKbps)
        TranscodeSettingsValidation.requireValidBitrate(downloadBitrateKbps)
        require(preferredFormat == null || TranscodeSettingsValidation.isValidFormatToken(preferredFormat)) {
            "Format must be a lowercase provider-neutral token other than the reserved auto sentinel"
        }
    }
}

@JvmInline
value class TranscodeRendererId(val value: String) {
    init {
        require(TranscodeSettingsValidation.isValidToken(value)) {
            "Renderer id must be a lowercase provider-neutral token"
        }
    }

    companion object {
        val Local = TranscodeRendererId("local")
    }
}

internal object TranscodeSettingsValidation {
    private const val MAX_TOKEN_LENGTH = 32
    private val TOKEN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")

    fun isValidToken(value: String): Boolean =
        value.length <= MAX_TOKEN_LENGTH && TOKEN.matches(value)

    fun isValidFormatToken(value: String): Boolean = value != "auto" && isValidToken(value)

    fun requireValidBitrate(value: Int?) {
        require(value == null || value > 0) { "Bitrate must be off or positive" }
    }

    fun persistedBitrate(value: Int?): Int? = value?.takeIf { it > 0 }

    fun persistedFormat(value: String?): String? = value?.takeIf(::isValidFormatToken)
}
