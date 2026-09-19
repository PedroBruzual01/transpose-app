package com.realtimetranspose.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Pull-based PCM decoder: MediaExtractor feeding a synchronous MediaCodec
 * decode loop. Not thread-safe — drive it from a single dedicated thread
 * (see [com.realtimetranspose.audio.TransposePlayer]).
 *
 * Android decoders always output raw PCM as 16-bit signed little-endian,
 * interleaved by channel, regardless of the source format (mp3/aac/etc.) —
 * that assumption is what [readChunk] relies on.
 */
class AudioFileDecoder(context: Context, uri: Uri) : AutoCloseable {

    val sampleRate: Int
    val channelCount: Int
    val durationUs: Long

    private val extractor = MediaExtractor()
    private val codec: MediaCodec

    private var inputDone = false
    private var outputDone = false

    // Leftover decoded samples from the last codec output buffer that didn't
    // fully fit into the caller's requested chunk size yet.
    private var pending: ShortArray = ShortArray(0)
    private var pendingOffset = 0

    init {
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track found in file")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) { "Track has no MIME type" }
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /**
     * Fills `outChannels[channel]` (each sized >= [maxFrames]) with
     * deinterleaved PCM in [-1, 1] float range, starting at index 0. Returns
     * the number of frames written, or -1 once the file is fully drained.
     */
    fun readChunk(outChannels: Array<FloatArray>, maxFrames: Int): Int {
        var written = 0

        while (written < maxFrames) {
            if (pendingOffset < pending.size) {
                val framesAvailable = (pending.size - pendingOffset) / channelCount
                val framesToCopy = minOf(framesAvailable, maxFrames - written)
                for (f in 0 until framesToCopy) {
                    for (c in 0 until channelCount) {
                        outChannels[c][written + f] =
                            pending[pendingOffset + f * channelCount + c] / 32768f
                    }
                }
                written += framesToCopy
                pendingOffset += framesToCopy * channelCount
                continue
            }

            if (outputDone) break

            feedInput()
            val decoded = drainOutput() ?: continue
            pending = decoded
            pendingOffset = 0
        }

        return if (written == 0 && outputDone) -1 else written
    }

    /** Jumps decode position to [positionUs] and resets internal decoder state. */
    fun seekTo(positionUs: Long) {
        extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        pending = ShortArray(0)
        pendingOffset = 0
    }

    private fun feedInput() {
        if (inputDone) return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        val sampleSize = extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** Null means "nothing new yet, caller should loop again" — not EOF by itself. */
    private fun drainOutput(): ShortArray? {
        val info = MediaCodec.BufferInfo()
        val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
        if (outputIndex < 0) {
            // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_FORMAT_CHANGED / INFO_OUTPUT_BUFFERS_CHANGED
            // — none need special handling here: we trust the extractor's
            // declared sampleRate/channelCount, and simply retry.
            return null
        }

        val outputBuffer = codec.getOutputBuffer(outputIndex)
        val shorts = if (outputBuffer != null && info.size > 0) {
            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            outputBuffer.position(info.offset)
            outputBuffer.limit(info.offset + info.size)
            val shortBuffer = outputBuffer.asShortBuffer()
            ShortArray(shortBuffer.remaining()).also { shortBuffer.get(it) }
        } else {
            ShortArray(0)
        }
        codec.releaseOutputBuffer(outputIndex, false)

        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            outputDone = true
        }
        return shorts
    }

    override fun close() {
        codec.stop()
        codec.release()
        extractor.release()
    }
}
