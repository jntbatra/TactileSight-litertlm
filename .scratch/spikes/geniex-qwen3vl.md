# Spike: Qwen3-VL-4B on-device via Qualcomm GenieX (Snapdragon Android)

> **Partly current (noted 2026-07-18).** GenieX is now **one of three** engines
> ([ADR-0010](../../docs/adr/0010-three-engine-on-device-inference.md)), so this spike still applies
> to that engine — but it is no longer the whole plan. Since it was written we ran it for real on the
> 8 Elite: **71 tok/s prefill / 11 tok/s decode** (Gemma-4-E4B), **20/13** (Qwen3-VL-4B) — on the
> **GPU** via `llama_cpp`, **not** the NPU. Reaching the NPU is the open question the three-engine
> benchmark exists to answer.

Status: research spike — plan validated against primary sources, NOT yet run on hardware.
Goal: prove `one RGB frame + one text prompt -> short text answer`, fully on-device, on a
flagship Snapdragon Android phone, and measure end-to-end latency. This is the TactileSight
vision-language "brain."

Date: 2026-07-11. Hackathon: Noida, July 18-19 2026.

---

## TL;DR / bottom line up front

- **GenieX is real** and is the right runtime. It exposes two backends: `qairt` (Qualcomm AI
  Runtime, Hexagon NPU only, from precompiled AI Hub bundles) and `llama_cpp` (community GGUF,
  NPU/GPU/CPU). Source: <https://github.com/qualcomm/GenieX>, <https://aihub.qualcomm.com/geniex>.
- **Qwen3-VL-4B-Instruct is real and on AI Hub** as a GenieX-QAIRT model:
  <https://aihub.qualcomm.com/models/qwen3_vl_4b_instruct>.
- **CRITICAL DEVICE CAVEAT:** AI Hub lists this model's supported mobile devices as
  **Snapdragon 8 Elite** and **Snapdragon 8 Elite Gen 5** (plus X/X2 Elite laptops and
  QCS9075/SA8775P IoT/auto). **Snapdragon 8 Gen 2 and 8 Gen 3 are NOT on the supported list.**
  The same is true for the sibling text model `qualcomm/Qwen3-4B` on Hugging Face. Precompiled
  QAIRT bundles are compiled per-SoC (per Hexagon arch), so an 8-Elite bundle will not load on an
  8 Gen 2/3.
- **Consequence for the stated target (8 Gen 2/3):** the GenieX-QAIRT primary path is *not
  guaranteed* on 8 Gen 2/3 with the stock bundle. Two realistic routes:
  1. **Get an 8-Elite class device for the demo** -> QAIRT-primary path is officially supported
     and low-risk. This is the strong recommendation.
  2. **Stay on 8 Gen 2/3** -> treat **llama.cpp GGUF (Qwen3-VL-4B-Instruct-GGUF + mmproj)** as the
     primary, not the fallback. It will most likely run on Adreno GPU/CPU (a few seconds latency,
     which the brief says is acceptable). Hexagon offload for VLMs via llama.cpp on 8 Gen 2/3 is
     unproven — do not assume NPU acceleration there.

Full GO/NO-GO criteria at the bottom.

---

## 1. Setup path

### 1a. Components and where they come from

| Piece | Source (primary) | Notes |
|---|---|---|
| GenieX runtime | `github.com/qualcomm/GenieX` | "on-device Gen AI inference runtime… run frontier LLMs and VLMs across NPU, GPU, CPU." Community version of Qualcomm "Genie". |
| GenieX Android lib | Gradle: `com.qualcomm.qti:geniex-android:0.3.1` | Version string per repo README at time of research — **verify exact latest version** before pinning. |
| GenieX Python | `pip install geniex` | For desktop/laptop-on-Snapdragon or Linux ARM64 dev. |
| GenieX CLI | `curl -fsSL https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-geniex/install.sh \| sh` | Linux ARM64 / Windows-on-Snapdragon. Handy for a fast desk check. |
| QAIRT model bundle (NPU) | AI Hub model page -> HF `qualcomm/*` repo, download the row matching your device | For Qwen3-VL-4B: `aihub.qualcomm.com/models/qwen3_vl_4b_instruct`. QAIRT SDK **2.45**, precision **w4a16**, context **4096**. |
| GGUF bundle (fallback / 8 Gen 2/3) | `huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF` (model + `mmproj-*.gguf`) | Community GGUF with the multimodal projector needed for image input. Precision `Q4_0` is the GenieX-recommended llama.cpp precision ("best Hexagon NPU support"). |
| QAIRT SDK (for the tutorial export path) | Qualcomm AI Engine Direct / QAIRT 2.45 | Needed only if you export a bundle yourself instead of downloading. |
| Android reference app | `github.com/qualcomm/ai-hub-apps` (Kotlin ChatApp / GenieX demo) | Copy its wiring; don't build from scratch. |

