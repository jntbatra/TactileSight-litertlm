# Spike: AI4Bharat speech on Android — on-device multilingual ASR + TTS

Status: ready-for-human
Owner: pixelpunchllp@gmail.com
Date: 2026-07-11
Target languages: Hindi (hi), Tamil (ta), Telugu (te), Bengali (bn), Marathi (mr)
Constraint: fully on-device, CPU-only, no network (blind users, privacy). "Real and working, not mocked."

---

## 0. TL;DR / GO-NO-GO

- **ASR (IndicConformer): GO.** AI4Bharat IndicConformer is a hybrid CTC + RNNT Conformer.
  Export the **CTC branch to ONNX**, run it with **ONNX Runtime Mobile** (directly, or via
  **sherpa-onnx**, which already ships a prebuilt Android AAR, a mic frontend, and a Silero VAD).
  Use the **30M** real-time variant (or an INT8-quantized 120M monolingual) for CPU real-time.
  The 600M multilingual model is accurate but too heavy for a phone.
- **TTS (AI4Bharat Indic Parler-TTS): NO-GO on device.** Indic Parler-TTS is Parler-TTS Mini =
  **880M-param autoregressive transformer** decoding audio tokens one step at a time. On a phone
  CPU this is **many seconds per sentence** — unusable for a live navigation aid.
- **TTS (production choice): GO with Android built-in TTS.** Google's on-device TTS engine has
  downloadable **offline** voices for hi/ta/te/bn/mr. Latency is tens–low-hundreds of ms.
  Force offline by selecting a `Voice` where `isNetworkConnectionRequired() == false`.
  Fallback for any language Google can't do offline: **sherpa-onnx + Piper/VITS** (CPU real-time).
- **Android's default `SpeechRecognizer` is CLOUD** (audio → Google servers). Confirmed below.
  That is a privacy leak and needs connectivity, so we do **not** use it — we use IndicConformer.

**Bottom line: build ASR on IndicConformer-CTC (ONNX Runtime Mobile / sherpa-onnx), build TTS on
Android's offline TTS engine, and treat Indic Parler-TTS as desktop-only. The single biggest risk —
TTS latency — is solved by NOT using a neural autoregressive TTS on device.**

---

## 1. ASR — IndicConformer on Android

### 1.1 Model landscape (verified)

AI4Bharat ships several distinct things under the "IndicConformer" name — do not confuse them:

| Model | Params | Where | Fit for phone |
|---|---|---|---|
| IndicConformer **30M** (original real-time variant) | ~30M | `models.ai4bharat.org` / older release; docs say "can be deployed on an android device and accessed via websockets" | **Best fit** — small, real-time on CPU |
| `indicconformer_stt_<lang>_hybrid_ctc_rnnt_large` (monolingual) | **120M** (17 Conformer blocks, dim 512) | HF, one repo per language (hi/ta/te/bn/mr all exist) | OK if **INT8-quantized**; borderline |
| `indic-conformer-600m-multilingual` | **600M** | HF `ai4bharat/indic-conformer-600m-multilingual` | Too big for a phone; server-side only |

- Repo: <https://github.com/AI4Bharat/IndicConformerASR> (MIT).
- HF collection: <https://huggingface.co/collections/ai4bharat/indicconformer-66d9e933a243cba4b679cb7f>
- All are **hybrid CTC + RNNT**: one encoder, two decoder heads. You pick the head at runtime.
- Input: **16 kHz mono** audio (hard requirement).
- Native framework is AI4Bharat's **NeMo fork**:
  `git clone https://github.com/AI4Bharat/NeMo.git && cd NeMo && git checkout nemo-v2 && bash reinstall.sh`

Reference PyTorch inference (from the HF model cards) — this is what we replicate in ONNX:

```python
import nemo.collections.asr as nemo_asr
model = nemo_asr.models.ASRModel.from_pretrained("ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large")
model.freeze(); model.eval()

model.cur_decoder = "ctc"
ctc_text  = model.transcribe(['16k_mono.wav'], batch_size=1, logprobs=False, language_id='hi')[0]

model.cur_decoder = "rnnt"
rnnt_text = model.transcribe(['16k_mono.wav'], batch_size=1, language_id='hi')[0]
```

