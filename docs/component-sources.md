# Component sources

Open **Components**, select a component, and use **Sources** immediately to the
left of Refresh. Add a GitHub `owner/repository` or repository URL, change row
visibility, then press **Save**. Closing the menu discards unsaved edits.

Each component has its own list. Built-in repositories can be hidden; added
repositories can also be removed. Hidden sources are not checked and their
available downloads disappear. Downloaded components remain available offline,
including after their source is removed. Adding a repository does not install a
package or change the active component.

**Updates** uses the same editor. **Update from** explicitly selects the repository
whose catalog is followed. Hiding that selected repository stops its checks;
DroidDeck does not switch to another fork. The selected channel stays associated
with the selected repository.

Leave **Catalog branch** blank to follow the selected repository's default branch.
Set a branch such as `dev` or `wallentx/powervr` to use that branch's catalog instead.
Branch names are case-sensitive. Branch edits take effect only after **Save**.
Each branch selection has its own cached catalog, and changing branches preserves
the selected repository and update channel. A missing explicit branch does not
fall back to another branch.

GitHub rate limits produce a message asking you to try again after a few minutes.
Cached releases stay available, and refresh requests wait for GitHub's retry or
reset time before trying that host again. An Updates source must publish
`catalog.json` on its default branch, or on the branch explicitly selected in
Sources. A missing repository or catalog gets a separate error.

## Release identification

Existing component release conventions remain supported. New producers should
attach a schema-1 sidecar to the same GitHub release as the archive. The sidecar
identifies the component and the exact asset; release tags remain producer-owned.
GitHub must report SHA-256 digests for both the sidecar and archive. The declared
archive name, size, and SHA-256 must match that release's asset metadata.

| Component | Preferred sidecar | Existing convention |
| --- | --- | --- |
| System Vulkan | `droiddeck-component-system-vulkan-<variant>.json` | None; variants are `powervr`, `xclipse`, and `mali` |
| FEX, DXVK, VKD3D | `droiddeck-component-<component>.json` | WCP files under the release tags below |
| Proton | `droiddeck-component-proton.json` | Existing ARM64 GE, CachyOS, and DroidDeck Proton archive names |
| Turnip | Existing driver archive names | Banners-Turnip, WinNative, and `DD-Turnip-vX.Y.Z.zip` formats |
| App updates | `catalog.json` on the repository's default or selected branch | Existing schema-1 update catalog |

WCP tag mappings:

| Release tag | Component |
| --- | --- |
| `FexCore-Linux` | `fex` |
| `Dxvk-Linux` | `dxvk` |
| `Dxvk-arm64ec-Linux` | `dxvk` |
| `Dxvk-gplasync-Linux` | `dxvk` |
| `Vkd3d-proton-Linux` | `vkd3d` |

A WCP sidecar can describe one archive with `artifact`, or several with
`artifacts`. The downloaded WCP's `profile.json` must declare the matching
component type. For example:

```json
{
  "schema": 1,
  "component": "dxvk",
  "artifact": {
    "name": "dxvk-custom.wcp",
    "size": 123456,
    "sha256": "<64 lowercase hexadecimal characters>"
  }
}
```

Proton's sidecar additionally declares a stable variant, display name, and
installed tree prefix:

```json
{
  "schema": 1,
  "component": "proton",
  "variant": "my-proton-arm64",
  "name": "My Proton",
  "prefix": "My-Proton",
  "artifact": {
    "name": "my-proton-aarch64.tar.xz",
    "size": 123456,
    "sha256": "<64 lowercase hexadecimal characters>"
  }
}
```

An update catalog must identify its configured repository in `ciRepo`. Its
`sourceRepo` identifies the application source repository. APK release URLs must
belong to one of those repositories. Actual APK signatures, signer lineage,
package identity, version, size, and digest are verified before installation;
catalog signer text alone is not proof. Another signing key cannot silently
replace an installed application.

The catalog publisher follows the destination repository's default branch too:

```sh
python3 tools/release/update_catalog.py \
  --source-repo owner/repository --ci-repo owner/repository
```

Pass `--branch dev` to publish to a particular branch, and select that same branch
in the app's Sources editor. `--dry-run` prints the generated catalog without
publishing it. The existing `main-<commit>` Preview release-tag convention is
independent of the branch containing `catalog.json`.

## System Vulkan bundles

