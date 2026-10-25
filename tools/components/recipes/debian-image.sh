#!/usr/bin/env bash
set -euo pipefail
. tools/linuxfs/debian-image.env
image=public.ecr.aws/docker/library/debian:bullseye
if gh release download "$DEBIAN_IMAGE_TAG" -R "$GITHUB_REPOSITORY" -p "$DEBIAN_IMAGE_ASSET" -O /tmp/debian-image.tar.zst \
   && { [ -z "$DEBIAN_IMAGE_SHA256" ] || echo "$DEBIAN_IMAGE_SHA256  /tmp/debian-image.tar.zst" | sha256sum -c -; }; then
  zstd -dc /tmp/debian-image.tar.zst | docker load
  rm -f /tmp/debian-image.tar.zst
else
  echo "::warning::the hosted Debian image could not be fetched; pulling from the registry instead"
  for attempt in 1 2 3 4 5; do
    docker pull "$image" && break
    [ "$attempt" -lt 5 ] || { echo "::error::could not pull $image after $attempt attempts"; exit 1; }
    echo "pull attempt $attempt failed (registry busy?); retrying in $((attempt * 30)) s"
    sleep $((attempt * 30))
  done
fi
docker image ls "$image"
