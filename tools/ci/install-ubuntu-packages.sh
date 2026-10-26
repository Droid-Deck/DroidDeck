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
options=(
  -o Dir::Etc::sourcelist=sources.list.d/ubuntu.sources
  -o Dir::Etc::sourceparts=-
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
echo "::warning::Runner mirror failed or timed out; retrying with the official Ubuntu mirror."
sources=$(mktemp --suffix=.sources)
trap 'rm -f "$sources"' EXIT
mirror=https://archive.ubuntu.com/ubuntu
case "$(dpkg --print-architecture)" in
  arm64) mirror=https://ports.ubuntu.com/ubuntu-ports ;;
esac
sed -E "s@^URIs:.*@URIs: $mirror@" /etc/apt/sources.list.d/ubuntu.sources > "$sources"
chmod 644 "$sources"
options[1]="Dir::Etc::sourcelist=$sources"
install_packages