System Vulkan support downloads a complete bundle for the detected GPU family.
Each bundle contains its bridge, WSI, layers, Proton wrapper, matching DXVK,
licenses, and build provenance. The APK owns device detection and runtime policy;
packages cannot supply environment variables, HAL paths, or installation hooks.

The sidecar declares `schema: 1`, `component: "system-vulkan"`, `variant`,
`version`, `requires: {componentApi: 1, abi: "arm64-v8a", minSdk: 26}`, and
`artifact: {name, size, sha256, treeSha256}`. Its archive name is exactly
`system-vulkan-<variant>.tar.zst`. `provenance` contains the configured repository,
source commit, and GitHub build URL.

Inside the archive, `component.json` repeats the identity, requirements, tree
digest, and provenance. It lists every payload file's `path`, `type: "file"`,
`size`, `sha256`, and numeric `mode` (`420` or `493`), plus license paths.
`treeSha256` hashes these UTF-8 records sorted by path:

```text
path<NUL>file<NUL>size<NUL>sha256<NUL>mode<LF>
```

Bundles contain regular files and directories. The producer dereferences safe
links from the pinned build inputs. The installer rejects links, duplicate or
unlisted entries, unsafe paths, unsupported requirements, and oversized payloads.
Only a completely verified staging directory becomes an installed bundle.

Each GPU family has its own immutable release tag and version:

| GPU family | Tag pattern |
| --- | --- |
| PowerVR | `system-vulkan-powervr-vMAJOR.MINOR.PATCH` |
| Xclipse | `system-vulkan-xclipse-vMAJOR.MINOR.PATCH` |
| Mali | `system-vulkan-mali-vMAJOR.MINOR.PATCH` |

Use the pinned native inputs to produce one variant:

```sh
python3 tools/powervr/package_components.py \
  --output "$TMPDIR/droiddeck-system-vulkan" \
  --repository owner/repository \
  --repository-id 123456 \
  --commit <full-source-commit> \
  --build-url https://github.com/owner/repository/actions/runs/123456 \
  --variant mali \
  --version v0.1.0
```

The packager derives `system-vulkan-mali-v0.1.0` from these arguments and requires
an empty output directory. Publish that variant's archive and sidecar together.
The generated `component-bootstrap.json` updates only that profile, preserving
the other profiles from `tools/powervr/component-bootstrap.json` when the producer
repository and numeric ID match. Use `--bootstrap <path>` to supply newer pins;
pins belonging to a different producer are not carried across repositories.
After verifying the published assets, adopt the generated pins before building
the app. If another profile was updated after packaging, regenerate the candidate
from the latest pins before adopting it. Automatic first installation accepts only these exact
pins and the declared numeric repository ID. Missing pins disable automatic
download; they do not select an unpinned release or another repository.

The publisher's `prerelease` input uses GitHub's existing release flag and defaults
to `true`; set it to `false` for a stable release. Optional version suffixes such as
`v0.2.0-rc.1` are accepted, but the GitHub flag determines the channel. The component
list includes both channels and uses the existing **Stable** and **Preview** labels.
Cached candidates and installed receipts retain that flag. Older entries without
channel metadata stay unlabeled until current metadata is available.

Existing shared-tag releases remain discoverable through their exact profile
sidecars. Tag names do not replace manifest, repository, or checksum validation.
Auto still installs the APK's pinned recommendation only when a component is
missing; a newer stable or preview entry does not replace a working installation.

`-PcomponentRepository=owner/repository` selects the build's pinned GPU producer.
PR builds set `DROIDDECK_COMPONENT_REPOSITORY` to the PR's head repository. Other
CI builds use `GITHUB_REPOSITORY`; local builds use the GitHub `origin` push
repository, or the official repository when no GitHub origin is available. The
official repository is the only initial source and stays visible, including in
fork builds. Build provenance and bootstrap pins do not add a repository or
change source visibility. Add another producer explicitly through Sources.
Saved source choices are preserved across restarts and upgrades. Bootstrap pins
apply only when their repository matches that producer and the source is enabled.
Manual component selection remains available for configured compatible sources.

Previously extracted complete System Vulkan runtimes are adopted as local legacy
packages, preserving offline use. Their original remote provenance is not
invented. Running sessions retain their selected runtime snapshot; a newly
installed or activated bundle applies to a subsequent session.
