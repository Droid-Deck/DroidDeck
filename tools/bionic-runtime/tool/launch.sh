#!/system/bin/sh
# DroidDeck-Bionic launcher, Android side. ../proton calls it: launch.sh <verb> <compatdata dir> <exe> [args...]
# Runs <exe> on the bionic Proton layer in ../files with the prefix <compatdata>/pfx, inside DroidDeck's
# gamescope session. Every program from app data starts through /system/bin/linker64 (targetSdk 29+ refuses
# execve of app data files); the runtime pack's execshim does the same for Wine's own starts.
verb=$1; COMPAT=$2; EXE=$3
shift 3
TOOL=$(cd "$(dirname "$0")/.." && pwd)
F=$TOOL/files
PACK=$F/runtime
ROOTFS=${DROIDDECK_BIONIC_ROOT:?started without DROIDDECK_BIONIC_ROOT}
APP=${ROOTFS%/linuxfs}
LINKER=/system/bin/linker64
VERSION=$(awk '{print $2}' "$TOOL/version" 2>/dev/null)

mkdir -p "$COMPAT"
LOG=$COMPAT/droiddeck-bionic.log
[ "$verb" = runinprefix ] || { [ -f "$LOG" ] && mv -f "$LOG" "$LOG.1"; }
exec >> "$LOG" 2>&1
echo "$(date +%H:%M:%S) $verb $EXE ($VERSION, uid $(id -u), DISPLAY ${DISPLAY:-unset})"

# Keep Steam's game identity, DroidDeck's input/display/driver/sync settings and its game environment (the names
# ../proton lists in DROIDDECK_BIONIC_KEEP); drop the rest of the glibc guest's environment.
KEEP=" ${DROIDDECK_BIONIC_KEEP:-} "
for v in $(env | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p'); do
  case "$KEEP" in *" $v "*) continue ;; esac
  case "$v" in
    SteamAppId|SteamGameId|SteamOverlayGameId|SteamClientLaunch|SteamEnv|SteamAppUser|SteamUser|STEAM_COMPAT_*) ;;
    ANDROID_*|BOOTCLASSPATH|DEX2OATBOOTCLASSPATH|SYSTEMSERVERCLASSPATH|EXTERNAL_STORAGE|ASEC_MOUNTPOINT) ;;
    FAKE_*|SDL_*|BL_WIDTH|BL_HEIGHT|BL_VK_DRIVER|WINEDLLOVERRIDES) ;;
    DISPLAY|GAMESCOPE_WAYLAND_DISPLAY|FEX_*) ;;
    TU_DEBUG|MESA_NO_ERROR|DXVK_CONFIG|DXVK_HUD|DROIDDECK_FORCE_SSBS) ;;
    mesa_glthread|vblank_mode|ZINK_*|GALLIUM_HUD|GALLIUM_THREAD) ;;
    BL_SYNC|BL_FSYNC_FIRST|BL_SYNC_FALLBACK|PROTON_NO_ESYNC|PROTON_NO_FSYNC|PROTON_NO_NTSYNC|WINEESYNC) ;;
    PROTON_USE_D7VK) ;;
    WINE_OFFSCREEN_CHECK_MS|WINE_CPU_TOPOLOGY|WINE_LARGE_ADDRESS_AWARE|TZ) ;;
    DXVK_LOG_LEVEL|DXVK_HDR|VKD3D_DEBUG|VKD3D_SHADER_DEBUG) ;;
    DROIDDECK_GPU_LOAD|DROIDDECK_GPU_LOAD_DIR|DROIDDECK_VRAM_CAP|DROIDDECK_VRAM_CAP_MB) ;;
    VKD3D_FEATURE_LEVEL|VKD3D_SHADER_MODEL|vk_x11_strict_image_count) ;;
    *) unset "$v" 2>/dev/null ;;
  esac
