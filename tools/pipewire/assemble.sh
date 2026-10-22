#!/usr/bin/env bash
# assemble.sh <extracted packages root> <out.tzst>
# Packs the video side of SteamOS's PipeWire and WirePlumber: no audio plugins, no JACK.
set -euo pipefail
src=$1
out=$2
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
b=$work/bundle
mkdir -p "$b/bin" "$b/lib/pipewire-0.3" "$b/lib/spa-0.2" "$b/lib/wireplumber-0.5" "$b/share/licenses"

for tool in pipewire wireplumber wpctl; do cp "$src/usr/bin/$tool" "$b/bin/$tool"; done
for library in libpipewire-0.3.so.0 libwireplumber-0.5.so.0; do cp -L "$src/usr/lib/$library" "$b/lib/$library"; done
for module in "$src"/usr/lib/pipewire-0.3/libpipewire-module-*.so; do
    case ${module##*/} in *jack*) continue ;; esac
    cp "$module" "$b/lib/pipewire-0.3/"
done
cp "$src/usr/lib/spa-0.2/libspa.so" "$b/lib/spa-0.2/"
for plugin in support control videoconvert v4l2; do cp -R "$src/usr/lib/spa-0.2/$plugin" "$b/lib/spa-0.2/$plugin"; done
cp "$src"/usr/lib/wireplumber-0.5/*.so "$b/lib/wireplumber-0.5/"
mkdir -p "$b/share/pipewire"
cp "$src/usr/share/pipewire/pipewire.conf" "$src/usr/share/pipewire/client.conf" "$b/share/pipewire/"
cp -R "$src/usr/share/wireplumber" "$b/share/wireplumber"
for package in pipewire libpipewire wireplumber libwireplumber; do
    cp -R "$src/usr/share/licenses/$package" "$b/share/licenses/$package"
done

mkdir -p "$(dirname "$out")"
(cd "$b" && find . -type f | LC_ALL=C sort | tar -cf - -T -) | zstd -19 -q -f -o "$out"
