# PlayStore builds

`Build PlayStore Bundle` runs on pushes to `playstore`. It builds the exact pushed commit, checks the payload inventory and native libraries, then signs the bundle in the protected `signing-playstore` environment. Push builds upload the signed bundle as the `droiddeck-play-bundle-signed` workflow artifact and do not publish an app release.

For a versioned GitHub release, run the existing `Release APKs` workflow with branch `playstore` and a Version matching `versionName` in `app/build.gradle`. That entry calls `Build PlayStore Bundle`. The Play release uses a separate tag and explicitly leaves Latest unchanged. The manual entry works without adding the branch-only workflow to `main`.

Both `versionName` and the integer `versionCode` come directly from `app/build.gradle`. Increment the repository's version code for each new Play release. Build counters, timestamps, and repository variables never add offsets to it. Push artifact filenames also use the repository's version name.

The signing environment supplies `RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`, and `RELEASE_KEY_ALIAS`. Compilation never receives the release key. Signing checks the certificate against `keystore/release-signer.sha256`, keeps the alias out of signature filenames, and verifies every signed payload entry.

`components.lock.json` pins the native runtime, desktop packages, DroidDeck Proton, driver variants, native component variants, Decky channels, and synchronization packs. Native components have no external download fallback in the app. The bundled catalog pins current native variants rather than their complete release histories. Existing installed versions remain available.

The Windows prerequisite catalog remains intact. Its downloadable packages have pinned sizes and SHA-256 hashes and are installed into the guest runtime only after verification. This is an explicit exception to bundled-only component delivery. It does not enable native component downloads or app self-updates.

The separate `playstore-inputs-20261103` prerelease preserves inputs that upstream releases can replace or remove. Its payload filenames are content hashes, and `SOURCE-INVENTORY.json` records their original sources. The manifest retains runtime URL aliases separately from build fetch sources. This input prerelease is never Latest.

Staging verifies each input and deduplicates shared chunks across install-time feature modules. The final bundle check verifies the packaged inventory, every payload hash, individual module sizes, and the cumulative 4 GB budget. Bundle validation, 16 KB native-library alignment, and audio verification must also pass before signing.

Build checks establish artifact integrity. Device testing and Google Play review remain separate requirements.
