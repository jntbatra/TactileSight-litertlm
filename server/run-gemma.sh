#!/usr/bin/env bash
# run-gemma.sh — launches Gemma 4 E4B (vision) on llama.cpp/CUDA as an
# OpenAI-compatible server on :8080. This is the hackathon demo backend for
# TS_VLM_BACKEND=openai.
#
# Needs, both gitignored, in server/models/:
#   models/gemma-4-e4b-it.gguf          the text/weights GGUF
#   models/gemma-4-e4b-it-mmproj.gguf   the vision projector GGUF
# and a CUDA-built `llama-server` binary on PATH (or set LLAMA_SERVER_BIN
# below to its full path).
#
# Non-obvious flags and why they're here:
#   --chat-template-file gemma-vision-terse.jinja
#       Dodges the GGUF-embedded chat template. Some llama-server builds
#       have a bug where --chat-template-file is silently ignored once
#       --mmproj is also passed (github.com/ggml-org/llama.cpp issue
#       #24189) — if you see it clearly using the embedded template
#       instead, that's this bug; grab a newer llama.cpp build.
#   --reasoning off
#       Gemma 4's embedded template defaults to emitting a reasoning/
#       "thinking" turn before the real answer. For a one-sentence spoken
#       description that's pure latency with no upside, so it's forced off
#       here rather than relying on --chat-template-kwargs enable_thinking
#       (that flag is deprecated in favor of --reasoning).
#   --jinja
#       Required for a custom --chat-template-file to be honored at all.
#
# If mmproj loading crashes (SIGABRT during clip_model_loader::load_tensors)
# on your llama.cpp build, that's a known Gemma-4+CUDA+mmproj issue on some
# versions (ggml-org/llama.cpp #21402) — try the latest llama.cpp release
# before the demo, not after.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

LLAMA_SERVER_BIN="${LLAMA_SERVER_BIN:-llama-server}"
MODEL_DIR="${MODEL_DIR:-./models}"
MODEL_GGUF="${MODEL_GGUF:-$MODEL_DIR/gemma-4-e4b-it.gguf}"
MMPROJ_GGUF="${MMPROJ_GGUF:-$MODEL_DIR/gemma-4-e4b-it-mmproj.gguf}"
PORT="${PORT:-8080}"

for f in "$MODEL_GGUF" "$MMPROJ_GGUF"; do
  if [[ ! -f "$f" ]]; then
    echo "error: missing $f" >&2
    echo "  drop the Gemma 4 E4B GGUF + mmproj GGUF into $MODEL_DIR/ first" >&2
    exit 1
  fi
done

exec "$LLAMA_SERVER_BIN" \
  --model "$MODEL_GGUF" \
  --mmproj "$MMPROJ_GGUF" \
  --host 0.0.0.0 \
  --port "$PORT" \
  --n-gpu-layers 99 \
  --ctx-size 8192 \
  --jinja \
  --chat-template-file gemma-vision-terse.jinja \
  --reasoning off
