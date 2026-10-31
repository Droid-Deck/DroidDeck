#!/usr/bin/env bash
set -euo pipefail
test -f external/MaliBridge/scripts/build-client-linux.sh
bash external/MaliBridge/scripts/build-client-linux.sh app/src/main/assets/linuxfs aarch64-linux-gnu-gcc
