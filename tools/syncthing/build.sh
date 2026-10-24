#!/usr/bin/env bash
set -euo pipefail
OUTDIR=$(mkdir -p "$1" && cd "$1" && pwd)
: "${NDK:?set NDK to the Android NDK root}"
API=26
HERE=$(cd "$(dirname "$0")" && pwd)
FORK="$HERE/syncthing-android"
SRC="$FORK/syncthing/src/github.com/syncthing/syncthing"
if [[ ! -f "$SRC/build.go" ]]; then
  echo "Syncthing source missing; run: git submodule update --init --recursive tools/syncthing/syncthing-android" >&2
  exit 1
fi
command -v go >/dev/null || { echo "Go is required to build Syncthing" >&2; exit 1; }
toml="$FORK/gradle/libs.versions.toml"
GO_VERSION=$(sed -n 's/^go_version *= *"\(.*\)"/\1/p' "$toml")
APP_VERSION=$(sed -n 's/^version-name *= *"\(.*\)"/\1/p' "$toml")
[[ -n "$GO_VERSION" && -n "$APP_VERSION" ]] || { echo "Cannot read go_version/version-name from $toml" >&2; exit 1; }
ST_VERSION="v$(echo "$APP_VERSION" | cut -d. -f1-3)"
case "$(uname -s):$(uname -m)" in
  Darwin:*) NDK_HOST=darwin-x86_64 ;;
  Linux:x86_64) NDK_HOST=linux-x86_64 ;;
  Linux:aarch64|Linux:arm64) NDK_HOST=linux-aarch64 ;;
  *) echo "Unsupported build host: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
if [[ ! -d "$TOOLCHAIN" && -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
[[ -x "$CC" ]] || { echo "Android NDK toolchain not found at $TOOLCHAIN" >&2; exit 1; }

EPOCH=$(git -C "$SRC" show -s --format=%ct HEAD)
cache=${DROIDDECK_BUILD_CACHE:-"${HOME}/.cache/droiddeck-build"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# build.go writes its output into the source tree, so build from a copy.
cp -a "$SRC" "$WORK/syncthing"
rm -f "$WORK/syncthing/.git"
# -checklinkname=0: the fork's anet dependency links against internal net symbols.
(
  cd "$WORK/syncthing"
  export GOTOOLCHAIN="go${GO_VERSION}" GOPATH="$cache/go" GOMODCACHE="$cache/go/pkg/mod" GOFLAGS=-buildvcs=false
  export CGO_ENABLED=1 EXTRA_LDFLAGS=-checklinkname=0 BUILD_HOST=droiddeck BUILD_USER=reproducible-build SOURCE_DATE_EPOCH="$EPOCH"
  go version
  go run build.go -goos android -goarch arm64 -cc "$CC" -version "$ST_VERSION" -no-upgrade build
)
install -m755 "$WORK/syncthing/syncthing" "$OUTDIR/libsyncthingnative.so"
echo "Syncthing ${ST_VERSION} (fork ${APP_VERSION}, Go ${GO_VERSION}) built into $OUTDIR/libsyncthingnative.so"
