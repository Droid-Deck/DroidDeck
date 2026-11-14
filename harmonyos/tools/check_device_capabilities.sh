#!/usr/bin/env bash
#
# Checks whether a phone can run the port.
#
# The port's hardware requirements are short, and every one of them can be
# read from the phone:
#
#   * an aarch64 CPU. The HAP ships arm64-v8a libraries, and the guest
#     runtime is aarch64 as well, so the phone runs the guest natively;
#   * OpenGL ES 3.0. The compositor keeps the guest's frames in GLES
#     textures and presents them in an XComponent, so the phone's GPU has
#     to give the app an ES 3 context;
#   * enough free storage for the guest runtime, which unpacks to about
#     1.1 GiB, and enough RAM to hold it.
#
# The phone's GPU is *not* used for the guest's own rendering: an app on
# HarmonyOS gets no GPU device node (/dev/kgsl, /dev/mali0), so the runtime
# pins Mesa's software driver. The script reports that too, because it is
# the single fact that decides how well a game inside the guest runs.
#
# Usage:
#   tools/check_device_capabilities.sh [path to hdc]
#
# The phone has to be connected over USB with developer mode and USB
# debugging enabled, so that `hdc list targets` shows it.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# The only argument is the path to hdc, for the case where it is neither on
# the PATH nor in the SDK that OHOS_SDK_HOME points at.
hdc_override=""
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) die "unknown argument: $1" ;;
    *) hdc_override="$1"; shift ;;
  esac
done

# --- find hdc -------------------------------------------------------------

find_hdc() {
  local candidate
  for candidate in \
      "${1:-}" \
      "${OHOS_SDK_HOME:-}/toolchains/hdc" \
      "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc" \
      "${HOME}/Library/Huawei/Sdk/default/openharmony/toolchains/hdc"; do
    [ -n "${candidate}" ] && [ -x "${candidate}" ] && { printf '%s' "${candidate}"; return 0; }
  done
  command -v hdc 2>/dev/null && return 0
  return 1
}

hdc="$(find_hdc "${hdc_override}")" || {
  cat >&2 <<'EOT'
error: hdc was not found.

It ships with the OpenHarmony SDK, so either point OHOS_SDK_HOME at that
SDK or pass the path to hdc:

    tools/check_device_capabilities.sh /path/to/openharmony/toolchains/hdc
EOT
  exit 1
}
echo "==> hdc: ${hdc}"

# --- the device ------------------------------------------------------------

targets="$("${hdc}" list targets 2>/dev/null | tr -d '\r' | grep -v '^$' | grep -v 'Empty')"
if [ -z "${targets}" ]; then
  cat >&2 <<'EOT'
error: no phone is connected.

`hdc list targets` is empty. On the phone: Settings > About phone > tap the
build number seven times to turn on developer mode, then Settings > System
and updates > Developer options > USB debugging, and plug it into this
machine. Then run this script again.
EOT
  exit 1
fi

echo "==> device: ${targets}"

# shell <command> runs a command on the phone and prints what it said.
shell() {
  "${hdc}" shell "$@" 2>/dev/null | tr -d '\r'
}

# report <label> <state> <detail>
report() {
  printf '  %-22s %-6s %s\n' "$1" "$2" "$3"
}

echo
echo "==> what the port needs from this phone"

# --- the CPU ---------------------------------------------------------------

arch="$(shell uname -m)"
case "${arch}" in
  aarch64|arm64) report "CPU architecture" PASS "${arch} (the HAP and the guest are arm64)" ;;
  "") report "CPU architecture" UNKNOWN "the phone did not answer" ;;
  *) report "CPU architecture" FAIL "${arch} (arm64 is required)" ;;
esac

model="$(shell param get const.product.model | tr -d '\r')"
[ -n "${model}" ] && report "Model" INFO "${model}"

