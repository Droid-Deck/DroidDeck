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
