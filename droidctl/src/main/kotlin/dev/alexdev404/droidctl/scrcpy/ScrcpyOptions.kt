package dev.alexdev404.droidctl.scrcpy

/**
 * The options DroidCtl passes to the scrcpy server.
 *
 * Every key here exists in the pinned server's
 * `server/src/main/java/com/genymobile/scrcpy/Options.java`. The server aborts
 * on an unknown key, so this model is deliberately a closed set rather than a
 * free-form map: an option that does not exist cannot be expressed.
 *
 * v1 mirrors video only. `audio=false` is not a placeholder for a feature to be
 * filled in later at this layer -- adding audio means adding a socket and a
 * decoder, and this option is where that would start.
 */
data class ScrcpyOptions(
    /** 31-bit session id; the abstract socket is named `scrcpy_%08x` from it. */
    val scid: Int,
    val logLevel: LogLevel = LogLevel.Info,
    val maxSize: Int = 0,
    val videoBitRate: Int = DEFAULT_VIDEO_BIT_RATE,
    val maxFps: Int = 0,
    val stayAwake: Boolean = false,
    val showTouches: Boolean = false,
    val powerOn: Boolean = true,
) {
    enum class LogLevel(val wireValue: String) {
        Verbose("verbose"),
        Debug("debug"),
        Info("info"),
        Warn("warn"),
        Error("error"),
    }

    /** The name of the Target-side abstract socket the server will listen on. */
    val socketName: String get() = "scrcpy_%08x".format(scid)

    /**
     * The `key=value` arguments, in the order the reference client emits them.
     *
     * The scrcpy version is *not* included: it is a positional argument that
     * must come first, before any key=value pair.
     */
    fun toArguments(): List<String> = buildList {
        add("scid=%08x".format(scid))
        add("log_level=${logLevel.wireValue}")

        // v1 is video-only. Asking for audio would make the server open a third
        // socket that nothing on the Host reads.
        add("audio=false")
        add("control=true")
        add("video_codec=h264")

        if (maxSize > 0) add("max_size=$maxSize")
        add("video_bit_rate=$videoBitRate")
        if (maxFps > 0) add("max_fps=$maxFps")

        // The server listens and the client connects. The alternative (reverse
        // tunnel) needs the server to reach back to the Host, which does not
        // work over wireless debugging without extra setup.
        add("tunnel_forward=true")

        // Clipboard sync is out of scope for v1. Left on, the server would push
        // unsolicited device messages up the control socket for a clipboard
        // nothing here reads.
        add("clipboard_autosync=false")

        if (stayAwake) add("stay_awake=true")
        if (showTouches) add("show_touches=true")
        if (!powerOn) add("power_on=false")

        // These four default to true server-side; DroidCtl states them because
        // its stream parser depends on all four being on.
        add("send_device_meta=true")
        add("send_frame_meta=true")
        add("send_dummy_byte=true")
        add("send_stream_meta=true")
    }

    companion object {
        /** The server's own default, restated so the debug pane can show it. */
        const val DEFAULT_VIDEO_BIT_RATE = 8_000_000

        /** Where the server jar is pushed on the Target, matching the reference client. */
        const val DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"

        /**
         * Generates a session id.
         *
         * Only 31 bits: `Options.parse` reads the scid with
         * `Integer.parseInt(value, 16)`, which overflows on a value with the top
         * bit set, and then rejects the negative result.
         */
        fun generateScid(random: kotlin.random.Random = kotlin.random.Random.Default): Int =
            random.nextInt() and 0x7FFFFFFF
    }
}
