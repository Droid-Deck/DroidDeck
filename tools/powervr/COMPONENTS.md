# System Vulkan components

DroidDeck downloads one complete System Vulkan component for the detected GPU family. A component owns the libhybris bridge, Vulkan layers, WSI integration, Proton wrapper, licenses, provenance, and the matching DXVK payload. Files from different component releases are never mixed.

The component source scopes are:

- `gpu/system-vulkan/powervr`
- `gpu/system-vulkan/xclipse`
- `gpu/system-vulkan/mali`

The build-declared `COMPONENT_REPOSITORY` is the only automatic bootstrap producer. Custom sources can be checked and installed manually, but adding one does not download, activate, or change a component. Hiding or removing a source stops its remote checks; already installed bundles remain available from their immutable SHA-256 directories.

## Release assets

Each release publishes one vendor's pair under its own immutable tag:

```text
system-vulkan-powervr-v0.1.0
  droiddeck-component-system-vulkan-powervr.json
  system-vulkan-powervr.tar.zst
system-vulkan-xclipse-v0.1.0
  droiddeck-component-system-vulkan-xclipse.json
  system-vulkan-xclipse.tar.zst
system-vulkan-mali-v0.1.0
  droiddeck-component-system-vulkan-mali.json
  system-vulkan-mali.tar.zst
```

The sidecar schema is `1`, `component` is `system-vulkan`, and `variant` is one of the three scope suffixes. `requires` fixes `componentApi: 1`, `abi: arm64-v8a`, and the minimum Android SDK. `artifact` binds the archive name, byte size, SHA-256, and normalized tree SHA-256. `provenance` binds the producing repository, full commit, and GitHub Actions run URL.

The archive starts with `component.json`. Its identity, requirements, tree digest, and provenance must equal the sidecar. Its `files` array is the complete normalized inventory: path, `type: file`, size, SHA-256, and decimal mode `420` or `493`. Symlinks, hard links, devices, duplicate paths, unknown files, traversal, oversized metadata, and files absent from the inventory are rejected. `licenses` lists inventory paths below `licenses/`.

## Packaging and publication

The packager consumes the checksum-pinned archives in `release.json`; it does not compile native code or read an APK payload.

```bash
python3 tools/powervr/package_components.py \
  --output out/system-vulkan \
  --repository wallentx/DroidDeck \
  --repository-id 1406436811 \
  --commit "$(git rev-parse HEAD)" \
  --build-url https://github.com/wallentx/DroidDeck/actions/runs/RUN_ID \
  --variant mali \
  --version v0.1.0
```

The output directory must be empty. The tag is derived from `--variant` and
`--version`; a Mali change does not assign new versions to PowerVR or Xclipse.
Versions use `vMAJOR.MINOR.PATCH`, optionally with a prerelease suffix such as
`-rc.1`. The APK version and the native input versions remain independent.

`Publish System Vulkan components` runs the tests, packages the selected `variant`,
records checksums, and can create a new immutable GitHub release. Its `prerelease`
input defaults to `true`; select `false` for a stable release. This uses GitHub's
release flag, which the app already parses, rather than inferring a channel from
the tag. Both channels are listed, using the existing Stable and Preview labels.
The workflow refuses to overwrite an existing release or tag and targets the
same commit recorded in the sidecar. A component release never becomes the
repository's latest app release.

The packager also emits `component-bootstrap.json`, preserving other profiles
from the checked-in pins when the producer repository and numeric ID match.
`--bootstrap <path>` can supply a different base file. A new producer starts its
own pin set; it cannot inherit another repository's tags. The source pin file is
never modified by packaging. After verifying the published assets, adopt the
candidate pins and rebuild the APK. Regenerate against current pins if another
profile changed in the meantime. The signed APK then pins each profile's exact
tag and sidecar hash. Empty or mismatched pins disable automatic bootstrap;
manual source selection remains available.

Old shared-tag releases remain compatible through their profile sidecars. Older
cached or installed builds with no recorded GitHub channel remain unlabeled;
their channel is not guessed from their version. Auto continues to use the APK's
explicit recommendation when needed, rather than updating to the newest preview.

Existing complete directories under `files/system_vulkan/<sha256>` are accepted offline as `Legacy local`. They retain no invented repository or release provenance. New downloads are staged and validated before atomic publication under `files/components/system-vulkan/<sha256>`.