Note `language_id='hi'` — the **model is told the language**; it does not auto-detect (see §1.5).

### 1.2 CTC vs RNNT — which to ship

| | CTC | RNNT (transducer) |
|---|---|---|
| ONNX export | **Single .onnx file**, trivial | 3 graphs (encoder + decoder/predictor + joiner) + a decode loop you write yourself |
| Decode | Greedy argmax over frames + collapse blanks — a few lines | Step-wise transducer loop, more code, more per-step overhead |
| Accuracy | Slightly lower | Slightly higher, better on long/streaming audio |
| CPU cost | Lower | Higher (autoregressive predictor) |

**Ship CTC for the spike and probably for the product.** For short command-style utterances the CTC
WER gap is small, export is one file, and greedy CTC is fastest on CPU. Keep RNNT as a later
accuracy upgrade. sherpa-onnx supports **both** if you want RNNT later.

### 1.3 ONNX export

**Fastest path — don't export at all, use the community package** `indic-asr-onnx` (PyPI). It ships
**INT8-quantized** IndicConformer ONNX models with both heads, CPU-optimized:

```bash
pip install indic-asr-onnx
```
```python
from indic_asr_onnx import IndicTranscriber
t = IndicTranscriber()
print(t.transcribe_ctc("audio.wav", "hi"))   # INT8 CTC, CPU
print(t.transcribe_rnnt("audio.wav", "hi"))
```
Use this to prove ASR quality on your target languages in minutes on a laptop, and to harvest the
`.onnx` + tokenizer files that you then bundle into the app.

**Manual export path (full control):** load in NeMo and call the exporter:
```python
model.cur_decoder = "ctc"
model.export("indicconformer_hi_ctc.onnx")   # NeMo ONNX exporter; CTC → single graph
```
Then quantize for mobile:
```python
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("indicconformer_hi_ctc.onnx", "indicconformer_hi_ctc.int8.onnx", weight_type=QuantType.QInt8)
```
For the sherpa-onnx route, follow their NeMo→sherpa export doc (it also emits the `tokens.txt`):
<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/nemo/how-to-export.html>

### 1.4 Runtime on Android — two options

**Option A (recommended for the spike): sherpa-onnx.** It wraps ONNX Runtime and already solves
the boring parts: prebuilt **Android AAR**, `AudioRecord` mic capture, **Silero VAD**, CTC/RNNT
greedy decode, and example apps. It runs NeMo Conformer-CTC ONNX models out of the box.
- Repo: <https://github.com/k2-fsa/sherpa-onnx> · Models/APKs: releases `tag/tts-models`, ASR docs
  <https://k2-fsa.github.io/sherpa/onnx/> — includes NeMo Conformer-CTC entries.
- Kotlin sketch:
```kotlin
val config = OfflineRecognizerConfig(
    modelConfig = OfflineModelConfig(
        nemoCtc = OfflineNemoEncDecCtcModelConfig(model = "indicconformer_hi_ctc.int8.onnx"),
        tokens = "tokens.txt", numThreads = 2, provider = "cpu"))
val recognizer = OfflineRecognizer(assets, config)
val stream = recognizer.createStream()
stream.acceptWaveform(pcmFloat16k, sampleRate = 16000)   // from AudioRecord, mono 16 kHz
recognizer.decode(stream)
val text = recognizer.getResult(stream).text
```

**Option B (full control): ONNX Runtime Mobile directly.**
- Add `onnxruntime-android` (or the smaller `onnxruntime-mobile`) AAR.
- Capture mic with `AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, …)`, convert PCM16→float, run the model's log-mel front-end (or export a model with the front-end baked in), `OrtSession.run(...)`, then greedy-CTC decode with `tokens.txt`.
- More code; only choose this if you must avoid the sherpa dependency.

**Op support gotcha:** convert with `onnxruntime-mobile` in mind. Conformer uses standard ops
(Conv, MatMul, LayerNorm/Softmax, relative-position attention) that ORT Mobile supports; the ORT
**full** package supports everything, the reduced-op mobile build may need a custom op config
generated from your model. Verify by loading the `.onnx` in an ORT Mobile smoke test before shipping.

