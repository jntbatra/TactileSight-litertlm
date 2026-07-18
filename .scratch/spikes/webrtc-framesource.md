# Spike: Band-camera → phone video path on Android (`FrameSource`)

> **Still relevant (noted 2026-07-18), with two changes.** WebRTC is still the band↔phone transport,
> but [ADR-0009](../../docs/adr/0009-multi-sensor-band-pipeline.md) now specifies the UNO Q's **own
> 5 GHz AP with a USB-gadget fallback, both as ICE candidates on one PeerConnection** (NFC pairing is
> dropped), and the link carries **depth + IR keyframes on a data channel** alongside RGB. Current
> contract: [`docs/band-interface.md`](../../docs/band-interface.md).

**Status:** ready-for-agent
**Date:** 2026-07-11
**Owner:** spike author
**Goal:** Prove a REAL, runnable path that feeds the TactileSight Query pipeline one frame per button-press, from a persistent live camera feed. Hackathon-guaranteed source = phone's own camera. Band-over-WebRTC = stretch swap-in. Both hidden behind one `FrameSource`.

---

## 0. TL;DR decision

- **Build `PhoneCameraSource` on CameraX now.** It is the guaranteed demo path and it stands alone. Persistent `Preview` + `ImageAnalysis(STRATEGY_KEEP_ONLY_LATEST)` gives us exactly "latest frame on demand." This is low-risk and well-documented.
- **Do NOT put full WebRTC on the hackathon critical path.** Signaling + ICE + a band-side WebRTC stack is days of yak-shaving for a hobby cam. Treat WebRTC as a *stretch*.
- **For the band stretch, prefer MJPEG-over-HTTP first, RTSP second, full WebRTC last.** An ESP32-CAM already exposes `http://<ip>/stream` (MJPEG) and `http://<ip>/snapshot` (single JPEG). We can pull the latest JPEG with zero signaling. That maps onto `FrameSource.latestFrame()` almost verbatim.
- Keep the two behind one interface so the Query pipeline never learns which is live.

---

## 1. `FrameSource` interface

The pipeline needs exactly one thing: *"give me the most recent frame, now."* It does not stream, it does not subscribe per-frame, it pulls on button-press. Keep the interface that small.

```kotlin
/** A frame handed to the Query pipeline. Owns its pixels; caller must recycle(). */
data class Frame(
    val bitmap: Bitmap,          // ARGB_8888, upright (rotation already applied)
    val timestampMs: Long,       // capture time (SystemClock.elapsedRealtime)
    val widthPx: Int,
    val heightPx: Int,
) {
    fun recycle() = bitmap.recycle()
}

/**
 * A persistent live camera feed with a single-frame "capture" (retain latest).
 * The Query pipeline depends ONLY on this. It never learns whether the pixels
 * came from CameraX or a band over the network.
 */
interface FrameSource {
    /** Begin the persistent feed. Idempotent. Safe to call in onResume(). */
    suspend fun start()

    /** Stop the feed and release hardware/sockets. Safe in onPause(). */
    suspend fun stop()

    /**
     * The "capture" operation = retain the latest decoded frame.
     * Returns null if no frame has arrived yet (feed still warming up) or the
     * source is disconnected. NEVER blocks on new data — returns what's on hand.
     */
    suspend fun latestFrame(): Frame?

    /** Coarse health for UI + GO/NO-GO fallback. */
    val state: StateFlow<SourceState>   // Idle, Starting, Live, Stalled, Error(msg)
}
```

Design notes:
- `latestFrame()` returns the *retained* frame, it does not wait. "Capture" in the product = "copy whatever is currently latest." This is the semantic that both a local camera and a network feed can honor cheaply.
- One `Frame` type (upright `Bitmap`) so the pipeline never touches YUV/rotation/`ImageProxy`/`VideoFrame` details. Each implementation does its own color+rotation normalization internally.
- `state` lets the UI show "band disconnected → fall back to phone cam" without the pipeline branching.
- No per-frame callback in the interface. That is deliberate — it keeps `WebRtcFrameSource` (push model) and `PhoneCameraSource` (pull model) reconcilable: both keep an internal `@Volatile var latest` and hand out a copy on demand.

