#!/bin/bash
# Source this: starts a host vkbridge server on the given desktop driver (default RADV) and
# points this shell's Vulkan programs at the bridge ICD.
#   . tools/vkbridge/host/bridge_env.sh [icd-json]
_vkb_host=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
_vkb_icd=${1:-/usr/share/vulkan/icd.d/radeon_icd.json}
export VKBRIDGE_SOCKET=${VKBRIDGE_SOCKET:-${XDG_RUNTIME_DIR:-/tmp}/vkbridge-host.sock}
pgrep -x vkbridge-server >/dev/null && kill $(pgrep -x vkbridge-server) 2>/dev/null; sleep 0.3
rm -f "$VKBRIDGE_SOCKET"
VK_DRIVER_FILES=$_vkb_icd VK_ICD_FILENAMES= nohup "$_vkb_host/out/vkbridge-server" --socket "$VKBRIDGE_SOCKET" \
    --log "$_vkb_host/out/server.log" >/dev/null 2>&1 &
for _i in $(seq 50); do [ -S "$VKBRIDGE_SOCKET" ] && break; sleep 0.1; done
export VK_DRIVER_FILES=$_vkb_host/out/vkbridge_icd.json
echo "vkbridge server up ($_vkb_icd) at $VKBRIDGE_SOCKET"
