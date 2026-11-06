#!/usr/bin/env bash
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
common=$(git -C "${repo_root}" rev-parse --path-format=absolute --git-common-dir)
main_root=$(dirname "${common}")
[[ "${main_root}" != "${repo_root}" && -d "${main_root}/app/build" ]] || exit 0

paths=(
    app/build/prepared-assets app/build/runtime-inputs.json app/build/runtimeAssets
    app/build/packs app/build/localPackAssets app/build/packs-local-aside app/build/quest-inputs
    app/build/rustJniLibs app/build/vkbridgeJniLibs
    app/src/main/assets/directaudio app/src/main/assets/droiddeck-esync app/src/main/assets/linuxfs
    app/src/main/assets/desktop-removal
    app/src/main/jniLibs/arm64-v8a
    app/src/main/rust/stores/target
)
for path in "${paths[@]}"; do
    [[ -e "${main_root}/${path}" ]] || continue
    if [[ -d "${main_root}/${path}" ]]; then
        [[ -z "$(ls -A "${repo_root}/${path}" 2>/dev/null)" ]] || continue
        mkdir -p "$(dirname "${repo_root}/${path}")"
        staged=$(mktemp -d "${repo_root}/${path}.seed.XXXXXX")
        trap 'rm -rf "${staged}"' EXIT
        cp -R "${main_root}/${path}/." "${staged}/"
        if [[ -d "${repo_root}/${path}" ]] && ! rmdir "${repo_root}/${path}" 2>/dev/null; then
            rm -rf "${staged}"; trap - EXIT; continue
        fi
        if [[ -e "${repo_root}/${path}" ]]; then
            rm -rf "${staged}"; trap - EXIT; continue
        fi
        mv "${staged}" "${repo_root}/${path}"
        trap - EXIT
    elif [[ ! -e "${repo_root}/${path}" ]]; then
        mkdir -p "$(dirname "${repo_root}/${path}")"
        staged=$(mktemp "${repo_root}/${path}.seed.XXXXXX")
        trap 'rm -f "${staged}"' EXIT
        cp "${main_root}/${path}" "${staged}"
        mv "${staged}" "${repo_root}/${path}"
        trap - EXIT
    fi
done
