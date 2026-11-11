#!/usr/bin/env bash
set -euo pipefail
missing=()
for package in "$@"; do
  if [ "$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null || true)" != 'install ok installed' ]; then
    missing+=("$package")
  fi
done
if [ "${#missing[@]}" = 0 ]; then
  echo "Ubuntu build dependencies are already installed."
  exit 0
fi
options=()
# Runners without the deb822 Ubuntu sources file keep their own apt configuration.
ubuntu_sources=${UBUNTU_SOURCES_FILE:-/etc/apt/sources.list.d/ubuntu.sources}
if [ -f "$ubuntu_sources" ]; then
  options+=(-o "Dir::Etc::sourcelist=$ubuntu_sources" -o Dir::Etc::sourceparts=-)
fi
options+=(
  -o Acquire::Retries=1
  -o Acquire::http::Timeout=15
  -o Acquire::https::Timeout=15
  -o DPkg::Lock::Timeout=30
)
install_packages() {
  echo "Updating Ubuntu package indexes."
  sudo timeout --kill-after=10s 60s apt-get "${options[@]}" -o APT::Update::Error-Mode=any update -q || return $?
  echo "Installing: ${missing[*]}"
  sudo timeout --kill-after=10s 120s env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get "${options[@]}" install -y -q --no-install-recommends "${missing[@]}"
}
if install_packages; then
  exit 0
fi
[ -f "$ubuntu_sources" ] || exit 1
sources=$(mktemp --suffix=.sources)
trap 'rm -f "$sources"' EXIT
# The last mirror of each list is outside Canonical's network, so it still answers when runners cannot reach Canonical.
mirrors=(https://archive.ubuntu.com/ubuntu https://mirrors.edge.kernel.org/ubuntu)
case "$(dpkg --print-architecture)" in
  arm64) mirrors=(https://ports.ubuntu.com/ubuntu-ports https://mirrors.ocf.berkeley.edu/ubuntu-ports) ;;
esac
status=1
for mirror in "${mirrors[@]}"; do
  echo "::warning::Ubuntu mirror failed or timed out; retrying with $mirror."
  sed -E "s@^URIs:.*@URIs: $mirror@" "$ubuntu_sources" > "$sources"
  chmod 644 "$sources"
  options[1]="Dir::Etc::sourcelist=$sources"
  status=0
  install_packages || status=$?
  [ "$status" = 0 ] && exit 0
done
exit "$status"