### 1b. Two GenieX backends (the mental model)

- **`qairt` (primary, NPU-only):** loads a *precompiled* bundle built for a *specific* Snapdragon.
  Best latency, uses Hexagon NPU. Requires Hexagon v73+. Bundle must match your SoC — this is the
  whole 8 Gen 2/3 problem.
- **`llama_cpp` (fallback, broad):** loads any GGUF from Hugging Face, dispatches to NPU/GPU/CPU.
  Works on far more devices, including where no QAIRT bundle exists. Use `Q4_0`. VLM support needs
  the `mmproj` projector file. Expect GPU/CPU (not NPU) on 8 Gen 2/3.

Hexagon versions (for the v73+ requirement): 8 Gen 2 = v73, 8 Gen 3 = v75, 8 Elite ≈ v79. So
8 Gen 2/3 *meet* the raw v73+ floor — the blocker is bundle *availability/compilation*, not the
architecture floor.

### 1c. Wiring into an Android/Kotlin app (shape)

1. Add `implementation("com.qualcomm.qti:geniex-android:<ver>")` to `build.gradle.kts`.
2. Ship the model bundle: push to `/data/local/tmp` for CLI experiments, or package/download into
   app-private storage for the app (a 4B w4a16 bundle is ~2.5-3 GB — do not bundle in the APK;
   download on first run).
3. Kotlin flow mirrors the Python API: load model -> build chat prompt with an image -> stream
   tokens. Use `ai-hub-apps` Kotlin ChatApp as the template; extend its message content to include
   the camera frame (VLM image input).
4. TactileSight integration point: `CameraX frame (RGB) + fixed prompt -> GenieX.generate() ->
   short text -> haptic/TTS layer`.

Python reference (from GenieX README — Kotlin API mirrors this):

```python
# GGUF via llama.cpp (fallback / 8 Gen 2/3)
from geniex import AutoModelForCausalLM
model = AutoModelForCausalLM.from_pretrained("Qwen/Qwen3-VL-4B-Instruct-GGUF", precision="Q4_0")

# QAIRT via AI Hub bundle (primary / 8 Elite)
model = AutoModelForCausalLM.from_pretrained("ai-hub-models/Qwen3-VL-4B-Instruct")
```

---

## 2. Minimal spike — smallest thing that proves image+prompt->text + latency

Run all three checks in order; stop at the first that passes on your actual device.

### Check A — desk sanity (fastest, on an 8-Elite dev box or Linux ARM64)

```bash
# install CLI
curl -fsSL https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-geniex/install.sh | sh
# pull + run the AI Hub VLM bundle; drop an image in when prompted
geniex infer ai-hub-models/Qwen3-VL-4B-Instruct
```

Proves the model + GenieX-QAIRT path works at all before touching a phone.

### Check B — QAIRT bundle on the phone NPU (primary path)

Uses the `llm_on_genie` tutorial flow (genie CLI on-device).
Source: <https://github.com/qualcomm/ai-hub-apps/tree/main/tutorials/llm_on_genie>.

