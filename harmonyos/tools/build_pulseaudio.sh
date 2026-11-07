#!/usr/bin/env bash
#
# Builds PulseAudio for HarmonyOS 7 (aarch64, musl).
#
# Upstream DroidDeck runs a PulseAudio **server** on the host: the guest's
# Steam client connects to it through PULSE_SERVER, and the server's sink
# writes to the phone's audio HAL. Upstream's sink module is built from
# source, but against a *prebuilt* libpulsecore that is bionic, so it cannot
# be used on HarmonyOS. The port therefore builds the server core itself.
#
# What is produced:
#
#   pulseaudio                the server daemon
#   libpulsecore-<n>.so       the server core
#   libpulsecommon-<n>.so     the code both sides share
#   libpulse-<n>.so           the client library
#
# The result is installed into entry/libs/arm64-v8a/ so the HAP ships it.
#
# Usage: OHOS_SDK_HOME=/path/to/openharmony-sdk tools/build_pulseaudio.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${OHOS_SDK_HOME:?set OHOS_SDK_HOME to the OpenHarmony SDK root}"
NDK="${OHOS_SDK_HOME}/native"
work="${WORK_DIR:-${TMPDIR:-/tmp}/droiddeck-pulseaudio}"

PULSE_VERSION="17.0"
SOURCE_URL="https://www.freedesktop.org/software/pulseaudio/releases/pulseaudio-${PULSE_VERSION}.tar.xz"

export PATH="${PATH}:${HOME}/Library/Python/3.14/bin"

if [ ! -x "${NDK}/llvm/bin/clang" ]; then
  echo "the NDK compiler was not found at ${NDK}/llvm/bin/clang" >&2
  exit 1
fi

mkdir -p "${work}"
source_dir="${work}/pulseaudio-${PULSE_VERSION}"
build_dir="${work}/build"

if [ ! -d "${source_dir}" ]; then
  echo "==> fetching pulseaudio ${PULSE_VERSION}"
  curl -sL --max-time 900 -o "${work}/pulseaudio.tar.xz" "${SOURCE_URL}"
  tar -xf "${work}/pulseaudio.tar.xz" -C "${work}"
fi

# --- the one change PulseAudio needs for this target ---------------------
#
# There is no gettext library for musl, and the OpenHarmony SDK's
# libintl.h declares nothing. PulseAudio already carries a fallback for
# that: with ENABLE_NLS off, every gettext call becomes a macro that
# returns the string unchanged. Upstream's own Android build runs the same
# way — its hand-written config.h leaves NLS out.
#
# The translations are lost, which does not matter: the port's strings are
# English.
python3 - "${source_dir}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = root / 'meson.build'
text = manifest.read_text()

if "cdata.set('ENABLE_NLS', 1)" in text:
    # The define has to be removed rather than set to zero: PulseAudio's
    # i18n.h tests it with #ifdef, which is true for a zero value.
    text = text.replace("cdata.set('ENABLE_NLS', 1)",
                        "# NLS is off for this target: there is no gettext library for it.")
    text = text.replace(
        """if cc.has_function('dgettext')
  if host_machine.system() != 'windows'
    libintl_dep = []
  else
    libintl_dep = cc.find_library('intl')
  endif
else
  libintl_dep = cc.find_library('intl')
endif""",
        """# No gettext library for this target; the NLS fallback in
# src/pulsecore/i18n.h takes over.
libintl_dep = []""")
    manifest.write_text(text)
    print('patched: NLS disabled')
else:
    print('already patched')
PY

# PulseAudio's NEON code is armv7: it uses the q registers, which an
# aarch64 assembler does not know. On aarch64 the plain C is used instead.
python3 - "${source_dir}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = root / 'src/pulsecore/meson.build'
text = manifest.read_text()

old = """foreach simd_kwargs : simd_variants

  if host_machine.cpu_family() == 'arm' and 'neon' in simd_kwargs"""
new = """foreach simd_kwargs : simd_variants

  # The NEON sources are armv7: they use the q registers, which an aarch64
  # assembler does not know.
  if 'neon' in simd_kwargs and host_machine.cpu_family() != 'arm'
    continue
  endif

  if host_machine.cpu_family() == 'arm' and 'neon' in simd_kwargs"""

if old in text:
    manifest.write_text(text.replace(old, new))
    print('patched: the armv7 NEON code is skipped')
else:
    print('the NEON code is already skipped')
PY

# musl has no gethostid(), which PulseAudio calls for a session identifier.
python3 - "${source_dir}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = root / 'src/meson.build'
text = manifest.read_text()

old = "  'pulsecore/sndfile-util.c',"
new = "  'pulsecore/sndfile-util.c',\n  'pulsecore/musl-compat.c',"

