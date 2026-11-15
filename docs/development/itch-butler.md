# itch.io patch updates with butler

An update to an itch game that was published with butler can be applied as a patch instead of downloading the whole upload again. Everything else keeps the full update described in [itch-updates.md](itch-updates.md).

## Pack

`butler`, `7z.so` and `libc7zip.so` ship as the `butler` add-on pack (`tools/packs/build.sh`). The pack is fetched from `broth.itch.zone` by version, and `tools/packs/butler/release.env` pins the version and the SHA-256 of the zip. The `linux-arm64` build needs glibc 2.29. The first patch update that finds no pack starts its download and does a full update; later updates use it.

## When a patch is used

All of these must hold, or the update falls back to the full download:

- the installed copy and the new upload are the same upload id and both have a build id (`buildId` is kept in the sidecar's `extra`)
- the upgrade path `/builds/{installed}/upgrade-paths/{new}` has at most four builds, each with a patch file, and the last one has a signature
- the patches together are no larger than the full upload
- `butler apply` exits 0 and the result matches the new build's signature

## How it is applied

Patches and the signature are fetched from `/wharf/builds/{build}/files/{file}/download` with the API key; the redirect to storage is followed without the key. `butler apply -d` writes the whole new game into `.droiddeck-update` inside the game folder and leaves the installed files alone, files the game wrote (saves) are ignored, and `--signature` makes butler check the result against the new build. From there the update is the same as a full one: the executable is picked, `ItchStaging.merge` swaps the files in, and the record is rewritten. A failure at any point deletes the staging folder and continues with the full download.

## What is not verified

The upgrade-path and build-file field names (`upgrade_path`, `files`, `sub_type`) and the `download_key_id` query on the file download follow the published API listing and butler's own request paths, and have not been tried against a real game. A game whose install does not match its build exactly, for example one whose files were flattened on install, falls back to the full update.
