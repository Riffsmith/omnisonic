package app.omnisonic.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun format(
    sampleRate: Int = 48_000,
    channels: Int = 2,
    encoding: Int = C.ENCODING_PCM_16BIT,
) = AudioProcessor.AudioFormat(sampleRate, channels, encoding)

internal fun shortsBuffer(samples: ShortArray): ByteBuffer {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
    samples.forEach { buffer.putShort(it) }
    buffer.flip()
    return buffer
}

internal fun floatsBuffer(samples: FloatArray): ByteBuffer {
    val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
    samples.forEach { buffer.putFloat(it) }
    buffer.flip()
    return buffer
}

internal fun signed24Buffer(values: IntArray): ByteBuffer {
    val buffer = ByteBuffer.allocate(values.size * 3).order(ByteOrder.nativeOrder())
    values.forEachIndexed { index, value -> PcmOps.writeSigned24At(buffer, index * 3, value) }
    buffer.position(values.size * 3)
    buffer.flip()
    return buffer
}

internal fun ints32Buffer(values: IntArray): ByteBuffer {
    val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
    values.forEach { buffer.putInt(it) }
    buffer.flip()
    return buffer
}

internal fun AudioProcessor.process(input: ByteBuffer): ByteBuffer {
    queueInput(input)
    return getOutput()
}

internal fun ByteBuffer.readShorts(): ShortArray {
    val result = ShortArray(remaining() / 2)
    for (i in result.indices) result[i] = getShort()
    return result
}

internal fun ByteBuffer.readFloats(): FloatArray {
    val result = FloatArray(remaining() / 4)
    for (i in result.indices) result[i] = getFloat()
    return result
}

internal fun ByteBuffer.signed24Values(): IntArray {
    val result = IntArray(remaining() / 3)
    for (i in result.indices) result[i] = PcmOps.readSigned24At(this, i * 3)
    return result
}

internal fun ByteBuffer.readInts(): IntArray {
    val result = IntArray(remaining() / 4)
    for (i in result.indices) result[i] = getInt()
    return result
}
