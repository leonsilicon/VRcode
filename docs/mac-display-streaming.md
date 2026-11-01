# Mac display streaming: decision and plan

Decided 2026-11-01 for the **Mac display** part of `PLAN.md`: a Mac screen live in vrcode, usable
with the lid closed, and fully operable from the headset: hover, click, double-click, right-click,
drag, scroll and keyboard.

Constraints set by the user on 2026-11-01:

- **No separate app.** Nothing runs beside vrcode: no Meta Virtual Display, Moonlight, Glass or VNC
  app on the Quest, and no extra host app on the Mac. Everything lives in the vrcode Quest app and
  the vrcode Mac service.
- **Licenses don't matter.** GPL code may be copied.
- Pick the best fit for vrcode, not the least code.

Facts were checked against current sources (linked at the end), the installed macOS 27.0 SDK, the
cached Spatial SDK 0.14.0 AARs, and the connected Quest 3 over ADB.

## Decision

**Stream the Mac display natively, inside vrcode's own two halves:**

- **Mac service:** ScreenCaptureKit → VideoToolbox H.264 (low-latency mode) → a dedicated TCP socket.
- **Quest app:** Qualcomm's low-latency hardware decoder (`MediaCodec`) → a `VideoSurfacePanel`
  compositor layer.
- **Input:** goes back over the existing vrcode connection and becomes `CGEvent`s on the Mac.

The proven parts are copied rather than written fresh:

- Mac capture, encoder settings, virtual display, keep-awake and event injection from
  [Glass](https://github.com/Ntarasiuk/glass).
- Quest decoder setup from [moonlight-android](https://github.com/moonlight-stream/moonlight-android).
- The overall shape from [scrcpy](https://github.com/Genymobile/scrcpy). It streams raw H.264 over
  an ADB TCP socket into `MediaCodec`, the same design in the other direction, and advertises
  [35–70 ms](https://github.com/Genymobile/scrcpy/pull/646) latency.

### Why no existing stack is used whole

| Stack | Why it loses for vrcode |
|---|---|
| Sunshine + Moonlight | Sunshine is a separate host app. Video, audio and control are UDP-only, so it can't use the USB / Wi-Fi ADB link vrcode runs on (`adb reverse` is TCP only). Its macOS port is experimental and still uses AVCaptureScreenInput. Its useful parts (decoder tuning, input) are copied instead |
| Glass | A separate host app. Video uses WebTransport (QUIC/UDP), so again no ADB link. The client is a web page, so vrcode would show it in a WebView rather than a direct video layer. Only 11 days old. Its macOS code is the best reference and is copied |
| LiveKit / WebRTC | Needs a `livekit-server` process, or about 1,100 lines of peer-to-peer glue. Its congestion control, jitter buffer and pacing are built for the internet, not a USB cable. On this Quest's `c2.qti` decoders libwebrtc drops to H.264 Constrained Baseline and never uses the `.low_latency` decoders. Adds about 17 MB to the APK |
| VNC (Apple's server, or LibVNCServer embedded) | Works over TCP and has input built in, but uses pixel codecs (zlib/ZRLE/Tight). That is CPU-heavy and choppy at 2560×1600 with scrolling or video. Apple's server can't be turned on from the command line and merges all displays into one image |
| AirPlay, NDI, SRT, GStreamer, FFmpeg, Media3 | UDP or mDNS transports, no input path back to the Mac, the old AVCaptureScreenInput API, or about 500 ms of player buffering |

The native path is the only one that:

- uses the transport vrcode already has (TCP over `adb reverse` on USB or Wi-Fi ADB, and direct LAN
  later);
- draws into the sharpest Quest surface (a compositor layer);
- picks the Quest's dedicated low-latency decoders;
- controls every input event, which right-click and scrolling need.

## Design

### Mac service (`mac/`, Swift)

| Part | How | Copy from |
|---|---|---|
| Packaging | Ship the service as a signed `.app` LaunchAgent. Since macOS 26.1, bare background executables don't appear in the Screen Recording list, and on 27 `CGRequestScreenCaptureAccess()` no longer prompts, so request access through ScreenCaptureKit. Needs Screen & System Audio Recording and Accessibility | — |
| Capture | `SCStream` of one display at its native **pixel** size (`CGDisplayPixelsWide/High`; `captureResolution` applies only to windows). Pixel format `420v` (the encoder's native input), cursor shown, `minimumFrameInterval` set from the chosen fps, small `queueDepth`. ScreenCaptureKit sends frames only when the screen changes | Glass `macos/capture.rs`; Apple's "Capturing screen content in macOS" sample |
| Encode | VideoToolbox H.264 High:<br>• `EnableLowLatencyRateControl` and `RequireHardwareAcceleratedVideoEncoder`<br>• `RealTime`, `AllowFrameReordering = false`<br>• expected frame rate, BT.709/sRGB colour tags<br>• `MaxAllowedFrameQP` to keep text sharp<br>• a long GOP, with `ForceKeyFrame` on request<br>HEVC Main is a setting to try later | Glass `macos/encode.rs` |
| Stream socket | A new loopback port, **7274**, sending video only, Mac → Quest. Frames are Annex-B access units with xmux's 16-byte header layout (protocol version 1). Kept off 7273 so video frames never queue ahead of speech, AI or input. Backpressure: while unsent bytes exceed a threshold, skip *captured* frames (never drop encoded ones, which breaks decoding). When the backlog clears, send the next frame, or an IDR if it was long. Bitrate is set per link: start around 30 Mbit/s on USB and 15 on Wi-Fi ADB, then tune | scrcpy's server (same idea) |
| Input injection | `CGEvent` posted to the HID tap:<br>• mouse moved; left and right down/up; left and right dragged<br>• scroll wheel in pixel and line units<br>• `kCGMouseEventClickState` for double and triple clicks (synthetic events don't count clicks by themselves)<br>• modifier flags tracked across key events<br>• keys by virtual keycode; text via `CGEventKeyboardSetUnicodeString` for voice and keyboard paste | Glass `macos/input.rs` (right/other buttons, drag types, click state, flags); Sunshine `src/platform/macos/input.cpp` for keycodes |
| Coordinates | Global point = `CGDisplayBounds(displayID).origin + (u·widthPts, v·heightPts)`, from the panel UV the Quest sends | — |
| Virtual display | `CGVirtualDisplay` (private, still header-less in macOS 27) at the size, HiDPI flag and refresh the Quest sends. Created when the lid is closed (nothing else is online) or on request | Glass `macos/virtual_display.rs` (`create_scaled`), or DeskPad (Swift) |
| Keep awake | A virtual display does not stop lid-close sleep. Install a sudoers rule once that allows exactly `pmset -a disablesleep 0` and `1`, and toggle it while a headset is connected and the Mac is on power | Glass `lid.rs` |
| Lock screen | Injected keys reach the lock screen while a user is logged in. FileVault after a reboot needs a local login | — |

### Quest app (`app/`, Kotlin)

| Part | How | Copy from |
|---|---|---|
| Panel | `VideoSurfacePanelRegistration` as a flat quad (curved panels report wrong hit UVs) in layer mode, so it goes direct to the compositor (`mips = 1`, no scene texture). Grabbable and resizable like the xmux windows, with its pose and size in the saved layout | Spatial SDK media docs |
| Decoder | `MediaCodec.createByCodecName("c2.qti.avc.decoder.low_latency")` (present on this Quest, as are the HEVC, VP9 and AV1 variants), configured with:<br>• `KEY_LOW_LATENCY = 1`<br>• `vendor.qti-ext-dec-low-latency.enable = 1`<br>• `vendor.qti-ext-dec-picture-order.enable = 1`<br>• `KEY_OPERATING_RATE` set high and `KEY_PRIORITY = 0`<br>Release each output buffer to the Surface as soon as it is dequeued. On a decoder error or a gap, request an IDR. A dedicated thread reads the 7274 socket | moonlight-android `MediaCodecHelper.java` (vendor keys, low-latency decoder choice), `MediaCodecDecoderRenderer.java` (decode loop) |
| Hit test | vrcode intersects the ray with the panel quad itself, using the entity's pose and its live `IsdkPanelDimensions`. This happens before `xmux.hitTest`, so it doesn't depend on video panels delivering pointer events. Result: UV in [0,1]², sent as a fraction so Mac-side resolution changes need nothing from the Quest | — |
| Cursor | The Mac draws its own cursor into the stream, keeping its shape (I-beam, resize arrows). vrcode's existing ray cursor sits on top for instant feedback | `PointerCursor` |

### Input mapping (ray on the Mac panel)

Gestures already defined in `PLAN.md` keep their meaning; the Mac panel just becomes another
target. Every action has an on-panel equivalent, as the plan requires.

| Mac action | Hands | Controllers | On-panel equivalent |
|---|---|---|---|
| Move and hover (menus, tooltips) | Ray movement → mouse-moved events, rate-limited to the stream fps | Ray | — |
| Left click | Right **index pinch**: press on pinch, release on unpinch | Trigger | — |
| Double and triple click | Repeated pinches within the system double-click interval and a few points apart; the Mac service sets the click state | Trigger | — |
| Drag (select text, move windows, sliders) | Index pinch held past a small dead zone, then move. Under the dead zone the pinch stays a click | Trigger held | — |
| **Right click** | Right **middle pinch, palm away**. This is already vrcode's right-click (`RightClickFeature`); route it to the Mac when the ray is on the Mac panel instead of opening vrcode's terminal menu. The Mac's own context menu appears in the stream, and an index pinch picks an item | B button | Toolbar "right-click next" toggle |
| Scroll, vertical | Right **thumb swipe forward/back** (`MicrogestureBits.RightMicrogestureSwipeForward/Back` in Spatial SDK 0.14). `PLAN.md` gives thumb swipes no app action. Each swipe is N lines | Thumbstick (continuous, pixel units) | Scroll strip beside the panel: pinch-drag scrolls continuously |
| Scroll, horizontal | Right thumb swipe left/right | Thumbstick | Scroll strip |
| Modifier clicks (⌘-click, ⇧-click, ⌥-drag) | Hold the modifier on a hardware keyboard, or latch it on vrcode's floating keyboard | — | Sticky modifiers on the floating keyboard |
| Keyboard | While the Mac panel has focus: a hardware keyboard paired with the Quest (Meta mapped to Command), the floating keyboard, and voice-pane Paste, all sent as `CGEvent`s instead of to xmux | — | Floating keyboard |

Notes:

- **Focus.** An index pinch on the Mac panel gives it keyboard focus. A pinch on a terminal gives
  focus back to xmux. Enter, Escape and voice keep targeting whatever has focus.
- **Click accuracy.** Pinching moves the ray. Click at the hit point from about 50 ms before the
  pinch began, and keep drags on a smoothed ray. This matters most for the Mac's small targets
  (menu bar, 16 px icons).
- **Gesture test.** Check that a thumb swipe can't be mistaken for an index pinch on this firmware,
  and that thumb swipes don't conflict with ISDK grab.

### Protocol

- **Port 7273 (control).** New kinds; 9–12 are taken by `CMUX_REQUEST`/`CMUX_EVENT` and
  `AI_REQUEST`/`AI_RESULT`, so take the next free numbers in `docs/vrcode-protocol.md` when this is
  built:

  | Proposed kind | Body |
  |---|---|
  | `DISPLAY_START` | mode (`mirror`/`virtual`), virtual size, HiDPI, fps, codec, bitrate |
  | `DISPLAY_STOP` | — |
  | `DISPLAY_STATE` | the displays, the chosen one's pixel and point size, stream status |
  | `DISPLAY_KEYFRAME` | request an IDR |
  | `POINTER` | u, v, buttons, click count |
  | `SCROLL` | dx, dy, units |
  | `KEY` | key code, down/up, modifiers |
  | `TEXT` | a string to type |

  JSON is fine at these rates. A compact binary `POINTER` can come later if move events turn out to
  be costly.
- **Port 7274 (video).** Mac → Quest only. Each frame is one access unit with a header carrying a
  sequence number and the capture timestamp, so latency can be logged on the Quest.
- `scripts/quest-link.py` gains the 7274 reverse tunnel next to 7272 and 7273.

### Picture quality

- **What the headset can resolve.** The Quest 3 resolves about 25 pixels per degree. A panel 1.2 m
  wide seen from 1 m spans about 62°, so roughly 1,550 headset pixels. A 2560×1600 stream (a HiDPI
  1280×800-point virtual display) already exceeds that.
- **Chroma.** With 4:2:0, colour is at 1280×800, one colour sample per point. Syntax-coloured text
  should survive. The readability gate checks it.
- **Mirror mode.** In mirror mode with the lid open, scale the 3456×2234 built-in display down to
  about 2560 wide in ScreenCaptureKit rather than streaming all of it.
- **Bitrate.** Mostly-static text needs little. Scrolling and video are what need the USB bitrate.

## Build plan

Each step ends in a gate that is checked on the Quest.

1. **Video only, mirror mode, USB.** About 2 days.
   - Mac: package the service as an `.app`; capture → encode → 7274.
   - Quest: panel + low-latency decoder.
   - Gates:
     - Mac text readable at 1 m.
     - Glass-to-glass latency under 100 ms over USB. Film the Mac display and the headset lens
       together at 240 fps with a millisecond counter on screen. Also log capture-timestamp-to-render
       on the Quest.
     - Sustained 60 fps while scrolling a long file.
   - If H.264 text isn't readable at a sensible bitrate, try HEVC Main before anything else.
2. **Input.** About 2 days.
   - Ray → UV → hover, click, double-click, drag, right-click (middle pinch), scroll (thumb swipes,
     thumbstick, strip), keyboard and focus routing, text paste.
   - Gates:
     - Clicks land on the intended control, menu-bar items included.
     - A right-click opens the Mac context menu at the ray and an item can be picked.
     - Text selection by drag works.
     - Scrolling works in Safari and VS Code.
     - ⌘-click works.
3. **Lid closed.** About 2 days.
   - Virtual display at the size and HiDPI chosen on the Quest; keep-awake rule; switching between
     mirror and virtual.
   - Gates:
     - With the lid closed and only power attached, the display is usable for an hour.
     - The lock screen can be unlocked from the headset.
     - Opening the lid returns the Mac to normal.
4. **Wi-Fi and polish.**
   - Backpressure and per-link bitrate for Wi-Fi ADB; reconnects with an IDR; panel pose and size in
     the saved layout; Mac display settings in the vrcode menu.
   - Gate: over Wi-Fi ADB, no latency build-up after 10 minutes of scrolling.

Audio stays as `PLAN.md` describes: a separate Core Audio process tap stream. ScreenCaptureKit
could also capture audio on the same clock, but it doesn't mute the Mac's speakers the way the tap
does.

## Risks to check early

- **Screen Recording re-prompt.** macOS 15+ periodically asks again whether an app may keep
  recording. Check whether that prompt stops capture for a LaunchAgent, and whether it can be
  answered from the headset with the lid closed (it would appear on the virtual display). If it
  blocks capture, answer it with the lid open.
- **Wi-Fi ADB.** Its throughput and TCP head-of-line blocking on Wi-Fi loss can make latency spike.
  Capture-side frame skipping bounds it; direct LAN (once the service has TLS) is the cure.
- **Decoder limits.** Check that `c2.qti.avc.decoder.low_latency` accepts H.264 High at 2560×1600,
  60 fps (level 5.1). If it does not, use the standard decoder with the vendor keys.
- **Thumb swipes.** Detection reliability on this firmware, and accidental swipes during pinches.

## Sources

- Platform checks (2026-11-01):
  - **Quest 3** (Horizon OS build 209): decoders `c2.qti.{avc,hevc,vp9,av1}.decoder` with
    `.low_latency` variants; WebView 154.
  - **Spatial SDK 0.14.0 AARs**: `VideoSurfacePanelRegistration(id, (Entity, Surface) -> Unit,
    settings, setup)`, `PanelRenderMode.Layer`, `MicrogestureBits.Right*SwipeForward/Back/Left/Right`.
  - **macOS 27.0 SDK**: `CGVirtualDisplay` without a header.
  - **Local tools**: FFmpeg 9.0.1 has only `avfoundation`; `adb reverse` forwards TCP only.
- Copied from:
  - Glass [encode.rs](https://github.com/Ntarasiuk/glass/blob/main/crates/glass-host/src/macos/encode.rs), [capture.rs](https://github.com/Ntarasiuk/glass/blob/main/crates/glass-host/src/macos/capture.rs), [input.rs](https://github.com/Ntarasiuk/glass/blob/main/crates/glass-host/src/macos/input.rs), [virtual_display.rs](https://github.com/Ntarasiuk/glass/blob/main/crates/glass-host/src/macos/virtual_display.rs) and [lid.rs](https://github.com/Ntarasiuk/glass/blob/main/crates/glass-host/src/lid.rs).
  - moonlight-android [MediaCodecHelper.java](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java) and [MediaCodecDecoderRenderer.java](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java).
  - [Sunshine macOS input](https://github.com/LizardByte/Sunshine/tree/master/src/platform/macos).
  - [scrcpy](https://github.com/Genymobile/scrcpy).
- Spatial SDK: [VideoSurfacePanelRegistration](https://developers.meta.com/horizon/reference/spatial-sdk/v0.14.0/com_meta_spatial_toolkit_videosurfacepanelregistration), [media playback](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-media-playback), [compositor layers](https://developers.meta.com/horizon/documentation/spatial-sdk/os-compositor-layers), [curved-panel UV bug](https://communityforums.atmeta.com/discussions/Questions_Discussions/spatial-sdk-bug-2d-hitinfo-from-pointerevents-is-wrong-when-interacting-with-2d-/1363823).
- macOS:
  - [Screen Recording list in 26.1](https://developer.apple.com/forums/thread/807323)
  - [Lid-closed sleep with a virtual display](https://github.com/Ntarasiuk/glass/pull/68)
  - [Empty SCShareableContent with the lid closed](https://github.com/LanternOps/breeze/issues/4092)
  - [CGEvent at the login window](https://developer.apple.com/forums/thread/62687)
  - [SCK queue depth](https://developer.apple.com/documentation/screencapturekit/scstreamconfiguration/queuedepth)
- Rejected stacks:
  - Moonlight/Sunshine: [moonlight-common-c VideoStream.c (UDP)](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/VideoStream.c), [ports](https://games-on-whales.github.io/wolf/stable/protocols/index.html), [Sunshine macOS capture](https://github.com/LizardByte/Sunshine/blob/master/src/platform/macos/av_video.m).
  - LiveKit/WebRTC: [MacOSScreenCapturer.swift](https://github.com/livekit/client-sdk-swift/blob/main/Sources/LiveKit/Track/Capturers/MacOSScreenCapturer.swift), [MediaCodecVideoDecoderFactory](https://github.com/webrtc-sdk/webrtc/blob/m150_release/sdk/android/src/java/org/webrtc/MediaCodecVideoDecoderFactory.java).
  - VNC: [Apple VNC notes](https://github.com/Chapoly1305/iShareScreen/blob/myMain/docs/apple_vnc_rfc.md), [kickstart limits](https://support.apple.com/guide/remote-desktop/apd8b1c65bd/mac).
  - Media3: [Media3 RTSP](https://developer.android.com/media/media3/exoplayer/rtsp), [ExoPlayer #11221](https://github.com/google/ExoPlayer/issues/11221).
