# TEAM.md — how we build this

Working guide for the team. Product overview is in [`README.md`](README.md); the *why* behind every decision is in [`docs/adr/`](docs/adr/).

---

## Who owns what

| Track | Owns | Language | Blocked by |
|---|---|---|---|
| **App** | the Android app | Kotlin | starts at [#1](../../issues/1) |
| **AI PC server** | `server/` | Python | **nothing — [#19](../../issues/19) can start now** |
| **Band** | capture, haptics, transport | C/Python on the UNO Q | independent |

The two software tracks **cannot conflict** — different directories, different languages. They meet at one HTTP contract and one config value.

## Start here

Tickets are [GitHub Issues](../../issues). Work the **frontier**: any issue whose blockers are closed. Right now that's:

- **[#1](../../issues/1) Walking skeleton** — app track
- **[#19](../../issues/19) AI PC server** — server track

Take one issue at a time, in a fresh context. Each is a **vertical slice**: when it's done, something new works end to end.

---

## Setup

### Android build

Create `android/local.properties` (gitignored) with the SDK path and your Sarvam key:

```properties
sdk.dir=/path/to/Android/Sdk
sarvam.api.key=<your key>
```

**System JDK 25 will not work** — Gradle 8.x's Kotlin compiler throws `IllegalArgumentException: 25.0.3` parsing the version. Use Android Studio's bundled JBR (21), or any JDK 17–21.

⚠️ **`org.gradle.java.home` does not belong in `local.properties`** — Gradle never reads that file (it's an Android-plugin convention for `sdk.dir` only), so putting it there silently does nothing and you still get `25.0.3`. This doc said otherwise until #1 hit the wall. Pick one that works:

```bash
JAVA_HOME=/path/to/android-studio/jbr ./gradlew assembleDebug   # per invocation
```

…or set `org.gradle.java.home` in **`~/.gradle/gradle.properties`** (your machine, outside the repo — note it applies to all your Gradle projects). Android Studio's own Gradle JDK setting also works and is per-project.

Without `sdk.dir` you get `SDK location not found`.

### Models — sideloaded, not bundled, not downloaded

Models are 1–6 GB. They are **not** in the APK and **must not** be downloaded at the venue (we measured the hall network swinging between 0.3 and 15 MB/s with repeated drops — a 6 GB pull took hours and got killed).

```bash
adb push <bundle>/. /sdcard/Android/data/<pkg>/files/models/<engine>/
```

⚠️ **Push the bundle's *contents*, note the trailing `/.`** — not the folder. `adb push <dir> <target>` creates the new subdirectory as `shell:ext_data_rw` mode 770, which the app's uid cannot traverse, so the model becomes invisible to the app that needs it. The engine directory is created *by the app*, so it stays app-owned and readable. Symptom if you get this wrong, and it does not name the real cause:

```
[plugins/qairt/src/vlm.cpp:88] No .bin LLM shards found in: …/files/models/geniex
```

One directory holds one bundle, which is why each backend gets its own (`geniex/`, `geniex-gguf/`).

The app **scans that directory at startup** — adding a model is pushing a folder, no rebuild. Living in *external* files means an uninstall/reinstall doesn't wipe them.

Exception: **GenieX downloads its own models** through its ModelManager. That's its design; leave it alone.

### QAIRT dispatches on a fixed registry of VLM architectures

The QAIRT plugin will only load a bundle whose architecture it has a factory for. Get this wrong and the error names the model, not the cause:

```
dispatch: no VLM factory matches model_id 'qwen3_vl_4b_instruct'
```

Read the factories straight out of the shipped `.so` rather than guessing:

```bash
strings -n 5 jni/arm64-v8a/libgeniex_plugin_qairt.so | grep -oE "geniex[0-9]+[a-z0-9_]*(vl)[a-zA-Z0-9_]*" | sort -u
```

`0.3.1` had one (`qwen2_5_vl`). `0.3.12` has `qwen2_5_vl` **and** `qwen3_vl` — which is what unblocked our bundle. Text-only LLM factories are `qwen2_5` and `qwen3`.

`llama_cpp` has no such registry and will take any GGUF with an `mmproj`, which is why it is the fallback.

### Secrets

Sarvam credentials go in env/secure config. **This repo is public** — a committed key is a compromised key.

---

## The server contract (frozen)

Both tracks build against this. It already exists in `server/` and is tested.

```
POST /v1/describe   {mode, question, language, image_b64}  →  {spoken, rich, confident}
GET  /health        →  {status, backend}
```

- **base64 JSON, not multipart.**
- The **prompt lives server-side** in `server/prompt.py`. Do not write a private prompt anywhere else — that file encodes the spoken contract (terse, the VLM never states distance, hedge-never-guess). A second prompt means the phone silently behaves differently, and we have already lost hours to exactly that.
- Adding a VLM = **one backend class** implementing `generate(image_bytes, prompt) -> str`. Selected by `TS_VLM_BACKEND`.
- `server/test_app.py` green (`TS_VLM_BACKEND=mock`) **is** the compatibility check between tracks.

---

## Hard rules

These are design invariants, not preferences. Breaking them reintroduces bugs we already paid for.

1. **Load the model once and keep it.** Lock-guarded; release **only** when switching engine/model — never on Activity recreation. One VLM resident at a time. *(The previous app dropped its model on every rotation, leaked the native allocation, drove available RAM to ~1 GB and was OOM-killed six times.)*
2. **Never state a distance you didn't measure.** Robust percentile of *valid* depth pixels, minimum-valid-pixel threshold, otherwise **"distance unknown."** Average valid coverage is 62.6% — this path fires constantly. See [ADR-0013](docs/adr/0013-ir-aligned-calibration-free-distance.md).
3. **The VLM never states distance.** It cannot measure. Distance is appended by the phone from depth.
4. **Every press yields speech.** Silence, ASR failure, model failure — all degrade to *something spoken*. Never a dead press.
5. **No double-press gesture.** Three buttons × tap/hold is enough, and double-press is unreliable for users with tremor.
6. **Speech only after the mic closes**, or our own TTS feeds back into ASR.
7. **Privacy mode must actually block the cloud**, not just relabel the UI.

---

## Gotchas we've already hit

Save yourself the afternoon:

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — a differently-signed build can't update in place. Uninstall first (this wipes app data; models in the *external* dir survive).
- **OnePlus/ColorOS kills background apps aggressively**, including mid-download. Mitigate while developing:
  ```bash
  adb shell dumpsys deviceidle whitelist +<pkg>
  adb shell settings put global stay_on_while_plugged_in 3
  ```
- **`pkill -f "<name>"` can kill your own shell** (its command line contains the pattern). Use `pkill -x <name>`.
- **Depth holes are not an edge case.** Glass, dark and reflective surfaces read invalid. Test against a hole-heavy scene deliberately.
- **RGB is 16:9, depth/IR are 4:3** — different sensors 24.9 mm apart, so part of the colour frame has no depth behind it. **Corrected 2026-07-18, and the earlier corrections were also wrong.** Measured, not inferred: the 1280x720 colour mode is **neither a crop of the 4:3 frame nor an anamorphic scale of it** — it is a horizontally *wider*, vertically *narrower* readout. Both theories this file previously carried were false.

  The method that worked, after three cross-modal RGB-IR attempts failed (ORB 10 inliers, SIFT 4, template matching at noise): compare the **colour camera against itself** at different resolutions over its UVC interface. Same modality, so it converges — NCC 0.98. A joint fit of 1280x960 -> 1280x720 gives sx 0.886, sy 0.887: the 4:3 frame's full width maps to only 88.6% of the 720p frame's width.

  Real 720p intrinsics, corroborated against Orbbec's datasheet to within 1.7%:

  | | fx | fy | cx | cy |
  |---|---|---|---|---|
  | `calib.json` 1280x720 | 1090.8 | 818.1 | 635.4 | 364.3 |
  | **measured** | **966.5** | **967.3** | **634.6** | **364.4** |

  So `fy` should indeed equal `fx` (measured ratio 1.0008, square pixels) — but this file's earlier fix of `fy = fx = 1090.8` was wrong too, because **`fx` itself is off by 13%**. An Orbbec maintainer confirms the Pro Plus's high-resolution colour "is indeed not calibrated" ([OrbbecSDK_ROS2 #65](https://github.com/orbbec/OrbbecSDK_ROS2/issues/65)); `calib.json`'s 720p block is self-labelled "approximate".

  Resulting coverage: **x 0.09-0.88, full height.** Horizontal is the only real constraint. The previous bounds (x 0.034-0.928, y 0.025-0.955) were computed in the **640x480** colour frame and applied to **1280x720** images — a unit mismatch, not a bad estimate — and admitted side bands containing zero depth. Handled by `DepthCoverage`, which crops the frame before the VLM sees it so the VLM cannot describe something we can never measure.

- **Depth sees MORE vertically than colour, not less.** Depth V 45.0 deg against 720p colour V **40.8 deg** (not the 36.5 deg this file used to claim, which came from the bad `fy`). Measured on 3.87 M points across all 20 captures: 8.1% of depth projects above the colour frame and 4.8% below. `calib.json`'s note that "depth covers only the central vertical region" is backwards, and so was our vertical crop.

- **There is no hardware depth-to-colour registration on the Pro Plus.** Colour is a UVC device (`2bc5:050f`) and depth is a separate OpenNI device (`2bc5:060f`); Orbbec states the OpenNI SDK does not support the UVC RGB camera. The depth firmware never sees colour frames, so there is nothing to register against — alignment is ours to do, by reprojection.

- **⚠️ "IR brightness predicts depth holes" does not reproduce on the shipped captures.** A live session measured mean IR 185 at holes vs 55 at valid pixels (3.4x). But across the 20 captures that ship in the APK, **mean IR is 3.17/255** — the frames are essentially black — and the contrast is *inverted* (0.855). Both can be true under different exposure, but [ADR-0013](docs/adr/0013-ir-aligned-calibration-free-distance.md)'s "IR saturation doubles as a depth-confidence signal" has **no support in our own test data**, so anything built on it will not work offline. Re-measure before depending on it.

- **`calib.json` is not trustworthy above 640x480.** Its 640x480 block is factory calibration; its 1280x720 block is a rescale it admits is approximate. Depth intrinsics (fx/fy 579.55, cx 317.87, cy 243.06) and `depth_to_color_extrinsics` (baseline X -24.888 mm) are fine.

---

## Test data

21 real captures from the band camera: `rgb.jpg` (1280×720), `depth_raw.npy` (640×480 `uint16`, millimetres), `ir.jpg` (640×480), `metadata.json`. Metric depth spans 0.45–9.94 m.

They ship in the APK so the **whole pipeline runs with no band hardware** — that's [#3](../../issues/3), and it unblocks both the distance work and the benchmark. Do it early.

---

## Docs map

| File | What it's for |
|---|---|
| [`README.md`](README.md) | product overview (shareable) |
| [`CONTEXT.md`](CONTEXT.md) | domain glossary + settled design — **start with the REBUILD section** |
| [`docs/adr/0010`–`0013`](docs/adr/) | the current architecture decisions |
| `docs/adr/0001`–`0009` | earlier decisions; several superseded — check the header note on each |
| `server/` | the cloud tier (contract above) |

### GenieX version is load-bearing — keep it current

We were pinned to `0.3.1` while `0.3.12` was out, and that one number was the difference between "Qwen3-VL cannot run on the NPU" and a working NPU path. `0.3.1`'s QAIRT plugin exports only `qwen2_5_vl`; `0.3.12` adds `geniex::qwen3_vl::makeModel`. Same bundle, same device, same code — only the SDK changed.

Measured on one capture, 2026-07-18 (#2):

| | QAIRT / NPU (Qwen3-VL-4B) | llama_cpp / GPU (Gemma-4-E4B) |
|---|---|---|
| model load (once) | 6.7–7.2 s | 26.9 s |
| TTFT | 260 ms | 3417 ms |
| prefill | 1287 tok/s | 93 tok/s |
| decode | 28 tok/s | 12–14 tok/s |

The NPU run was also the more accurate of the two. `ModelConfig`'s 12-arg positional constructor is unchanged across the upgrade, so the bump is source-compatible.

**AI Hub's Gemma-4-E4B has no QAIRT bundle** — its `release_assets.json` lists only `geniex_llamacpp` (q4_0 GGUF, pointing at Google's repo). Gemma cannot reach the NPU this way; "supported on 8 Elite Gen 5" there means the GGUF runs, not that an NPU build exists.
