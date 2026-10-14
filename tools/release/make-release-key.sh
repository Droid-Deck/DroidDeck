#!/usr/bin/env bash
set -euo pipefail

REPO=Droid-Deck/DroidDeck
ENVIRONMENTS=(signing-main release)
ALIAS=droiddeck
OUT=${DROIDDECK_KEY_DIR:-$HOME/droiddeck-release-key}
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)

die() { echo "error: $*" >&2; exit 1; }
command -v keytool >/dev/null || die "keytool not found (Termux: pkg install openjdk-17)"
command -v gh >/dev/null || die "gh not found"
gh auth status >/dev/null 2>&1 || die "gh is not logged in (gh auth login)"
[ -e "$OUT/droiddeck-release.p12" ] && die "a key already exists in $OUT - there must only ever be one"
case "$OUT" in "$repo"|"$repo"/*) die "the key must not be made inside the repo" ;; esac
for env in "${ENVIRONMENTS[@]}"; do
  gh api "repos/$REPO/environments/$env" >/dev/null 2>&1 || die "GitHub environment '$env' does not exist yet"
done

umask 077
mkdir -p "$OUT"
KS="$OUT/droiddeck-release.p12"
KS_PW=$(head -c 60 /dev/urandom | base64 -w0 | tr -d '/+=')
KS_PW=${KS_PW:0:48}
[ ${#KS_PW} -ge 40 ] || die "could not make a password"
export KS_PW
printf '%s\n' "$KS_PW" > "$OUT/password.txt"

keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=DroidDeck, O=The412Banner" \
  -storepass:env KS_PW -keypass:env KS_PW >/dev/null 2>&1 || die "keytool could not make the key"
listing=$(keytool -list -v -keystore "$KS" -storepass:env KS_PW -alias "$ALIAS")
SHA=$(awk '/SHA256:/ { print $2 }' <<<"$listing" | head -n1 | tr -d ':' | tr 'A-F' 'a-f')
[[ $SHA =~ ^[0-9a-f]{64}$ ]] || die "could not read the key's certificate digest"

for env in "${ENVIRONMENTS[@]}"; do
  base64 -w0 < "$KS" | gh secret set RELEASE_KEYSTORE_B64 --env "$env" -R "$REPO" >/dev/null
  printf '%s' "$KS_PW" | gh secret set RELEASE_STORE_PASSWORD --env "$env" -R "$REPO" >/dev/null
  echo "secrets set for environment: $env"
done
unset KS_PW

printf '%s\n' "$SHA" > "$repo/keystore/release-signer.sha256"
cat <<EOF

DroidDeck's signing key is made and in GitHub.

  Key:       $KS
  Password:  $OUT/password.txt
  Digest:    $SHA   (public - written to keystore/release-signer.sha256)

Now:
  1. Back up the folder $OUT (both files) to at least two safe places.
  2. Commit keystore/release-signer.sha256 (only that file).
EOF
