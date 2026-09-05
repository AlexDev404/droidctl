package dev.alexdev404.droidctl.scrcpy

/**
 * How long the handshake waits at each step.
 *
 * The defaults are sized for a Target on the far side of a poor wireless link,
 * not a LAN. Every one of these has to cover a full round trip through the adb
 * server, over the network, to a phone that may also be busy starting a JVM.
 */
data class HandshakeTimeouts(
    /**
     * Connecting to the forwarded Host port.
     *
     * Local, so this only ever fires when the adb server itself is wedged.
     */
    val connectTimeoutMs: Int = 10_000,

    /**
     * Waiting for the server's first byte once the tunnel reaches it.
     *
     * Deliberately long, because this timeout is not a cheap retry: reaching the
     * server means it has already spent its video accept on this socket, so
     * giving up here costs the whole session rather than one attempt. It only
     * fires when the byte is genuinely in flight -- a server that is not up yet
     * shows as end of stream instead, immediately and for free.
     */
    val dummyByteTimeoutMs: Int = 20_000,

    /**
     * Total time the server is given to come up and start listening.
     *
     * Wall clock rather than a count of attempts: what matters is how long the
     * Target has had, and on a slow link the command that launches the server
     * has to cross the network before `app_process` even begins.
     */
    val serverStartupBudgetMs: Long = 60_000,

    /** Gap between attempts while the server is still starting. */
    val retryDelayMs: Long = 200,
)
