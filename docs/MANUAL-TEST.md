# DroidCtl manual test checklist

Everything in this list needs two physical devices and cannot be covered by the
automated tests. Work through it in order: a failure at step *n* makes every
later step meaningless.

**Host** — the rooted device running DroidCtl (Magisk + the `adb-ndk` module).
**Target** — the stock, unrooted device being mirrored, with *Wireless
debugging* enabled in Developer options.

Both must be on the same network, and that network must not have client
isolation enabled (a lot of guest and hotel Wi-Fi does).

Where a step names a log tag, open **Settings → Open the debug pane** on the
Host; every DroidCtl log line lands there as well as in logcat. `adb logcat` on
the Host works too if you have a third machine.

---

## 1. First-run gate

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 1.1 | Launch DroidCtl for the first time | Magisk prompts for superuser access; grant it | `DroidCtl/Adb` |
| 1.2 | Watch the gate screen | It reaches "Ready." and prints the output of `adb version` | `DroidCtl/Adb` |
| 1.3 | Deny superuser instead (revoke it in Magisk, force-stop, relaunch) | "This app requires root", with the Magisk remediation. **Not** a blank screen | `DroidCtl/Adb` |
| 1.4 | Temporarily rename `/system/xbin/adb` (a Magisk module change, so reboot) | "No adb binary found", naming the adb-ndk module and its URL, listing the paths searched | `DroidCtl/Adb` |

If 1.2 fails, nothing else can work. The message shows adb's own stderr
verbatim; that text is the diagnosis.

## 2. Pairing

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 2.1 | On the Target: Developer options → Wireless debugging → **Pair device with pairing code** | The Target shows an IP, a **pairing** port and a six-digit code | — |
| 2.2 | On the Host: "Pair a new Target...", enter that IP:port and the code, tap Pair | "Paired with ..." and a reminder that the *connect* port is a different number | `DroidCtl/Adb` |
| 2.3 | Check the debug pane and logcat | The six-digit code appears **nowhere**. Any `adb pair` line reads `<redacted>` | `DroidCtl/Adb` |
| 2.4 | Enter a wrong code deliberately | A failure message containing adb's own text, with the code still redacted | `DroidCtl/Adb` |

Step 2.3 is a privacy check, not a nicety: verify it by eye every time this code
path changes.

## 3. Connecting

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 3.1 | On the Target, note the IP and port under Wireless debugging (**not** the pairing port) | — | — |
| 3.2 | Enter it in the manual IP:port field, tap Connect | The Target appears in the "adb devices" section with state `device` | `DroidCtl/Adb` |
| 3.3 | Before accepting the debugging prompt on the Target, refresh | State shows `unauthorized`, with the "accept the prompt on the Target" remediation | `DroidCtl/Adb` |
| 3.4 | Reboot the Target and try the saved entry again | Connect fails; the message should lead you to re-read the port, which changes on reboot | `DroidCtl/Adb` |
| 3.5 | Restart the app | The Target is remembered and reconnects with one tap | `DroidCtl/Session` |

## 4. mDNS discovery

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 4.1 | With the Target paired and wireless debugging on, watch the "Discovered (mDNS)" section | The Target appears within a few seconds and resolves to an IP:port | `DroidCtl/Adb` |
| 4.2 | Open the pairing dialog on the Target, then look at the pairing screen | The Target appears under "Targets in pairing mode" | `DroidCtl/Adb` |
| 4.3 | Move both devices to a network with client isolation | The list stays empty and says so; the manual field still works | `DroidCtl/Adb` |

Discovery coming up empty is not a bug on every network. Step 4.3 confirms the
app says so rather than looking broken.

## 5. Server push, launch and handshake

This is the first step that exercises the scrcpy server itself.

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 5.1 | Tap **Mirror** on a connected Target | The state moves Pushing server → Starting server → Waiting for the scrcpy server | `DroidCtl/Session` |
| 5.2 | Open the debug pane while it starts | "scrcpy server output" shows the server's own `INFO: Device: [...]` banner | `DroidCtl/ScrcpyServer` |
| 5.3 | Check the log for the handshake | `Dummy byte received; the tunnel is live`, then `Target "<name>": h264 <W>x<H>` | `DroidCtl/Proto` |
| 5.4 | Check the debug pane's Session section | scid, forwarded port and Target serial are all populated | — |
| 5.5 | On the Target: `adb shell ls -l /data/local/tmp/scrcpy-server.jar` from another machine | The jar is there and its size matches `droidctl/build/generated/scrcpyServer/assets/scrcpy-server.jar` | — |

