#!/usr/bin/env bash
set -euo pipefail
wrapper=${1:?usage: unwrap-dwarfs.sh WRAPPER OUTPUT}
output=${2:?usage: unwrap-dwarfs.sh WRAPPER OUTPUT}
[ "$(tail -c 32 "$wrapper" | head -c 8)" = "SQUEEZE!" ] || { echo "unwrap-dwarfs.sh: $wrapper has no SQUEEZE! trailer" >&2; exit 1; }
packed=$(tail -c 16 "$wrapper" | od -An -tu8 -N8 | tr -d ' ')
[ "$packed" -gt 0 ] && [ "$packed" -le $(( $(wc -c < "$wrapper") - 32 )) ] || { echo "unwrap-dwarfs.sh: bad payload size in $wrapper" >&2; exit 1; }
tail -c $((packed + 32)) "$wrapper" | head -c "$packed" | zstd -dcq > "$output"
