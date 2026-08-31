# DroidCtl

A native Android app that mirrors and controls a **second** Android device over
wireless ADB, using the scrcpy server as the on-target agent.

Two devices, and the distinction matters everywhere in this code:

| Term | Meaning |
|---|---|
| **Host** | The Android device running DroidCtl. Rooted (Magisk), with the [`adb-ndk`](https://github.com/Magisk-Modules-Repo/adb-ndk) module providing a static `adb` at `/system/xbin/adb`. The device the user holds. |
| **Target** | The Android device being mirrored. Unrooted, stock, unmodified, with *Wireless debugging* enabled. Nothing is permanently installed on it. |

DroidCtl is a GUI wrapper around three things: shelling out to `adb` as root, a
`MediaCodec` decoder rendering to a `Surface`, and a touch/key serializer
speaking scrcpy's control protocol. The novelty is the assembly.

## Build

```
./gradlew :droidctl:assembleDebug        # APK, with the scrcpy server bundled
./gradlew :droidctl:testDebugUnitTest    # golden-vector and touch-mapping tests
```

The repository's `gradle.properties` sets `org.gradle.jvmargs=-Xmx1536m`, which
is sized for building the scrcpy server jar alone. Building this module needs
more, and the more cores a machine has the more parallel workers compete for
that heap. If a build dies with an opaque *"A failure occurred while executing
&lt;something&gt;WorkAction"* and no cause in the summary, that is what it is —
re-run with `--stacktrace` to see the `OutOfMemoryError`, and build with more:

```
./gradlew :droidctl:assembleDebug -Dorg.gradle.jvmargs=-Xmx4g
```

`:droidctl:packageScrcpyServer` builds `:server` (the scrcpy server in this same
repository) and copies the result into the APK's assets as `scrcpy-server.jar`
alongside its SHA-256. The jar is therefore **not committed to git**: it is built
from source on every build, which is what lets the protocol implementation claim
a pinned version.

`:droidctl:checkScrcpyVersionPin` fails the build if
`gradle/libs.versions.toml`'s `scrcpy` version stops matching `:server`'s
`versionName`. The server refuses to start unless the client announces the exact
matching version, so silent drift there would only ever surface as an obscure
runtime abort on the Target.

## Layout

```
adb/       AdbBinary, AdbCommand, AdbClient, AdbDiscovery, RootShellSession
scrcpy/    ScrcpyServerAsset, ScrcpyOptions, ScrcpyLauncher, ScrcpyConnection,
           VideoStream, ControlChannel, ControlMessage
video/     VideoDecoder, VideoSink/VideoStreamPump, SurfaceHolderBridge, RawStreamDump
input/     TouchMapper (+ ViewportMapping), MotionEventAdapter, KeyMapper
session/   MirrorSession, SessionState
ui/        discovery/ pairing/ mirror/ settings/ common/
model/     Target, ConnectionInfo
data/      DroidCtlPreferences (DataStore)
debug/     FakeServerEndpoint; DebugSupport has separate debug/ and release/ bodies
```

`droidctl/src/debug/` holds `FakeScrcpyServer` and the recorded sample stream;
`droidctl/src/release/` holds the release-build stub that reports the fake server
as unavailable.

## Connection quality

scrcpy fixes both the video bit rate and the resolution when the server starts
(`Controller.resizeDisplay` only works for a virtual display), so the choice has
to be made before launch and lasts for the session.

Settings offers a ladder, each rung naming what it costs and what it gives up —
`256 kbps · 25% resolution` through `8 Mbps · full resolution` — plus
**Automatic**. Resolution is a fraction of the Target's own longer side, read
with `wm size`, so a rung means the same thing on a 720p phone and a 1440p one.

Automatic measures the link by **timing the `scrcpy-server.jar` push**, which
has to happen anyway: no extra traffic and no added delay. It then picks the
best rung whose bit rate fits inside 60% of the measurement.

The jar is pushed every session by default, which is what keeps Automatic's
measurement current. On a slow link that costs real time — three quarters of a
megabyte is nearly half a minute at 256 kbps — so **Re-send the server every
session** can be turned off in settings: the Target's copy is checksummed and
left alone when it matches, and Automatic falls back to the figure remembered
from the last real push to that Target. That is a trade the user makes
knowingly, not an optimisation applied behind their back. The margin covers
what the push does not measure — it runs Host to Target while video runs the
other way, and an encoder treats its bit rate as an average it overshoots on
scene changes. A push too brief to time is treated as a fast link, not a slow
one.

The debug pane shows the measurement, the chosen rung, the resulting
`max_size`, and live throughput off the video socket.

## How a session works

1. `adb connect <host>:<port>` — Target appears in `adb devices`.
2. Extract `scrcpy-server.jar` from assets and verify its SHA-256; push it to
   `/data/local/tmp/` only if the Target's copy differs, timing the transfer as
   the bandwidth probe.
3. Read `wm size` and pick the quality rung.
4. Bind an ephemeral local port, then
   `adb forward tcp:<port> localabstract:scrcpy_<scid>`.
5. `adb shell CLASSPATH=... app_process / com.genymobile.scrcpy.Server 4.1 ...`,
   kept as a live handle so its stdout/stderr reach the debug pane.
6. Connect two ordinary `java.net.Socket`s to `127.0.0.1:<port>` — **video
   first, control second** — read the dummy byte, the 64-byte device name, the
   codec id and the first session record.
7. Feed the video socket to `MediaCodec` in async mode, rendering to the mirror
   `SurfaceView`; serialize touches and keys onto the control socket.

Only the adb invocations need root. The data path is unprivileged.

Teardown runs on every exit path, is idempotent, and goes: control socket, video
socket, decoder and its handler thread, the `app_process` invocation, `adb
forward --remove`, and optionally `adb disconnect`. Forwards this app creates are
recorded in DataStore so a crashed run's leftovers can be cleared at next start.

## Verification

Automated, on the JVM, no device needed:

* **Golden vectors** for every control message and for the 12-byte frame header,
  hand-computed from the pinned server sources rather than from the encoder.
* **`TouchMapper` and `ViewportMapping`** over portrait/landscape Host and
  Target, letterboxing on both axes, multi-pointer sequences, and margin taps.
* **Option keys** checked against the `case` labels in the server's
  `Options.parse` — the server aborts on an unknown key.

On a single device, no Target needed:

* **Fake server** (debug builds, *Settings → Use the fake scrcpy server*):
  replays a recorded H.264 stream from a loopback socket using the real
  handshake and framing, exercising sockets → framing → decoder → surface.
* **Raw-dump mode** (*Settings → Raw-dump mode*): writes the post-header payload
  stream to a `.h264` file instead of decoding it, so a socket/framing fault can
  be told from a decoder fault in one step.

Everything else — real pairing, real root, real injection on a Target — is in
[`../docs/MANUAL-TEST.md`](../docs/MANUAL-TEST.md).

## Not in v1

USB/OTG, audio forwarding, clipboard sync, file transfer, UHID keyboard
passthrough, recording to file, multiple simultaneous Targets, and any support
for an unrooted Host. The module boundaries leave room for audio and clipboard
(`ScrcpyOptions` and `ControlMessage` are where each would start), but neither
ships.
