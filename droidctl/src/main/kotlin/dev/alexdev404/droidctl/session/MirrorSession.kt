package dev.alexdev404.droidctl.session

import android.content.Context
import android.view.MotionEvent
import dev.alexdev404.droidctl.DroidCtlLog
import dev.alexdev404.droidctl.adb.AdbClient
import dev.alexdev404.droidctl.transport.ProcessLine
import dev.alexdev404.droidctl.data.DroidCtlPreferences
import dev.alexdev404.droidctl.data.MirrorSettings
import dev.alexdev404.droidctl.debug.DebugSupport
import dev.alexdev404.droidctl.debug.FakeServerEndpoint
import dev.alexdev404.droidctl.input.KeyMapper
import dev.alexdev404.droidctl.input.MotionEventAdapter
import dev.alexdev404.droidctl.input.TouchMapper
import dev.alexdev404.droidctl.adb.DisplaySize
import dev.alexdev404.droidctl.model.ConnectionInfo
import dev.alexdev404.droidctl.model.ConnectionQuality
import dev.alexdev404.droidctl.model.QualityMode
import dev.alexdev404.droidctl.model.KnownTarget
import dev.alexdev404.droidctl.model.TransportKind
import dev.alexdev404.droidctl.transport.DeviceTransport
import dev.alexdev404.droidctl.transport.SshCredentials
import dev.alexdev404.droidctl.transport.TransportFactory
import dev.alexdev404.droidctl.scrcpy.ControlChannel
import dev.alexdev404.droidctl.scrcpy.ControlMessage
import dev.alexdev404.droidctl.scrcpy.ScrcpyConnection
import dev.alexdev404.droidctl.scrcpy.ScrcpyLauncher
import dev.alexdev404.droidctl.scrcpy.PushMeasurement
import dev.alexdev404.droidctl.scrcpy.ScrcpyOptions
import dev.alexdev404.droidctl.scrcpy.ServerDelivery
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
import kotlinx.coroutines.flow.first
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
    private val transports: TransportFactory,
    private val launcher: ScrcpyLauncher,
    private val preferences: DroidCtlPreferences,
    private val scope: CoroutineScope,
) {
    private val log = DroidCtlLog.session

    /** Serialises start and stop: a stop racing a start leaks a tunnel. */
    private val lifecycleMutex = Mutex()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _decoderStats = MutableStateFlow(DecoderStats())
    val decoderStats: StateFlow<DecoderStats> = _decoderStats.asStateFlow()

    private val _serverOutput = MutableStateFlow<List<ProcessLine>>(emptyList())
    val serverOutput: StateFlow<List<ProcessLine>> = _serverOutput.asStateFlow()

    private val _rawDumpPath = MutableStateFlow<String?>(null)
    val rawDumpPath: StateFlow<String?> = _rawDumpPath.asStateFlow()

    private val _quality = MutableStateFlow(ConnectionQuality.UNMEASURED_DEFAULT)

    /** The rung this session is running on. */
    val quality: StateFlow<ConnectionQuality> = _quality.asStateFlow()

    private val _linkMeasurement = MutableStateFlow<PushMeasurement?>(null)

    /** What pushing the server measured about the link, for the debug pane. */
    val linkMeasurement: StateFlow<PushMeasurement?> = _linkMeasurement.asStateFlow()

    private val _network = MutableStateFlow<NetworkSample?>(null)

    /** Live throughput off the video socket. Reported only; nothing acts on it. */
    val network: StateFlow<NetworkSample?> = _network.asStateFlow()

    private val _targetDisplay = MutableStateFlow<DisplaySize?>(null)

    /** The Target's own screen size, which a quality rung is a fraction of. */
    val targetDisplay: StateFlow<DisplaySize?> = _targetDisplay.asStateFlow()

    val surfaceBridge = SurfaceHolderBridge()
    val touchMapper = TouchMapper()

    // --- Live session resources; every one of them is released by teardown ---
    private var serverHandle: ScrcpyServerHandle? = null
    private var fakeEndpoint: FakeServerEndpoint? = null
    private var connection: ScrcpyConnection? = null
    private var controlChannel: ControlChannel? = null
    @Volatile
    private var decoder: VideoDecoder? = null
    private var pump: VideoStreamPump? = null
    private var rawDump: RawStreamDump? = null
    private var serverLogJob: Job? = null
    private var statsJob: Job? = null
    private var viewportJob: Job? = null
    private var startJob: Job? = null
    private var networkJob: Job? = null
    private var bandwidthMonitor: BandwidthMonitor? = null

    /**
     * Bumped for every start attempt and every teardown.
     *
     * The decoder and the video pump report failures asynchronously, so a report
     * can arrive after the attempt it belongs to is already gone. Without a
     * generation to compare against, a dead attempt's end-of-stream tears down
     * the *live* session that replaced it -- which is how a single background /
     * foreground cycle turns into a reconnect that never settles.
     */
    private var generation = 0

    /**
     * The transport this session is running over, held for the whole session.
     *
     * One per attempt rather than one per app: an SSH transport owns a live
     * connection that has to be closed, and a reconnect after the link dropped
     * has to build a new one rather than reuse the dead one.
     */
    private var activeTransport: DeviceTransport? = null
    private var activeTarget: KnownTarget? = null
    private var activeSettings: MirrorSettings = MirrorSettings()

    /** The Target's current video size, as last reported by the stream. */
    private var targetSize: Pair<Int, Int>? = null

    /** True once teardown has begun, so the stream ending is not reported as a failure. */
    @Volatile
    private var stopping = false

    private var reconnectAttempt = 0

    /** When the current session started streaming, for the reconnect budget. */
    private var streamingSinceMillis = 0L

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
        // adb-only: an SSH tunnel dies with the process that opened it, so
        // there is nothing of that kind to survive a crash.
        val adb = transports.adb ?: return
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

    /**
     * adb, for the operations only adb has: pairing, `adb connect`, the device
     * list.
     *
     * Throws rather than returning null because reaching any of these without a
     * working adb means the UI offered an adb-mode action on a Host that failed
     * the adb gate, and a message saying so is far more use than a silent
     * no-op.
     */
    private fun requireAdb(): AdbClient = transports.requireAdb()

    suspend fun pair(host: String, port: Int, code: String): Result<Unit> {
        _state.value = SessionState.Pairing("$host:$port")
        val result = runCatching { requireAdb() }.mapCatching { adb ->
            adb.pair(host, port, code).getOrThrow()
        }
        _state.value = result.fold(
            onSuccess = { SessionState.Idle },
            onFailure = { SessionState.Failed("pair", it.message ?: "Pairing failed", cause = it) },
        )
        return result
    }

    /** Runs `adb connect` and remembers the Target on success. */
    suspend fun connectTarget(host: String, port: Int, name: String?): Result<KnownTarget> {
        _state.value = SessionState.Connecting("$host:$port")
        val adb = runCatching { requireAdb() }.getOrElse { error ->
            _state.value = SessionState.Failed("connect", error.message ?: "adb is unavailable", cause = error)
            return Result.failure(error)
        }
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
                    transport = TransportKind.Adb,
                ).also { preferences.rememberTarget(it) }
            }
            .onSuccess { _state.value = SessionState.Idle }
            .onFailure {
                _state.value =
                    SessionState.Failed("connect", it.message ?: "Could not connect", cause = it)
            }
    }

    /**
     * Verifies an SSH login and remembers the Target on success.
     *
     * The counterpart to [connectTarget]: there is no `adb connect` to run, but
     * the same thing has to happen -- prove the Host can actually reach the
     * Target before handing the user a card that starts a mirroring session.
     *
     * The entry is saved *before* the connection is attempted, because the host
     * key pinned on first use has to attach to a stored Target; a login that
     * fails leaves behind an entry the user can retry or forget, which is the
     * same thing a failed `adb connect` leaves in the adb server.
     */
    suspend fun connectSshTarget(
        host: String,
        port: Int,
        user: String,
        name: String?,
    ): Result<KnownTarget> {
        val account = user.trim().ifBlank { SshCredentials.DEFAULT_USER }
        _state.value = SessionState.Connecting("$account@$host:$port")

        val candidate = KnownTarget(
            name = name?.trim()?.ifBlank { null } ?: "$host:$port",
            host = host,
            port = port,
            lastConnectedAtMillis = System.currentTimeMillis(),
            transport = TransportKind.Ssh,
            sshUser = account,
        )
        preferences.rememberTarget(candidate)
        val stored = storedTarget(candidate)

        return transports.open(stored)
            .mapCatching { transport ->
                // Closed again straight away: this is a probe, and a session
                // started later opens its own connection. Holding an idle SSH
                // session open between screens would only give it time to be
                // dropped by a NAT or an sshd timeout, and be discovered as a
                // failure at the worst moment.
                transport.use {
                    // Cheap, present on every Android build, and it doubles as
                    // proof that the login lands somewhere that can run
                    // commands rather than a shell that immediately exits.
                    val model = it.exec("getprop ro.product.model").getOrNull()?.trim()
                    log.i("SSH login to ${transport.description} succeeded${model?.let { m -> " ($m)" } ?: ""}")
                    model
                }
            }
            .mapCatching { model ->
                val named = stored.copy(
                    name = name?.trim()?.ifBlank { null }
                        ?: model?.ifBlank { null }
                        ?: stored.name,
                )
                preferences.rememberTarget(named)
                // Re-read, so the caller gets the host key that was just pinned.
                storedTarget(named)
            }
            .onSuccess { _state.value = SessionState.Idle }
            .onFailure {
                _state.value =
                    SessionState.Failed("connect", it.message ?: "Could not connect", cause = it)
            }
    }

    /** The persisted entry for [target], or [target] itself if it has gone. */
    private suspend fun storedTarget(target: KnownTarget): KnownTarget =
        preferences.knownTargets.first().firstOrNull { it.serial == target.serial } ?: target

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
        val job = scope.launch {
            lifecycleMutex.withLock {
                if (_state.value.isBusy || _state.value is SessionState.Streaming) {
                    log.w("start() ignored: a session is already ${_state.value.label}")
                    return@withLock
                }
                // Whoever starts a session owns cleaning up after the last one.
                // Teardown is idempotent and a no-op when nothing is live, and
                // doing it here means a stop() that raced this start can safely
                // stand aside rather than tearing down what it just built.
                teardownLocked(disconnectAdb = false)
                stopping = false
                activeTarget = target
                activeSettings = settings
                reconnectAttempt = 0
                _targetDisplay.value = null
                _linkMeasurement.value = null
                startLocked(target, settings)
            }
        }
        startJob = job
    }

    private suspend fun startLocked(target: KnownTarget, settings: MirrorSettings) {
        // Everything this attempt creates is tagged with this number, so a
        // report from a previous attempt can be told apart and dropped.
        generation++
        val sessionGeneration = generation
        val useFake = settings.useFakeServer && DebugSupport.isFakeServerAvailable
        _serverOutput.value = emptyList()

        try {
            if (useFake) {
                _quality.value = (settings.qualityMode as? QualityMode.Fixed)?.quality
                    ?: ConnectionQuality.UNMEASURED_DEFAULT
                val options = buildOptions(settings)
                log.withScid(options.socketName)
                    .i("USE_FAKE_SERVER: routing the connection layer at the bundled fake server")
                val endpoint = withContext(Dispatchers.IO) { DebugSupport.startFakeServer(context) }
                fakeEndpoint = endpoint
                openStream(target, settings, options, endpoint.port, sessionGeneration)
                return
            }

            _state.value =
                SessionState.Preparing(target, "Reaching the Target over ${target.transport.label}")
            val transport = transports.open(target).getOrElse { error ->
                failLocked("open-transport", error, serverOutputLines())
                return
            }
            activeTransport = transport

            _state.value = SessionState.Preparing(target, "Delivering the scrcpy server")

            // Only pushed when the Target does not already have this exact jar.
            // When it is pushed, the transfer doubles as the bandwidth probe --
            // it is the only sizeable transfer before the video stream exists.
            val delivery = launcher.ensureServerOnTarget(
                transport = transport,
                allowSkip = !settings.alwaysPushServer,
            ).getOrElse { error ->
                failLocked("push-server", error, serverOutputLines())
                return
            }
            val fresh = (delivery as? ServerDelivery.Pushed)?.measurement
            if (fresh != null) {
                _linkMeasurement.value = fresh
                preferences.rememberBandwidth(target, fresh.bitsPerSecond)
            }

            if (_targetDisplay.value == null) {
                _state.value = SessionState.Preparing(target, "Reading the Target's screen size")
                // max_size is absolute while a rung is a fraction of the
                // Target's own screen, so that size is needed before launching.
                _targetDisplay.value = readDisplaySize(transport)
            }

            _quality.value = resolveQuality(
                mode = settings.qualityMode,
                freshBitsPerSecond = fresh?.takeIf { it.isMeaningful }?.bitsPerSecond,
                rememberedBitsPerSecond = target.lastMeasuredBitsPerSecond,
                pushWasTooBriefToTime = fresh != null && !fresh.isMeaningful,
            )
            val options = buildOptions(settings)
            val scoped = log.withScid(options.socketName)

            _state.value = SessionState.Preparing(target, "Starting the scrcpy server")
            val handle = launcher.launch(transport, options).getOrElse { error ->
                failLocked("start-server", error, serverOutputLines())
                return
            }
            serverHandle = handle
            serverLogJob = scope.launch {
                handle.output.collect { line ->
                    _serverOutput.value = (_serverOutput.value + line).takeLast(SERVER_LOG_LINES)
                }
            }
            scoped.i(
                "Launching at ${_quality.value.label}" +
                    (options.maxSize.takeIf { it > 0 }?.let { " (max_size=$it)" } ?: "")
            )

            openStream(target, settings, options, handle.hostPort, sessionGeneration)
        } catch (e: kotlinx.coroutines.CancellationException) {
            teardownLocked(disconnectAdb = false)
            throw e
        } catch (e: Throwable) {
            failLocked("start", e, serverOutputLines())
        }
    }

    /**
     * The half of a start that is the same whether the server is real or fake:
     * connect the sockets, wire up the decoder, the control channel and the
     * touch transform, and begin streaming.
     */
    private suspend fun openStream(
        target: KnownTarget,
        settings: MirrorSettings,
        options: ScrcpyOptions,
        hostPort: Int,
        sessionGeneration: Int,
    ) {
        val scoped = log.withScid(options.socketName)
        val info = ConnectionInfo(target.serial, target.transport, hostPort, options.socketName, options)
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

        if (settings.turnScreenOff) {
            // The server restores the display itself when it exits, including on
            // an abrupt disconnection, so there is nothing to undo on teardown.
            scoped.i("Turning the Target's screen off for this session")
            channel.send(ControlMessage.SetDisplayPower(on = false))
        }

        // Prime the transform before the first touch can arrive.
        targetSize = opened.meta.width to opened.meta.height
        refreshViewport()
        viewportJob = scope.launch {
            surfaceBridge.viewSize.collect { refreshViewport() }
        }

        networkJob = scope.launch { publishNetworkSamples(sessionGeneration) }

        streamPump.start()

        streamingSinceMillis = System.currentTimeMillis()
        _state.value = SessionState.Streaming(target, info, opened.meta)
        scoped.i(
            "Streaming from ${target.name} at ${_quality.value.label} " +
                "(${opened.meta.codecName} ${opened.meta.width}x${opened.meta.height})"
        )
    }

    /**
     * The Target's screen size, via `wm size`.
     *
     * Read through the transport rather than through adb, because it is needed
     * in both modes and `wm` is a Target-side command either way. A failure is
     * logged and tolerated: without a size a rung falls back to its absolute
     * cap, which is worse than scaling but far better than not connecting.
     */
    private suspend fun readDisplaySize(transport: DeviceTransport): DisplaySize? = transport
        .exec("wm size")
        .mapCatching { output ->
            AdbClient.parseDisplaySize(output.lines())
                ?: throw IOException("Could not parse `wm size` output: ${output.trim()}")
        }
        .onFailure { log.w("Could not read the Target's display size: ${it.message}") }
        .getOrNull()

    /**
     * The rung to launch on, and a log line saying why.
     *
     * A fixed rung is used as-is and nothing is measured for it -- the
     * measurement only exists to answer a question the user has already
     * answered.
     */
    private fun resolveQuality(
        mode: QualityMode,
        freshBitsPerSecond: Long?,
        rememberedBitsPerSecond: Long?,
        pushWasTooBriefToTime: Boolean,
    ): ConnectionQuality {
        if (mode is QualityMode.Fixed) {
            log.i("Quality: ${mode.quality.label} (chosen in settings, not measured)")
            return mode.quality
        }
        val decision = QualityDecision.automatic(
            freshBitsPerSecond,
            rememberedBitsPerSecond,
            pushWasTooBriefToTime,
        )
        log.i("Quality: ${decision.quality.label} (automatic: ${decision.reason})")
        return decision.quality
    }

    private fun buildOptions(settings: MirrorSettings) = ScrcpyOptions(
        scid = ScrcpyOptions.generateScid(),
        maxSize = _quality.value.maxSizeFor(_targetDisplay.value?.longerSide),
        videoBitRate = _quality.value.bitRate,
        maxFps = settings.maxFps,
        stayAwake = settings.stayAwake,
        showTouches = settings.showTouches,
    )

    /** Publishes throughput for the debug pane. Reports only; nothing acts on it. */
    private suspend fun publishNetworkSamples(sessionGeneration: Int) {
        while (generation == sessionGeneration && !stopping) {
            delay(NETWORK_SAMPLE_INTERVAL_MS)
            _network.value = bandwidthMonitor?.sample() ?: continue
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
            return SessionVideoSink(measured(dump), generation)
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
        val myGeneration = generation
        val videoDecoder = VideoDecoder(scid) { error ->
            scope.launch { onStreamFailure(myGeneration, error) }
        }
        videoDecoder.attachSurface(surface)
        decoder = videoDecoder

        // The Host losing its surface (the user switched apps) is routine, not a
        // session failure: keep decoding, stop rendering, and pick the new
        // surface up when it comes back.
        surfaceBridge.onSurfaceLifecycle = { current ->
            val active = decoder
            if (active != null) {
                if (current != null) active.attachSurface(current) else active.detachSurface()
            }
        }
        statsJob = scope.launch { videoDecoder.stats.collect { _decoderStats.value = it } }
        // Seed the decoder with the size from the handshake; the stream sends
        // another size record on every change.
        videoDecoder.onSizeChanged(meta.width, meta.height)
        return SessionVideoSink(measured(videoDecoder), myGeneration)
    }

    /** Wraps [delegate] in this session's bandwidth monitor. */
    private fun measured(delegate: VideoSink): VideoSink =
        BandwidthMonitor(delegate).also { bandwidthMonitor = it }

    /**
     * Wraps the real sink so the session sees the two things it must react to:
     * a Target size change (the touch transform is stale until it does) and the
     * stream ending (otherwise a dead session keeps reporting "Streaming").
     */
    private inner class SessionVideoSink(
        private val delegate: VideoSink,
        private val generation: Int,
    ) : VideoSink {
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
                    generation,
                    cause ?: IOException("The Target closed the video stream (the scrcpy server exited)"),
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

    /**
     * A stream failed.
     *
     * @param reportedGeneration which start attempt the report came from.
     *   Anything older than the current attempt is stale: the session it refers
     *   to has already been torn down, and acting on it would kill the live one.
     */
    private suspend fun onStreamFailure(reportedGeneration: Int, error: Throwable) {
        if (stopping) return
        lifecycleMutex.withLock {
            // Teardown may have started while this was waiting for the lock.
            if (stopping) return
            if (reportedGeneration != generation) {
                log.d("Ignoring a failure from a previous session attempt: ${error.message}")
                return
            }
            val target = activeTarget

            // A session that streamed for a while before dropping gets a fresh
            // budget; one that fails immediately, over and over, must not
            // reconnect forever.
            val streamedFor = if (streamingSinceMillis == 0L) 0L
            else System.currentTimeMillis() - streamingSinceMillis
            if (streamedFor >= STABLE_SESSION_MS) reconnectAttempt = 0

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
        // Captured before anything else: a start() that lands while this is
        // running installs a newer job, and cancelling *that* would abort the
        // session the user just asked for and strand it at its initial state.
        val jobToCancel = startJob
        // Set before taking the lock so a start still inside the socket retry
        // loop stops treating its own failure as something to reconnect from.
        stopping = true
        jobToCancel?.cancelAndJoin()
        lifecycleMutex.withLock {
            if (startJob !== jobToCancel) {
                // A start() landed while this was waiting. That session is the
                // user's current intent and it cleans up after this one itself,
                // so stopping here would tear down what they just asked for.
                log.d("stop() stood aside for a newer start()")
                return
            }
            startJob = null
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
     * 4. the tunnel (an `adb forward --remove` and its DataStore record, or the
     *    SSH forward and the relay behind it);
     * 5. the transport itself;
     * 6. optionally `adb disconnect`.
     */
    private suspend fun teardownLocked(disconnectAdb: Boolean) {
        stopping = true
        // Anything still in flight from this attempt is now stale.
        generation++
        streamingSinceMillis = 0L
        surfaceBridge.onSurfaceLifecycle = null

        viewportJob?.cancel()
        viewportJob = null

        networkJob?.cancel()
        networkJob = null
        bandwidthMonitor = null
        _network.value = null

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

        val handle = serverHandle
        runCatching { handle?.process?.close() }
            .onFailure { log.w("Could not stop the scrcpy server process", it) }

        runCatching { fakeEndpoint?.close() }
            .onFailure { log.w("Could not stop the fake server", it) }
        fakeEndpoint = null

        // After the server process, so nothing is still writing through it, and
        // before the transport, which is what the tunnel is built on.
        val transport = activeTransport
        if (handle != null && transport != null) {
            runCatching { transport.closeTunnel(handle.tunnel) }
                .onFailure { log.w("Could not close the tunnel (${handle.tunnel})", it) }
        }
        serverHandle = null

        runCatching { transport?.close() }
            .onFailure { log.w("Could not close the transport", it) }
        activeTransport = null

        touchMapper.reset()
        targetSize = null

        if (disconnectAdb) {
            // Only adb has a connection of its own to drop; an SSH session is
            // already gone with the transport closed just above.
            val adb = transports.adb
            activeTarget
                ?.takeIf { it.transport == TransportKind.Adb }
                ?.let { target ->
                    adb?.disconnect(target.serial)
                        ?.onFailure { log.d("adb disconnect ${target.serial}: ${it.message}") }
                }
        }
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 1_000L

        /**
         * How long a session must stream before a later failure is treated as a
         * fresh problem rather than a continuation of the last one.
         */
        const val STABLE_SESSION_MS = 10_000L
        const val SERVER_LOG_LINES = 300
        const val NETWORK_SAMPLE_INTERVAL_MS = 2_000L
        const val SURFACE_TIMEOUT_MS = 10_000L
    }
}
