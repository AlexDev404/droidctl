package dev.alexdev404.droidctl.video

import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.scrcpy.VideoPacket
import dev.alexdev404.droidctl.scrcpy.VideoStreamEvent
import dev.alexdev404.droidctl.scrcpy.VideoStreamReader
import java.io.DataInputStream
import java.io.IOException

/** Consumer of the demuxed video stream. */
interface VideoSink {
    /** The Target's video size, once at the start and again on every change. */
    fun onSizeChanged(width: Int, height: Int)

    /** One packet, config or media, in stream order. */
    fun onPacket(packet: VideoPacket)

    /** The stream ended. [cause] is null for a clean end of stream. */
    fun onEndOfStream(cause: Throwable?)
}

/**
 * Reads the video socket on a dedicated [Thread] and pushes to a [VideoSink].
 *
 * This is the one place the project uses a raw thread rather than a coroutine,
 * and it is deliberate: the loop spends essentially all of its time blocked in
 * a socket read. On a dispatcher it would occupy a pool thread indefinitely and
 * starve unrelated work, and `Dispatchers.IO`'s elasticity is no answer when
 * the block is permanent rather than occasional.
 */
class VideoStreamPump(
    private val input: DataInputStream,
    private val sink: VideoSink,
    private val scid: String,
) {
    private val log = DroidCtlLog.video.withScid(scid)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        check(thread == null) { "VideoStreamPump already started" }
        running = true
        thread = Thread({ run() }, "DroidCtl-VideoPump-$scid").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stops the loop.
     *
     * The read itself is only interrupted by the socket closing, which the
     * session does right after calling this; [join] then returns promptly.
     */
    fun stop() {
        running = false
    }

    fun join(timeoutMs: Long = 2_000) {
        runCatching { thread?.join(timeoutMs) }
        thread = null
    }

    private fun run() {
        val reader = VideoStreamReader(input)
        var cause: Throwable? = null
        try {
            while (running) {
                val event = reader.readEvent() ?: break
                when (event) {
                    is VideoStreamEvent.SizeChanged -> {
                        log.i("Target video size is now ${event.width}x${event.height}")
                        sink.onSizeChanged(event.width, event.height)
                    }

                    is VideoStreamEvent.Packet -> sink.onPacket(event.packet)
                }
            }
        } catch (e: IOException) {
            // Expected once the session tears the socket down; a genuine failure
            // otherwise, and either way it must reach the sink so the UI stops
            // showing a frozen last frame and says what happened.
            if (running) {
                cause = e
                log.e("Video stream failed", e)
            } else {
                log.d("Video stream closed during teardown: ${e.message}")
            }
        }
        sink.onEndOfStream(cause)
    }
}