if old in text:
    manifest.write_text(text.replace(old, new, 1))
    print('patched: musl-compat added to the core')
else:
    print('musl-compat is already in the core')
PY

# Linux capabilities.
#
# caps.c needs libcap, which musl does not have. The port runs the server as
# the app's own user, so the two functions it provides have nothing to do,
# and a small stub takes its place.
cp "${root}/tools/pulseaudio/caps_stub.c" "${source_dir}/src/daemon/caps-stub.c"
python3 - "${source_dir}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = root / 'src/daemon/meson.build'
text = manifest.read_text()

if "  'caps.c',\n" in text:
    manifest.write_text(text.replace("  'caps.c',\n", "  'caps-stub.c',\n", 1))
    print('patched: the libcap code is replaced with a stub')
else:
    print('the libcap code is already replaced')
PY

# --- the libsndfile stub ---------------------------------------------------
#
# PulseAudio's core links libsndfile so that it can play sound files as
# streams. The port never does that — the audio comes from the guest over
# the Pulse protocol — and there is no libsndfile for musl, so a small
# stub provides the symbols the core links against. It is found the same
# way a real one would be: through pkg-config.
echo "==> building the libsndfile stub"
stub_dir="${work}/sndfile-stub"
mkdir -p "${stub_dir}/include" "${stub_dir}/lib" "${stub_dir}/pkgconfig"
cp "${root}/tools/pulseaudio/sndfile_stub.h" "${stub_dir}/include/sndfile.h"
"${NDK}/llvm/bin/clang" \
  --target=aarch64-linux-ohos --sysroot="${NDK}/sysroot" -D__MUSL__ \
  -O2 -fPIC -Wall -Wextra -Wno-unused-parameter \
  -I"${stub_dir}/include" \
  -c "${root}/tools/pulseaudio/sndfile_stub.c" -o "${stub_dir}/sndfile_stub.o"
"${NDK}/llvm/bin/llvm-ar" rcs "${stub_dir}/lib/libsndfile.a" "${stub_dir}/sndfile_stub.o"
cat > "${stub_dir}/pkgconfig/sndfile.pc" <<EOF
prefix=${stub_dir}
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: sndfile
Description: Minimal libsndfile for the HarmonyOS build of PulseAudio
Version: 1.0.31
Libs: -L\${libdir} -lsndfile
Cflags: -I\${includedir}
EOF

export PKG_CONFIG_LIBDIR="${stub_dir}/pkgconfig"
export PKG_CONFIG_PATH="${stub_dir}/pkgconfig"

# --- libltdl ---------------------------------------------------------------
#
# PulseAudio loads its modules through GNU libltdl, and there is no ltdl for
# musl. It is built here from the libtool release, which carries the ltdl
# sources with a standalone configure.
echo "==> building libltdl for aarch64-linux-musl"
LIBTOOL_VERSION="2.4.7"
libltdl_dir="${work}/libtool-${LIBTOOL_VERSION}/libltdl"
if [ ! -d "${libltdl_dir}" ]; then
  curl -sL --max-time 900 -o "${work}/libtool.tar.gz" \
    "https://ftp.gnu.org/gnu/libtool/libtool-${LIBTOOL_VERSION}.tar.gz"
  tar -xzf "${work}/libtool.tar.gz" -C "${work}"
fi
if [ ! -f "${libltdl_dir}/.libs/libltdlc.a" ]; then
  (
    # The ltdl sources are built in place, because their configure needs
    # libtool's build-aux directory next to them.
    cd "${libltdl_dir}"
    export CC="${NDK}/llvm/bin/clang"
    export AR="${NDK}/llvm/bin/llvm-ar"
    export RANLIB="${NDK}/llvm/bin/llvm-ranlib"
    export CFLAGS="--target=aarch64-linux-ohos --sysroot=${NDK}/sysroot -D__MUSL__ -O2 -fPIC"
    export LDFLAGS="--target=aarch64-linux-ohos --sysroot=${NDK}/sysroot"
    ./configure --host=aarch64-linux-musl --prefix="${work}/libltdl-install" \
      --disable-ltdl-install > "${work}/libltdl-configure.log" 2>&1
    make > "${work}/libltdl-make.log" 2>&1
  )
fi
if [ ! -f "${libltdl_dir}/.libs/libltdlc.a" ]; then
  echo "libltdl could not be built; see ${work}/libltdl-make.log" >&2
  exit 1
