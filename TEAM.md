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

The build is picky about the JDK — we lost time to this, so it's pinned in `local.properties` (gitignored, create your own):

```properties
sdk.dir=/path/to/Android/Sdk
org.gradle.java.home=/path/to/android-studio/jbr   # JDK 21
```

**System JDK 25 will not work** — Gradle 8.x's Kotlin compiler throws `IllegalArgumentException: 25.0.3` parsing the version. Use Android Studio's bundled JBR (21). Without `sdk.dir` you get `SDK location not found`.

### Models — sideloaded, not bundled, not downloaded

Models are 1–6 GB. They are **not** in the APK and **must not** be downloaded at the venue (we measured the hall network swinging between 0.3 and 15 MB/s with repeated drops — a 6 GB pull took hours and got killed).

```bash
adb push <model-dir> /sdcard/Android/data/<pkg>/files/models/<engine>/
```

The app **scans that directory at startup** — adding a model is pushing a folder, no rebuild. Living in *external* files means an uninstall/reinstall doesn't wipe them.

Exception: **GenieX downloads its own models** through its ModelManager. That's its design; leave it alone.

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
- **RGB is 16:9, depth/IR are 4:3** — depth covers only the central region. Objects at the top/bottom of the RGB frame have no depth.
- **`calib.json`'s 1280×720 colour intrinsics are wrong** (`fy` assumes a stretch; 16:9 is a crop, so `fy` should equal `fx`). Irrelevant for the IR path — it needs no colour intrinsics — but don't build RGB↔depth projection on it without a proper checkerboard calibration.

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