### 1a. `PhoneCameraSource` (CameraX — the guaranteed path)

Bind two use cases to the lifecycle at once: `Preview` (persistent, on screen) + `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`. The analyzer does nothing but stash the newest frame. `latestFrame()` copies the stash.

```kotlin
class PhoneCameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewSurfaceProvider: Preview.SurfaceProvider,
) : FrameSource {

    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state = _state.asStateFlow()

    // The single retained frame. Overwritten ~30x/sec by the analyzer.
    @Volatile private var latestBitmap: Bitmap? = null
    @Volatile private var latestTsMs: Long = 0
    @Volatile private var latestRotation: Int = 0

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    override suspend fun start() {
        _state.value = SourceState.Starting
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()
        provider = cameraProvider

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewSurfaceProvider)          // persistent live feed
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // latest wins
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // easy Bitmap
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        // Copy out — the proxy's buffer is reused after close().
                        val bmp = Bitmap.createBitmap(
                            proxy.width, proxy.height, Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(proxy.planes[0].buffer)
                        latestBitmap = bmp
                        latestTsMs = SystemClock.elapsedRealtime()
                        latestRotation = proxy.imageInfo.rotationDegrees
                        if (_state.value != SourceState.Live) _state.value = SourceState.Live
                    } finally {
                        proxy.close()   // MUST close or Preview freezes
                    }
                }
            }

        withContext(Dispatchers.Main) {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }
    }

    override suspend fun latestFrame(): Frame? {
        val src = latestBitmap ?: return null
        // Copy so the caller owns it and the analyzer can keep overwriting.
        val upright = rotateBitmap(src, latestRotation)   // apply rotationDegrees
        return Frame(upright, latestTsMs, upright.width, upright.height)
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) { provider?.unbindAll() }
        analysisExecutor.shutdown()
        _state.value = SourceState.Idle
    }
}
```

Key facts from primary sources:
- `STRATEGY_KEEP_ONLY_LATEST` delivers only the newest frame; the image-queue depth is ignored (Android CameraX docs, *Image analysis*). Exactly the retain-latest semantic we want.
- You MUST call `ImageProxy.close()` or the Preview (and other use cases) freezes (Android CameraX docs). This is the single most common CameraX bug.
- `OUTPUT_IMAGE_FORMAT_RGBA_8888` lets you `copyPixelsFromBuffer` straight into an ARGB Bitmap, skipping YUV→RGB conversion. Slightly heavier per frame but massively simpler code — fine for on-press capture.
- Binding `Preview` + `ImageAnalysis` together keeps the preview persistent while frames flow. This is the intended multi-use-case pattern.

### 1b. `WebRtcFrameSource` (band — stretch)

WebRTC delivers frames by *push*: you attach a `VideoSink` to the remote `VideoTrack`; its `onFrame(VideoFrame)` fires ~30x/sec. We invert push→pull with the same `@Volatile latest` stash. The `VideoFrame` buffer is I420 (YUV); convert to RGB with the library's `YuvConverter` (GL) or a CPU I420→ARGB path.

```kotlin
class WebRtcFrameSource(
    private val eglBase: EglBase,
    private val signaling: LocalSignalingChannel,   // see §2c
) : FrameSource, VideoSink {

    @Volatile private var latest: Frame? = null
    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state = _state.asStateFlow()

    override fun onFrame(frame: VideoFrame) {
        // Called on WebRTC's render thread. Convert once, stash, do NOT block.
        val bmp = videoFrameToBitmap(frame, eglBase)   // YuvConverter or CPU I420
        latest?.recycle()
        latest = Frame(bmp, SystemClock.elapsedRealtime(), bmp.width, bmp.height)
        if (_state.value != SourceState.Live) _state.value = SourceState.Live
    }

    override suspend fun latestFrame(): Frame? = latest   // hand out newest; no wait

    override suspend fun start() {
        // PeerConnectionFactory.initialize(...); create factory with eglBase;
        // build PeerConnection; on remote track: (track as VideoTrack).addSink(this);
        // exchange SDP offer/answer + ICE via `signaling`. See §2.
    }
    override suspend fun stop() { /* dispose peerConnection, factory, eglBase */ }
}
```

