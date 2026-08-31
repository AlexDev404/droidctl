package dev.alexdev404.droidctl.model

import kotlin.math.roundToInt

/**
 * A rung on the quality ladder: how much bandwidth to ask the Target's encoder
 * for, and how much of the Target's own resolution to ask it to send.
 *
 * Both are scrcpy *launch* options and cannot be changed while a session runs:
 * `Controller.resizeDisplay` only works for a virtual display (it casts the
 * capture to `NewDisplayCapture`), so a real screen being mirrored keeps
 * whatever it was started with. The rung is therefore chosen once, before the
 * server starts -- by the user, or by [forMeasuredBandwidth] from a measurement
 * of the link.
 *
 * Resolution is a fraction of the Target's own longer side rather than an
 * absolute size, so the same rung means the same thing on a 720p phone and a
 * 1440p one.
 */
enum class ConnectionQuality(
    /** Shown in settings and the debug pane. */
    val label: String,
    /** `video_bit_rate`, in bits per second. */
    val bitRate: Int,
    /** Fraction of the Target's native longer side; 1.0 means no cap at all. */
    val resolutionScale: Float,
) {
    Lowest("256 kbps · 25% resolution", 256_000, 0.25f),
    Low("512 kbps · 35% resolution", 512_000, 0.35f),
    Medium("1 Mbps · 50% resolution", 1_000_000, 0.50f),
    High("2.5 Mbps · 70% resolution", 2_500_000, 0.70f),
    Higher("5 Mbps · 85% resolution", 5_000_000, 0.85f),
    Full("8 Mbps · full resolution", 8_000_000, 1.0f);

    /**
     * The `max_size` to pass to the server for a Target whose longer side is
     * [nativeLongerSide] pixels, or 0 for "do not cap".
     *
     * Rounded down to a multiple of 8: encoders reject or silently adjust odd
     * dimensions, and letting the server discover that is a worse experience
     * than picking a safe number here.
     */
    fun maxSizeFor(nativeLongerSide: Int?): Int {
        if (resolutionScale >= 1f) return NO_LIMIT
        val base = nativeLongerSide?.takeIf { it > 0 } ?: FALLBACK_LONGER_SIDE
        val scaled = (base * resolutionScale).roundToInt()
        return (scaled / ALIGNMENT * ALIGNMENT).coerceAtLeast(MIN_MAX_SIZE)
    }

    companion object {
        const val NO_LIMIT = 0

        /** Assumed longer side when the Target's real size could not be read. */
        const val FALLBACK_LONGER_SIDE = 2400

        private const val ALIGNMENT = 8
        private const val MIN_MAX_SIZE = 320

        /**
         * What Automatic falls back to when the link could not be measured.
         *
         * Mid-ladder on purpose: a wrong guess in either direction is only
         * mildly wrong, where guessing the top on a slow link makes the session
         * unusable and guessing the bottom makes a good link look broken.
         */
        val UNMEASURED_DEFAULT = Medium

        /**
         * The best rung a link of [bitsPerSecond] can be expected to carry.
         *
         * A rung is only chosen if the measurement clears its bit rate with
         * [HEADROOM] to spare. The margin covers what the measurement does not:
         * TCP and adb framing overhead, retransmits, a link whose two directions
         * differ, and the fact that an encoder treats its bit rate as an average
         * and overshoots it on scene changes. Running a rung right at the
         * measured ceiling produces a stream that stutters whenever anything
         * moves.
         */
        fun forMeasuredBandwidth(bitsPerSecond: Long): ConnectionQuality {
            val usable = bitsPerSecond * HEADROOM
            return entries.lastOrNull { it.bitRate <= usable } ?: entries.first()
        }

        /**
         * Fraction of a measured link a rung is allowed to ask for.
         *
         * @see forMeasuredBandwidth
         */
        const val HEADROOM = 0.6

        fun fromName(name: String?): ConnectionQuality? =
            entries.firstOrNull { it.name == name }
    }
}

/** What the user picked in settings. */
sealed interface QualityMode {

    /**
     * Measure the link as the session starts and pick the rung that fits.
     *
     * Measured once, at launch, because scrcpy cannot change either setting
     * afterwards.
     */
    data object Automatic : QualityMode

    /** Always use [quality], whatever the link measures. */
    data class Fixed(val quality: ConnectionQuality) : QualityMode

    /** True when the rung should come from a measurement rather than the user. */
    val isAutomatic: Boolean get() = this is Automatic

    val label: String
        get() = when (this) {
            is Automatic -> "Automatic"
            is Fixed -> quality.label
        }

    /** Stored in DataStore as a single string. */
    fun encode(): String = when (this) {
        is Automatic -> AUTOMATIC
        is Fixed -> quality.name
    }

    companion object {
        private const val AUTOMATIC = "auto"

        fun decode(raw: String?): QualityMode = when {
            raw == null || raw == AUTOMATIC -> Automatic
            else -> ConnectionQuality.fromName(raw)?.let { Fixed(it) } ?: Automatic
        }

        /** Everything the settings picker offers, Automatic first. */
        fun all(): List<QualityMode> =
            listOf(Automatic) + ConnectionQuality.entries.reversed().map { Fixed(it) }
    }
}
