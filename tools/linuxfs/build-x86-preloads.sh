#!/bin/sh
set -eu
out=${1:?usage: build-x86-preloads.sh <linuxfs asset directory>}
for arch in x86_64 i386; do
  case $arch in
    x86_64) bits=-m64 ;;
    i386) bits=-m32 ;;
  esac
  mkdir -p "$out/$arch"
  gcc $bits -shared -fPIC -O2 -Wall -pthread \
    -o "$out/$arch/libblsession.so" tools/linuxfs/preload/*.c -ldl
  g++ $bits -shared -fPIC -O2 -Wall -Wno-attributes -Wno-nonnull-compare \
    -pthread -std=c++17 -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL \
    -o "$out/$arch/libfakeinput.so" app/src/main/cpp/fakeinput_steam.cpp -ldl
  strip --strip-unneeded "$out/$arch/libblsession.so" "$out/$arch/libfakeinput.so"
  for lib in "$out/$arch/libblsession.so" "$out/$arch/libfakeinput.so"; do
    newest=$(objdump -T "$lib" | grep -o 'GLIBC_2\.[0-9]*' | sort -u -t. -k2,2n | tail -1)
    minor=${newest#GLIBC_2.}
    if [ "$minor" -gt 31 ]; then
      echo "$lib needs $newest; Steam Linux Runtime 3.0 has glibc 2.31" >&2
      exit 1
    fi
    if objdump -p "$lib" | grep -q 'NEEDED.*lib\(stdc++\|gcc_s\)'; then
      echo "$lib links the C++ runtime dynamically" >&2
      exit 1
    fi
  done
done
for target in x86_64/faultreport i386/faultreport; do
  arch=${target%/*} name=${target#*/}
  case $arch in
    x86_64) bits=-m64 ;;
    i386) bits=-m32 ;;
  esac
  lib=$out/$arch/lib$name.so
  gcc $bits -shared -fPIC -O2 -Wall -Wextra -nostdlib -fno-stack-protector -fno-builtin -fvisibility=hidden \
    -o "$lib" tools/linuxfs/fex/$name.c
  strip --strip-unneeded "$lib"
  if objdump -p "$lib" | grep -q NEEDED || [ -n "$(nm -D --undefined-only "$lib" 2>/dev/null)" ]; then
    echo "$lib must not need any library" >&2
    exit 1
  fi
done
lib=$out/x86_64/libwebkitinput.so
gcc -m64 -shared -fPIC -O2 -Wall -Wextra -nostdlib -fno-stack-protector -fno-builtin -fvisibility=hidden \
  -o "$lib" tools/linuxfs/fex/webkitinput.c
strip --strip-unneeded "$lib"
if objdump -p "$lib" | grep -q NEEDED || [ "$(nm -D --undefined-only "$lib" | awk '{print $1 $2}' | LC_ALL=C sort | tr '\n' ' ')" != "wdlsym wg_getenv wg_object_get_data wg_object_set wg_object_set_data wg_type_check_instance_is_a wgtk_container_forall wgtk_container_get_type wgtk_key_snooper_install wgtk_widget_get_toplevel wwebkit_user_script_new wwebkit_user_script_unref wwebkit_web_view_get_type wwebkit_web_view_get_uri wwebkit_web_view_run_javascript " ]; then
  echo "$lib must need nothing but weak GLib, GTK and WebKit calls" >&2
  exit 1
fi
stub=$(mktemp -d)
gcc -m64 -shared -nostdlib -Wl,-soname,/usr/lib/x86_64-linux-gnu/libvulkan.so.1 -o "$stub/libvulkan.so.1" -x c /dev/null
lib=$out/x86_64/libvulkan-thunk.so
gcc -m64 -shared -fPIC -O2 -Wall -Wextra -fvisibility=hidden -Wl,-soname,libvulkan.so.1 -Wl,-z,nodelete -Wl,--no-as-needed \
  -o "$lib" tools/linuxfs/fex/vulkanpin.c "$stub/libvulkan.so.1" -ldl
rm -rf "$stub"
strip --strip-unneeded "$lib"
newest=$(objdump -T "$lib" | grep -o 'GLIBC_2\.[0-9]*' | sort -u -t. -k2,2n | tail -1)
if ! objdump -p "$lib" | grep -q 'NEEDED *\/usr/lib/x86_64-linux-gnu/libvulkan\.so\.1$' \
   || ! objdump -p "$lib" | grep -q 'FLAGS_1 *0x0*8$' || [ "${newest#GLIBC_2.}" -gt 31 ]; then
  echo "$lib must be nodelete, need the Vulkan thunk and run on glibc 2.31" >&2
  exit 1
fi