### 1.5 Feeding mic audio + automatic language identification

- **Mic:** `AudioRecord` at 16 kHz mono PCM16 → normalize to float [-1,1] → feed frames to VAD →
  on speech-end, run the recognizer on the buffered utterance (offline/non-streaming CTC is simplest;
  utterances in this app are short commands so full-utterance decode is fine).
- **Language identification — the real gap.** IndicConformer needs `language_id`. AI4Bharat's
  **IndicLID is TEXT-only** (script/roman classifier), not audio, so it does **not** solve spoken LID.
  Options, in order of pragmatism:
  1. **User-configured language.** A blind user sets their language once in setup. Zero cost, most
     reliable. **Recommended for the hackathon.**
  2. **600M multilingual model.** Handles multiple languages in one model, but too heavy for phone.
  3. **Separate audio LID** (e.g. a VoxLingua107 / SpeechBrain ECAPA LID, or whisper-tiny's language
     token) run on the first ~2 s. Adds a second model and latency; defer past the spike.

Treat "auto language switching from audio" as a **post-hackathon** feature; ship with a language
setting.

---

## 2. TTS — options and the latency question

### 2.1 Why AI4Bharat Indic Parler-TTS is NOT on-device viable

- Indic Parler-TTS = multilingual Indic extension of **Parler-TTS Mini = 880M params**, a FLAN-T5
  text encoder feeding a **transformer decoder that autoregressively generates audio codec tokens**.
- Autoregressive = one forward pass per audio frame token; hundreds of steps per sentence. Even the
  official speed-ups (SDPA, Flash-Attention-2, `torch.compile`, batching, streaming) target **GPU**.
- Peak memory ~1.8 GB fp16 / 3.6 GB fp32. **This will not synthesize a sentence in ≤1–2 s on a phone
  CPU** — expect several seconds. It is a great **server/cloud** voice, not an on-device one.
- Repos: <https://huggingface.co/ai4bharat/indic-parler-tts> ·
  <https://github.com/AI4Bharat/Indic-TTS> (FastPitch+HiFiGAN; lighter than Parler but still no
  maintained real-time Android build).

### 2.2 Recommended: Android built-in TTS (offline voices)

- Android's `TextToSpeech` API + Google's TTS engine (`com.google.android.tts`) offers
  **downloadable offline voice data** for Indian languages. Google TTS provides voices for
  hi/bn/ta/te/mr (also kn, gu, ml, etc.); third-party engines (e.g. **Vocalizer**) cover
  bn/mr/te/ta/kn/hi too and plug into the same API.
- **These are non-neural / lightweight concatenative-or-parametric voices → latency in the tens to
  low-hundreds of ms.** This is the whole point: it removes the TTS-latency risk.
- **Forcing offline (verified API):** `KEY_FEATURE_NETWORK_SYNTHESIS` is deprecated (API 21). Instead
  enumerate `getVoices()`, keep voices where **`Voice.isNetworkConnectionRequired() == false`**, and
  `setVoice(thatVoice)`. The engine then must synthesize on-device without network.

```kotlin
tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) {
    val hiOffline = tts.voices.firstOrNull {
        it.locale.language == "hi" && !it.isNetworkConnectionRequired &&
        !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
    }
    if (hiOffline != null) tts.voice = hiOffline
}}
```

**Availability gotcha:** offline voice packs are *downloaded per device* (Settings → System →
Languages → Text-to-speech → Install voice data). At startup, check the selected voice really is
offline and installed; if not, prompt to install the pack, or fall back to §2.3. Never assume the
pack is present.

### 2.3 Fallback: sherpa-onnx + Piper/VITS (offline neural, CPU real-time)

- **Piper** (VITS single-pass, end-to-end) runs comfortably on midrange phone CPUs and is designed
  for on-device. sherpa-onnx ships Piper/VITS voices incl. **Hindi** and an Android TTS-engine APK.
- Models: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models> ·
  Docs: <https://k2-fsa.github.io/sherpa/onnx/tts/all/>
- Use this where Google/Vocalizer lack an offline voice for a target language, or when you want a
  consistent voice across all 5 languages without depending on per-device packs.