done
export PATH=/system/bin:/system/xbin
# DISPLAY: the X server Steam gives games. DroidDeck's gamescope runs two (Steam's UI on :0, games on
# STEAM_GAME_DISPLAY_0), so Steam's menus do not take X focus from the game.
export HOME=$COMPAT WINEPREFIX=$COMPAT/pfx TMPDIR=$ROOTFS/tmp LANG=en_US.UTF-8 DISPLAY=${DISPLAY:-:0}
# Proton's user name: the same C:\users\steamuser (saves, settings) as DroidDeck-Proton in a shared prefix
export USER=steamuser LOGNAME=steamuser

# Graphics: DroidDeck's chosen Turnip (its Android half) through Bannerlator's Wayland adapter, and gamescope's
# WSI layer handing the swapchain to gamescope (zero copy).
DRVDIR=$APP/graphics_driver/$(basename "$(dirname "${BL_VK_DRIVER:-/x/none/icd.json}")")
if [ ! -f "$DRVDIR/libvulkan_freedreno.so" ]; then
  for d in "$APP"/graphics_driver/*/; do [ -f "$d/libvulkan_freedreno.so" ] && { DRVDIR=${d%/}; break; }; done
fi
echo "driver $DRVDIR"
RTD=$APP/.wayland-rt
export VK_ICD_FILENAMES=$PACK/adapter/icd.json ADRENOTOOLS_DRIVER_PATH=$DRVDIR/ ADRENOTOOLS_HOOKS_PATH=$PACK/lib
export ADRENOTOOLS_DRIVER_NAME=libvulkan_freedreno.so
export VK_ADD_IMPLICIT_LAYER_PATH=$PACK/layer ENABLE_GAMESCOPE_WSI=1 GAMESCOPE_WAYLAND_DISPLAY=${GAMESCOPE_WAYLAND_DISPLAY:-gamescope-0}
export XDG_RUNTIME_DIR=$RTD DROIDDECK_WSI_BYPASS_CHECK_MS=100 GAMESCOPE_WSI_FORCE_BYPASS=1
[ -e "$RTD/droiddeck-gamescope-limiter" ] && export GAMESCOPE_LIMITER_FILE=$RTD/droiddeck-gamescope-limiter

# Wine: DXVK/VKD3D over the builtins, X11 inside gamescope (winewayland would pick DroidDeck's compositor),
# FEX for x86 code, userspace ntsync (no /dev/ntsync on Android), Valve's Android Steam client library.
export WINEDLLOVERRIDES="${WINEDLLOVERRIDES:+$WINEDLLOVERRIDES;}d3d8,d3d9,d3d10core,d3d11,dxgi,d3d12,d3d12core=n,b;winewayland.drv=d"
# Sync as chosen in DroidDeck (Wine sync, per-game PROTON_NO_*): ntsync is userspace ntsync (no /dev/ntsync on
# Android) with esync as its fallback; Android has no futex_waitv, so an fsync choice runs ntsync.
on() { case "$1" in 1|on|true|yes) return 0 ;; esac; return 1; }
NT=1; ES=1
[ "${BL_SYNC:-1}" = 0 ] && NT=0
[ "${BL_FSYNC_FIRST:-0}" = 1 ] && NT=1
[ "${BL_SYNC:-1}" = 0 ] && [ "${BL_SYNC_FALLBACK:-1}" = 0 ] && ES=0
on "${PROTON_NO_NTSYNC:-}" && NT=0
{ on "${PROTON_NO_ESYNC:-}" || [ "${WINEESYNC:-}" = 0 ]; } && ES=0
export WINENTSYNC=$NT WINEESYNC=$ES
# Userspace ntsync keeps its state in one file. Per prefix, not in the shared TMPDIR (DroidDeck-Proton's ntsync
# uses that too), and cleared before Wine starts in this prefix: a process killed while it held the file's lock
# leaves it held, and every later start in that prefix hangs at its first ntsync wait.
export NTSYNC_SHM=$COMPAT/ntsync-shm
# DroidDeck's Force SSBS (its glibc libssbs.so preload) is bionic Wine's own WINE_FORCE_SSBS
on "${DROIDDECK_FORCE_SSBS:-}" && export WINE_FORCE_SSBS=1
export HODLL=libwow64fex.dll WINEDEBUG=err+all,fixme-all
export XENVIRONMENT=$TOOL/bionic/Xdefaults
# The adapter's Wayland swapchain gets its buffers back from gamescope later than DroidDeck-Proton's Turnip does;
# with gamescope's default three images the game waits for one about a fifth of the time at high frame rates.
# droiddeck-game-env's fix for a game that needs its own image count sets vk_x11_strict_image_count instead.
case "${vk_x11_strict_image_count:-}" in
  true|1) ;;
  *) export GAMESCOPE_WSI_MIN_IMAGE_COUNT=${GAMESCOPE_WSI_MIN_IMAGE_COUNT:-4} GAMESCOPE_WSI_ENSURE_MIN_IMAGE_COUNT=${GAMESCOPE_WSI_ENSURE_MIN_IMAGE_COUNT:-1} ;;
esac
# FEX: DroidDeck-Proton's defaults (its share/fex-emu/Config.json: x87 in 64-bit precision, TSO without the vector
# and memcpy parts); droiddeck-game-env's per-game choices (FEX_* in the environment, e.g. .NET, Unity) win.
export FEX_TSOENABLED=${FEX_TSOENABLED:-1} FEX_VECTORTSOENABLED=${FEX_VECTORTSOENABLED:-0} \
  FEX_MEMCPYSETTSOENABLED=${FEX_MEMCPYSETTSOENABLED:-0} FEX_HALFBARRIERTSOENABLED=${FEX_HALFBARRIERTSOENABLED:-1} \
  FEX_X87REDUCEDPRECISION=${FEX_X87REDUCEDPRECISION:-1} FEX_MULTIBLOCK=${FEX_MULTIBLOCK:-1} FEX_MAXINST=${FEX_MAXINST:-500} FEX_DISKCACHE=${FEX_DISKCACHE:-1}
echo "fex $(env | grep '^FEX_' | sort | tr '\n' ' ')"
echo "sync ntsync=$WINENTSYNC esync=$WINEESYNC ssbs=${WINE_FORCE_SSBS:-0} tu_debug=${TU_DEBUG:-} kept=$(echo $KEEP)"
export WINESTEAMCLIENTPATH64=$ROOTFS/root/.local/share/Steam/androidarm64/libsteamclient.so
# The layer sends a game's steamclient(64).dll to lsteamclient (and so to DroidDeck's Steam through the library
# above) only with WINE_LSTEAMCLIENT set; without it Valve's Windows steamclient runs alone and SteamAPI_Init fails.
export WINE_LSTEAMCLIENT=${WINE_LSTEAMCLIENT:-1}
export DXVK_LOG_PATH=$COMPAT PULSE_SERVER=unix:$APP/pulseaudio/PS0
export ANDROID_SYSVSHM_SERVER=$COMPAT/.no-sysvshm-server  # its client faults on an unset name, inside a Wine lock

# The pack's imagefs libraries carry Winlator's prefix for plugins and X11 data. No FONTCONFIG_*: with the
# imagefs fonts.conf (Android's /system/fonts) DiRT Showdown quit right after start.
export GST_PLUGIN_SYSTEM_PATH=$PACK/lib/gstreamer-1.0 GST_REGISTRY=$COMPAT/gst-registry.bin GST_REGISTRY_FORK=no
export XLOCALEDIR=$PACK/share/X11/locale XKB_CONFIG_ROOT=$PACK/share/X11/xkb
export SSL_CERT_FILE=$PACK/etc/tls/cert.pem SSL_CERT_DIR=$PACK/etc/tls/certs OPENSSL_CONF=$PACK/etc/tls/openssl.cnf
export WINE_ADDITIONAL_CERTS_DIR=$PACK/etc/tls/cert.pem

# Controller: the bionic winebus has no udev bus, so Steam's virtual pad is shown to it as an Xbox 360 pad;
# Steam's ignore list names that id because of the built-in pad, which Wine does not see here.
export FAKE_EVDEV_STEAM_PAD_AS_X360=1
export SDL_GAMECONTROLLER_IGNORE_DEVICES="$(echo "${SDL_GAMECONTROLLER_IGNORE_DEVICES:-}" | sed -e 's#0x045e/0x028e,##' -e 's#,0x045e/0x028e$##')"

# OpenGL, and WineD3D on it (DirectDraw, Direct3D 1-8 without DXVK): Mesa's Zink on Vulkan through winex11's EGL
# backend (the runtime's egl/), the way Bannerlator's Fast OpenGL runs it; DroidDeck's GL settings above are kept.
export WINE_USE_EGL=1 WINE_X11FORCEGLX=1 GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink LIBGL_KOPPER_DRI2=1
export ZINK_DESCRIPTORS=${ZINK_DESCRIPTORS:-lazy} ZINK_DEBUG=${ZINK_DEBUG:-compact} vblank_mode=${vblank_mode:-0}
# Gallium's threaded context off, as in Bannerlator's Fast OpenGL: with it, WineD3D (DirectDraw / Direct3D 1-7)
# crashed in native code within seconds (AIO Direct3D 7, SIGSEGV).
export GALLIUM_THREAD=${GALLIUM_THREAD:-0}
# Mesa's GL thread off unless the game's own DroidDeck environment sets it: with DroidDeck's session-wide
# mesa_glthread=true, WineD3D's persistent buffer writes lost vertex colours on this Mesa build (AIO 64-bit
# Direct3D 7 drew its cube white; DroidDeck-Proton's Mesa does not do this).
case "$KEEP" in *" mesa_glthread "*) ;; *) export mesa_glthread=false ;; esac

# egl/ first: the layer's own lib/ carries a Wayland-only Mesa EGL of the same names, which winex11 cannot use
LIBS=$PACK/egl:$F/lib:$PACK/lib:/system/lib64
PRELOAD=$PACK/preload/libexecshim.so:$PACK/preload/libnotag.so:$PACK/preload/libfakeinput.so
wine_() { LD_LIBRARY_PATH=$LIBS LD_PRELOAD=$PRELOAD "$LINKER" "$F/lib/wine/aarch64-unix/wine" "$@"; }
wineserver_() { LD_LIBRARY_PATH=$LIBS LD_PRELOAD=$PRELOAD "$LINKER" "$F/bin/wineserver" "$@"; }

is_builtin() { head -c 128 "$1" 2>/dev/null | grep -q "Wine builtin DLL"; }
link_dir() { # src dst: links every file of src into dst; replaces links and Wine builtin copies, keeps native DLLs
  [ -d "$1" ] || return 0
  for f in "$1"/*; do
    [ -f "$f" ] || continue
    t=$2/${f##*/}
    if [ -L "$t" ]; then
      [ "$(readlink "$t")" = "$f" ] && continue
      rm -f "$t"
    elif [ -e "$t" ]; then
      is_builtin "$t" || continue
      rm -f "$t"
    fi
    ln -s "$f" "$t"
  done
}
winpath() { echo "Z:$1" | sed 's#/#\\#g'; }

