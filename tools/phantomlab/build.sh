#!/usr/bin/env bash
# Builds phantomlab.apk without Gradle: javac, d8, aapt2, the NDK's clang, apksigner (debug key).
# usage: tools/phantomlab/build.sh [out.apk]   (ANDROID_SDK_ROOT or ANDROID_HOME must be set)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?set ANDROID_SDK_ROOT}}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
platform=$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)
ndk=${ANDROID_NDK_ROOT:-$(ls -d "$sdk"/ndk/* | sort -V | tail -1)}
out=${1:-$here/build/phantomlab.apk}
work=$here/build
exe=""
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) exe=".exe" ;; esac
bat=""
[ -n "$exe" ] && bat=".bat"

rm -rf "$work/classes" "$work/dex" "$work/lib" "$work/unsigned.apk"
mkdir -p "$work/classes" "$work/dex"

javac -nowarn --release 8 -cp "$platform/android.jar" -d "$work/classes" $(find "$here/src" -name '*.java') 2>&1 | grep -v "^warning\|^Note:" || true
"$bt/d8$bat" --min-api 29 --lib "$platform/android.jar" --output "$work/dex" $(find "$work/classes" -name '*.class')

host=$(ls -d "$ndk"/toolchains/llvm/prebuilt/* | head -1)
for abi in arm64-v8a x86_64; do
    case $abi in arm64-v8a) triple=aarch64-linux-android29 ;; x86_64) triple=x86_64-linux-android29 ;; esac
    mkdir -p "$work/lib/$abi"
    "$host/bin/clang$exe" --target=$triple -shared -fPIC -O2 -Wall -o "$work/lib/$abi/liblab.so" "$here/jni/lab.c" -llog
    "$host/bin/clang$exe" --target=$triple -fPIE -pie -O2 -Wall -o "$work/lib/$abi/libplabexe.so" "$here/jni/exe.c"
done

"$bt/aapt2$exe" link -o "$work/unsigned.apk" -I "$platform/android.jar" --manifest "$here/AndroidManifest.xml"
python3 - "$work" <<'EOF'
import os, sys, zipfile
work = sys.argv[1]
with zipfile.ZipFile(os.path.join(work, "unsigned.apk"), "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(work, "dex", "classes.dex"), "classes.dex")
    for abi in os.listdir(os.path.join(work, "lib")):
        for so in ("liblab.so", "libplabexe.so"):
            z.write(os.path.join(work, "lib", abi, so), "lib/%s/%s" % (abi, so))
EOF

key=$work/debug.keystore
[ -f "$key" ] || keytool -genkeypair -keystore "$key" -storepass android -keypass android -alias lab \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=phantomlab" >/dev/null 2>&1
"$bt/zipalign$exe" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
"$bt/apksigner$bat" sign --ks "$key" --ks-pass pass:android --out "$out" "$work/aligned.apk"
echo "$out"
