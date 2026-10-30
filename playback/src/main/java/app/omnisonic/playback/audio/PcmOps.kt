package app.omnisonic.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal object PcmOps {

    internal const val PCM32_SCALE = 2147483648f

    fun supported(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_16BIT ||
            encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT ||
            encoding == C.ENCODING_PCM_FLOAT

    fun requireSupported(format: AudioProcessor.AudioFormat) {
        if (!supported(format.encoding)) {
            throw AudioProcessor.UnhandledAudioFormatException(format)
        }
    }

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 1
    }

    /**
     * `transform` receives one sample and returns the replacement sample.
     * Domain by encoding: s16 raw [-32768, 32767], s24 raw, s32 normalised [-1, 1),
     * float [-1, 1]. Sample write saturates to the encoding's real range.
     */
    fun forEachSample(buffer: ByteBuffer, encoding: Int, transform: (Float) -> Float) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (buffer.hasRemaining()) {
                    val at = buffer.position()
                    val scaled = transform(buffer.getShort(at).toFloat()).roundToInt()
                    buffer.putShort(at, scaled.coerceIn(-32768, 32767).toShort())
                    buffer.position(at + 2)
                }
            }
            C.ENCODING_PCM_24BIT -> {
                while (buffer.hasRemaining()) {
                    val at = buffer.position()
                    val scaled = transform(readSigned24At(buffer, at).toFloat()).roundToInt()
                    writeSigned24At(buffer, at, scaled.coerceIn(-0x800000, 0x7FFFFF))
                    buffer.position(at + 3)
                }
            }
            C.ENCODING_PCM_32BIT -> {
                while (buffer.hasRemaining()) {
                    val at = buffer.position()
                    val scaled = transform(buffer.getInt(at) / PCM32_SCALE) * PCM32_SCALE
                    buffer.putInt(at, saturateInt31(scaled))
                    buffer.position(at + 4)
                }
            }
            else -> {
                while (buffer.hasRemaining()) {
                    val at = buffer.position()
                    buffer.putFloat(at, saturateFloat(transform(buffer.getFloat(at))))
                    buffer.position(at + 4)
                }
            }
        }
    }

    fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_24BIT -> readSigned24At(buffer, buffer.position()).also { buffer.position(buffer.position() + 3) }.toFloat()
        C.ENCODING_PCM_32BIT -> buffer.getInt(buffer.position()).also { buffer.position(buffer.position() + 4) } / PCM32_SCALE
        C.ENCODING_PCM_FLOAT -> buffer.getFloat(buffer.position()).also { buffer.position(buffer.position() + 4) }
        else -> buffer.getShort(buffer.position()).also { buffer.position(buffer.position() + 2) }.toFloat()
    }

    fun writeSample(out: ByteBuffer, encoding: Int, value: Float) = when (encoding) {
        C.ENCODING_PCM_24BIT -> writeSigned24At(out, out.position(), value.roundToInt().coerceIn(-0x800000, 0x7FFFFF))
            .also { out.position(out.position() + 3) }
        C.ENCODING_PCM_32BIT -> out.putInt(saturateInt31(value * PCM32_SCALE))
        C.ENCODING_PCM_FLOAT -> out.putFloat(saturateFloat(value))
        else -> out.putShort(value.roundToInt().coerceIn(-32768, 32767).toShort())
    }

    fun readSigned24At(buffer: ByteBuffer, offset: Int): Int {
        val b0 = buffer.get(offset).toInt() and 0xFF
        val b1 = buffer.get(offset + 1).toInt() and 0xFF
        val b2 = buffer.get(offset + 2).toInt() and 0xFF
        var value = (b2 shl 16) or (b1 shl 8) or b0
        if (b2 and 0x80 != 0) value -= 0x1000000
        return value
    }

    fun writeSigned24At(buffer: ByteBuffer, offset: Int, value: Int) {
        buffer.put(offset, (value and 0xFF).toByte())
        buffer.put(offset + 1, ((value shr 8) and 0xFF).toByte())
        buffer.put(offset + 2, ((value shr 16) and 0xFF).toByte())
    }

    fun saturateFloat(value: Float): Float = when {
        value.isNaN() -> 0f
        value > 1.0f -> 1.0f
        value < -1.0f -> -1.0f
        else -> value
    }

    fun saturateInt31(value: Float): Int = when {
        value.isNaN() -> 0
        value >= Int.MAX_VALUE.toFloat() -> Int.MAX_VALUE
        value <= Int.MIN_VALUE.toFloat() -> Int.MIN_VALUE
        else -> value.roundToInt()
    }
}
