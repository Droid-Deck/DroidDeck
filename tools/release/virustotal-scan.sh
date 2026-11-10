#!/usr/bin/env bash
# Usage: virustotal-scan.sh <file>...
# Scans each file on VirusTotal and writes the verdicts to $GITHUB_STEP_SUMMARY.
# Exits 1 when an engine flags a file and FAIL_ON_DETECTION=1.
set -euo pipefail

API=${VT_API:-https://www.virustotal.com/api/v3}
: "${VT_API_KEY:?VT_API_KEY is not set}"
POLL_SECONDS=${POLL_SECONDS:-30}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-1200}
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/stdout}

vt() {
  curl -sS --fail-with-body --retry 5 --retry-delay 20 --retry-all-errors \
    -H "x-apikey: $VT_API_KEY" "$@"
}

# The free API allows 4 requests a minute; poll slowly.
wait_for_analysis() {
  local id=$1 waited=0 status
  while :; do
    status=$(vt "$API/analyses/$id" | jq -r .data.attributes.status)
    [ "$status" = completed ] && return 0
    [ "$waited" -ge "$TIMEOUT_SECONDS" ] && return 1
    sleep "$POLL_SECONDS"
    waited=$((waited + POLL_SECONDS))
  done
}

flagged_total=0
{
  echo "### VirusTotal"
  echo
  echo "| File | Flagged | Engines | Detections |"
  echo "| --- | --- | --- | --- |"
} >> "$SUMMARY"

for file in "$@"; do
  name=$(basename "$file")
  sha=$(sha256sum "$file" | cut -d' ' -f1)
  link="https://www.virustotal.com/gui/file/$sha"

  known=$(curl -sS -o /dev/null -w '%{http_code}' -H "x-apikey: $VT_API_KEY" "$API/files/$sha")
  if [ "$known" != 200 ]; then
    # Files over 32 MB need a one-time upload URL.
    url=$(vt "$API/files/upload_url" | jq -r .data)
    id=$(vt -X POST -F "file=@$file" "$url" | jq -r .data.id)
    echo "uploaded $name ($sha), analysis $id"
    if ! wait_for_analysis "$id"; then
      echo "::warning title=VirusTotal::$name was still being analysed after ${TIMEOUT_SECONDS}s: $link"
      echo "| [$name]($link) | pending | | |" >> "$SUMMARY"
      continue
    fi
  fi

  report=$(vt "$API/files/$sha")
  malicious=$(jq -r '.data.attributes.last_analysis_stats.malicious' <<<"$report")
  suspicious=$(jq -r '.data.attributes.last_analysis_stats.suspicious' <<<"$report")
  engines=$(jq -r '[.data.attributes.last_analysis_stats[]] | add' <<<"$report")
  detections=$(jq -r '[.data.attributes.last_analysis_results[]
      | select(.category == "malicious" or .category == "suspicious")
      | "\(.engine_name): \(.result)"] | join("<br>")' <<<"$report")
  flagged=$((malicious + suspicious))
  flagged_total=$((flagged_total + flagged))
  echo "$name: $flagged/$engines flagged $link"
  echo "| [$name]($link) | $flagged | $engines | ${detections:-none} |" >> "$SUMMARY"
  if [ "$flagged" -gt 0 ]; then
    echo "::warning title=VirusTotal::$name flagged by $flagged/$engines engines: ${detections//<br>/, } $link"
  fi
done

if [ "$flagged_total" -gt 0 ] && [ "${FAIL_ON_DETECTION:-0}" = 1 ]; then
  echo "::error::VirusTotal flagged the release"
  exit 1
fi
