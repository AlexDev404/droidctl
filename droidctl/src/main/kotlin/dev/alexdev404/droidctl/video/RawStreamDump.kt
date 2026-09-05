package dev.alexdev404.droidctl.video

import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.scrcpy.VideoPacket
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException

/**
 * Writes the post-header payload stream to a `.h264` file instead of (or as
 * well as) decoding it.
 *
 * This is the fastest way to tell a socket/framing fault from a decoder fault:
 * if the dump plays in `ffplay` on a desktop, the sockets and the framing are
 * fine and the problem is in [VideoDecoder]; if it does not, the bytes were
 * already wrong before the decoder ever saw them.
 *
 * The payloads are Annex-B H.264 exactly as `MediaCodec` produced them on the
 * Target, so concatenating them yields a playable elementary stream:
 *
 * ```
 * adb pull /sdcard/Android/data/dev.alexdev404.droidctl/files/dumps/<name>.h264
 * ffplay -f h264 <name>.h264
 * ```
 */
class RawStreamDump(private val file: File) : VideoSink {

    private val log = DroidCtlLog.video

    private var output = BufferedOutputStream(file.outputStream())
    private var bytesWritten = 0L
    private var failed = false

    val path: String get() = file.absolutePath
    val sizeBytes: Long get() = bytesWritten

    override fun onSizeChanged(width: Int, height: Int) {
        // Not part of the elementary stream; recorded in the log so the dump's
        // dimensions are recoverable when playing it back.
        log.i("Raw dump ${file.name}: Target video size ${width}x$height")
    }

    override fun onPacket(packet: VideoPacket) {
        if (failed) return
        try {
            output.write(packet.payload)
            bytesWritten += packet.payload.size
        } catch (e: IOException) {
            failed = true
            log.e("Raw dump to ${file.absolutePath} failed after $bytesWritten bytes", e)
        }
    }

    override fun onEndOfStream(cause: Throwable?) {
        runCatching { output.flush(); output.close() }
            .onFailure { log.w("Could not close the raw dump", it) }
        log.i("Raw dump finished: ${file.absolutePath} ($bytesWritten bytes)")
    }
}
