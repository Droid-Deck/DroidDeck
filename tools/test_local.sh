#!/usr/bin/env bash
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
if [[ $(uname -s) == Linux ]]; then
    exec python3 -m unittest discover -s tools/tests "$@"
fi
. "${repo_root}/tools/docker_platform.sh"
image=${DROIDDECK_BUILD_IMAGE}
python3 tools/docker_preflight.py "$image"
exec docker run --rm --platform "${DROIDDECK_DOCKER_PLATFORM}" --user "$(id -u):$(id -g)" \
    -v "$repo_root:/src:ro" -w /src "$image" python3 -m unittest discover -s tools/tests "$@"
