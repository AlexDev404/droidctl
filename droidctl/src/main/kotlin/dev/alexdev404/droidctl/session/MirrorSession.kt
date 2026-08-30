package dev.alexdev404.droidctl.session

import android.content.Context
import android.view.MotionEvent
import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.adb.AdbClient
import dev.alexdev404.droidctl.adb.ProcessLine
import dev.alexdev404.droidctl.data.DroidCtlPreferences
import dev.alexdev404.droidctl.data.MirrorSettings
import dev.alexdev404.droidctl.debug.DebugSupport
import dev.alexdev404.droidctl.debug.FakeServerEndpoint
import dev.alexdev404.droidctl.input.KeyMapper
import dev.alexdev404.droidctl.input.MotionEventAdapter
import dev.alexdev404.droidctl.input.TouchMapper
import dev.alexdev404.droidctl.model.ConnectionInfo
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.scrcpy.ControlChannel
import dev.alexdev404.droidctl.scrcpy.ControlMessage
import dev.alexdev404.droidctl.scrcpy.ScrcpyConnection
import dev.alexdev404.droidctl.scrcpy.ScrcpyLauncher
import dev.alexdev404.droidctl.scrcpy.ScrcpyOptions
import dev.alexdev404.droidctl.scrcpy.ScrcpyServerHandle
import dev.alexdev404.droidctl.video.DecoderStats
import dev.alexdev404.droidctl.video.RawStreamDump
import dev.alexdev404.droidctl.video.SurfaceHolderBridge
import dev.alexdev404.droidctl.video.VideoDecoder
import dev.alexdev404.droidctl.video.VideoSink
import dev.alexdev404.droidctl.video.VideoStreamPump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates a whole mirroring session: adb, the scrcpy server, the sockets,
 * the decoder and the control channel.
 *
 * This is the one intentionally stateful object in the app. Everything it drives
 * is otherwise a value or a narrow component, and keeping the ordering rules in
 * one place is what makes teardown auditable.
 */
