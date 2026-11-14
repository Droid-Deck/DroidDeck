#!/usr/bin/env bash
#
# Signs an unsigned HAP, so the phone can install it.
#
# A phone only installs a HAP that Huawei has signed. DevEco Studio can do
# that with a click, but the click needs the IDE open, and the signing
# material is the developer's own: a .p12 keystore, the .cer certificate and
# the .p7b profile that DevEco Studio's "Automatically generate signature"
# produces under ~/.ohos/config/.
#
# This script drives DevEco Studio's own signing tool, hap-sign-tool, so the
# whole path is one command and can run on a build machine with no IDE open.
# It never handles the key material itself: the passwords are read from the
# environment, and nothing is written to the repository.
#
# Usage:
#   tools/sign_hap.sh --in <unsigned.hap> --out <signed.hap>
#                     [--keystore <file.p12>] [--cert <file.cer>]
#                     [--profile <file.p7b>] [--alias <key alias>]
#                     [--password <keystore password>] [--key-password <key password>]
#                     [--sign-tool <hap-sign-tool.jar>]
#
# The defaults are the files DevEco Studio writes when it generates a
# signature automatically:
#
#   ~/.ohos/config/<bundle>/<alias>.p12, .cer, .p7b
#
# The passwords come from DDK_KEYSTORE_PASSWORD and DDK_KEY_PASSWORD when
# they are not given on the command line.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root}" || exit 1

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

in_file=""
out_file=""
keystore=""
cert=""
profile=""
alias_name="debugKey"
keystore_password="${DDK_KEYSTORE_PASSWORD:-}"
key_password="${DDK_KEY_PASSWORD:-}"
sign_tool=""

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,33p' "${BASH_SOURCE[0]}"; exit 0 ;;
    --in) in_file="$2"; shift 2 ;;
    --out) out_file="$2"; shift 2 ;;
    --keystore) keystore="$2"; shift 2 ;;
    --cert) cert="$2"; shift 2 ;;
    --profile) profile="$2"; shift 2 ;;
    --alias) alias_name="$2"; shift 2 ;;
    --password) keystore_password="$2"; shift 2 ;;
    --key-password) key_password="$2"; shift 2 ;;
    --sign-tool) sign_tool="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "${in_file}" ] || die "--in is required"
[ -f "${in_file}" ] || die "the HAP was not found: ${in_file}"
[ -n "${out_file}" ] || out_file="${in_file%.hap}-signed.hap"

# --- the signing material --------------------------------------------------

config="${HOME}/.ohos/config"
if [ -z "${keystore}" ] || [ -z "${cert}" ] || [ -z "${profile}" ]; then
  found="$(find "${config}" -name '*.p12' 2>/dev/null | head -1)"
  if [ -n "${found}" ]; then
    base="${found%.p12}"
    [ -z "${keystore}" ] && keystore="${found}"
    [ -z "${cert}" ] && cert="${base}.cer"
    [ -z "${profile}" ] && profile="${base}.p7b"
  fi
fi

[ -n "${keystore}" ] && [ -f "${keystore}" ] || die "the keystore was not found; generate a signature in DevEco Studio first, or pass --keystore"
[ -n "${cert}" ] && [ -f "${cert}" ] || die "the certificate was not found; pass --cert"
[ -n "${profile}" ] && [ -f "${profile}" ] || die "the profile was not found; pass --profile"

if [ -z "${keystore_password}" ] || [ -z "${key_password}" ]; then
  cat >&2 <<'EOT'
error: the passwords are missing.

The keystore password and the key password come from the environment, so
that they never end up in the shell history or in this repository:

    DDK_KEYSTORE_PASSWORD=... DDK_KEY_PASSWORD=... tools/sign_hap.sh --in ...
EOT
  exit 1
fi

# --- the signing tool ------------------------------------------------------

find_sign_tool() {
  local candidate
  for candidate in \
      "${sign_tool}" \
      "${DEVECO_HOME:-}/Contents/tools/hap-sign-tool/lib/hap-sign-tool.jar" \
      "/Applications/DevEco-Studio.app/Contents/tools/hap-sign-tool/lib/hap-sign-tool.jar" \
      "/Applications/DevEco Studio.app/Contents/tools/hap-sign-tool/lib/hap-sign-tool.jar" \
      "${HOME}/Library/Huawei/Sdk/default/openharmony/toolchains/hap-sign-tool.jar"; do
    [ -n "${candidate}" ] && [ -f "${candidate}" ] && { printf '%s' "${candidate}"; return 0; }
  done
  # Any jar with that name inside a DevEco installation.
  find /Applications "${HOME}/Applications" -name 'hap-sign-tool*.jar' -type f 2>/dev/null | head -1
}

tool="$(find_sign_tool)" || die "hap-sign-tool was not found; it ships inside DevEco Studio. Pass --sign-tool, or set DEVECO_HOME."
echo "==> sign tool: ${tool}"

java_bin="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  java_bin="${JAVA_HOME}/bin/java"
fi
command -v "${java_bin}" >/dev/null 2>&1 || die "java was not found; install a JDK or set JAVA_HOME"

# --- sign ------------------------------------------------------------------

echo "==> signing ${in_file}"
"${java_bin}" -jar "${tool}" sign-app \
  -keyAlias "${alias_name}" \
  -signAlg SHA256withECDSA \
  -mode localSign \
  -appCertFile "${cert}" \
  -profileFile "${profile}" \
  -inFile "${in_file}" \
  -outFile "${out_file}" \
  -keystoreFile "${keystore}" \
  -keystorePwd "${keystore_password}" \
  -keyPwd "${key_password}" || {
  echo
  echo "error: the signing failed; the message above says why." >&2
  exit 1
}

[ -f "${out_file}" ] || die "the signed HAP was not written"

echo
echo "==> signed: ${out_file}"
echo "    $(wc -c < "${out_file}" | tr -d ' ') bytes"
echo
echo "  Install it with:"
echo "      hdc install ${out_file}"
echo "  and check the port with:"
echo "      tools/device_smoke.sh"