The Query pipeline is identical for both: `frameSource.latestFrame()`. Swap the concrete class in one DI/composition spot; nothing downstream changes. That is the whole point.

---

## 2. WebRTC receive path on Android

### 2a. Library options
- **GetStream `stream-webrtc-android`** — `io.getstream:stream-webrtc-android` (Maven Central, actively versioned, 1.3.x as of mid-2026). This is the practical choice: Google stopped publishing the official WebRTC Android artifact years ago and JCenter is dead, so Stream maintains a pre-compiled build tracking upstream WebRTC. Same `org.webrtc.*` API (`PeerConnectionFactory`, `PeerConnection`, `VideoTrack`, `VideoSink`, `SurfaceViewRenderer`, `EglBase`, `YuvConverter`). UI helpers in `stream-webrtc-android-ui` (`VideoTextureViewRenderer`).
  - `implementation("io.getstream:stream-webrtc-android:1.3.x")`
- **Build WebRTC yourself from webrtc.googlesource.com** — canonical but a multi-hour `depot_tools`/`gn`/`ninja` build. Not for a hackathon.
- **Verdict:** if we do WebRTC at all, use GetStream's artifact.

### 2b. Receiving a stream + grabbing the latest frame (WebRTC)
1. `PeerConnectionFactory.initialize()`, build factory with an `EglBase.Context`.
2. Create `PeerConnection` with ICE servers. For same-LAN you can even pass an empty ICE-server list and rely on host candidates (see §2c/§4).
3. Set the remote description (offer from band), create + set local answer.
4. On `onTrack` / `onAddStream`, cast the remote track to `VideoTrack` and `videoTrack.addSink(mySink)`.
5. In `VideoSink.onFrame(VideoFrame)`: retain the frame. To get a Bitmap, use `VideoFrameDrawer` + `YuvConverter` (GPU) or read the `VideoFrame.Buffer.toI420()` planes and convert on CPU. Stash it (§1b).

The "capture = latest frame" requirement is trivial here — you are already receiving a live sink; capture just copies the current stash.

### 2c. Local signaling WITHOUT a cloud server
WebRTC needs a side channel to swap SDP offer/answer + ICE candidates. It does NOT need to be a cloud server — any two-way byte pipe works. On one LAN / phone hotspot:
- **Simplest:** a tiny HTTP server on the band (ESP32/Pi) with `POST /offer` returning an answer (the "WHEP-lite"/one-shot pattern). Phone POSTs its SDP, band replies with its SDP. Trickle ICE can be skipped if both sides gather host candidates and put them in the SDP (non-trickle / vanilla ICE).
- **Or:** a 30-line WebSocket/plain-TCP relay on the phone hotspot.
- Because both devices are on the same subnet, ICE resolves via **host candidates** only — no STUN, no TURN, no cloud. This is the big simplification of the local-only case.

### 2d. NFC to bootstrap signaling
NFC's job is only to hand the phone the band's connection creds so it knows *where to talk*, before any IP session exists.
- Band exposes an NFC tag (NTAG21x) or does host-card-emulation carrying an **NDEF** record with: band's Wi-Fi/hotspot SSID+PSK (optional), band IP:port, and the signaling path (e.g. `tactilesight://band?ip=192.168.4.1&port=8080&transport=mjpeg`). A Wi-Fi-provisioning NDEF record can even auto-join the band's SoftAP.
- Phone reads the tag via `NfcAdapter` foreground dispatch / `ReaderCallback`, parses the URI, then opens the transport (MJPEG pull, RTSP, or WebRTC signaling POST). NFC does zero media — it is pure out-of-band bootstrap. This is a genuinely nice demo beat ("tap the band, feed appears").