# The prefix carries this tool's builtins when it was last set up by this version; another Proton (or an
# older DroidDeck-Bionic) leaves its own links there, and the compatdata version file tells DroidDeck-Proton
# to relink when it runs this prefix again.
prepare_prefix() {
  P=$COMPAT/pfx
  if [ -f "$P/.droiddeck-bionic" ] && [ "$(cat "$P/.droiddeck-bionic")" = "$VERSION" ] && \
     [ "$(cat "$COMPAT/version" 2>/dev/null)" = "$VERSION" ]; then return 0; fi
  if [ ! -f "$P/system.reg" ]; then
    echo "$(date +%H:%M:%S) new prefix from the layer's template"
    mkdir -p "$P" && cp -a "$F/default_pfx/." "$P/"
  fi
  W=$P/drive_c/windows
  mkdir -p "$W/system32" "$W/syswow64" "$P/dosdevices"
  echo "$(date +%H:%M:%S) linking builtins"
  link_dir "$F/lib/wine/aarch64-windows" "$W/system32"
  link_dir "$F/lib/wine/i386-windows" "$W/syswow64"
  for c in dxvk vkd3d-proton; do
    link_dir "$F/lib/wine/$c/aarch64-windows" "$W/system32"
    link_dir "$F/lib/wine/$c/i386-windows" "$W/syswow64"
  done
  # This layer's Wine always uses the profile C:\users\xuser, Proton's is C:\users\steamuser: one links to the
  # other so saves and settings are the same whichever tool runs the game.
  U=$P/drive_c/users
  if [ -d "$U/steamuser" ] && [ ! -e "$U/xuser" ]; then ln -s steamuser "$U/xuser"
  elif [ -d "$U/xuser" ] && [ ! -L "$U/xuser" ] && [ ! -e "$U/steamuser" ]; then ln -s xuser "$U/steamuser"; fi
  [ -e "$P/dosdevices/c:" ] || ln -s ../drive_c "$P/dosdevices/c:"
  [ -e "$P/dosdevices/z:" ] || ln -s / "$P/dosdevices/z:"
  wine_ regedit /S "$(winpath "$TOOL/bionic/desktop.reg")"
  wineserver_ -w
  echo "$VERSION" > "$P/.droiddeck-bionic"
  echo "$VERSION" > "$COMPAT/version"
  # Proton (DroidDeck-Proton, GE, ...) reads tracked_files when it takes this prefix over; one this tool made has
  # none. The version above is not a Proton prefix version, so Proton updates the prefix and never treats it as
  # one from a newer Proton (which would delete its tracked files).
  [ -e "$COMPAT/tracked_files" ] || : > "$COMPAT/tracked_files"
  echo "$(date +%H:%M:%S) prefix ready"
}

