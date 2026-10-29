# Sourced by the build scripts: picks the Docker platform and the matching cross-build image.
docker_platform=${DROIDDECK_DOCKER_PLATFORM:-}
if [[ -z "${docker_platform}" ]]; then
    case "$(uname -m)" in arm64|aarch64) docker_platform=linux/arm64 ;; *) docker_platform=linux/amd64 ;; esac
fi
case "${docker_platform}" in linux/arm64|linux/arm64/*) docker_platform=linux/arm64 ;; esac
if [[ -z "${DROIDDECK_BUILD_IMAGE:-}" ]]; then
    DROIDDECK_BUILD_IMAGE=droiddeck-local-cross:24.04-v3
    [[ "${docker_platform}" == linux/arm64 ]] && DROIDDECK_BUILD_IMAGE+=-arm64
fi
export DROIDDECK_DOCKER_PLATFORM=${docker_platform} DROIDDECK_BUILD_IMAGE