```bash
# 1. Download the device-matching bundle row from the model's HF page, unzip -> genie_bundle/
# 2. Push to device
adb push genie_bundle /data/local/tmp
adb shell

# 3. On-device env (aarch64-android), Hexagon v73 libs
export QAIRT_HOME=<path_to_sdk_on_device>
export PATH=${QAIRT_HOME}/bin/aarch64-android/:${PATH}
export LD_LIBRARY_PATH=${QAIRT_HOME}/lib/aarch64-android:${LD_LIBRARY_PATH}
export ADSP_LIBRARY_PATH=${QAIRT_HOME}/lib/hexagon-v73/unsigned

# 4a. Text smoke test (proves NPU generate loop)
genie-t2t-run -c genie_config.json --prompt_file prompt.txt --profile perf.txt

# 4b. VLM image+text: use the model-provided script that "exercises image input"
genie-app -s genie-app-script.txt
```

`--profile perf.txt` gives token timings for the latency measurement. For VLMs the model ships a
`genie-app-script.txt`; use it rather than hand-formatting the multimodal prompt.

### Check C — llama.cpp GGUF multimodal (fallback / the realistic path on 8 Gen 2/3)

GenieX `llama_cpp` backend, or plain llama.cpp `llama-mtmd-cli` cross-compiled for android-arm64:

```bash
# model + projector
# from huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF
adb push Qwen3-VL-4B-Instruct-Q4_0.gguf   /data/local/tmp/
adb push mmproj-Qwen3-VL-4B-Instruct-F16.gguf /data/local/tmp/
adb push test.jpg /data/local/tmp/

adb shell
cd /data/local/tmp
# time it for the latency number
time ./llama-mtmd-cli \
  -m   Qwen3-VL-4B-Instruct-Q4_0.gguf \
  --mmproj mmproj-Qwen3-VL-4B-Instruct-F16.gguf \
  --image test.jpg \
  -p "Describe what is directly ahead in one short sentence." \
  -n 64
```

Via the GenieX Python/Kotlin API instead: load `Qwen/Qwen3-VL-4B-Instruct-GGUF` at `Q4_0`, add the
image to the chat message, stream up to ~64 tokens, wrap the `generate()` call in a timer.

**Latency target:** brief says "a few seconds is acceptable." Measure time-to-first-token and
tokens/sec separately (image encode via the ViT projector is a big one-time cost per frame; the
text decode is the streaming part).

---

## 3. Gotchas / failure modes

1. **Device support is THE risk.** Qwen3-VL-4B QAIRT bundles are published for 8 Elite / 8 Elite
   Gen 5 (mobile), not 8 Gen 2/3. A bundle compiled for one Hexagon arch will not load on another.
   Confirm your exact phone SoC first. (Verified on the AI Hub model page and `qualcomm/Qwen3-4B`
   HF card.)
2. **"Export it yourself" is not a guaranteed escape hatch.** The `qai-hub-models` export flow can
   target specific devices, but VLM export + an 8 Gen 2/3 target is unproven here and needs cloud
   compile jobs + QAIRT 2.45 — not something to discover at the hackathon. Treat as a spike-before,
   not a demo-day, activity.
3. **NPU access / permissions.** On-device QAIRT needs the Hexagon libs on `ADSP_LIBRARY_PATH`
   (`lib/hexagon-v73/unsigned`) and skel/stub libs present. Retail phones can restrict unsigned
   Hexagon offload; a dev/engineering device or the vendor QRD is safest. Non-rooted retail phones
   may fall back to CPU silently.
4. **llama.cpp on Hexagon is nascent for VLMs.** The `mmproj` vision path likely runs on Adreno
   GPU/CPU, not NPU, on 8 Gen 2/3. Do not promise NPU acceleration on the fallback. Still fine for a
   "few seconds" answer.
5. **Memory footprint.** 4B at w4a16/Q4_0 ≈ 2.2-2.8 GB weights, plus ViT vision encoder, plus KV
   cache at 4096 context, plus activations -> realistically ~4-6 GB peak. The llm_on_genie tutorial
   recommends **12-16 GB device memory** for larger models. On an 8/12 GB phone this is tight with
   OS + camera + app; watch for OOM kills. Cap context (512-1024 is plenty for a one-shot Q&A) to
   shrink KV cache.
6. **Context / prompt format.** QAIRT bundles here are fixed at 4096 context. Use the model's chat
   template (`<|im_start|>…`); for VLMs use the provided `genie-app-script.txt` rather than
   hand-rolling image tokens.
