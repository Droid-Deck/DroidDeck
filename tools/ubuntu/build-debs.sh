#!/bin/bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(realpath -m "${1:?out dir}")
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 DEBEMAIL=maxjividen@gmail.com DEBFULLNAME=maxjivi05
export DEB_BUILD_OPTIONS="nocheck noddebs parallel=$(nproc)"
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/ubuntu.sources
apt-get update -qq
apt-get install -y -qq build-essential devscripts equivs patch >/dev/null
mkdir -p "$out"
work=$(mktemp -d)
cd "$work"

apt-get source -qq mesa
cd mesa-*/
sed -i '0,/GALLIUM_DRIVERS += freedreno/s//GALLIUM_DRIVERS += freedreno\n\tconfflags_GALLIUM += -Dfreedreno-kmds=msm,kgsl/' debian/rules
grep -q 'freedreno-kmds=msm,kgsl' debian/rules
for p in "$here"/patches/mesa/*.patch; do patch -p1 --forward < "$p"; done
dch -l +droiddeck -D resolute "Enable the KGSL kernel interface for freedreno and Turnip."
apt-get build-dep -y -qq ./ >/dev/null
dpkg-buildpackage -b -uc -us >"$work/mesa.log" 2>&1 || { tail -80 "$work/mesa.log"; exit 1; }
cd "$work"

apt-get source -qq kwin
cd kwin-*/
for p in "$here"/patches/kwin/*.patch; do patch -p1 --forward < "$p"; done
dch -l +droiddeck -D resolute "Scale nested outputs, open the selected render node, forward scroll details."
apt-get build-dep -y -qq ./ >/dev/null
dpkg-buildpackage -b -uc -us >"$work/kwin.log" 2>&1 || { tail -80 "$work/kwin.log"; exit 1; }
cd "$work"

for deb in *.deb; do
  case $deb in *-dbgsym_*) continue ;; esac
  cp "$deb" "$out/"
done
ls -l "$out"