### 2.4 MEASURE TTS latency on CPU (the key risk) — real code

**Android built-in TTS** — measure end-to-end synth time via `UtteranceProgressListener`:
```kotlin
tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
    var t0 = 0L
    override fun onStart(id: String) { t0 = System.nanoTime() }
    override fun onDone(id: String)  {
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("TTS", "synth+playback ms=$ms")
    }
    override fun onError(id: String) {}
})
val p = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "u1") }
tts.speak("रास्ता साफ है, सीधे चलिए।", TextToSpeech.QUEUE_FLUSH, p, "u1")
```
For **synthesis-only** latency (exclude audio playback), use `synthesizeToFile(...)` and time from
call to `onDone` — this is the number that matters for "does the answer feel instant."

**sherpa-onnx / Piper** — time the generate call and compute RTF:
```kotlin
val t0 = System.nanoTime()
val audio = tts.generate(text, sid = 0, speed = 1.0f)   // GeneratedAudio (samples + sampleRate)
val genMs = (System.nanoTime() - t0) / 1_000_000
val audioMs = 1000L * audio.samples.size / audio.sampleRate
Log.i("TTS", "gen=${genMs}ms audio=${audioMs}ms RTF=${genMs.toFloat()/audioMs}")  // RTF < 1 = faster than real time
```
**Pass criteria:** synth latency for a typical 6–10 word navigation sentence **≤ ~300 ms** feels
instant; **> 1–2 s** is unusable. Android offline voices comfortably pass; Piper on midrange CPU
should give RTF < 1 (measure on the actual target device). Indic Parler-TTS will fail this — do not
even benchmark it on device.

---

## 3. Minimal runnable spike

Two stages: (A) a 5-minute laptop sanity check of AI4Bharat model quality, then (B) the actual
on-device Android proof. Do both — (A) validates the model, (B) validates the phone.

### 3.1 Stage A — desktop ONNX sanity check (proves the AI4Bharat model works, INT8/CPU)

```bash
python -m venv .venv && . .venv/bin/activate
pip install indic-asr-onnx soundfile sounddevice

# record 4s from mic → 16k mono wav (or use ffmpeg on an existing clip)
python - <<'PY'
import sounddevice as sd, soundfile as sf, time
sr=16000; sec=4
print("speak now..."); a=sd.rec(int(sec*sr), samplerate=sr, channels=1); sd.wait()
sf.write("phrase.wav", a, sr)

from indic_asr_onnx import IndicTranscriber
t=IndicTranscriber()
t0=time.time(); text=t.transcribe_ctc("phrase.wav","hi"); dt=time.time()-t0
print(f"ASR (CTC, INT8, CPU): '{text}'  in {dt*1000:.0f} ms for {sec}s audio  RTF={dt/sec:.2f}")
PY
```
Expect a correct Hindi transcript and **RTF < 1** on a laptop CPU. Repeat with `"ta"/"te"/"bn"/"mr"`.

Quick offline TTS-out check on the same machine (Piper via sherpa-onnx, or `espeak-ng -v hi`):
```bash
pip install sherpa-onnx
# download a Hindi vits/piper model from the sherpa-onnx tts-models release, then:
python - <<'PY'
import sherpa_onnx, time, soundfile as sf
tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model="hi.onnx", tokens="tokens.txt", dataDir="espeak-ng-data"),
        numThreads=2)))
t0=time.time(); a=tts.generate("रास्ता साफ है, सीधे चलिए।", sid=0, speed=1.0); dt=time.time()-t0
sf.write("out.wav", a.samples, a.sample_rate)
print(f"TTS gen {dt*1000:.0f} ms for {len(a.samples)/a.sample_rate*1000:.0f} ms audio  RTF={dt/(len(a.samples)/a.sample_rate):.2f}")
PY
```

### 3.2 Stage B — Android on-device proof (the real deliverable)

Minimum viable app, one screen, two buttons:
1. **Speech-in:** `AudioRecord` (16 kHz mono PCM16) → sherpa-onnx `OfflineRecognizer` with the
   IndicConformer-CTC INT8 model (from Stage A) in `assets/` → show transcript + log decode ms.
   (Code in §1.4 Option A.)
