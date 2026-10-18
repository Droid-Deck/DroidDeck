# System Vulkan components

DroidDeck downloads one complete System Vulkan component for the detected GPU family. A component owns the libhybris bridge, Vulkan layers, WSI integration, Proton wrapper, licenses, provenance, and the matching DXVK payload. Files from different component releases are never mixed.

The component source scopes are:

- `gpu/system-vulkan/powervr`
- `gpu/system-vulkan/xclipse`
- `gpu/system-vulkan/mali`

The build-declared `COMPONENT_REPOSITORY` is the only automatic bootstrap producer. Custom sources can be checked and installed manually, but adding one does not download, activate, or change a component. Hiding or removing a source stops its remote checks; already installed bundles remain available from their immutable SHA-256 directories.

## Release assets

Each release publishes these pairs:

```text
droiddeck-component-system-vulkan-powervr.json
system-vulkan-powervr.tar.zst
droiddeck-component-system-vulkan-xclipse.json
system-vulkan-xclipse.tar.zst
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
  --release-tag system-vulkan-VERSION \
  --version VERSION
```

`Publish System Vulkan components` runs the tests, packages all three variants, records checksums, and can create a new immutable GitHub release. It refuses to overwrite an existing tag and explicitly targets the same commit recorded in each sidecar and checked out by the workflow.

The packager also emits `component-bootstrap.json`. After the release is published and its assets are verified, replace `tools/powervr/component-bootstrap.json` with that generated file and rebuild the APK. The signed APK then pins the producer's numeric GitHub repository ID, release tag, and sidecar SHA-256 for each GPU family. Empty or mismatched pins disable automatic bootstrap and never fall back to another fork; manual source selection remains available.

Existing complete directories under `files/system_vulkan/<sha256>` are accepted offline as `Legacy local`. They retain no invented repository or release provenance. New downloads are staged and validated before atomic publication under `files/components/system-vulkan/<sha256>`.