### 2e. SIMPLER fallbacks for a hobby band cam (recommended first)
For an ESP32-CAM / Pi Zero, full WebRTC is overkill. Two much simpler transports map onto `FrameSource` cleanly:
- **MJPEG-over-HTTP (recommended stretch).** ESP32-CAM firmware already serves `http://<ip>/stream` (multipart MJPEG, ~15-20 FPS at VGA) and `http://<ip>/snapshot` (one JPEG). For our retain-latest semantic we don't even need the continuous stream — `latestFrame()` can GET `/snapshot` on button-press, or a background loop decodes the MJPEG multipart stream and stashes the newest JPEG. Decode with `BitmapFactory.decodeByteArray`. Zero signaling, zero ICE, zero session state. This is the fastest working band path.
- **RTSP.** ESP32-CAM RTSP firmware (e.g. `rzeldent/esp32cam-rtsp`, `aleiei/ESP32-CAM-RTSP`) serves `rtsp://<ip>:554/mjpeg/1`, ~10-15 FPS VGA. Lower latency, smaller packets, VLC-compatible — but needs an RTSP client on Android (ExoPlayer with RTSP, or libVLC). More moving parts than MJPEG for the same "grab a frame" outcome.

A `MjpegFrameSource` would satisfy the exact same interface as `WebRtcFrameSource` and is realistically the band path we'd ship for the hackathon.

---

## 3. Minimal runnable spike

### 3a. Prove CameraX persistent preview + retain-latest-on-press NOW
Smallest app (single Activity):
- Permissions: `<uses-permission android:name="android.permission.CAMERA"/>`; request at runtime.
- Gradle: `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`.
- Layout: a `PreviewView` (persistent live feed) + a "Capture" button + an `ImageView` (shows retained frame).
- Wire `PhoneCameraSource` from §1a. Button → `lifecycleScope.launch { imageView.setImageBitmap(source.latestFrame()?.bitmap) }`.
- **Success = the preview runs continuously and each button press freezes the current frame into the ImageView, upright, with no preview stutter.** If the preview freezes, you forgot `proxy.close()`.

This is ~150 lines and needs one phone. It is the demo. Build it first.

### 3b. Receive a frame from a stand-in sender over the chosen transport
Pick MJPEG for the spike — it proves the network path with the least ceremony and no second WebRTC stack:
- **Stand-in sender = a laptop webcam or second phone serving MJPEG.** Fastest: `ffmpeg -f v4l2 -i /dev/video0 -f mpjpeg -listen 1 http://0.0.0.0:8080/stream` (Linux webcam → MJPEG server), or an "IP Webcam" Android app on a second phone (serves `http://<ip>:8080/video` MJPEG + `/shot.jpg` snapshot), or the actual ESP32-CAM.
- **Receiver spike:** `MjpegFrameSource.latestFrame()` = `HttpURLConnection` GET `http://<ip>:8080/shot.jpg` → `BitmapFactory.decodeByteArray` → `Frame`. ~40 lines. Put both devices on the phone's hotspot so it's a real same-LAN test.
- **Success = pressing Capture shows the stand-in camera's current frame in the ImageView, through the FrameSource interface, with the Query pipeline unchanged.**
- WebRTC receive spike is a *later* rung: only attempt after MJPEG works end-to-end and only if time remains. It adds `stream-webrtc-android`, a signaling POST, and YUV→Bitmap conversion.

Spike ladder (do in order, stop when out of time):
1. `PhoneCameraSource` end-to-end (guaranteed demo). ✅ ship this.
2. `MjpegFrameSource` from a laptop/second-phone stand-in (proves network `FrameSource`).
3. `MjpegFrameSource` from a real ESP32-CAM (proves band hardware).
4. NFC tap → parse band URI → auto-connect (demo polish).
5. `WebRtcFrameSource` (only if 1-4 done and hours to spare).