prefix_busy() { # a process of this prefix is running
  for p in /proc/[0-9]*; do
    [ "${p#/proc/}" = "$$" ] && continue
    tr '\0' '\n' < "$p/environ" 2>/dev/null | grep -qx "WINEPREFIX=$COMPAT/pfx" && return 0
  done
  return 1
}

stop_wine() { # wineserver -k, then whatever of this prefix is left (e.g. a game's crash reporter)
  wineserver_ -k 2>/dev/null
  sleep 1
  for p in /proc/[0-9]*; do
    tr '\0' '\n' < "$p/environ" 2>/dev/null | grep -qx "WINEPREFIX=$COMPAT/pfx" || continue
    [ "${p#/proc/}" = "$$" ] && continue
    echo "$(date +%H:%M:%S) left over: ${p#/proc/} $(cat "$p/comm" 2>/dev/null)"
    kill -9 "${p#/proc/}" 2>/dev/null
  done
}

# DroidDeck's component check (before a fix in the app) put its own FEX into this tool for games with a per-game
# FEX choice; its unix libraries are glibc builds bionic Wine cannot load. Put the tool's FEX back.
for f in aarch64-unix/libwow64fex.so aarch64-unix/libarm64ecfex.so aarch64-windows/libwow64fex.dll aarch64-windows/libarm64ecfex.dll; do
  [ -f "$PACK/fex/$f" ] || continue
  cmp -s "$PACK/fex/$f" "$F/lib/wine/$f" || { cat "$PACK/fex/$f" > "$F/lib/wine/$f" && echo "$(date +%H:%M:%S) restored the tool's FEX $f"; }
