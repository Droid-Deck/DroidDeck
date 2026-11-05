#!/usr/bin/env bash
set -euo pipefail
export NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}}"
tools/aaudio-sink/build.sh "$PWD/pa13" "$PWD/sink-out"
rm -rf pa-bundle && mkdir -p pa-bundle
tar --use-compress-program=unzstd -xf app/src/main/assets/pulseaudio.tzst -C pa-bundle
test ! -f pa-bundle/modules/arm64/module-aaudio-sink.so
install -m755 sink-out/module-aaudio-sink.so pa-bundle/modules/arm64/module-aaudio-sink.so
install -m755 sink-out/module-directaudio-native-sink.so pa-bundle/modules/arm64/module-directaudio-native-sink.so
(cd pa-bundle && tar --use-compress-program='zstd -19' -cf ../pulseaudio.tzst .)
mkdir -p app/build/prepared-assets
mv pulseaudio.tzst app/build/prepared-assets/pulseaudio.tzst
tar --use-compress-program=unzstd -tf app/build/prepared-assets/pulseaudio.tzst | grep -c '\.so$'
rm -rf pa-bundle sink-out