**If 5.3 never happens**, the failure overlay shows the server's stderr. A Java
stack trace there is the answer; a version-mismatch message means the bundled jar
and the app disagree (see `docs/PROTOCOL.md`).

## 6. Video

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 6.1 | Complete step 5 | The Target's screen appears, aspect-correct, letterboxed on black | `DroidCtl/Video` |
| 6.2 | Open the debug pane | Frames decoded climbs; dropped stays near zero; FPS and decode latency are plausible (< 50 ms) | `DroidCtl/Video` |
| 6.3 | Rotate the **Target** | The video re-lays out within a second; the log shows a new size and `recreating the decoder` | `DroidCtl/Video` |
| 6.4 | Rotate the **Host** | The letterboxing swaps axes; the log shows a new viewport line | `DroidCtl/Input` |
| 6.5 | Leave it streaming for five minutes | No growth in dropped frames, no drift in latency | `DroidCtl/Video` |

### If video is black — isolate it in one step

Turn on **Settings → Raw-dump mode** and mirror again. The decoder is bypassed
and the payload stream is written to
`/sdcard/Android/data/dev.alexdev404.droidctl/files/dumps/droidctl-<time>-<scid>.h264`.
Pull it and play it:

```
adb pull /sdcard/Android/data/dev.alexdev404.droidctl/files/dumps/<file>.h264
ffplay -f h264 <file>.h264
```

* **It plays** → the sockets and the framing are correct; the fault is in
  `VideoDecoder` or the surface.
* **It does not play, or is 0 bytes** → the bytes were already wrong before the
  decoder saw them; the fault is in the socket or framing layer.

### If you have no Target at all

Debug builds only: turn on **Settings → Use the fake scrcpy server** and mirror
any entry. A recorded 480x960 stream plays back through the real sockets,
framing, decoder and surface — a downward-sweeping bright bar on a gradient,
with a white square fixed in the top-left corner. This exercises everything
except adb, the tunnel, the server launch and input injection.

## 7. Touch input

Do these with **Settings → Show touches on the Target** enabled so you can see
where each touch actually landed.

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 7.1 | Tap an icon near the centre | The Target's touch indicator appears under your finger, not offset | `DroidCtl/Input` |
| 7.2 | Tap each of the four corners of the video | The indicator lands in the matching corner of the Target | `DroidCtl/Input` |
| 7.3 | Tap in the **black letterbox margin** | Nothing at all happens on the Target — in particular, no edge swipe or back gesture | `DroidCtl/Input` |
| 7.4 | Swipe slowly, then flick fast | Both scroll; the fast one is not decimated into two far-apart jumps | — |
| 7.5 | Pinch to zoom in a maps or photos app | Both fingers track independently | — |
| 7.6 | Start a drag, leave the video mid-gesture, come back, release | The drag follows the video's edge while outside and completes cleanly | — |
| 7.7 | Rotate the Target, then repeat 7.1 and 7.2 | Still accurate. Stale dimensions would show up here as a consistent offset | `DroidCtl/Input` |
| 7.8 | While a finger is down, press the Host's Home key, then return | No stuck finger on the Target | — |

## 7b. Connection quality

| # | Do this | Expect | Log tag |
|---|---|---|---|
| 7b.1 | Settings → Connection quality → pick `256 kbps · 25% resolution`, then mirror | Visibly soft video. The debug pane's `max_size` is about a quarter of the Target's longer side | `DroidCtl/Session` |
| 7b.2 | Pick `8 Mbps · full resolution`, mirror again | Sharp video; the debug pane shows `max_size: uncapped` | `DroidCtl/Session` |
| 7b.3 | Pick **Automatic** on good Wi-Fi and mirror | The log shows `Automatic quality: ... -> <rung>`, and the debug pane's "Measured at connect" is plausible for your network | `DroidCtl/Session` |
| 7b.4 | Compare the debug pane's "Target screen" against the Target's real resolution | They match (`wm size` was read correctly) | `DroidCtl/Session` |
| 7b.5 | Move the Host far from the access point, or throttle the network, then mirror on Automatic | A lower rung is chosen than in 7b.3, provided the server is re-pushed (see 7b.7) | `DroidCtl/Session` |
| 7b.7 | Mirror twice in a row and compare the logs | The second run logs "The Target already has this scrcpy server; skipping the push" and reaches video markedly faster. Automatic reports the rung as measured "on an earlier session" | `DroidCtl/ScrcpyServer` |
| 7b.8 | With a fixed rung selected, mirror and read the log | Exactly one quality line, reading "chosen in settings, not measured". No push timing is used | `DroidCtl/Session` |
| 7b.9 | On a slow link, watch the progress text while connecting | It moves through "Delivering the scrcpy server", "Reading the Target's screen size" and "Starting the scrcpy server" rather than sitting on one label | — |
| 7b.6 | Watch the debug pane's "Video throughput" on a busy screen, then a static one | Rises with motion, falls to near zero when nothing moves | — |

