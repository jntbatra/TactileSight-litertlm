#!/usr/bin/env bash
# Start the cloud-tier VLM backend: Gemma 4 E4B on llama.cpp + CUDA (RTX 5060).
#
# This is the hard-won working config (2026-07-18). Gemma 4 is new enough that the defaults
# fight it, so the non-obvious flags matter:
#   --chat-template-file gemma-vision-terse.jinja
#       Gemma 4's *embedded* template crashes llama.cpp's Jinja engine, and the bundled
#       "interleaved" template forces the model into verbose reasoning mode (answer ends up
#       empty in `content`). This minimal template = simple Gemma turns + the <|image|> marker,
#       no thinking scaffold → clean terse answers.
#   -ngl 99   all layers on the GPU (uses ~4.7 GB VRAM, leaves headroom on an 8 GB card).
#
# Serves an OpenAI-compatible endpoint on :8080. Point the FastAPI server at it with:
#   TS_VLM_BACKEND=openai TS_OPENAI_BASE_URL=http://localhost:8080/v1 \
#     uvicorn app:app --host 0.0.0.0 --port 8000
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LLAMA_SERVER="${LLAMA_SERVER:-$HOME/llama.cpp/build/bin/llama-server}"
CUDA_LIB="${CUDA_LIB:-/usr/local/cuda-13.2/lib64}"
MODEL="${MODEL:-$HERE/models/gemma-4-E4B-it-Q4_K_M.gguf}"
MMPROJ="${MMPROJ:-$HERE/models/mmproj-gemma-4-E4B-it-Q8_0.gguf}"
TEMPLATE="${TEMPLATE:-$HERE/gemma-vision-terse.jinja}"
PORT="${PORT:-8080}"
CTX="${CTX:-8192}"

for f in "$LLAMA_SERVER" "$MODEL" "$MMPROJ" "$TEMPLATE"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

echo "Starting Gemma 4 E4B VLM server on :$PORT (GPU) ..."
exec env LD_LIBRARY_PATH="$CUDA_LIB:${LD_LIBRARY_PATH:-}" "$LLAMA_SERVER" \
  -m "$MODEL" \
  --mmproj "$MMPROJ" \
  --jinja --chat-template-file "$TEMPLATE" \
  -ngl 99 -c "$CTX" \
  --host 0.0.0.0 --port "$PORT"