done

if [ "$verb" != runinprefix ] && prefix_busy; then
  echo "$(date +%H:%M:%S) Wine left running in this prefix; stopping it"
  stop_wine
fi
prefix_busy || rm -f "$NTSYNC_SHM".v*

prepare_prefix

# Steam's Windows client files in the prefix, the ones Proton copies from Steam's legacycompat folder: a game's
# steam_api loads C:\Program Files (x86)\Steam\steamclient64.dll, and a prefix this tool made has none.
SC=$ROOTFS/root/.local/share/Steam/legacycompat
D="$COMPAT/pfx/drive_c/Program Files (x86)/Steam"
if [ -d "$SC" ]; then
  mkdir -p "$D"
  for c in steamclient.dll:steamclient.dll steamclient64.dll:steamclient64.dll GameOverlayRenderer64.dll:GameOverlayRenderer64.dll \
           SteamService.exe:steam.exe Steam.dll:Steam.dll; do
    s=$SC/${c%%:*}; d=$D/${c#*:}
    [ -f "$s" ] || continue
    [ -f "$d" ] && [ ! "$s" -nt "$d" ] && continue
    cp "$s" "$d.new" && mv -f "$d.new" "$d" && touch -r "$s" "$d" && echo "$(date +%H:%M:%S) Steam client file ${c#*:}"
  done
fi

# D7VK (Direct3D 7 and older on Vulkan) for 32-bit games when PROTON_USE_D7VK is set and not 0, as DroidDeck-Proton:
# its ddraw.dll in syswow64 with Wine's own beside it as ddraw_.dll. Switched off, Wine's link goes back. Only the
# links in the prefix are removed, never the files they point to.
S=$COMPAT/pfx/drive_c/windows/syswow64
if [ -n "${PROTON_USE_D7VK:-}" ] && [ "$PROTON_USE_D7VK" != 0 ] && [ -f "$PACK/d7vk/ddraw.dll" ]; then
  rm -f "$S/ddraw.dll" "$S/ddraw_.dll"
  ln -s "$PACK/d7vk/ddraw.dll" "$S/ddraw.dll" && ln -s "$F/lib/wine/i386-windows/ddraw.dll" "$S/ddraw_.dll"
  export WINEDLLOVERRIDES="$WINEDLLOVERRIDES;ddraw=n,b"
  echo "$(date +%H:%M:%S) D7VK on (32-bit ddraw)"
elif [ -L "$S/ddraw_.dll" ] || [ -e "$S/ddraw_.dll" ]; then
  rm -f "$S/ddraw.dll" "$S/ddraw_.dll"
  ln -s "$F/lib/wine/i386-windows/ddraw.dll" "$S/ddraw.dll"
  echo "$(date +%H:%M:%S) D7VK off: Wine's ddraw again"
fi
cd "$(dirname "$EXE")" || exit 1

if [ "$verb" = runinprefix ]; then
  wine_ "$EXE" "$@"
  exit $?
fi

name=${EXE##*/}
comm=$(echo "$name" | cut -c1-15)
game_pids() { grep -lx "$comm" /proc/[0-9]*/comm 2>/dev/null | cut -d/ -f3; }
# The game's windows go straight to gamescope's X server, as with DroidDeck-Proton (no Wine desktop): the game
# sees gamescope's real modes and refresh rate, and gamescope manages its windows.
wine_ "$EXE" "$@" &
WP=$!
# Steam's overlay library marks a game's windows with the app ID Steam tracks (STEAM_GAME); that library is
# glibc-only, so this prefix's windows (WM_CLASS = the Windows program name) are tagged from outside, or gamescope
# never shows them.
GT=
if [ "${SteamAppId:-0}" != 0 ]; then
  XD=${DISPLAY#*:}; XD=${XD%%.*}
  "$LINKER" "$PACK/bin/gstag" "${XD:-0}" "$SteamAppId" .exe 86400 > "$COMPAT/gstag.log" 2>&1 &
  GT=$!
fi
trap 'echo "$(date +%H:%M:%S) stopped by Steam"; [ -n "$GT" ] && kill $GT 2>/dev/null; stop_wine; exit 0' TERM INT HUP
n=0
while [ $n -lt 90 ] && [ -z "$(game_pids)" ]; do sleep 1; n=$((n + 1)); done
echo "$(date +%H:%M:%S) game process $(game_pids | tr '\n' ' ')after ${n}s"
while [ -n "$(game_pids)" ]; do sleep 2; done
echo "$(date +%H:%M:%S) game exited"
[ -n "$GT" ] && kill $GT 2>/dev/null
stop_wine
wait $WP
echo "$(date +%H:%M:%S) done"
