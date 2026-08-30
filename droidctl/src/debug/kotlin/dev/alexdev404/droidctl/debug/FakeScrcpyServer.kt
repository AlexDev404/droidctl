package dev.alexdev404.droidctl.debug

import android.content.Context
import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.scrcpy.VideoStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * A stand-in for the scrcpy server running on a Target.
 *
 * It listens on a loopback port, speaks the real handshake and the real frame
 * framing, and replays a recorded H.264 stream from `src/debug/assets`. That
 * covers the entire video path -- sockets, dummy byte, device meta, codec id,
 * session record, frame headers, decoder, surface -- on a single device with no
 * Target, no root and no adb involved.
 *
 * What it deliberately does *not* cover: adb itself, the tunnel, the server
 * launch, and input injection. Those need real hardware; see
 * `docs/MANUAL-TEST.md`.
 */
class FakeScrcpyServer(private val context: Context) : FakeServerEndpoint {

    private val log = DroidCtlLog.server
    private val lines = Collections.synchronizedList(mutableListOf<String>())

    private lateinit var serverSocket: ServerSocket
    private var thread: Thread? = null

    @Volatile
    private var running = false

    override val port: Int get() = serverSocket.localPort

    override fun log(): List<String> = synchronized(lines) { lines.toList() }

    fun start() {
        serverSocket = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        running = true
        say("FakeScrcpyServer listening on 127.0.0.1:$port")
        thread = Thread({ serve() }, "DroidCtl-FakeServer").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        runCatching { thread?.join(1_000) }
        thread = null
        say("FakeScrcpyServer stopped")
    }

    private fun serve() {
        var video: Socket? = null
        var control: Socket? = null
        try {
            // Same accept order as DesktopConnection.open with tunnel_forward:
            // video first (and it carries the dummy byte), then control.
            video = serverSocket.accept()
            video.tcpNoDelay = true
            video.getOutputStream().write(0)
            video.getOutputStream().flush()
            say("Video socket accepted; dummy byte sent")

            control = serverSocket.accept()
            say("Control socket accepted")
            startControlDrain(control)

            val packets = loadStream()
            val output = DataOutputStream(video.getOutputStream().buffered())

            writeDeviceMeta(output, DEVICE_NAME)
            output.writeInt(VideoStream.CODEC_ID_H264)
            writeSessionRecord(output, WIDTH, HEIGHT)
            output.flush()
            say("Handshake sent: $DEVICE_NAME, h264, ${WIDTH}x$HEIGHT, ${packets.size} packets")

            var index = 0
            var ptsUs = 0L
            val frameIntervalUs = 1_000_000L / FPS
            while (running) {
                val packet = packets[index % packets.size]
                index++
                if (packet.isConfig) {
                    writeFrameHeader(output, pts = 0, config = true, keyFrame = false, size = packet.data.size)
                } else {
                    writeFrameHeader(
                        output,
                        pts = ptsUs,
                        config = false,
                        keyFrame = packet.isKeyFrame,
                        size = packet.data.size,
                    )
                    ptsUs += frameIntervalUs
                }
                output.write(packet.data)
                output.flush()
                if (!packet.isConfig) Thread.sleep(frameIntervalUs / 1000)
            }
        } catch (e: Exception) {
            if (running) {
                say("FakeScrcpyServer failed: $e")
                log.e("Fake server failed", e)
            }
        } finally {
            runCatching { control?.close() }
            runCatching { video?.close() }
        }
    }

    private fun startControlDrain(control: Socket) {
        Thread({
            runCatching {
                val input = control.getInputStream()
                val scratch = ByteArray(1024)
                var total = 0L
                while (running) {
                    val read = input.read(scratch)
                    if (read < 0) break
                    total += read
                    say("Control channel received $total bytes so far")
                }
            }
        }, "DroidCtl-FakeServer-Control").apply { isDaemon = true }.start()
    }

    /** `DesktopConnection.sendDeviceMeta`: 64 bytes, NUL-padded. */
    private fun writeDeviceMeta(output: DataOutputStream, name: String) {
        val buffer = ByteArray(DEVICE_NAME_FIELD_LENGTH)
        val bytes = name.toByteArray(StandardCharsets.UTF_8)
        bytes.copyInto(buffer, 0, 0, minOf(bytes.size, DEVICE_NAME_FIELD_LENGTH - 1))
        output.write(buffer)
    }