fi
mkdir -p "${work}/libltdl-install/lib" "${work}/libltdl-install/include/libltdl"
cp "${libltdl_dir}/.libs/libltdlc.a" "${work}/libltdl-install/lib/libltdl.a"
cp "${libltdl_dir}/ltdl.h" "${work}/libltdl-install/include/ltdl.h"
# ltdl.h includes its own headers as <libltdl/...>, so they have to be
# installed in that layout.
cp "${libltdl_dir}"/libltdl/*.h "${work}/libltdl-install/include/libltdl/"
echo "    libltdl built"

# The musl compatibility shim.
#
# musl has no gethostid(), which PulseAudio calls to build a session
# identifier. The declaration is force-included into every translation unit,
# and the implementation is compiled into the core.
cp "${root}/tools/pulseaudio/musl_compat.c" "${source_dir}/src/pulsecore/musl-compat.c"
cp "${root}/tools/pulseaudio/musl_compat.h" "${source_dir}/src/pulsecore/musl_compat.h"
cp "${root}/tools/pulseaudio/musl_compat.h" "${work}/musl_compat.h"

# The cross file points at the SDK's clang directly, because the NDK's
# wrapper scripts use GNU readlink, which macOS does not have. It is
# generated, because the SDK's location is not fixed.
cross_file="${work}/meson-aarch64-ohos.ini"
cat > "${cross_file}" <<EOF
[binaries]
c = '${NDK}/llvm/bin/clang'
cpp = '${NDK}/llvm/bin/clang++'
ar = '${NDK}/llvm/bin/llvm-ar'
strip = '${NDK}/llvm/bin/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['--target=aarch64-linux-ohos', '--sysroot=${NDK}/sysroot', '-D__MUSL__', '-I${work}/libltdl-install/include', '-include', '${work}/musl_compat.h']
c_link_args = ['--target=aarch64-linux-ohos', '--sysroot=${NDK}/sysroot', '-L${work}/libltdl-install/lib']
cpp_args = ['--target=aarch64-linux-ohos', '--sysroot=${NDK}/sysroot', '-D__MUSL__', '-I${work}/libltdl-install/include', '-include', '${work}/musl_compat.h']
cpp_link_args = ['--target=aarch64-linux-ohos', '--sysroot=${NDK}/sysroot', '-L${work}/libltdl-install/lib']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

if [ ! -f "${build_dir}/build.ninja" ]; then
  echo "==> configuring for aarch64-linux-musl"
  # Everything that needs a desktop or a service manager is off: this build
  # is a headless server with a native-protocol socket.
  meson setup "${build_dir}" "${source_dir}" \
    --cross-file "${cross_file}" \
    -Dpkg_config_path="${stub_dir}/pkgconfig" \
    -Ddaemon=true \
    -Dclient=true \
    -Ddatabase=simple \
    -Ddoxygen=false -Dgcov=false -Dman=false -Dtests=false \
    -Dvalgrind=disabled \
    -Dsystem_user= -Dsystem_group= -Daccess_group= \
    -Dlegacy-database-entry-format=false \
    -Dstream-restore-clear-old-devices=false \
    -Drunning-from-build-tree=false \
    -Datomic-arm-linux-helpers=false -Datomic-arm-memory-barrier=false \
    -Dpadsplibdir= -Dpulsedsp-location= \
    -Dmodlibexecdir=lib/pulseaudio/modules \
    -Dalsadatadir= -Dsystemduserunitdir= -Dudevrulesdir= \
    -Dbashcompletiondir= -Dzshcompletiondir= \
    -Dalsa=disabled -Dasyncns=disabled -Davahi=disabled \
    -Dbluez5=disabled -Dbluez5-gstreamer=disabled \
    -Dbluez5-native-headset=false -Dbluez5-ofono-headset=false \
    -Dconsolekit=disabled -Ddbus=disabled -Delogind=disabled \
    -Dfftw=disabled -Dglib=disabled -Dgsettings=disabled \
    -Dgstreamer=disabled -Dgtk=disabled -Dhal-compat=false \
    -Dipv6=true -Djack=disabled -Dlirc=disabled -Dopenssl=disabled \
    -Dorc=disabled -Doss-output=disabled -Dsamplerate=disabled \
    -Dsoxr=disabled -Dspeex=disabled -Dsystemd=disabled \
    -Dtcpwrap=disabled -Dudev=disabled -Dx11=disabled \
    -Denable-smoother-2=false -Dadrian-aec=true -Dwebrtc-aec=disabled
fi

echo "==> building"
meson compile -C "${build_dir}"

# The daemon.
#
# The port ships it as a shared library, the way upstream does: the app's
# native library directory is where the HAP's binaries are packaged and the
# one place on HarmonyOS where a binary can be executed. The entry point is
# main(), so the library can be run directly.
python3 - "${source_dir}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = root / 'src/daemon/meson.build'
text = manifest.read_text()

old = "executable('pulseaudio',"
new = """shared_library('pulseaudio',
  pulseaudio_sources,
  pulseaudio_headers,
  name_prefix : 'lib',
  include_directories : [configinc, topinc],
  link_args : ['-Wl,-e,main', '-ffast-math'],
  link_with : [libpulsecore],
  dependencies : [ltdl_dep, cap_dep, dbus_dep, libsystemd_dep, dl_dep, libintl_dep, platform_dep, platform_socket_dep, libpulsecommon_dep, libpulse_dep],
  c_args : pa_c_args,
  install : true,
)

executable('pulseaudio',"""

if new not in text:
    manifest.write_text(text.replace(old, new, 1))
    print('patched: the daemon is built as a shared library too')
else:
    print('the daemon is already built as a shared library')
PY

echo "==> installing into the project"
mkdir -p "${root}/entry/libs/arm64-v8a" "${root}/entry/src/main/cpp/thirdparty/pulseaudio/include"

install_one() {
  local src="$1" dst="$2"
  if [ -f "${src}" ]; then
    cp "${src}" "${dst}"
    echo "    $(basename "${dst}")"
  fi
}

# The libraries, wherever meson put them.
#
# Each one is installed under the name its SONAME says, because the app
# resolves a dependency by that name: a library shipped as
# libpulse.so.0.24.3 but named libpulse.so.0 in its SONAME would never be
# found.
while IFS= read -r lib; do
  [ -f "${lib}" ] || continue
  base="$(basename "${lib}")"
  case "${base}" in
    *.symbols)
      # A build byproduct, not a library.
      continue
      ;;
  esac
  soname="$("${NDK}/llvm/bin/llvm-readelf" -d "${lib}" 2>/dev/null |
    sed -n 's/.*Library soname: \[\(.*\)\].*/\1/p')"
  [ -n "${soname}" ] || soname="${base}"
  install_one "${lib}" "${root}/entry/libs/arm64-v8a/${soname}"
