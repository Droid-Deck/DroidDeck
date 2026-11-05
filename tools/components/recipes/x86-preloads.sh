#!/usr/bin/env bash
set -euo pipefail
docker run --rm -v "$PWD:/src" -w /src public.ecr.aws/docker/library/debian:bullseye bash -c '
  set -euo pipefail
  install_compilers() {
    apt-get -o Acquire::Check-Valid-Until=false update -qq &&
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends gcc g++ gcc-multilib g++-multilib binutils >/dev/null
  }
  printf "deb http://deb.debian.org/debian bullseye main\ndeb http://security.debian.org/debian-security bullseye-security main\n" > /etc/apt/sources.list
  if ! install_compilers; then
    printf "deb http://archive.debian.org/debian bullseye main\ndeb http://archive.debian.org/debian-security bullseye-security main\n" > /etc/apt/sources.list
    install_compilers
  fi
  tools/linuxfs/build-x86-preloads.sh app/src/main/assets/linuxfs
'
sudo chown -R "$(id -u):$(id -g)" app/src/main/assets/linuxfs
ls -l app/src/main/assets/linuxfs/x86_64 app/src/main/assets/linuxfs/i386
