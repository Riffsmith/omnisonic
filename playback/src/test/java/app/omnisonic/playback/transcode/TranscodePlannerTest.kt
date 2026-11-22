package app.omnisonic.playback.transcode

import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.playback.renderer.LocalPlaybackRenderer
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class TranscodePlannerTest {

    data class Case(
        val name: String,
        val input: TranscodePlanInput,
        val expected: TranscodeDecision,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("decisionCases")
    fun `decision table`(caseName: String, input: TranscodePlanInput, expected: TranscodeDecision) {
        assertThat(TranscodePlanner.plan(input)).isEqualTo(expected)
    }

    @Test
    fun `table has at least 40 uniquely named cases`() {
        val names = cases().map { it.name }

        assertThat(names.size).isAtLeast(40)
        assertThat(names.distinct()).containsExactlyElementsIn(names)
    }

    @Test
    fun `plan is deterministic for every table case across repeated calls`() {
        cases().forEach { case ->
            val first = TranscodePlanner.plan(case.input)

            repeat(3) {
                assertThat(TranscodePlanner.plan(case.input)).isEqualTo(first)
            }
            assertThat(first).isEqualTo(case.expected)
        }
    }

    @Test
    fun `local playback renderer id maps to the local settings renderer id`() {
        assertThat(TranscodeRendererId(LocalPlaybackRenderer.RENDERER_ID))
            .isEqualTo(TranscodeRendererId.Local)
    }

    companion object {
        private val traitsTranscode = ProviderTraits()
        private val traitsNoTranscode = ProviderTraits(supportsServerTranscode = false)

        private val rendererUnknown = RendererCapabilities(null)
        private val rendererCommon = RendererCapabilities(setOf("mp3", "aac", "flac", "opus"))
        private val rendererFlacOnly = RendererCapabilities(setOf("flac"))
        private val rendererNone = RendererCapabilities(emptySet())

        private val srcMp3 = TranscodeSource(format = "mp3", bitrateKbps = 320, sampleRateHz = 44100)
        private val srcFlac = TranscodeSource(format = "flac", bitrateKbps = 1000, sampleRateHz = 96000)
        private val srcDsd = TranscodeSource(format = "dsd", bitrateKbps = 2822, sampleRateHz = 2822400)
        private val srcUnknown = TranscodeSource(format = null)
        private val srcFlacNoMeta = TranscodeSource(format = "flac")
        private val srcFlacUpper = TranscodeSource(format = "FLAC", bitrateKbps = 1000, sampleRateHz = 96000)
        private val srcFlacPadded = TranscodeSource(format = " flac ")

        private val settingsOff = TranscodeSettings()
        private val settingsWifi320 = TranscodeSettings(wifiBitrateKbps = 320)
        private val settingsCell128 = TranscodeSettings(cellularBitrateKbps = 128)
        private val settingsDl256 = TranscodeSettings(downloadBitrateKbps = 256)
        private val settingsAll = TranscodeSettings(
            wifiBitrateKbps = 320,
            cellularBitrateKbps = 128,
            downloadBitrateKbps = 256,
        )
        private val settingsMandatory = TranscodeSettings(mandatory = true)
        private val settingsFormatOpus = TranscodeSettings(preferredFormat = "opus")
        private val settingsMandatoryFormat = TranscodeSettings(
            mandatory = true,
            preferredFormat = "mp3",
            wifiBitrateKbps = 192,
        )

        private fun input(
            source: TranscodeSource = srcMp3,
            renderer: RendererCapabilities = rendererCommon,
            settings: TranscodeSettings = settingsOff,
            traits: ProviderTraits = traitsTranscode,
            kind: TranscodeRequestKind = TranscodeRequestKind.STREAM_WIFI,
        ) = TranscodePlanInput(source, renderer, settings, traits, kind)

        private fun case(
            name: String,
            input: TranscodePlanInput,
            expected: TranscodeDecision,
        ) = Case(name, input, expected)

        fun cases(): List<Case> = listOf(
            case(
                "off settings direct-play a supported source on wifi",
                input(),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "off settings direct-play on cellular",
                input(kind = TranscodeRequestKind.STREAM_CELLULAR),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "off settings direct-play for download",
                input(kind = TranscodeRequestKind.DOWNLOAD),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "off settings direct-play with unknown renderer capabilities",
                input(renderer = rendererUnknown),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "provider without server transcode still direct-plays a supported source",
                input(traits = traitsNoTranscode),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "unknown source format direct-plays with known non-empty capabilities",
                input(source = srcUnknown),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "unknown source format direct-plays with unknown capabilities",
                input(source = srcUnknown, renderer = rendererUnknown),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "uppercase source format is normalized for the support check",
                input(source = srcFlacUpper, renderer = rendererFlacOnly),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "whitespace-padded source format is normalized",
                input(source = srcFlacPadded, renderer = rendererFlacOnly),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "flac source direct-plays inside the common capability set",
                input(source = srcFlac),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "explicit wifi bitrate forces server transcode on wifi",
                input(settings = settingsWifi320),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 320, format = null),
            ),
            case(
                "explicit wifi bitrate stays off on cellular",
                input(settings = settingsWifi320, kind = TranscodeRequestKind.STREAM_CELLULAR),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "explicit wifi bitrate stays off for download",
                input(settings = settingsWifi320, kind = TranscodeRequestKind.DOWNLOAD),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "explicit cellular bitrate forces server transcode on cellular",
                input(
                    settings = settingsCell128,
                    kind = TranscodeRequestKind.STREAM_CELLULAR,
                ),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 128, format = null),
            ),
            case(
                "explicit cellular bitrate stays off on wifi",
                input(settings = settingsCell128),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "explicit cellular bitrate stays off for download",
                input(settings = settingsCell128, kind = TranscodeRequestKind.DOWNLOAD),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "explicit download bitrate forces server transcode for download",
                input(settings = settingsDl256, kind = TranscodeRequestKind.DOWNLOAD),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 256, format = null),
            ),
            case(
                "explicit download bitrate stays off on wifi",
                input(settings = settingsDl256),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "explicit download bitrate stays off on cellular",
                input(settings = settingsDl256, kind = TranscodeRequestKind.STREAM_CELLULAR),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "combined settings resolve the wifi slot",
                input(settings = settingsAll),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 320, format = null),
            ),
            case(
                "combined settings resolve the cellular slot",
                input(settings = settingsAll, kind = TranscodeRequestKind.STREAM_CELLULAR),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 128, format = null),
            ),
            case(
                "combined settings resolve the download slot",
                input(settings = settingsAll, kind = TranscodeRequestKind.DOWNLOAD),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 256, format = null),
            ),
            case(
                "bitrate 1 boundary counts as explicit",
                input(settings = TranscodeSettings(wifiBitrateKbps = 1)),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 1, format = null),
            ),
            case(
                "max int bitrate passes through unchanged",
                input(settings = TranscodeSettings(wifiBitrateKbps = Int.MAX_VALUE)),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = Int.MAX_VALUE, format = null),
            ),
            case(
                "download slot off wins over explicit wifi and cellular",
                input(
                    settings = TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 128),
                    kind = TranscodeRequestKind.DOWNLOAD,
                ),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "format preference alone forces server transcode with format only",
                input(settings = settingsFormatOpus),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = "opus"),
            ),
            case(
                "format preference combines with explicit wifi bitrate",
                input(settings = TranscodeSettings(wifiBitrateKbps = 192, preferredFormat = "opus")),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 192, format = "opus"),
            ),
            case(
                "format preference applies on the cellular slot",
                input(
                    settings = settingsFormatOpus,
                    kind = TranscodeRequestKind.STREAM_CELLULAR,
                ),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = "opus"),
            ),
            case(
                "format preference applies for download",
                input(settings = settingsFormatOpus, kind = TranscodeRequestKind.DOWNLOAD),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = "opus"),
            ),
            case(
                "dotted format token passes through unchanged",
                input(settings = TranscodeSettings(preferredFormat = "mp4.audio")),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = "mp4.audio"),
            ),
            case(
                "mandatory forces server transcode with server-default parameters",
                input(settings = settingsMandatory),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = null),
            ),
            case(
                "mandatory forces transcode even with unknown renderer capabilities",
                input(settings = settingsMandatory, renderer = rendererUnknown),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = null),
            ),
            case(
                "mandatory combines with explicit bitrate and format",
                input(settings = settingsMandatoryFormat),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 192, format = "mp3"),
            ),
            case(
                "mandatory with provider lacking transcode refuses",
                input(settings = settingsMandatory, traits = traitsNoTranscode),
                TranscodeDecision.Refused(TranscodeRefusalReason.PROVIDER_CANNOT_TRANSCODE),
            ),
            case(
                "explicit bitrate with provider lacking transcode refuses",
                input(settings = settingsWifi320, traits = traitsNoTranscode),
                TranscodeDecision.Refused(TranscodeRefusalReason.PROVIDER_CANNOT_TRANSCODE),
            ),
            case(
                "format preference with provider lacking transcode refuses",
                input(settings = settingsFormatOpus, traits = traitsNoTranscode),
                TranscodeDecision.Refused(TranscodeRefusalReason.PROVIDER_CANNOT_TRANSCODE),
            ),
            case(
                "cellular refusal applies only on the cellular slot",
                input(
                    settings = settingsCell128,
                    traits = traitsNoTranscode,
                    kind = TranscodeRequestKind.STREAM_CELLULAR,
                ),
                TranscodeDecision.Refused(TranscodeRefusalReason.PROVIDER_CANNOT_TRANSCODE),
            ),
            case(
                "same cellular settings direct-play on wifi without provider transcode",
                input(settings = settingsCell128, traits = traitsNoTranscode),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "unsupported source format triggers server transcode with default parameters",
                input(source = srcDsd),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = null),
            ),
            case(
                "unsupported source format transcodes to the preferred format",
                input(source = srcDsd, settings = settingsFormatOpus),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = "opus"),
            ),
            case(
                "unsupported source format with unsupported provider refuses with renderer reason",
                input(source = srcDsd, traits = traitsNoTranscode),
                TranscodeDecision.Refused(TranscodeRefusalReason.RENDERER_CANNOT_PLAY_SOURCE),
            ),
            case(
                "empty renderer capability set plays nothing",
                input(renderer = rendererNone),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = null),
            ),
            case(
                "empty renderer capability set with unknown source format still needs transcode",
                input(source = srcUnknown, renderer = rendererNone),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = null),
            ),
            case(
                "empty renderer capability set without provider transcode refuses",
                input(renderer = rendererNone, traits = traitsNoTranscode),
                TranscodeDecision.Refused(TranscodeRefusalReason.RENDERER_CANNOT_PLAY_SOURCE),
            ),
            case(
                "unknown renderer capabilities cannot prove a mismatch",
                input(source = srcDsd, renderer = rendererUnknown),
                TranscodeDecision.DirectPlay,
            ),
            case(
                "missing source bitrate and sample rate still plans from format and settings",
                input(source = srcFlacNoMeta, settings = settingsWifi320),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = 320, format = null),
            ),
            case(
                "missing all source metadata with mandatory transcodes when provider supports",
                input(source = srcUnknown, settings = settingsMandatory, renderer = rendererUnknown),
                TranscodeDecision.ServerTranscode(maxBitRateKbps = null, format = null),
            ),
            case(
                "missing all source metadata with mandatory refuses when provider cannot transcode",
                input(
                    source = srcUnknown,
                    settings = settingsMandatory,
                    traits = traitsNoTranscode,
                    renderer = rendererUnknown,
                ),
                TranscodeDecision.Refused(TranscodeRefusalReason.PROVIDER_CANNOT_TRANSCODE),
            ),
        )

        @JvmStatic
        fun decisionCases(): List<Arguments> =
            cases().map { Arguments.of(it.name, it.input, it.expected) }
    }
}
