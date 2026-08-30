# The scrcpy protocol, as DroidCtl implements it

**Pinned scrcpy version: `4.1`** — the version `:server` in this repository
builds (`server/build.gradle`, `versionName "4.1"`). The pin lives in
`gradle/libs.versions.toml` (`scrcpy = "4.1"`) and
`:droidctl:checkScrcpyVersionPin` fails the build if the two ever diverge.

The client↔server protocol is **not stable across scrcpy releases**: option
names, control-message opcodes and field widths have all changed between
versions. Every constant in `droidctl/src/main/kotlin/.../scrcpy/` was read out
of the sources listed below rather than from documentation, and the server
refuses to start at all unless the client announces the exact matching version
string — that check is deliberate upstream behaviour and DroidCtl does not try
to work around it.

## Sources of truth

| File | What was taken from it |
|---|---|
| `server/src/main/java/com/genymobile/scrcpy/Options.java` | The set of valid `key=value` options |
| `server/src/main/java/com/genymobile/scrcpy/Server.java` | Argument order; the version check |
| `server/src/main/java/com/genymobile/scrcpy/device/DesktopConnection.java` | Socket accept order, socket name, device-meta field |
| `server/src/main/java/com/genymobile/scrcpy/device/Streamer.java` | Stream framing and the packet flags |
| `server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java` | Control-message type ids |
| `server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java` | Control-message field order and widths |
| `server/src/main/java/com/genymobile/scrcpy/control/Controller.java` | How the server interprets touch actions |
| `server/src/main/java/com/genymobile/scrcpy/util/Binary.java` | Fixed-point encodings |
| `server/src/main/java/com/genymobile/scrcpy/util/StringUtils.java` | UTF-8 truncation |
| `app/src/server.c`, `app/src/demuxer.c`, `app/src/control_msg.c` | The reference client's own behaviour |

## Deviations from the implementation spec's reference tables

The spec (`SPEC android scrcpy controller.md`, §4) presents its tables as
"likely-correct but unverified". Four of them are wrong for scrcpy 4.1. In each
case the pinned source wins.

### 1. Frame header flag bits — **spec is wrong**

The spec says bit 63 is the config flag and bit 62 the key-frame flag. In
`Streamer.java` they are:

```java
private static final long PACKET_FLAG_SESSION   = 1L << 63;
private static final long PACKET_FLAG_CONFIG    = 1L << 62;
private static final long PACKET_FLAG_KEY_FRAME = 1L << 61;
```

Bit 63 marks a **session** record, not a config packet. Implementing the spec's
version would read every session record as a config packet, hand its 12 bytes to
`MediaCodec` as SPS/PPS, and misclassify every key frame.

The PTS therefore occupies the low **61** bits, not 62
(`SC_PACKET_PTS_MASK` in `demuxer.c` is `SC_PACKET_FLAG_KEY_FRAME - 1`).

### 2. The video dimensions are not in a codec-meta header — **spec is wrong**

The spec describes reading "codec id (4B), width (4B), height (4B)" as one
header. In scrcpy 4.1, `send_stream_meta=true` writes only the **4-byte codec
id**; the dimensions arrive in a *session record*, which is a 12-byte header
with bit 63 set:

```
byte 0..3   flags:  1000000...0000R   (R = the client-resized flag)
byte 4..7   video width
byte 8..11  video height
```

The first record after the codec id is always a session record. Another one is
sent whenever the Target's video size changes, which is how a rotation is
signalled — so this is not a start-up-only concern: it is the event that must
trigger both a decoder reconfigure and a touch-transform recompute.

### 3. Scroll amounts are normalised by 16 — **spec is incomplete**

The spec's table gives `hscroll(2), vscroll(2)`, which is the right width, but
not the encoding. `ControlMessageReader.parseInjectScrollEvent`:

```java
float hScroll = Binary.i16FixedPointToFloat(dis.readShort()) * 16;
```

The wire value is a signed 16-bit fixed-point number in `[-1, 1]`; the real
range is `[-16, 16]`. The client must divide by 16 and clamp before encoding
(`control_msg.c` does exactly that). Sending an unnormalised value scrolls
sixteen times too far.

### 4. `SET_DISPLAY_POWER` takes a boolean, not a "mode" — **cosmetic**

The spec calls the payload `mode(1)`. `parseSetDisplayPower` reads
`dis.readBoolean()`, so it is one byte, `0` or `1`. Same width; the name in the
spec is misleading.

## Deviations DroidCtl makes on purpose

### Touch actions on the wire

The spec's §5.4 asks for the full `MotionEvent` lifecycle including
`ACTION_POINTER_DOWN` / `ACTION_POINTER_UP`. Those must **not** go on the wire.
`Controller.injectTouch` derives them itself:

```java
// secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
if (action == MotionEvent.ACTION_UP) {
    action = MotionEvent.ACTION_POINTER_UP | (pointerIndex << ACTION_POINTER_INDEX_SHIFT);
} else if (action == MotionEvent.ACTION_DOWN) {
    action = MotionEvent.ACTION_POINTER_DOWN | (pointerIndex << ACTION_POINTER_INDEX_SHIFT);
}
```

`TouchMapper` therefore maps Android's `ACTION_POINTER_DOWN` to
`ACTION_DOWN` (0) and `ACTION_POINTER_UP` to `ACTION_UP` (1) for the finger that
changed. The full lifecycle is still handled — it is just flattened before it is
sent.

### `ACTION_CANCEL` is sent as `ACTION_UP`

`Controller.injectTouch` only clears a pointer from `PointersState` when the
action is `ACTION_UP`:

```java
pointer.setUp(action == MotionEvent.ACTION_UP);
```

A forwarded `ACTION_CANCEL` would leave that pointer pressed on the Target for
the rest of the session. `TouchMapper.onCancel` releases every active pointer
with `ACTION_UP` and zero pressure instead.

### Options DroidCtl does not send

`ScrcpyOptionsTest` asserts every key it emits against the `case` labels in
`Options.parse`, because the server aborts on an unknown key. v1 sends
`audio=false` and `clipboard_autosync=false`: both features are out of scope, and
leaving clipboard sync on would make the server push unsolicited device messages
up a control socket nothing reads.

## Wire reference (verified against scrcpy 4.1)

### Server launch

```
adb -s <serial> push scrcpy-server.jar /data/local/tmp/scrcpy-server.jar
adb -s <serial> forward tcp:<hostPort> localabstract:scrcpy_<scid>
adb -s <serial> shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server 4.1 \
    scid=<8 hex digits> log_level=info audio=false control=true video_codec=h264 \
    video_bit_rate=<n> tunnel_forward=true clipboard_autosync=false \
    send_device_meta=true send_frame_meta=true send_dummy_byte=true send_stream_meta=true
```

`scid` is a random **31-bit** value formatted as `%08x`. `Options.parse` reads it
with `Integer.parseInt(value, 16)`, which overflows on a value with bit 31 set
and then rejects the negative result — so the top bit must be clear
(`scrcpy.c:scrcpy_generate_scid` masks with `0x7FFFFFFF` for the same reason).

### Handshake, with `tunnel_forward=true`

The server listens on the abstract socket; the client connects, in this order:

1. **video** socket — the server writes one `0x00` dummy byte on accepting it.
   Reading that byte is the only proof the tunnel reaches a live server rather
   than a stale `adb forward`.
2. **control** socket — no dummy byte.

Then, on the video socket:

1. 64 bytes, NUL-padded: the device name.
2. 4 bytes, big-endian: the codec id (`"h264"` = `0x68323634`). A value of `0`
   means the Target disabled the stream; `1` means it hit a configuration error.
3. A 12-byte session record with the video dimensions.
4. Then, repeatedly, 12-byte records as described above.

The reference client retries the first connect 100 times, 100 ms apart;
DroidCtl matches that.

### Control messages implemented

| Type | Name | Payload after the type byte | Total |
|---|---|---|---|
| 0 | `INJECT_KEYCODE` | action(1) keycode(4) repeat(4) metaState(4) | 14 |
| 1 | `INJECT_TEXT` | length(4) + UTF-8 bytes (≤ 300) | 5 + n |
| 2 | `INJECT_TOUCH_EVENT` | action(1) pointerId(8) position(12) pressure(2) actionButton(4) buttons(4) | 32 |
| 3 | `INJECT_SCROLL_EVENT` | position(12) hscroll(2) vscroll(2) buttons(4) | 21 |
| 4 | `BACK_OR_SCREEN_ON` | action(1) | 2 |
| 5 | `EXPAND_NOTIFICATION_PANEL` | — | 1 |
| 6 | `EXPAND_SETTINGS_PANEL` | — | 1 |
| 7 | `COLLAPSE_PANELS` | — | 1 |
| 10 | `SET_DISPLAY_POWER` | on(1) | 2 |
| 11 | `ROTATE_DEVICE` | — | 1 |

`position` is x(4, int32) y(4, int32) screenWidth(2, uint16) screenHeight(2,
uint16). The server rescales by the embedded dimensions, so they must come from
the most recent session record — a stale pair after a rotation silently lands
every tap in the wrong place.

`pressure` is unsigned 16-bit fixed-point over `[0, 1]`, with `1.0` encoded as
`0xFFFF` (`Binary.u16FixedPointToFloat` special-cases that value).

All multi-byte fields are big-endian. Every one of these has a hand-computed
golden vector in `droidctl/src/test/kotlin/.../ControlMessageGoldenVectorTest.kt`.