---

## 4. Gotchas + GO/NO-GO

**CameraX gotchas**
- Forgetting `ImageProxy.close()` freezes the preview — the #1 pitfall. Always `close()` in `finally`.
- `rotationDegrees` from `imageInfo` must be applied or the retained frame is sideways. The persistent `PreviewView` auto-rotates; the analyzer output does not.
- Do heavy work off the analyzer thread; the analyzer here only stashes a copy, so it stays cheap.
- `RGBA_8888` output format avoids YUV conversion but costs more bandwidth per frame — fine for on-press, not for 30 FPS ML.

**WebRTC / network gotchas**
- **Signaling is the tax, not the media.** SDP offer/answer + ICE exchange is where hackathon hours die. There is no getting a WebRTC frame without first standing up a signaling channel.
- **NAT/local-network:** same-LAN is the easy case — host ICE candidates only, no STUN/TURN. But Android's "Wi-Fi + no internet" and hotspot client isolation can silently drop peer-to-peer traffic. Test that the two devices can actually `ping`/HTTP each other on the chosen network before blaming WebRTC. Some phone hotspots isolate clients — verify.
- **Latency:** MJPEG snapshot pull is fine for on-press capture (we don't need low-latency streaming, we need one fresh frame). RTSP < MJPEG-stream < nothing for continuous; WebRTC is lowest-latency but we don't need that for a single retained frame.
- **Hobby-cam reality:** ESP32-CAM has no WebRTC stack out of the box. Bolting one on is a project. MJPEG/RTSP are what the firmware already speaks. So for the band, **full WebRTC is the wrong tool** — MJPEG is the right one. Keep the interface named around "frame source," not "WebRTC," precisely so this substitution is free.
- **NFC:** foreground dispatch quirks, tag type/NDEF size limits (NTAG213 ~144 bytes — enough for a URI). Nice-to-have, not on the critical path.

**GO / NO-GO**

> **GO:** `PhoneCameraSource` (CameraX persistent preview + `STRATEGY_KEEP_ONLY_LATEST` retain-latest) is the demo and it stands alone — low risk, one phone, ~150 lines, fully documented by primary sources. Build it first behind the `FrameSource` interface.
>
> **CONDITIONAL GO (stretch):** band feed via **`MjpegFrameSource`** (ESP32-CAM `/snapshot` or `/stream`), swapped in behind the same interface, with an NFC tap to hand over the band's IP. This is achievable and cheap.
>
> **NO-GO for the hackathon critical path:** full **WebRTC** to the band. Real signaling/ICE work plus a non-existent WebRTC stack on a hobby cam make it a time sink. Keep `WebRtcFrameSource` as an interface-compatible placeholder to demo *only if everything else is done*. The product design (retain-latest on a pull interface) does not depend on WebRTC, so nothing is lost by deferring it.

---

## Primary sources
- CameraX *Image analysis* (STRATEGY_KEEP_ONLY_LATEST, must close ImageProxy): https://developer.android.com/media/camera/camerax/analyze
- GetStream WebRTC Android (why Google's artifact is gone; API; Maven): https://github.com/GetStream/webrtc-android and https://central.sonatype.com/artifact/io.getstream/stream-webrtc-android
- ESP32-CAM MJPEG (`/stream`, `/snapshot`): https://randomnerdtutorials.com/esp32-cam-video-streaming-web-server-camera-home-assistant/ and https://github.com/arkhipenko/esp32-cam-mjpeg
- ESP32-CAM RTSP (`rtsp://<ip>:554/mjpeg/1`): https://github.com/rzeldent/esp32cam-rtsp and https://github.com/aleiei/ESP32-CAM-RTSP
- WebRTC upstream (build-from-source context): https://webrtc.googlesource.com/src/