    /** `Streamer.writeSessionMeta`: flags(4) width(4) height(4), MSB set. */
    private fun writeSessionRecord(output: DataOutputStream, width: Int, height: Int) {
        output.writeInt((VideoStream.FLAG_SESSION ushr 32).toInt())
        output.writeInt(width)
        output.writeInt(height)
    }

    /** `Streamer.writeFrameMeta`: ptsAndFlags(8) size(4). */
    private fun writeFrameHeader(
        output: OutputStream,
        pts: Long,
        config: Boolean,
        keyFrame: Boolean,
        size: Int,
    ) {
        var ptsAndFlags = if (config) VideoStream.FLAG_CONFIG else pts
        if (!config && keyFrame) ptsAndFlags = ptsAndFlags or VideoStream.FLAG_KEY_FRAME
        val data = DataOutputStream(output)
        data.writeLong(ptsAndFlags)
        data.writeInt(size)
    }

    private data class SamplePacket(val data: ByteArray, val isConfig: Boolean, val isKeyFrame: Boolean)

    /**
     * Splits the recorded Annex-B stream into scrcpy-shaped packets.
     *
     * The leading parameter sets (SPS/PPS) become one config packet; every VCL
     * NAL, together with any non-VCL NALs preceding it, becomes one media
     * packet. That is the shape `MediaCodec` on a Target produces.
     */
    private fun loadStream(): List<SamplePacket> {
        val bytes = context.assets.open(SAMPLE_ASSET).use { it.readBytes() }
        val nals = splitAnnexB(bytes)
        require(nals.isNotEmpty()) { "$SAMPLE_ASSET is not an Annex-B H.264 stream" }

        val packets = mutableListOf<SamplePacket>()
        val pending = mutableListOf<ByteArray>()
        for (nal in nals) {
            val type = nal[startCodeLength(nal)].toInt() and 0x1F
            pending += nal
            // Parameter sets and SEI are not frames; they ride along with the
            // next VCL NAL (or become the config packet, for the leading ones).
            if (type != NAL_NON_IDR && type != NAL_IDR) continue
            val payload = pending.reduce { a, b -> a + b }
            pending.clear()
            if (packets.isEmpty()) {
                // The parameter sets that precede the first frame go out as a
                // config packet of their own, exactly as MediaCodec emits them.
                val configEnd = payload.size - nal.size
                if (configEnd > 0) {
                    packets += SamplePacket(payload.copyOfRange(0, configEnd), isConfig = true, isKeyFrame = false)
                }
                packets += SamplePacket(nal, isConfig = false, isKeyFrame = type == NAL_IDR)
            } else {
                packets += SamplePacket(payload, isConfig = false, isKeyFrame = type == NAL_IDR)
            }
        }
        return packets
    }

    /** Splits an Annex-B stream into NAL units, each keeping its start code. */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val offsets = mutableListOf<Int>()
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
                // A four-byte start code is a three-byte one preceded by a zero.
                offsets += if (i > 0 && bytes[i - 1].toInt() == 0) i - 1 else i
                i += 3
            } else {
                i++
            }
        }
        return offsets.mapIndexed { index, offset ->
            val end = offsets.getOrNull(index + 1) ?: bytes.size
            bytes.copyOfRange(offset, end)
        }
    }

    /** 3 for `00 00 01`, 4 for `00 00 00 01`. */
    private fun startCodeLength(nal: ByteArray): Int = if (nal[2].toInt() == 1) 3 else 4

    private fun say(message: String) {
        synchronized(lines) {
            lines += message
            while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        }
        log.d(message)
    }

    private companion object {
        const val SAMPLE_ASSET = "sample-stream.h264"
        const val DEVICE_NAME = "FakeScrcpyServer (recorded stream)"
        const val DEVICE_NAME_FIELD_LENGTH = 64
        const val WIDTH = 480
        const val HEIGHT = 960
        const val FPS = 30
        const val MAX_LOG_LINES = 200

        const val NAL_NON_IDR = 1
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
    }
}