class MirrorSession(
    private val context: Context,
    private val adb: AdbClient,
    private val launcher: ScrcpyLauncher,
    private val preferences: DroidCtlPreferences,
    private val scope: CoroutineScope,
) {
    private val log = DroidCtlLog.session

    /** Serialises start and stop: a stop racing a start leaks a forward. */
    private val lifecycleMutex = Mutex()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _decoderStats = MutableStateFlow(DecoderStats())
    val decoderStats: StateFlow<DecoderStats> = _decoderStats.asStateFlow()

    private val _serverOutput = MutableStateFlow<List<ProcessLine>>(emptyList())
    val serverOutput: StateFlow<List<ProcessLine>> = _serverOutput.asStateFlow()

    private val _rawDumpPath = MutableStateFlow<String?>(null)
    val rawDumpPath: StateFlow<String?> = _rawDumpPath.asStateFlow()

    val surfaceBridge = SurfaceHolderBridge()
    val touchMapper = TouchMapper()

    // --- Live session resources; every one of them is released by teardown ---
    private var serverHandle: ScrcpyServerHandle? = null
    private var fakeEndpoint: FakeServerEndpoint? = null
    private var connection: ScrcpyConnection? = null
    private var controlChannel: ControlChannel? = null
    private var decoder: VideoDecoder? = null
    private var pump: VideoStreamPump? = null
    private var rawDump: RawStreamDump? = null
    private var serverLogJob: Job? = null
    private var statsJob: Job? = null
    private var viewportJob: Job? = null
    private var startJob: Job? = null
    private var activeForward: Pair<String, Int>? = null
    private var activeTarget: KnownTarget? = null
    private var activeSettings: MirrorSettings = MirrorSettings()

    /** The Target's current video size, as last reported by the stream. */
    private var targetSize: Pair<Int, Int>? = null

    /** True once teardown has begun, so the stream ending is not reported as a failure. */
    @Volatile
    private var stopping = false

    private var reconnectAttempt = 0

    // ------------------------------------------------------------------
    // Startup housekeeping
    // ------------------------------------------------------------------

    /**
     * Removes forwards a previous run of this app created and never cleaned up.
     *
     * They survive a crash or a force-stop because they live in the adb server,
     * not in this process, and a leftover on the port the next session picks
     * makes that session fail in a way that looks like the Target's fault.
     */
    suspend fun clearStaleForwards() {
        val recorded = preferences.recordedForwards()
        if (recorded.isEmpty()) return
        log.i("Clearing ${recorded.size} stale adb forward(s) from a previous run")
        for ((serial, port) in recorded) {
            adb.removeForward(serial, port)
                .onFailure { log.d("Stale forward tcp:$port on $serial was already gone: ${it.message}") }
        }
        preferences.clearAllForwardRecords()
    }

    // ------------------------------------------------------------------
    // Connect / pair
    // ------------------------------------------------------------------

    suspend fun pair(host: String, port: Int, code: String): Result<Unit> {
        _state.value = SessionState.Pairing("$host:$port")
        val result = adb.pair(host, port, code)
        _state.value = result.fold(
            onSuccess = { SessionState.Idle },
            onFailure = { SessionState.Failed("pair", it.message ?: "Pairing failed", cause = it) },
        )
        return result
    }

    /** Runs `adb connect` and remembers the Target on success. */
    suspend fun connectTarget(host: String, port: Int, name: String?): Result<KnownTarget> {
        _state.value = SessionState.Connecting("$host:$port")
        return adb.connect(host, port)
            .mapCatching { serial ->
                val devices = adb.devices().getOrDefault(emptyList())
                val device = devices.firstOrNull { it.serial == serial }
                if (device != null && !device.state.isUsable) {
                    throw IOException(device.remediation ?: "The Target is not ready (${device.state})")
                }
                KnownTarget(
                    name = name ?: device?.displayName ?: serial,
                    host = host,
                    port = port,
                    lastConnectedAtMillis = System.currentTimeMillis(),
                ).also { preferences.rememberTarget(it) }
            }
            .onSuccess { _state.value = SessionState.Idle }
            .onFailure {
                _state.value =
                    SessionState.Failed("connect", it.message ?: "Could not connect", cause = it)
            }
    }

    // ------------------------------------------------------------------
    // Mirroring
    // ------------------------------------------------------------------

    /**
     * Starts mirroring [target].
     *
     * Runs on the session's own scope rather than the caller's, and the job is
     * kept so that [stop] can cancel a start that is still in flight -- the
     * socket retry loop alone can take ten seconds, and a user who taps Cancel
     * should not wait it out.
     *
     * Any failure tears down whatever was already built, so a failed start never
     * leaves a forward or a server process behind.
     */
    fun start(target: KnownTarget, settings: MirrorSettings) {
        startJob = scope.launch {
            lifecycleMutex.withLock {
                if (_state.value.isBusy || _state.value is SessionState.Streaming) {
                    log.w("start() ignored: a session is already ${_state.value.label}")
                    return@withLock
                }
                stopping = false
                activeTarget = target
                activeSettings = settings
                reconnectAttempt = 0
                startLocked(target, settings)
            }
        }
    }

    private suspend fun startLocked(target: KnownTarget, settings: MirrorSettings) {
        val options = ScrcpyOptions(
            scid = ScrcpyOptions.generateScid(),
            maxSize = settings.maxSize,
            videoBitRate = settings.videoBitRate,
            maxFps = settings.maxFps,
            stayAwake = settings.stayAwake,
            showTouches = settings.showTouches,
        )
        val scoped = log.withScid(options.socketName)
        val useFake = settings.useFakeServer && DebugSupport.isFakeServerAvailable
        _serverOutput.value = emptyList()

        try {
            val hostPort: Int
            if (useFake) {
                scoped.i("USE_FAKE_SERVER: routing the connection layer at the bundled fake server")
                val endpoint = withContext(Dispatchers.IO) { DebugSupport.startFakeServer(context) }
                fakeEndpoint = endpoint
                hostPort = endpoint.port
            } else {
                _state.value = SessionState.PushingServer(target)
                val handle = launcher.launch(target.serial, options).getOrElse { error ->
                    failLocked("start-server", error, serverOutputLines())
                    return
                }
                serverHandle = handle
                hostPort = handle.hostPort
                activeForward = handle.serial to handle.hostPort
                preferences.recordForward(handle.serial, handle.hostPort)

                serverLogJob = scope.launch {
                    handle.output.collect { line ->
                        _serverOutput.value = (_serverOutput.value + line).takeLast(SERVER_LOG_LINES)
                    }
                }
            }

            val info = ConnectionInfo(target.serial, hostPort, options.socketName, options)
            _state.value = SessionState.StartingServer(target, info)
            _state.value = SessionState.AwaitingSockets(target, info)

            val opened = ScrcpyConnection.open(
                hostPort = hostPort,
                scid = options.socketName,
                serverDiagnostics = ::serverDiagnostics,
            ).getOrElse { error ->
                failLocked("await-sockets", error, serverOutputLines())
                return
            }
            connection = opened

            val sink = buildSink(options.socketName, settings, opened.meta)
            val streamPump = VideoStreamPump(opened.videoInput, sink, options.socketName)
            pump = streamPump

            val channel = ControlChannel(opened.controlSocket, scope, options.socketName)
            channel.start()
            controlChannel = channel

            // Prime the transform before the first touch can arrive.
            targetSize = opened.meta.width to opened.meta.height
            refreshViewport()
            viewportJob = scope.launch {
                surfaceBridge.viewSize.collect { refreshViewport() }
            }

            streamPump.start()

            reconnectAttempt = 0
            _state.value = SessionState.Streaming(target, info, opened.meta)
            scoped.i(
                "Streaming from ${target.name} " +
                    "(${opened.meta.codecName} ${opened.meta.width}x${opened.meta.height})"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            teardownLocked(disconnectAdb = false)
            throw e
        } catch (e: Throwable) {
            failLocked("start", e, serverOutputLines())
        }
    }

    /**
     * Builds the video sink for this session.
     *
     * In raw-dump mode the decoder is not created at all rather than teed off:
     * the whole point of the mode is to answer "are the bytes coming off the
     * socket correct?" with no decoder anywhere in the picture.
     */
    private suspend fun buildSink(
        scid: String,
        settings: MirrorSettings,
        meta: dev.alexdev404.droidctl.scrcpy.TargetMeta,
    ): VideoSink {
        if (settings.rawDumpEnabled) {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "dumps")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dump = RawStreamDump(File(dir, "droidctl-$stamp-$scid.h264"))
            rawDump = dump
            _rawDumpPath.value = dump.path
            log.i("Raw-dump mode: writing the payload stream to ${dump.path} (no decoder)")
            return SessionVideoSink(dump)
        }

        // The surface may not exist yet if the mirror screen has not finished
        // composing; waiting is correct, configuring a decoder without one is
        // not. Bounded, because this wait holds the lifecycle lock: an
        // unbounded one would make a surface that never arrives hang teardown
        // too.
        val surface = withTimeoutOrNull(SURFACE_TIMEOUT_MS) { surfaceBridge.awaitSurface() }
            ?: throw IOException(
                "No mirror surface appeared within ${SURFACE_TIMEOUT_MS}ms; there is nothing to " +
                    "render the Target's video into"
            )
        val videoDecoder = VideoDecoder(scid) { error -> scope.launch { onStreamFailure(error) } }
        videoDecoder.attachSurface(surface)
        decoder = videoDecoder
        statsJob = scope.launch { videoDecoder.stats.collect { _decoderStats.value = it } }
        // Seed the decoder with the size from the handshake; the stream sends
        // another size record on every change.
        videoDecoder.onSizeChanged(meta.width, meta.height)
        return SessionVideoSink(videoDecoder)
    }

    /**
     * Wraps the real sink so the session sees the two things it must react to:
     * a Target size change (the touch transform is stale until it does) and the
     * stream ending (otherwise a dead session keeps reporting "Streaming").
     */
    private inner class SessionVideoSink(private val delegate: VideoSink) : VideoSink {
        override fun onSizeChanged(width: Int, height: Int) {
            targetSize = width to height
            refreshViewport()
            delegate.onSizeChanged(width, height)
        }

        override fun onPacket(packet: dev.alexdev404.droidctl.scrcpy.VideoPacket) =
            delegate.onPacket(packet)

        override fun onEndOfStream(cause: Throwable?) {
            delegate.onEndOfStream(cause)
            if (stopping) return
            scope.launch {
                onStreamFailure(
                    cause ?: IOException("The Target closed the video stream (the scrcpy server exited)")
                )
            }
        }
    }

    private fun refreshViewport() {
        val (width, height) = targetSize ?: return
        val size = surfaceBridge.viewSize.value
        touchMapper.updateMapping(size.width, size.height, width, height)
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /** Forwards a Host touch event to the Target. Returns true if it was consumed. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val channel = controlChannel ?: return false
        val messages = MotionEventAdapter.toMessages(event, touchMapper)
        if (messages.isEmpty()) return false
        channel.sendAll(messages)
        return true
    }

    /** Sends already-built control messages (overlay buttons, IME text). */
    fun send(messages: List<ControlMessage>) {
        controlChannel?.sendAll(messages)
    }

    fun sendText(text: String) = send(KeyMapper.text(text))

    val isControllable: Boolean get() = controlChannel != null

    // ------------------------------------------------------------------
    // Failure and reconnect
    // ------------------------------------------------------------------

    private suspend fun onStreamFailure(error: Throwable) {
        if (stopping) return
        lifecycleMutex.withLock {
            // Teardown may have started while this was waiting for the lock.
            if (stopping) return
            val target = activeTarget
            if (target == null || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                failLocked("streaming", error, serverOutputLines())
                return
            }
            reconnectAttempt++
            log.w("Stream failed; reconnect attempt $reconnectAttempt of $MAX_RECONNECT_ATTEMPTS", error)
            _state.value = SessionState.Reconnecting(
                target,
                reconnectAttempt,
                error.message ?: error::class.java.simpleName,
            )
            teardownLocked(disconnectAdb = false)
            delay(RECONNECT_DELAY_MS * reconnectAttempt)
            stopping = false
            startLocked(target, activeSettings)
        }
    }

    /** Must be called with [lifecycleMutex] held. */
    private suspend fun failLocked(stage: String, error: Throwable, serverLines: List<String>) {
        log.e("Session failed during $stage", error)
        teardownLocked(disconnectAdb = false)
        _state.value = SessionState.Failed(
            stage = stage,
            message = error.message ?: error::class.java.simpleName,
            serverOutput = serverLines,
            cause = error,
        )
    }

    private fun serverDiagnostics(): String = serverOutputLines().joinToString("\n")

    private fun serverOutputLines(): List<String> =
        (serverHandle?.snapshot()?.map { it.text } ?: emptyList()) +
            (fakeEndpoint?.log() ?: emptyList())

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /**
     * Stops the session from a caller that is about to go away.
     *
     * Teardown runs on the session's own scope, not the caller's: a composition
     * scope is cancelled the moment the screen leaves, which would abandon the
     * teardown halfway and leak the forward and the server process.
     */
    fun requestStop(disconnectAdb: Boolean = false) {
        scope.launch { stop(disconnectAdb) }
    }

    /**
     * Stops the session.
     *
     * Safe to call from any state, any number of times, including from
     * `onDestroy` while a start is still in flight.
     */
    suspend fun stop(disconnectAdb: Boolean = false) {
        // Set before taking the lock so a start still inside the socket retry
        // loop stops treating its own failure as something to reconnect from.
        stopping = true
        startJob?.cancelAndJoin()
        startJob = null
        lifecycleMutex.withLock {
            if (_state.value == SessionState.Idle && serverHandle == null && connection == null) return
            teardownLocked(disconnectAdb)
            _state.value = SessionState.Stopped
        }
    }

    /**
     * Releases every resource, in the order that produces the fewest spurious
     * errors, and tolerates each one already being gone.
     *
     * 1. control socket, then video socket -- the server's controller stops
     *    before its encoder loses the socket it writes to;
     * 2. the decoder and its handler thread;
     * 3. the `app_process` invocation on the Target;
     * 4. `adb forward --remove`, and its record in DataStore;
     * 5. optionally `adb disconnect`.
     */
    private suspend fun teardownLocked(disconnectAdb: Boolean) {
        stopping = true

        viewportJob?.cancel()
        viewportJob = null

        // Stop reading before the socket goes away so the pump reports a
        // teardown rather than a stream failure.
        pump?.stop()

        runCatching { controlChannel?.close() }
            .onFailure { log.w("Could not close the control channel", it) }
        controlChannel = null

        runCatching { connection?.close() }
            .onFailure { log.w("Could not close the scrcpy sockets", it) }
        connection = null

        pump?.join()
        pump = null

        statsJob?.cancel()
        statsJob = null

        runCatching { decoder?.release() }
            .onFailure { log.w("Could not release the decoder", it) }
        decoder = null

        rawDump = null

        serverLogJob?.cancel()
        serverLogJob = null

        runCatching { serverHandle?.process?.close() }
            .onFailure { log.w("Could not stop the scrcpy server process", it) }

        runCatching { fakeEndpoint?.close() }
            .onFailure { log.w("Could not stop the fake server", it) }
        fakeEndpoint = null

        activeForward?.let { (serial, port) ->
            adb.removeForward(serial, port)
                .onFailure { log.w("Could not remove forward tcp:$port on $serial: ${it.message}") }
            preferences.clearForwardRecord(serial, port)
        }
        activeForward = null
        serverHandle = null

        touchMapper.reset()
        targetSize = null

        if (disconnectAdb) {
            activeTarget?.let { target ->
                adb.disconnect(target.serial)
                    .onFailure { log.d("adb disconnect ${target.serial}: ${it.message}") }
            }
        }
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 1_000L
        const val SERVER_LOG_LINES = 300
        const val SURFACE_TIMEOUT_MS = 10_000L
    }
}
