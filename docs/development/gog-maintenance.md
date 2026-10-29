# GOG maintenance

Installed GOG games are checked after library refresh and when details open.
Successful automatic checks are throttled to 30 minutes; two metadata workers
run at most. Manual checks bypass the throttle. Downloads and application always
require a user action. Unknown metadata, authentication failures, and network
errors remain distinct from a current installation.

## Records and recovery

`.droiddeck-gog/installed.json` is a schema-versioned installation inventory.
It retains source, generation, opaque build identifier, branch, display version,
language, installed products, sanitized manifests, and file hashes/chunks.
Migration uses the existing marker, sidecar, and authentic GOG metadata; missing
historical content is never replaced with the latest manifest as an assumed
description of installed files.
Authentic `goggame-*.id` build metadata takes precedence over legacy markers;
the numeric `version` field in a GOG info file is a schema, not a game version.

`operation.json` pins the operation, exact folder, previous sidecar/inventory,
target, and phase. Staged chunks are durable work and are rehashed on resume.
The existing download queue persists GOG operation/folder details; older queue
entries keep their install behavior. Uncertain external patch operations cannot
be retried as ordinary downloads. Existing sidecar choices and prefix locations
are retained. Newly entitled DLC is not added automatically.

Depot updates and repair verify files, reuse matching chunks, and stage changed
files on the installation filesystem. Replacements are atomic per file. There
is no whole-game rollback: interrupted application keeps the journal and blocks
launch until recovery. Obsolete managed files are deleted only when they still
match their previous content. Untracked and modified obsolete files survive.

The native maintenance contract separates network, reused, and applied bytes.
Generation 1 range downloads retain authentic hashes and never trust equal
size alone. A verified Java file-download path remains available when the native
library is unavailable. Free-space checks include staged replacements and
transfer payloads; a full offline refresh uses a conservative size estimate.

## Deltas and installers

Galaxy deltas are requested for the exact source/target pair, and used only
when smaller than missing chunks. Source, payload, and reconstructed target are
verified independently. Decoder errors, unsupported features, or changed source
content fall back to verified chunks. Reconstruction is serialized and bounded
below 64 MiB. Upstream decoder sources and notices are pinned under
`app/src/main/rust/stores/vendor/xdelta3`; Gradle tracks those build inputs.

Offline patches preserve provider grouping and filenames. Download metadata is
resolved before confirmation; no patch chain is inferred from a filename.
Installer application is interactive through the selected game's Proton tool
and actual prefix. A detached Linux helper owns the installer, tracks prefix
children, writes durable status, and excludes cloud/achievement game-launch
hooks. Registry setup uses the existing GOG script path and authentic setup
payloads. Installer exit alone does not establish the installed version: the
app reconciles the resulting build against a manifest and checks its files.
Unverifiable results require recovery/full refresh instead of silently restoring
the previous manifest. External installer cancellation belongs to the installer.
Setup runs the script interpreter only when an actual GOG script exists, and
registers the authentic installation path, version, and build in the selected
prefix. Patch filenames are decoded from provider URLs without changing literal
plus signs or multipart names. Offline refresh retains the existing executable
layout and includes empty files and directories. Installers can put payload files
at the extraction root while placing placeholders under `app/`; refresh combines
both locations and verifies the selected executable before changing installed
files. Installer language selection accepts the installed locale's base language
and never silently chooses an unrelated language. Refresh with selected DLC also
requires authentic matching depot metadata and completes the selected depots
before publishing the installed record or deleting obsolete content.

Short helper status requests detect a dead installer owner or abandoned start.
Session loss or suspension requires recovery; it never triggers automatic replay.
The maintenance menu offers result verification for interrupted patch operations.

The same POSIX record lease excludes maintenance and game launches from DD or
Steam. Session staging publishes the helper to existing Linux installations.
Maintenance processes use full x87 precision. Some installers copy 64-bit data
through x87 integer loads/stores; FEX's reduced-precision mode rounds those values
and corrupts path strings. This override is local to maintenance and leaves the
saved game performance profile unchanged. See [FEX issue #5827](https://github.com/FEX-Emu/FEX/issues/5827).

## References and validation

The manifest/chunk/delta approach follows
[heroic-gogdl](https://github.com/Heroic-Games-Launcher/heroic-gogdl/blob/main/gogdl/dl/objects/v2.py).
Generation 1 layout is checked against actual public Rayman 2 manifests in
`app/src/test/resources/gog`. Setup requirements are informed by
[Heroic's GOG setup](https://github.com/Heroic-Games-Launcher/HeroicGamesLauncher/blob/main/src/backend/storeManagers/gog/setup.ts).
The separation of depot and offline maintenance is also reflected in
[Lutris](https://github.com/lutris/lutris/blob/master/lutris/services/gog.py).
[innoextract](https://constexpr.org/innoextract/) extracts data; it does not run
installer scripts and checks.

Automated coverage includes hash verification, moved/repeated chunk reuse,
interrupted staging, corrupt deltas and fallback, generation 1 same-size
corruption, path protection, multipart metadata, external patch recovery,
launch leases, prefix process detection, and the Java fallback. Run Rust and
Linux helper suites in WSL. JVM results, release/native/runtime-input checks,
CI results, and actual-device evidence must be reported separately in the PR.

Thor testing has verified a real Rayman 2 depot update, repair of deliberately
corrupted Rayman content, process-death download recovery, shared Java/Linux
launch exclusion, and a Neo Cab repair on external SD storage with its sidecar
unchanged. The owned ELDERBORN 1.08-to-1.09 standalone patch initially failed with
`Path not found`: a Wine file trace showed corrupted path characters under reduced
x87 precision. With full precision, the actual patch completed through DD's normal
installer UI, returned zero, and its files matched all 381 entries in target build
`55745895026856358`. The saved performance configuration was byte-for-byte unchanged.
The patched game then reached its main menu through the existing Steam shortcut;
its process used the ordinary reduced-precision setting rather than the installer's
full-precision override. The original sidecar remained identical and the operation
journal cleared after verification.
Rayman 2 also launches through its existing Steam shortcut after maintenance and
reaches its interactive language menu. Its existing cloud download remains on
the ordinary game-launch path; patch execution bypasses that path.
