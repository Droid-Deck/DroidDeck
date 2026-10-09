#!/bin/bash
# Source this: a host bridge server that behaves like the Mali-G720 driver as far as Phase 0
# found (no BCn, clip/cull distance, vertex attribute divisor, maintenance5; Vulkan 1.3).
_vkb_host=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export VKBRIDGE_SOCKET=${XDG_RUNTIME_DIR:-/tmp}/vkbridge-host.sock
pgrep -x vkbridge-server >/dev/null && kill $(pgrep -x vkbridge-server) 2>/dev/null; sleep 0.3
rm -f "$VKBRIDGE_SOCKET"
VKBRIDGE_FAKE_MISSING=${VKB_MISSING:-bc,clip,cull,divisor,maint5} VK_DRIVER_FILES=${VKB_HOST_ICD:-/usr/share/vulkan/icd.d/radeon_icd.json} \
    nohup "$_vkb_host/out/vkbridge-server" --socket "$VKBRIDGE_SOCKET" --log "$_vkb_host/out/server.log" >/dev/null 2>&1 &
for _i in $(seq 50); do [ -S "$VKBRIDGE_SOCKET" ] && break; sleep 0.1; done
chmod 666 "$VKBRIDGE_SOCKET"
export VK_DRIVER_FILES=$_vkb_host/out/vkbridge_icd.json
export VKBRIDGE_HIDE_EXTS=VK_KHR_maintenance5,VK_EXT_vertex_attribute_divisor,VK_KHR_vertex_attribute_divisor
export VKBRIDGE_MAX_API=1.3
echo "vkbridge server up, imitating Mali's gaps"
