package dev.alexdev404.droidctl.scrcpy

import dev.alexdev404.droidctl.DroidCtlLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

/**
 * The outbound half of the control socket.
 *
 * Messages are funnelled through a [Channel] and written by a **single**
 * consumer coroutine. Touch events arrive from the UI thread while overlay
 * buttons and IME text arrive from elsewhere; two threads writing to the same
 * socket would interleave halfway through a message and desynchronise the
 * server's reader for the rest of the session.
 *
 * The channel is bounded and drops the oldest message when it overflows. A
 * backed-up control socket means the Target is not keeping up; queueing an
 * unbounded backlog of stale touch coordinates would only make it worse.
 */
class ControlChannel(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val scid: String,
) {
    private val log = DroidCtlLog.proto.withScid(scid)

    private val outbound = Channel<ControlMessage>(
        capacity = CAPACITY,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private var writerJob: Job? = null
    private var drainJob: Job? = null

    @Volatile
    var failure: Throwable? = null
        private set

    fun start() {
        check(writerJob == null) { "ControlChannel already started" }
        writerJob = scope.launch(Dispatchers.IO) { writeLoop(socket.getOutputStream()) }
        drainJob = scope.launch(Dispatchers.IO) { drainLoop() }
    }

    /** Queues [message]. Never blocks; returns false once the channel is closed. */
    fun send(message: ControlMessage): Boolean = outbound.trySend(message).isSuccess

    /** Queues every message in [messages], in order. */
    fun sendAll(messages: List<ControlMessage>) {
        for (message in messages) send(message)
    }

    suspend fun close() {
        outbound.close()
        writerJob?.cancelAndJoin()
        drainJob?.cancelAndJoin()
        writerJob = null
        drainJob = null
    }

    private suspend fun writeLoop(output: OutputStream) {
        try {
            for (message in outbound) {
                val bytes = message.serialize()
                output.write(bytes)
                output.flush()
            }
        } catch (e: IOException) {
            // Not swallowed: without this the symptom is input that silently
            // stops working while video keeps playing, which reads as an input
            // bug rather than a dead socket.
            failure = e
            log.e("Control socket write failed; input to the Target has stopped", e)
        }
    }

    /**
     * Reads and discards anything the server sends back.
     *
     * DroidCtl asks for no device messages (clipboard sync is off and it never
     * sends `GET_CLIPBOARD`), but the socket is bidirectional: if the server
     * ever did write, an undrained receive buffer would eventually block its
     * controller thread and freeze input.
     */
    private suspend fun drainLoop() {
        try {
            val input = socket.getInputStream()
            val scratch = ByteArray(1024)
            while (scope.isActive) {
                val read = input.read(scratch)
                if (read < 0) break
                log.d("Discarded $read bytes of unexpected device messages")
            }
        } catch (e: IOException) {
            // The socket closing during teardown is the normal path here, so
            // this is logged at debug rather than treated as a failure.
            log.d("Control socket reader ended: ${e.message}")
        }
    }

    private companion object {
        const val CAPACITY = 256
    }
}
