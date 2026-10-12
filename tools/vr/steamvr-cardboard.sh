#!/bin/sh
# Starts (or stops, with "stop") SteamVR in a DroidDeck session with the Cardboard driver.
# Guest side; expects SteamVR at /opt/steamvr and the driver at /opt/droiddeck-vr/droiddeck
# (docs/cardboard-vr.md). Extra environment for vrcompositor can be passed in, e.g.
#   VK_LOADER_LAYERS_DISABLE='~implicit~' steamvr-cardboard.sh
set -u
steamvr=${STEAMVR:-/opt/steamvr}
driver=${DROIDDECK_VR_DRIVER:-/opt/droiddeck-vr/droiddeck}
layer=${DROIDDECK_VR_LAYER:-/opt/droiddeck-vr/layer}

stop() {
    for n in vrcompositor vrserver vrmonitor vrdashboard vrstartup vrwebhelper vrcompositor-launcher; do
        for p in $(pgrep -x "$n"); do kill "$p" 2>/dev/null; done
    done
}

if [ "${1:-}" = stop ]; then stop; exit 0; fi
stop
sleep 1

logs=$HOME/.local/share/Steam/logs
mkdir -p "$HOME/.config/openvr" "$HOME/.config/openxr/1" "$logs" "$HOME/.local/share/Steam/config"
cat > "$HOME/.config/openvr/openvrpaths.vrpath" <<JSON
{
  "config": ["$HOME/.local/share/Steam/config"],
  "external_drivers": ["$driver"],
  "jsonid": "vrpathreg",
  "log": ["$logs"],
  "runtime": ["$steamvr"],
  "version": 1
}
JSON
ln -sf "$steamvr/steamxr_linuxarm64.json" "$HOME/.config/openxr/1/active_runtime.json"

# vrcompositor asks for VK_KHR_display, which makes Turnip/KGSL report no GPU at all; the layer
# stands in for the display extensions (tools/vr/src/vk_layer_droiddeck_vr.c). The implicit layers
# (gamescope's WSI layer) stay out of SteamVR's processes.
export VK_LAYER_PATH="$layer${VK_LAYER_PATH:+:$VK_LAYER_PATH}"
export VK_INSTANCE_LAYERS="VK_LAYER_DROIDDECK_vr${VK_INSTANCE_LAYERS:+:$VK_INSTANCE_LAYERS}"
export VK_LOADER_LAYERS_DISABLE="${VK_LOADER_LAYERS_DISABLE:-~implicit~}"

# SteamVR's web helper (the dashboard) checks the X server with xset, which the runtime lacks.
shims=/tmp/droiddeck-vr-shims
mkdir -p "$shims"
if ! command -v xset >/dev/null 2>&1; then
    printf '#!/bin/sh\n# xset stand-in for vrwebhelper.sh: "q" only checks the display answers.\nexec xprop -root -display "${DISPLAY:-:0}" _NET_SUPPORTED >/dev/null 2>&1\n' > "$shims/xset"
    chmod +x "$shims/xset"
    export PATH="$shims:$PATH"
fi

cd "$steamvr/bin/linuxarm64" || exit 1
setsid ./vrstartup > /tmp/vrstartup.log 2>&1 &
echo "SteamVR starting; logs in $logs (vrserver.txt, vrcompositor.txt) and /tmp/vrstartup.log"
