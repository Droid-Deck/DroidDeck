#!/usr/bin/env bash
# Builds the app-model host DLL. The host is x64 Windows code: it runs inside
# the title's process under the runtime's Wine, so it is cross-compiled the same
# way every other Windows-side piece of the Xbox runtime is.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../../.." && pwd)
cc=${UWP_HOST_CC:-x86_64-w64-mingw32-gcc}
out=${1:-"$repo/app/src/main/assets/linuxfs/usr/local/lib/droiddeck-xbox/compat/uwp-host.dll"}
mkdir -p "$(dirname "$out")"
"$cc" -O2 -Wall -Wextra -shared -nostartfiles -Wl,-e,_DllMainCRTStartup -s -o "$out" \
    "$repo/tools/xbox/uwp-host/uwp-host.c" \
    "$repo/tools/xbox/uwp-host/uwp-services.c" \
    "$repo/tools/xbox/uwp-host/uwp-auth.c" \
    "$repo/tools/xbox/uwp-host/uwp-gamesave.c" \
    "$repo/tools/xbox/uwp-host/uwp-json.c" \
    -lkernel32 -lmsvcrt -lole32 -loleaut32 -luser32 -lgdi32 -luuid
ls -l "$out"
