package com.soundcatch.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** PCM 16bit 모노 소리를 AAC(.m4a) 파일로 저장한다. 하나의 녹음 파일마다 새로 만든다. */
class AacFileWriter private constructor(
    private val sampleRate: Int,
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
) {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var wroteSample = false
    private var inputSamples = 0L
    private var closed = false

    /** 지금까지 넣은 소리 길이(초) */
    val durationSec: Long get() = inputSamples / sampleRate

    fun write(samples: ShortArray, count: Int) {
        check(!closed) { "already closed" }
        var offset = 0
        var waits = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val buf = codec.getInputBuffer(index) ?: throw IllegalStateException("no input buffer")
                buf.clear()
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val n = minOf(buf.remaining() / 2, count - offset)
                buf.asShortBuffer().put(samples, offset, n)
                codec.queueInputBuffer(index, 0, n * 2, presentationUs(), 0)
                inputSamples += n
                offset += n
                waits = 0
            } else if (++waits > MAX_WAITS) {
                throw IllegalStateException("encoder stalled")
            }
            drain(endOfStream = false)
        }
    }

    /** 파일을 마무리한다. 제대로 저장됐으면 true. */
    fun close(): Boolean {
        if (closed) return false
        closed = true
        var ok = false
        try {
            var queued = false
            for (i in 0 until MAX_WAITS) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = queued)
            ok = wroteSample
        } catch (e: Exception) {
            ok = false
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                    ok = false
                }
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
        return ok
    }

    private fun presentationUs(): Long = inputSamples * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++waits > MAX_WAITS) return
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (index >= 0) {
                val out = codec.getOutputBuffer(index)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (out != null && !isConfig && info.size > 0 && muxerStarted) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    muxer.writeSampleData(track, out, info)
                    wroteSample = true
                }
                codec.releaseOutputBuffer(index, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_WAITS = 300

        fun open(file: File, sampleRate: Int, bitRate: Int = 64_000): AacFileWriter {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                return AacFileWriter(sampleRate, codec, muxer)
            } catch (e: Exception) {
                try {
                    codec.release()
                } catch (_: Exception) {
                }
                throw e
            }
        }
    }
}
