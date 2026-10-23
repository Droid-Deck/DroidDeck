#!/usr/bin/env bash
set -euo pipefail
options=(
  -o Dir::Etc::sourcelist=sources.list.d/ubuntu.sources
  -o Dir::Etc::sourceparts=-
  -o Acquire::Retries=3
  -o Acquire::http::Timeout=30
  -o Acquire::https::Timeout=30
  -o DPkg::Lock::Timeout=60
)
sudo apt-get "${options[@]}" -o APT::Update::Error-Mode=any update -qq
sudo env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get "${options[@]}" install -y -qq --no-install-recommends "$@"