Automatic decides once, at connect. It does not re-adjust mid-session, because
scrcpy cannot change either setting without relaunching the server — so a
network that degrades *during* a session stays on the rung it started on.
Reconnect to re-measure.

## 7c. Turning the Target's screen off

| # | Do this | Expect |
|---|---|---|
| 7c.1 | Settings → turn on "Turn the Target's screen off", then mirror | The Target's own screen goes black while the mirror keeps working |
| 7c.2 | Tap and swipe in the mirror | Input still lands on the Target |
| 7c.3 | Press the Target's physical power button | It stays off (the server keeps re-blanking it) |
| 7c.4 | Stop mirroring | The Target's screen comes back on |
| 7c.5 | Mirror again, then kill DroidCtl from the recents screen | The Target's screen still comes back — the server's CleanUp restores it even on an abrupt disconnection |

## 8. Buttons, keys and text

| # | Do this | Expect |
|---|---|---|
| 8.1 | Reveal the controls, tap BACK | The Target navigates back |
| 8.2 | Turn the Target's screen off, tap BACK | The Target's screen turns on (that is what `BACK_OR_SCREEN_ON` is for) |
| 8.3 | Tap HOME, then RECENTS | Both work |
| 8.4 | Tap the rotate button | The Target rotates |
| 8.5 | Tap the power button | The Target's screen turns off; tap it again to turn it back on |
| 8.6 | Tap the keyboard button, type `hello` into a text field on the Target | `hello` appears |
| 8.7 | Type an emoji and an accented character | Both appear (this is why text goes as `INJECT_TEXT`, not synthesized keycodes) |
| 8.8 | Press backspace a few times | Characters are deleted one at a time |
| 8.9 | Press the Host's volume keys | The Target's volume changes, not the Host's |

## 9. Teardown and leak checks

The point of this section is that nothing survives a session. Run it after a
crash as well as after a clean exit.

| # | Do this | Expect |
|---|---|---|
| 9.1 | Exit the mirror screen, then from another machine run `adb -s <target> forward --list` against the Host's adb server | No `scrcpy_*` forwards left |
| 9.2 | On the Target, check for a running server: `ps -A \| grep app_process` | No scrcpy server process |
| 9.3 | Connect and disconnect ten times in a row | Every session starts; no "address already in use", no growing list of forwards |
| 9.4 | Force-stop DroidCtl mid-session, relaunch | The log shows `Clearing N stale adb forward(s) from a previous run`, and the next session starts cleanly |
| 9.5 | Mirror, then turn Wi-Fi off on the Target | The session reports reconnect attempts, then fails with a message naming the stage |
| 9.6 | Turn Wi-Fi back on and reconnect | Works without restarting the app |
| 9.7 | Rotate the Host repeatedly during a session | No decoder leak: frames decoded keeps climbing, dropped does not spike |

## 10. Battery and screen

| # | Do this | Expect |
|---|---|---|
| 10.1 | Start the first session | A one-time notice warns that mirroring drains the battery |
| 10.2 | Leave the mirror screen idle for longer than the Host's screen timeout | The Host's screen stays on |
| 10.3 | Leave the mirror screen | The Host's screen timeout goes back to normal |

## 11. Licensing

| # | Do this | Expect |
|---|---|---|
| 11.1 | Settings → Open source licenses | scrcpy is listed with version 4.1, Genymobile's copyright and the upstream URL |
| 11.2 | Tap "Show license text" under scrcpy | The full Apache License 2.0 text is shown, read from the bundled asset |