7. **Version pinning.** `geniex-android:0.3.1` and QAIRT 2.45 are the versions seen during
   research; the bundle's listed QAIRT version must match the on-device SDK. Re-verify current
   numbers before the event — these move.
8. **Per-frame ViT cost.** Each new RGB frame re-runs the vision encoder. For TactileSight's
   continuous use this dominates latency; plan for on-demand capture (matches ADR-0007 on-demand
   default), not per-frame streaming.

---

## 4. GO / NO-GO checklist (day one)

Do these in order on the morning of day one. First branch that passes wins.

### Gate 0 — identify the device (5 min)
- [ ] Confirm exact SoC (`adb shell getprop ro.soc.model` / `ro.board.platform`).
- [ ] 8 Elite / 8 Elite Gen 5?  -> pursue **GenieX-QAIRT primary**.
- [ ] 8 Gen 2 / 8 Gen 3?        -> pursue **llama.cpp GGUF primary**; QAIRT is a stretch goal only.

### GO — GenieX-QAIRT (NPU primary) — pick this if ALL true
- [ ] Device is on the AI Hub supported list for `qwen3_vl_4b_instruct` (8 Elite / Elite Gen 5).
- [ ] Precompiled bundle for that exact device downloads + unzips to `genie_bundle/`.
- [ ] `genie-t2t-run … --profile` returns text on-device (NPU generate loop works).
- [ ] `genie-app -s genie-app-script.txt` returns text for an image+prompt (VLM path works).
- [ ] Peak memory fits the phone (no OOM at 4096 ctx; drop context if needed).
- [ ] End-to-end (frame->answer) within a few seconds.
=> **GO GenieX-QAIRT primary.**

### GO — llama.cpp GGUF (fallback / 8 Gen 2/3 primary) — pick this if
- [ ] `Qwen3-VL-4B-Instruct-GGUF` + `mmproj` push to device.
- [ ] `llama-mtmd-cli --image … -p …` (or GenieX `llama_cpp` at `Q4_0`) returns a sensible answer
      about the image.
- [ ] Latency acceptable (a few seconds; on-demand, not per-frame).
- [ ] Memory OK at reduced context (512-1024).
=> **GO llama.cpp fallback.** Accept CPU/GPU execution; do not claim NPU on 8 Gen 2/3.

### NO-GO / escalate — if
- [ ] No QAIRT bundle for the device AND llama.cpp multimodal fails to load/produces garbage.
- [ ] Memory OOMs even at minimum context.
- [ ] Retail device blocks Hexagon offload and CPU path is > ~10 s (too slow to demo).
=> Escalate: (a) borrow/switch to an 8-Elite device, or (b) drop model size to a 2-3B VLM GGUF,
   or (c) for the demo only, run the VLM on a tethered Snapdragon laptop / edge box while keeping
   the on-device claim scoped to the roadmap. Flag the privacy trade-off explicitly.

---

## Sources (primary)

- GenieX: <https://github.com/qualcomm/GenieX>, <https://github.com/qualcomm/GenieX/blob/main/notes/run.md>, <https://aihub.qualcomm.com/geniex>
- Model: <https://aihub.qualcomm.com/models/qwen3_vl_4b_instruct>, <https://huggingface.co/qualcomm/Qwen3-4B>
- On-device tutorial: <https://github.com/qualcomm/ai-hub-apps/tree/main/tutorials/llm_on_genie>
- GGUF multimodal fallback: <https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF>, <https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md>

## Confidence / uncertainty ledger
- HIGH: GenieX exists with qairt+llama.cpp backends; Qwen3-VL-4B is on AI Hub as GenieX-QAIRT;
  8 Gen 2/3 are absent from the supported-device list; GGUF+mmproj fallback exists.
- MEDIUM: exact `geniex-android` version, QAIRT 2.45 currency, memory peak estimate.
- LOW / UNVERIFIED: whether a self-exported QAIRT bundle can target 8 Gen 2/3 for this VLM;
  whether llama.cpp offloads the VLM to Hexagon on 8 Gen 2/3 (assume no); exact GenieX Kotlin VLM
  image API surface (mirror `ai-hub-apps` ChatApp, confirm on device).
