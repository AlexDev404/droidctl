package dev.alexdev404.droidctl.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.HandlerThread
import android.view.Surface
import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.scrcpy.VideoPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Live decoder counters, shown in the debug pane. */
data class DecoderStats(
    val width: Int = 0,
    val height: Int = 0,
    val framesDecoded: Long = 0,
    val framesDropped: Long = 0,
    val bytesReceived: Long = 0,
    /** Time from handing a packet to the codec until its frame is released. */
    val decodeLatencyMs: Double = 0.0,
    val fps: Double = 0.0,
    val codecName: String? = null,
) {
    val resolution: String get() = if (width > 0) "${width}x$height" else "unknown"
}

/**
 * H.264 decoder rendering straight to the mirror [Surface].
 *
 * Async mode only: [MediaCodec.setCallback] with a dedicated [HandlerThread].
 * Polling `dequeueInputBuffer` in a loop would either burn a core spinning or
 * add a frame of latency for every poll interval, and it fits badly with a
 * producer that arrives on a different thread.
 */
class VideoDecoder(
    private val scid: String,
    private val onFatalError: (Throwable) -> Unit,
) : VideoSink {

    private val log = DroidCtlLog.video.withScid(scid)

    private val lock = Any()

    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null
    private var surface: Surface? = null

    /** Input buffer indices the codec has offered and we have not filled yet. */
    private val availableInputBuffers = ArrayDeque<Int>()

    /** Packets read off the socket and not yet handed to the codec. */
    private val pendingPackets = ArrayDeque<VideoPacket>()

    /**
     * The most recent config packet.
     *
     * Kept because the codec must be fed SPS/PPS before any media packet, and
     * after a resolution change the new codec instance needs them again before
     * the next key frame arrives.
     */
    private var configPacket: VideoPacket? = null

    private var width = 0
    private var height = 0

    private val submittedAtNanos = HashMap<Long, Long>()
    private var framesDecoded = 0L
    private var framesDropped = 0L
    private var bytesReceived = 0L
    private var latencySumMs = 0.0
    private var latencySamples = 0L
    private var windowStartNanos = 0L
    private var windowFrames = 0L

    private val _stats = MutableStateFlow(DecoderStats())
    val stats: StateFlow<DecoderStats> = _stats.asStateFlow()

    /** Sets the surface to render into. Must be called before the first packet. */
    fun attachSurface(surface: Surface) {
        synchronized(lock) { this.surface = surface }
    }

    override fun onSizeChanged(width: Int, height: Int) {
        synchronized(lock) {
            if (this.width == width && this.height == height && codec != null) return
            if (codec != null) {
                // A resolution change (the Target rotated, or its display was
                // resized) cannot be absorbed by an already-configured codec.
                log.i("Target resized ${this.width}x${this.height} -> ${width}x$height; recreating the decoder")
                releaseCodecLocked()
            }
            this.width = width
            this.height = height
            startCodecLocked()
        }
    }

    override fun onPacket(packet: VideoPacket) {
        synchronized(lock) {
            bytesReceived += packet.payload.size
            if (packet.isConfig) {
                configPacket = packet
            }
            if (codec == null) {
                // Size has not arrived yet; only the config packet is worth keeping.
                if (!packet.isConfig) framesDropped++
                return
            }
            if (pendingPackets.size >= MAX_PENDING_PACKETS && !packet.isKeyFrame && !packet.isConfig) {
                // The codec is behind. Dropping a non-key frame costs a smear;
                // dropping a key frame or config costs the rest of the stream.
                framesDropped++
                publishStatsLocked()
                return
            }
            pendingPackets.addLast(packet)
            pumpLocked()
        }
    }

    override fun onEndOfStream(cause: Throwable?) {
        if (cause != null) onFatalError(cause)
        release()
    }

    /** Releases the codec and its thread. Idempotent. */
    fun release() {
        synchronized(lock) {
            releaseCodecLocked()
            pendingPackets.clear()
            availableInputBuffers.clear()
            submittedAtNanos.clear()
        }
    }

    private fun startCodecLocked() {
        val target = surface ?: run {
            onFatalError(IllegalStateException("No surface to render the Target's video into"))
            return
        }
        try {
            val thread = HandlerThread("DroidCtl-Decoder-$scid").apply { start() }
            callbackThread = thread

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                // Feeding the config packet as a normal buffer with
                // BUFFER_FLAG_CODEC_CONFIG is preferred over csd-0/csd-1 here:
                // the stream may re-send SPS/PPS at any time and this way there
                // is only one code path for it.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            }

            val created = MediaCodec.createDecoderByType(MIME_TYPE)
            created.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    synchronized(lock) {
                        availableInputBuffers.addLast(index)
                        pumpLocked()
                    }
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    val render = info.size > 0
                    runCatching { codec.releaseOutputBuffer(index, render) }
                        .onFailure { log.w("Could not release output buffer $index", it) }
                    if (render) recordDecodedFrame(info.presentationTimeUs)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    log.i("Decoder output format: $format")
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    log.e("MediaCodec error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
                    onFatalError(e)
                }
            }, android.os.Handler(thread.looper))

            created.configure(format, target, null, 0)
            created.start()
            codec = created

            log.i("Decoder started for ${width}x$height (${created.name})")
            _stats.value = _stats.value.copy(width = width, height = height, codecName = created.name)

            // Re-prime the new codec: without SPS/PPS it rejects every media
            // packet until the Target happens to send another config packet.
            configPacket?.let { pendingPackets.addFirst(it) }
            pumpLocked()
        } catch (e: Exception) {
            releaseCodecLocked()
            onFatalError(
                IllegalStateException("Could not start an H.264 decoder for ${width}x$height", e)
            )
        }
    }

    private fun releaseCodecLocked() {
        codec?.let { c ->
            runCatching { c.stop() }.onFailure { log.d("Decoder stop failed: ${it.message}") }
            runCatching { c.release() }.onFailure { log.w("Decoder release failed", it) }
        }
        codec = null
        callbackThread?.let { thread ->
            thread.quitSafely()
            runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) }
        }
        callbackThread = null
        availableInputBuffers.clear()
    }

    /** Moves packets into codec input buffers while both are available. */
    private fun pumpLocked() {
        val c = codec ?: return
        while (availableInputBuffers.isNotEmpty() && pendingPackets.isNotEmpty()) {
            val index = availableInputBuffers.removeFirst()
            val packet = pendingPackets.removeFirst()
            val buffer: ByteBuffer = try {
                c.getInputBuffer(index) ?: continue
            } catch (e: IllegalStateException) {
                log.w("Input buffer $index went away", e)
                continue
            }
            buffer.clear()
            if (buffer.remaining() < packet.payload.size) {
                // Cannot happen with KEY_MAX_INPUT_SIZE set generously, but a
                // silent overflow here would corrupt the stream rather than fail.
                framesDropped++
                log.w("Packet of ${packet.payload.size} bytes does not fit in a ${buffer.remaining()} byte input buffer")
                continue
            }
            buffer.put(packet.payload)

            val flags = if (packet.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            // A config packet carries no timestamp of its own.
            val pts = if (packet.isConfig) 0L else packet.ptsUs
            if (!packet.isConfig) submittedAtNanos[pts] = System.nanoTime()
            try {
                c.queueInputBuffer(index, 0, packet.payload.size, pts, flags)
            } catch (e: IllegalStateException) {
                log.w("Could not queue input buffer $index", e)
            }
        }
    }

    private fun recordDecodedFrame(ptsUs: Long) {
        synchronized(lock) {
            framesDecoded++
            windowFrames++
            val submitted = submittedAtNanos.remove(ptsUs)
            if (submitted != null) {
                latencySumMs += (System.nanoTime() - submitted) / 1_000_000.0
                latencySamples++
            }
            if (submittedAtNanos.size > MAX_TRACKED_TIMESTAMPS) {
                // Frames the codec dropped internally would otherwise leak entries.
                submittedAtNanos.clear()
            }
            publishStatsLocked()
        }
    }

    private fun publishStatsLocked() {
        val now = System.nanoTime()
        if (windowStartNanos == 0L) windowStartNanos = now
        val elapsedNanos = now - windowStartNanos
        val fps = if (elapsedNanos > FPS_WINDOW_NANOS) {
            val value = windowFrames * 1_000_000_000.0 / elapsedNanos
            windowStartNanos = now
            windowFrames = 0
            value
        } else {
            _stats.value.fps
        }
        _stats.value = _stats.value.copy(
            width = width,
            height = height,
            framesDecoded = framesDecoded,
            framesDropped = framesDropped,
            bytesReceived = bytesReceived,
            decodeLatencyMs = if (latencySamples == 0L) 0.0 else latencySumMs / latencySamples,
            fps = fps,
        )
    }

    private companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MAX_PENDING_PACKETS = 16
        const val MAX_TRACKED_TIMESTAMPS = 256
        const val MAX_INPUT_SIZE = 1 shl 21 // 2 MiB: comfortably above a key frame
        const val THREAD_JOIN_TIMEOUT_MS = 1_000L
        const val FPS_WINDOW_NANOS = 1_000_000_000L
    }
}
