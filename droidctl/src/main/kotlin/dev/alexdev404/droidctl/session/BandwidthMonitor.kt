package dev.alexdev404.droidctl.session

import dev.alexdev404.droidctl.scrcpy.VideoPacket
import dev.alexdev404.droidctl.video.VideoSink

/** What the link looked like over the monitor's window. */
data class NetworkSample(
    /** Bits per second actually arriving off the video socket. */
    val throughputBitsPerSecond: Long,
    /** How far behind the stream is now, relative to its first packet. */
    val driftMs: Long,
    /** How much [driftMs] moved across the window. Positive means falling behind. */
    val driftTrendMs: Long,
    val packets: Int,
    /** Real time spanned by the samples, which is shorter than the nominal window at first. */
    val spanMs: Long,
)

/**
 * Watches the video stream to work out whether the link is keeping up.
 *
 * The obvious signal, throughput, is a bad one on its own: a Target showing a
 * static screen sends almost nothing, so low throughput means "nothing is
 * happening" far more often than it means "the link is saturated".
 *
 * The signal that does distinguish them is **drift**. Every packet carries the
 * Target's own presentation timestamp; comparing it against the moment the
 * packet arrives here gives a figure whose absolute value is meaningless (the
 * two clocks are unrelated) but whose *movement* is not. Steady drift means the
 * link is carrying the stream as fast as the Target produces it. Drift that
 * climbs means packets are queueing somewhere between the encoder and here,
 * which is what running out of bandwidth looks like.
 *
 * A decorator rather than something inside the decoder, so it still measures in
 * raw-dump mode, where there is no decoder at all.
 */
class BandwidthMonitor(
    private val delegate: VideoSink,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowNanos: () -> Long = System::nanoTime,
) : VideoSink {

    private class Entry(val atMs: Long, val bytes: Int, val driftMs: Long)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** Drift of the first media packet; every later drift is relative to it. */
    private var baselineMs: Long? = null

    override fun onSizeChanged(width: Int, height: Int) = delegate.onSizeChanged(width, height)

    override fun onEndOfStream(cause: Throwable?) = delegate.onEndOfStream(cause)

    override fun onPacket(packet: VideoPacket) {
        record(packet)
        delegate.onPacket(packet)
    }

    private fun record(packet: VideoPacket) {
        val atMs = nowNanos() / 1_000_000
        synchronized(lock) {
            // Config packets carry SPS/PPS and no timestamp, so they say nothing
            // about timing. Their bytes still count towards throughput.
            val drift = if (packet.isConfig) {
                entries.lastOrNull()?.driftMs ?: 0L
            } else {
                val raw = atMs - packet.ptsUs / 1_000
                val baseline = baselineMs ?: raw.also { baselineMs = it }
                raw - baseline
            }
            entries.addLast(Entry(atMs, packet.payload.size, drift))
            val cutoff = atMs - windowMs
            while (entries.isNotEmpty() && entries.first().atMs < cutoff) {
                entries.removeFirst()
            }
        }
    }

    /**
     * The current view of the link, or null until there is enough to say
     * anything.
     *
     * Throughput is measured over the real span between the first and last
     * sample rather than the nominal window, so a window that has only just
     * started filling is not reported as a slow link.
     */
    fun sample(): NetworkSample? = synchronized(lock) {
        val first = entries.firstOrNull() ?: return null
        val last = entries.last()
        val spanMs = last.atMs - first.atMs
        if (spanMs <= 0) return null
        // Enough packets, or few packets spread over enough time. A static
        // screen produces only a handful of packets per window, and refusing to
        // report on those would blank the debug pane in exactly the situation
        // someone is most likely to be staring at it.
        if (entries.size < MIN_PACKETS && spanMs < windowMs / 2) return null

        val bytes = entries.sumOf { it.bytes.toLong() }
        NetworkSample(
            throughputBitsPerSecond = bytes * 8 * 1_000 / spanMs,
            driftMs = last.driftMs,
            driftTrendMs = last.driftMs - first.driftMs,
            packets = entries.size,
            spanMs = spanMs,
        )
    }

    /** Forgets everything. Used when a new session starts on the same monitor. */
    fun reset() = synchronized(lock) {
        entries.clear()
        baselineMs = null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 4_000L

        /** Below this, throughput and trend are noise unless the span is long. */
        private const val MIN_PACKETS = 8
    }
}
