#!/usr/bin/env bash
# sign-apk.sh <in.apk> <variant> <out.apk>
#
# Turns a CI build (signed with the public AOSP testkey) into one package of tools/release/variants.txt
# signed with DroidDeck's own key, carrying the hand-over from the testkey:
#   - v1 + v2 are signed by the testkey, v3 by our key, with a signed lineage testkey -> our key;
#   - an install from before (testkey) updates to it and keeps its data;
#   - the testkey loses its rollback right, so an apk signed with the testkey alone - which anyone
#     can make - is refused as an update to it (device-proven on the Pocket FIT, 2026-09-24);
#   - --rotation-min-sdk-version 28: the lineage applies from Android 9, not only from 13.
#
# The key comes from the environment and is never written anywhere but the path given:
#   RELEASE_KEYSTORE        path to the PKCS12 keystore
#   RELEASE_STORE_PASSWORD  its password (the key has the same one: PKCS12)
#   RELEASE_KEY_ALIAS       the key's alias
#   BUILD_TOOLS             Android build-tools dir (zipalign, apksigner, aapt)
#   RELEASE_SIGNER_SHA256   optional: the expected certificate digest, else keystore/release-signer.sha256
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }
[ $# -eq 3 ] || die "usage: sign-apk.sh <in.apk> <variant> <out.apk>"
in=$1 variant=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)

pkg=$(awk -v v="$variant" '!/^#/ && $1 == v { print $2 }' "$here/variants.txt")
[ -n "$pkg" ] || die "no variant '$variant' in tools/release/variants.txt"
expected=${RELEASE_SIGNER_SHA256:-$(tr -d ' \n' < "$repo/keystore/release-signer.sha256" 2>/dev/null || true)}
[[ $expected =~ ^[0-9a-f]{64}$ ]] \
  || die "keystore/release-signer.sha256 is missing: make the key first (tools/release/make-release-key.sh)"
: "${RELEASE_KEYSTORE:?}" "${RELEASE_STORE_PASSWORD:?}" "${RELEASE_KEY_ALIAS:?}" "${BUILD_TOOLS:?}"
[ -s "$RELEASE_KEYSTORE" ] || die "the release keystore is empty - is the secret set for this environment?"

TESTKEY="$repo/keystore/testkey.p12"
TESTKEY_SHA256=a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
export TESTKEY_PASSWORD=android
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The package name and everything the manifest derives from it; component class names stay.
P=com.droiddeck.launcher
if [ "$pkg" = "$P" ]; then
  python3 "$here/axml_rename.py" "$in" "$work/renamed.apk"
else
  python3 "$here/axml_rename.py" "$in" "$work/renamed.apk" \
    "$P=$pkg" "$P.androidx-startup=$pkg.androidx-startup" "$P.logs=$pkg.logs" \
    "$P.documents=$pkg.documents" "$P.home=$pkg.home" "$P.agent=$pkg.agent" \
    "$P.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION=$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
fi
got=$("$BUILD_TOOLS/aapt" dump badging "$work/renamed.apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[ "$got" = "$pkg" ] || die "$variant: the apk says package '$got', not '$pkg'"
if [ "$pkg" != "$P" ]; then
  # A provider left on the old authority would clash with the standard app installed beside it.
  tree=$("$BUILD_TOOLS/aapt" dump xmltree "$work/renamed.apk" AndroidManifest.xml)
  grep -E 'android:(authorities|taskAffinity)' <<<"$tree" | grep -F "\"$P." \
    && die "$variant: the manifest still names $P in an authority or task"
fi

"$BUILD_TOOLS/zipalign" -p -f 4 "$work/renamed.apk" "$work/aligned.apk"

# The lineage: the testkey vouches for our key. Made fresh each time (it is two certificates and
# a signature, nothing secret beyond the key that signs it).
"$BUILD_TOOLS/apksigner" rotate --out "$work/lineage" \
  --old-signer --ks "$TESTKEY" --ks-type PKCS12 --ks-pass env:TESTKEY_PASSWORD \
    --ks-key-alias testkey --key-pass env:TESTKEY_PASSWORD --set-rollback false \
  --new-signer --ks "$RELEASE_KEYSTORE" --ks-type PKCS12 --ks-pass env:RELEASE_STORE_PASSWORD \
    --ks-key-alias "$RELEASE_KEY_ALIAS" --key-pass env:RELEASE_STORE_PASSWORD

"$BUILD_TOOLS/apksigner" sign \
  --ks "$TESTKEY" --ks-type PKCS12 --ks-pass env:TESTKEY_PASSWORD \
    --ks-key-alias testkey --key-pass env:TESTKEY_PASSWORD \
  --next-signer --ks "$RELEASE_KEYSTORE" --ks-type PKCS12 --ks-pass env:RELEASE_STORE_PASSWORD \
    --ks-key-alias "$RELEASE_KEY_ALIAS" --key-pass env:RELEASE_STORE_PASSWORD \
  --lineage "$work/lineage" --rotation-min-sdk-version 28 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out "$out" "$work/aligned.apk"
rm -f "$out.idsig"

# What the apk must carry, checked rather than assumed.
"$BUILD_TOOLS/apksigner" verify --min-sdk-version 26 "$out" >/dev/null || die "$variant: does not verify"
# (Listed first, then searched: grep -q stops reading early, which fails a pipe under pipefail.)
listing=$(unzip -l "$out")
grep -qE 'META-INF/.*\.(SF|RSA)$' <<<"$listing" || die "$variant: no JAR signature (old installers)"
# Android 9+ must see our key and only our key. apksigner labels signers "Signer #1", "Signer
# (minSdkVersion=28, ...)" or "V3.0 Signer:" depending on its version; the digests are what count.
certs=$("$BUILD_TOOLS/apksigner" verify --min-sdk-version 28 --print-certs "$out")
v3=$(sed -n 's/^.*Signer.* certificate SHA-256 digest: //p' <<<"$certs" | sort -u)
if [ "$v3" != "$expected" ]; then
  echo "$certs" >&2
  die "$variant: Android 9+ sees signer(s) [$(tr '\n' ' ' <<<"$v3")], not only DroidDeck's key $expected"
fi
lineage=$("$BUILD_TOOLS/apksigner" lineage --in "$out" --print-certs)
grep -q "Signer #1 in lineage certificate SHA-256 digest: $TESTKEY_SHA256" <<<"$lineage" \
  || die "$variant: the lineage does not start at the testkey - old installs could not update"
grep -q "Signer #2 in lineage certificate SHA-256 digest: $expected" <<<"$lineage" \
  || die "$variant: the lineage does not hand over to DroidDeck's key"
rollback=$(awk '/Signer #1 in lineage/{s=1} /Signer #2 in lineage/{s=0} s && /rollback capability/' <<<"$lineage")
grep -q false <<<"$rollback" \
  || die "$variant: the testkey kept its rollback right - a testkey-signed apk could replace this one"
echo "$variant ($pkg): signed, hand-over from the testkey, v3 signer $expected"