cpuinfo="$(shell cat /proc/cpuinfo)"
hardware="$(printf '%s' "${cpuinfo}" | sed -n 's/^Hardware[[:space:]]*:[[:space:]]*//p' | head -1)"
[ -n "${hardware}" ] && report "CPU" INFO "${hardware}"
cores="$(printf '%s' "${cpuinfo}" | grep -c '^processor' )"
if [ "${cores}" -gt 0 ] 2>/dev/null; then
  if [ "${cores}" -ge 4 ]; then
    report "CPU cores" PASS "${cores}"
  else
    report "CPU cores" WARN "${cores} (four or more are better for the guest)"
  fi
fi

# --- memory and storage ----------------------------------------------------

memtotal="$(shell cat /proc/meminfo | sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p')"
if [ -n "${memtotal}" ]; then
  mem_mib=$((memtotal / 1024))
  if [ "${mem_mib}" -ge 6144 ]; then
    report "RAM" PASS "${mem_mib} MiB"
  elif [ "${mem_mib}" -ge 4096 ]; then
    report "RAM" WARN "${mem_mib} MiB (6 GiB or more is comfortable)"
  else
    report "RAM" WARN "${mem_mib} MiB (the guest wants 4 GiB or more)"
  fi
fi

free_kb="$(shell df -k /data | awk 'NR==2 {print $4}')"
if [ -z "${free_kb}" ]; then
  free_kb="$(shell df -k /storage | awk 'NR==2 {print $4}')"
fi
if [ -n "${free_kb}" ]; then
  free_gib=$((free_kb / 1024 / 1024))
  if [ "${free_gib}" -ge 3 ]; then
    report "Free storage" PASS "${free_gib} GiB"
  else
    report "Free storage" WARN "${free_gib} GiB (the runtime needs about 1.1 GiB plus room to unpack)"
  fi
fi

# --- the GPU ---------------------------------------------------------------
#
# The app's own probe is the authority on OpenGL ES: it only reports a
# renderer once it has created an ES 3 context, so the line in the log
# proves that the phone's GPU gave the app one.

log_lines="$(shell hilog -T DroidDeck -x | grep -E 'gpu renderer|no EGL|eglInitialize|cannot create the EGL')"
renderer="$(printf '%s' "${log_lines}" | sed -n 's/.*gpu renderer=\([^ ]*\) vendor=\([^ ]*\).*/\1 (\2)/p' | head -1)"
if [ -n "${renderer}" ]; then
  report "OpenGL ES 3" PASS "${renderer}"
elif printf '%s' "${log_lines}" | grep -q 'no EGL\|eglInitialize'; then
  report "OpenGL ES 3" FAIL "the app could not get an EGL context"
else
  report "OpenGL ES 3" UNKNOWN "run the app once, then run this again"
fi

# The guest's rendering: an app gets no GPU device node on HarmonyOS, so the
# runtime pins Mesa's software driver. If a node turns up, the guest could
# be pointed at the phone's own driver instead.
nodes="$(shell ls /dev/kgsl* /dev/mali* /dev/dri 2>/dev/null | tr '\n' ' ')"
if [ -n "${nodes}" ]; then
  report "GPU device node" INFO "${nodes}"
else
  report "GPU device node" INFO "none (the guest renders on the CPU)"
fi

icd="$(shell ls /vendor/lib64/hw/vulkan* /etc/vulkan/icd.d 2>/dev/null | tr '\n' ' ')"
if [ -n "${icd}" ]; then
  report "Phone Vulkan driver" INFO "${icd}"
else
  report "Phone Vulkan driver" INFO "not visible to the app"
fi

bridge="$(printf '%s' "${log_lines}" | grep -c 'vulkan')"
if [ "${bridge}" -gt 0 ] 2>/dev/null; then
  report "Vulkan bridge" INFO "the app sees a Vulkan driver"
fi

echo
echo "==> summary"
if printf '%s' "${renderer}" | grep -q .; then
  echo "  The phone can run the launcher and the guest session."
else
  echo "  The CPU and the system look right, but OpenGL ES 3 is unproven."
  echo "  Install the HAP, open it once, and run this script again."
fi
echo "  The guest renders on the CPU (Mesa lavapipe): a game inside the"
echo "  guest will be slow, however good the phone's GPU is. See"
echo "  harmonyos/docs/HARDWARE.md."