done < <(find "${build_dir}" -maxdepth 3 \( -name "libpulse*.so*" -o -name "libprotocol-native.so*" \) -type f)

install_one "${build_dir}/src/pulseaudio/pulseaudio" \
             "${root}/entry/libs/arm64-v8a/pulseaudio"
# The daemon is built as a shared library, and meson prefixes the name with
# "lib" of its own accord.
install_one "${build_dir}/src/daemon/libpulseaudio.so" \
             "${root}/entry/libs/arm64-v8a/libpulseaudio.so"

# The modules the daemon loads by name. The protocol module is how the
# guest's clients reach the server, so it has to ship with it.
for module in module-native-protocol-unix module-native-protocol-fd; do
  install_one "${build_dir}/src/modules/${module}.so" \
               "${root}/entry/libs/arm64-v8a/${module}.so"
done

# The headers the sink module is built against.
echo "==> installing the PulseAudio headers"
include_root="${root}/entry/src/main/cpp/thirdparty/pulseaudio/include"
mkdir -p "${include_root}"
# The headers live in subdirectories too — pulsecore/filter/ and friends — so
# they are copied with their structure intact.
python3 - "${source_dir}" "${include_root}" <<'PY'
import pathlib
import shutil
import sys

source = pathlib.Path(sys.argv[1]) / 'src'
target = pathlib.Path(sys.argv[2])

for area in ('pulse', 'pulsecore'):
    base = source / area
    if not base.is_dir():
        continue
    for header in base.rglob('*.h'):
        destination = target / area / header.relative_to(base)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(header, destination)
print('    the headers are installed')
PY
# version.h is generated by the build.
install_one "${build_dir}/src/pulse/version.h" "${include_root}/pulse/version.h"
if [ -f "${build_dir}/config.h" ]; then
  cp "${build_dir}/config.h" "${include_root}/config.h"
fi

# --- the sink module ------------------------------------------------------
#
# The module is the port's counterpart of upstream's AAudio sink: it is
# loaded into the PulseAudio server and writes the guest's audio to the
# socket the native module reads.
echo "==> building the sink module"
# Only the installed headers are on the include path: PulseAudio has a
# pulsecore/poll.h of its own, and putting the source tree on the path would
# shadow the system's poll.h with it.
"${NDK}/llvm/bin/clang" \
  --target=aarch64-linux-ohos --sysroot="${NDK}/sysroot" -D__MUSL__ \
  -O2 -fPIC -shared -Wall -Wno-unused-parameter \
  -I"${include_root}" -I"${work}/libltdl-install/include" \
  -o "${root}/entry/libs/arm64-v8a/module-droiddeck-sink.so" \
  "${root}/entry/src/main/cpp/audio/module-droiddeck-sink.c" \
  -L"${root}/entry/libs/arm64-v8a" \
  -l:libpulsecore-17.0.so -l:libpulsecommon-17.0.so \
  -Wl,-soname,module-droiddeck-sink.so

echo "==> done"
ls -la "${root}/entry/libs/arm64-v8a/"
