package dev.alexdev404.droidctl.scrcpy

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * Parsing of the scrcpy video stream.
 *
 * Derived from the pinned server's
 * `server/src/main/java/com/genymobile/scrcpy/device/Streamer.java` and the
 * reference client's `app/src/demuxer.c`, both in this repository.
 *
 * With `send_stream_meta=true` and `send_frame_meta=true` the stream is:
 *
 * ```
 * codec id                     4 bytes, big-endian ("h264" as ASCII)
 * then, repeatedly, a 12-byte record:
 *   MSB of byte 0 set   -> session record: flags(4) width(4) height(4), no payload
 *   MSB of byte 0 clear -> frame record:   ptsAndFlags(8) size(4), then size bytes
 * ```
 *
 * The first record after the codec id is always a session record; it is where
 * the video dimensions come from. Another one arrives whenever the Target's
 * video size changes (rotation, display resize).
 */
object VideoStream {

    /** Length of both record kinds' headers. */
    const val HEADER_SIZE = 12

    // Streamer.java. NOTE: these are *not* the bit positions given in the
    // implementation spec's reference table; see docs/PROTOCOL.md.
    const val FLAG_SESSION = 1L shl 63
    const val FLAG_CONFIG = 1L shl 62
    const val FLAG_KEY_FRAME = 1L shl 61

    /** The PTS occupies everything below the key-frame flag. */
    const val PTS_MASK = FLAG_KEY_FRAME - 1

    // VideoCodec.java: the 4-byte ASCII representation of the codec name.
    const val CODEC_ID_H264 = 0x68323634
    const val CODEC_ID_H265 = 0x68323635
    const val CODEC_ID_AV1 = 0x00617631

    /** Streamer.writeDisableStream(false): the Target could not capture. */
    const val CODEC_ID_STREAM_DISABLED = 0

    /** Streamer.writeDisableStream(true): configuration error, stop. */
    const val CODEC_ID_STREAM_ERROR = 1

    /**
     * Interprets a 12-byte header.
     *
     * @throws IllegalArgumentException if [header] is not exactly
     *   [HEADER_SIZE] bytes; a short header means the read loop is broken.
     */
    fun parseHeader(header: ByteArray, offset: Int = 0): StreamRecord {
        require(header.size - offset >= HEADER_SIZE) {
            "video header must be $HEADER_SIZE bytes, got ${header.size - offset}"
        }
        return if (header[offset].toInt() and 0x80 != 0) {
            StreamRecord.Session(
                width = readInt(header, offset + 4),
                height = readInt(header, offset + 8),
                clientResized = header[offset + 3].toInt() and 1 != 0,
            )
        } else {
            val ptsAndFlags = readLong(header, offset)
            val config = ptsAndFlags and FLAG_CONFIG != 0L
            StreamRecord.Frame(
                // A config packet carries SPS/PPS, not media: its PTS field is
                // not a timestamp and must not be fed to the decoder as one.
                ptsUs = if (config) 0L else ptsAndFlags and PTS_MASK,
                isConfig = config,
                isKeyFrame = ptsAndFlags and FLAG_KEY_FRAME != 0L,
                payloadSize = readInt(header, offset + 8),
            )
        }
    }

    /** Name of a codec id, for logs and error messages. */
    fun codecName(codecId: Int): String = when (codecId) {
        CODEC_ID_H264 -> "h264"
        CODEC_ID_H265 -> "h265"
        CODEC_ID_AV1 -> "av1"
        else -> "0x%08x".format(codecId)
    }

    internal fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    internal fun readLong(bytes: ByteArray, offset: Int): Long =
        (readInt(bytes, offset).toLong() and 0xFFFFFFFFL shl 32) or
            (readInt(bytes, offset + 4).toLong() and 0xFFFFFFFFL)
}

/** One record of the video stream. */
sealed interface StreamRecord {

    /** Video dimensions, sent first and again after every size change. */
    data class Session(
        val width: Int,
        val height: Int,
        val clientResized: Boolean,
    ) : StreamRecord

    /** A media packet header; [payloadSize] bytes of payload follow it. */
    data class Frame(
        val ptsUs: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val payloadSize: Int,
    ) : StreamRecord
}

/** A frame read off the video socket, header and payload together. */
data class VideoPacket(
    val ptsUs: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is VideoPacket &&
            ptsUs == other.ptsUs &&
            isConfig == other.isConfig &&
            isKeyFrame == other.isKeyFrame &&
            payload.contentEquals(other.payload))

    override fun hashCode(): Int =
        (((ptsUs.hashCode() * 31 + isConfig.hashCode()) * 31) + isKeyFrame.hashCode()) * 31 +
            payload.contentHashCode()
}

/** What a single read off the video stream produced. */
sealed interface VideoStreamEvent {
    /** The Target's video size, at the start of the stream or after a change. */
    data class SizeChanged(val width: Int, val height: Int) : VideoStreamEvent

    /** A packet ready for the decoder. */
    data class Packet(val packet: VideoPacket) : VideoStreamEvent
}

/**
 * Reads the video stream off a socket.
 *
 * Reads use [DataInputStream.readFully] throughout: a plain `read()` on a
 * socket routinely returns fewer bytes than asked for, and treating a short
 * read as a complete one is the classic way to end up with corrupted frames
 * that the decoder rejects for reasons that look nothing like the cause.
 */
class VideoStreamReader(private val input: DataInputStream) {

    /**
     * Reads the 4-byte codec id that opens the stream.
     *
     * @throws IOException if the Target disabled the stream or hit a
     *   configuration error, both of which it signals in this field.
     */
    fun readCodecId(): Int {
        val header = ByteArray(4)
        input.readFully(header)
        return when (val codecId = VideoStream.readInt(header, 0)) {
            VideoStream.CODEC_ID_STREAM_DISABLED ->
                throw IOException(
                    "The Target explicitly disabled the video stream (it could not capture the screen)"
                )
            VideoStream.CODEC_ID_STREAM_ERROR ->
                throw IOException("The Target hit a video configuration error; check the server log")
            else -> codecId
        }
    }

    /**
     * Reads the next record, and its payload if it has one.
     *
     * @return null at end of stream (the Target disconnected or the server exited).
     */
    fun readEvent(): VideoStreamEvent? {
        val header = ByteArray(VideoStream.HEADER_SIZE)
        try {
            input.readFully(header)
        } catch (e: EOFException) {
            return null
        }
        return when (val record = VideoStream.parseHeader(header)) {
            is StreamRecord.Session -> {
                if (record.width <= 0 || record.height <= 0) {
                    throw IOException("Invalid video size from Target: ${record.width}x${record.height}")
                }
                VideoStreamEvent.SizeChanged(record.width, record.height)
            }

            is StreamRecord.Frame -> {
                if (record.payloadSize <= 0) {
                    throw IOException("Invalid packet length ${record.payloadSize} from Target")
                }
                val payload = ByteArray(record.payloadSize)
                input.readFully(payload)
                VideoStreamEvent.Packet(
                    VideoPacket(
                        ptsUs = record.ptsUs,
                        isConfig = record.isConfig,
                        isKeyFrame = record.isKeyFrame,
                        payload = payload,
                    )
                )
            }
        }
    }
}
