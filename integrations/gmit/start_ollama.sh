#!/usr/bin/env bash
set -euo pipefail

# Run the isolated local backend in the foreground; Ctrl-C stops only this server.
local_root="${GMIT_LOCAL_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/greta-gmit}"
ollama_binary="${OLLAMA_BIN:-$local_root/ollama-v0.40.11/bin/ollama}"
if [[ ! -x "$ollama_binary" ]]; then
    echo "Ollama is not installed at $ollama_binary. Set OLLAMA_BIN or follow docs/gmit/REPLAY.md." >&2
    exit 1
fi
export OLLAMA_HOST="${OLLAMA_HOST:-127.0.0.1:11434}"
export OLLAMA_MODELS="${OLLAMA_MODELS:-$local_root/models}"
export OLLAMA_CONTEXT_LENGTH="${OLLAMA_CONTEXT_LENGTH:-8192}"
export OLLAMA_NUM_PARALLEL=1
export OLLAMA_MAX_LOADED_MODELS=1
export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:-5m}"
exec "$ollama_binary" serve