2. **Speech-out:** Android `TextToSpeech`, force offline voice (§2.2), speak a fixed Hindi sentence,
   log synth ms via `UtteranceProgressListener` (§2.4).

Fastest scaffold: clone the sherpa-onnx **Android ASR demo** and the **Android TTS-engine demo**,
swap in the IndicConformer CTC assets, add the two timing logs. No model training, no server.

Deliverable of the spike = a screen recording + logcat showing: a spoken Hindi phrase transcribed
on-device, a Hindi sentence spoken on-device, and the two measured latencies.

---

## 4. Gotchas + GO/NO-GO criteria

### Gotchas
- **Default `SpeechRecognizer` = cloud.** `android.speech.SpeechRecognizer` streams audio to Google
  servers → privacy leak + needs network. Android 12+'s `createOnDeviceSpeechRecognizer` exists but
  is English-centric and does not give reliable offline Indic coverage. → **Use IndicConformer.**
- **Model size / RAM:** 30M ideal; 120M monolingual only after INT8; 600M multilingual is server-only.
  Bundle one model per shipped language (assets grow ~40–120 MB each) or download on first run.
- **ONNX op support:** validate the exported graph loads in `onnxruntime-mobile` (reduced-op build
  may need a per-model op config); the full `onnxruntime-android` AAR avoids this at a size cost.
- **16 kHz mono only** for ASR — resample/downmix mic input or you'll get garbage.
- **Language ID is unsolved on-device** — ship with a user language setting, not audio auto-detect.
- **TTS offline packs are per-device downloads** — detect missing packs and prompt/fallback.
- **RNNT export is 3 graphs + a decode loop** — start with CTC.

### GO/NO-GO decision rules
- **Ship Android built-in TTS if** an offline voice (`isNetworkConnectionRequired()==false`) exists
  and is installed for the language **and** measured synth latency ≤ ~300 ms. → Expected: **GO** for
  hi/ta/te/bn/mr on Google/Vocalizer engines.
- **Use sherpa-onnx Piper instead if** a target language has no offline system voice, or you need one
  consistent voice across languages, and measured **RTF < 1** on the target device.
- **Reject AI4Bharat Indic Parler-TTS for on-device** — 880M autoregressive, multi-second CPU synth.
  (Fine as a cloud/server voice or a future high-quality mode.)
- **IndicConformer ASR is GO if** CTC INT8 runs at **RTF < 1** on the target phone with acceptable WER
  on the 5 languages (validate in Stage A first). 30M/120M-INT8 expected to pass; 600M is NO-GO on
  phone.

### Future upgrade (out of scope for this spike)
- **Sarvam Edge** — commercial on-device Indic speech SDK (ASR+TTS) tuned for exactly this use case;
  evaluate as a paid drop-in replacement once the AI4Bharat + Android-TTS spike proves the UX. Sarvam
  also offers cloud ASR/TTS APIs if an online mode is ever acceptable (it isn't for the privacy-first
  offline product).

---

## Sources
- IndicConformer repo: <https://github.com/AI4Bharat/IndicConformerASR>
- HF model cards (hi/ta/te/mr … `_hybrid_ctc_rnnt_large`) and 600M: <https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual>
- `indic-asr-onnx` (INT8 CPU ONNX, CTC+RNNT): <https://pypi.org/project/indic-asr-onnx/>
- sherpa-onnx (ORT Mobile wrapper, Android AAR, NeMo CTC, Piper TTS): <https://github.com/k2-fsa/sherpa-onnx>, <https://k2-fsa.github.io/sherpa/onnx/>
- NeMo→sherpa CTC export: <https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/nemo/how-to-export.html>
- Indic Parler-TTS (880M): <https://huggingface.co/ai4bharat/indic-parler-tts> · Parler-TTS Mini: <https://github.com/huggingface/parler-tts>
- AI4Bharat Indic-TTS (FastPitch+HiFiGAN): <https://github.com/AI4Bharat/Indic-TTS>
- Android TTS offline voice API: <https://developer.android.com/reference/android/speech/tts/TextToSpeech> (`Voice.isNetworkConnectionRequired()`)
- IndicLID (text-only LID): <https://github.com/AI4Bharat/IndicLID>
