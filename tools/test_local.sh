#!/usr/bin/env bash
# Session helpers and ELF preload tests need Linux (/proc, inotify and glibc), as in CI.
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
if [[ $(uname -s) == Linux ]]; then
    exec python3 -m unittest discover -s tools/tests "$@"
fi
image=${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v3}
python3 tools/docker_preflight.py "$image"
exec docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" \
    -v "$repo_root:/src:ro" -w /src "$image" python3 -m unittest discover -s tools/tests "$@"
