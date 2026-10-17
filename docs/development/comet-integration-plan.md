# Comet integration research and implementation plan

Researched 2026-10-12 against DroidDeck `10ef8c4bd62b21872fc13382a808cb0f2f117b26` (0.4.0).
The research below informed the implementation on `feat/gog-comet-achievements`, rebased onto refreshed main `a2a9586b`. Controlled SDK unlock/upload has now been verified on Thor; wider gameplay coverage remains an acceptance test for users.

## Implementation and testing status

Implemented an opt-in GOG companion built from pinned Comet v0.3.2, credential transfer through stdin, one owned daemon per session/account, private persistent databases, per-game settings, and first-launch Galaxy service provisioning using the selected Proton. Local unlocks, pending uploads and successful uploads produce sanitized session events. The game settings card shows pending/uploaded/unavailable status for the companion in the current session; it is not a per-game achievement history. Local unlocks now show an animated DroidDeck notification with the achievement icon, name and game title. The achievement reader comes from main; upload acknowledgements refresh its progress. No Galaxy overlay is installed. The session-menu reader is available only for a live supported GOG/Epic game window with a nonempty achievement list, including while Steam's overlay is open; GOG browsing does not require Comet syncing to be enabled. Epic syncing remains a separate investigation.

Comet's patch also uses an unconditional periodic sync tick during SDK traffic, preserves retries after failed achievement POSTs, and limits SDK clients to enabled installed GOG games. Credential errors and raw upstream protocol output are not forwarded to Android/session logs.

Validation on AYN Thor (`192.168.0.8:38029`, Linux runtime r9):

- ARM64 companion runs and receives credentials through proot stdin; owned readiness occurs in under a second.
- A fresh disposable prefix installs the x64 dummy service through ARM64 Proton/FEX before its first launch.
- ELDERBORN (GOG product `1732383191`) launches through the normal managed Steam shortcut. Its Galaxy SDK authentication and 27 achievement definitions can be read through Comet, and independently from GOG's gameplay API.
- Normal session stop removes the owned companion and preserves its database.
- Release APK builds; 694 JVM tests pass and all 20 store-launch tests pass.
- Linux Docker suite: 548 tests, 518 pass, 29 skipped, one failure in the unchanged x86 SIGILL fault-report test (Docker reports `si_code 01`, assertion expects `02`). This is an environment-dependent mismatch, not a green full-suite result.

The reader updated from 0/27 to 1/27 and back after upload/reset without reopening. A temporary protocol probe unlocked locked ELDERBORN achievements after twenty seconds, repeated the SDK request, confirmed the new unlock independently through the GOG gameplay API, and cleared each tested achievement afterward. The notification appeared once over the focused game and dismissed after five seconds. A temporary per-app HTTPS rejection confirmed a local popup, pending upload, and successful retry after the rule was removed; the account records and network rule were restored. Account changes with pending data, first-ever offline launches, 32-bit titles, and wider game/Proton compatibility still need user testing. A current Steam live-shortcut API mismatch required restarting Steam after installing the test game; the normal startup shortcut worked. That existing issue is outside this change.

## User test guide

1. Sign into GOG and enable **GOG achievements** in Stores settings. It is off by default. Keep the game's own achievement switch enabled in its launch settings; cloud saves may be on or off.
2. Start a supported GOG game using a DroidDeck managed compatibility tool. Obtain a genuinely unearned achievement through normal gameplay.
3. Check the game's GOG profile independently. Allow at least ten seconds online and a normal game exit. A local unlock should show a five-second notification over the game. The popup confirms the local unlock; check the GOG profile or the uploaded event for server delivery.
4. Include the game/product ID, device, Proton choice, whether the prefix was new, and online/offline sequence with the session report. The `gog.achievements` events distinguish `unlocked-locally`, `pending-upload` and `uploaded`; `uploaded` is emitted only after a successful GOG POST. Do not include credentials.
5. If the game has trouble with the companion, disable its achievement switch and relaunch. Changes to these switches apply on the next game launch.

## Recommendation and scope

Integrate Comet as a native ARM64 Linux companion for **GOG Galaxy achievements** inside DroidDeck's existing Linux/Proton session. Start with an opt-in GOG setting and a per-game disable switch. Cover launches from Games, Stores, Android shortcuts, Steam's own Play button, and KDE when those paths use DroidDeck's managed compatibility tool.

Comet handles the Galaxy SDK requests made by a game and uploads achievements to that game's GOG account. It does not transfer achievements between storefronts, add achievements to games without Galaxy SDK support, or implement Epic achievement sync. [Comet README](https://github.com/imLinguin/comet/blob/19b32597019ea1e64f1916010441960bcaac6e49/README.md)

Treat the screenshot as two workstreams:

| Request | Approach | Initial deliverable |
| --- | --- | --- |
| GOG achievements | Comet + existing GOG sign-in + Proton prefix setup | An unlock reaches the correct GOG profile from a real DroidDeck launch |
| Epic achievements | Validate the game's EOS authentication, deployment, consent and achievement calls | Evidence of an EOS unlock and the corresponding Epic profile update; fixes only where a failure is demonstrated |

Epic Games Store achievements are linked to EOS achievements in a game-specific deployment. A generic launcher service cannot create that integration for a title. DroidDeck already supplies EOS identity/deployment arguments and a fresh exchange code. [Epic achievement setup](https://dev.epicgames.com/docs/epic-games-store/services/epic-achievements/achievements-setup), [Epic authentication guide](https://dev.epicgames.com/docs/epic-online-services/accounts-and-social/eos-epic-account-services/auth-interface/auth-guide/auth-intro)

## What was verified

- Local `main` fast-forwarded from `b6c1d3c0` to `10ef8c4b` and matched refreshed `origin/main`.
- Removed seven clean, remotely backed checkouts with non-force `git worktree remove`; pruned nine registrations pointing to missing directories. Kept dirty worktrees, open-PR work, credentials, captures, and uncertain checkouts. Remote branches and PRs were not changed.
- Inspected Comet release `v0.3.2` (`3d7488ee72f83b7e8462a70fd4c4638d6da71360`) and upstream main (`19b32597019ea1e64f1916010441960bcaac6e49`). Main has substantial unreleased changes, including progress-stat workarounds. Use a pinned release as the initial baseline; evaluate later changes individually.
- Downloaded `comet-aarch64-unknown-linux-gnu` and `dummy-service.zip` from v0.3.2. ELF inspection confirmed AArch64, interpreter `/lib/ld-linux-aarch64.so.1`, dependencies on `libgcc_s`, `libm`, `libc` and the loader, and required GLIBC symbols through 2.34. Binary size is 11,365,728 bytes, before APK compression.
- The current Linux runtime catalog is `linuxfs-r9`, described as Arch Linux ARM aarch64. This is separate from the Debian image used for x86 preload builds. Actual loader/library compatibility must still be checked in the installed runtime.
- Inspected Heroic main `db5272043e74336b72bc60caad14ca74701082d8`: its GOG launcher spawns Comet with `--from-heroic --username ... --quit`, then terminates it after the game. That establishes a reference integration, but its process and credential handling should not be copied wholesale.

Downloaded artifact hashes, useful for reproducing this inspection:

```text
comet-aarch64-unknown-linux-gnu
c8cd850a03ba66c10bf620eee2735946ddf0b5969cd38cae02c6bede6d15c5b8
dummy-service.zip
7fc5facd1fce4d516cd3ce527e7e4dbe0bfd17649cea805e8ea7c37d4ba58d9a
```

These are locally measured hashes, not independently signed upstream attestations. A patched build will have different hashes.

## Existing DroidDeck integration points

| Source | Existing behavior | Proposed change |
| --- | --- | --- |
| `stores/gog/GogAuth.kt`, `stores/LoginFlows.kt` | Saves access token, optional refresh token/user ID, username; refreshes expired access tokens | Add a validated Comet credential snapshot, recover missing identity where possible, serialize refresh and sign-out |
| `stores/StoreAccounts.kt`, `stores/CredentialCipher.kt` | Keeps store credentials encrypted with Android Keystore | Keep these as the durable credential authority; do not export a persistent Heroic auth file |
| `stores/StoreLaunchRequests.kt` | App-side request handler under the session's `stores/req` and `stores/resp` | Handle GOG companion preparation; ordinary replies contain status, not tokens |
| `tools/linuxfs/overlay/usr/local/bin/droiddeck-store-launch` | Finds sidecars beside a launcher or ancestor of a direct `.exe`; runs before managed Proton launches | Detect GOG settings, request companion readiness and arrange prefix setup, independently of cloud saves |
| `tools/linuxfs/overlay/usr/local/bin/steam-compatibility` | Generates compatibility wrappers; waits for Proton and calls `--exited` when cloud saves are enabled | Generalize post-game handling so achievements still get lifecycle handling with cloud saves disabled |
| `session/SessionService.kt`, `session/SessionFiles.kt` | Stages session helpers, starts request observation and tears down the guest | Own the companion's session identity and cleanup; request graceful shutdown before guest teardown |
| `stores/StoreGameSidecar.kt`, `stores/gog/GogPrefs.kt` | Persist game and store settings | Add backward-compatible GOG achievement settings; keep game tokens out of sidecars |
| `app/build.gradle`, `tools/build_local.sh`, `.github/workflows/build.yml`, `tools/release/runtime_inputs.py` | Prepare, cache and attest runtime assets | Include pinned Comet binary, service tools, licenses and build inputs in both local and CI APK paths |

Paths above are under `app/src/main/java/com/droiddeck/launcher/` unless fully qualified.

`ComponentsManager.kt` manages FEX/DXVK/VKD3D packages. Comet should initially be an internal runtime asset rather than an additional graphics-component type.

## Proposed architecture

### 1. Package a pinned companion

Build from Comet v0.3.2 with a small, reviewable patch set and its protocol submodule pinned. Put the executable and dummy-service tools under a dedicated guest directory such as `/usr/local/lib/droiddeck-comet/`. Ship the Apache-2.0 license and attribution. Add a `tools/comet/` build recipe and source/patch/hash metadata using the existing prepared-runtime conventions.

Keep the current upstream ARM64 asset as the initial compatibility probe. The production build needs the credential and logging changes below. Ensure the Rust/toolchain baseline supports the pinned release and that CI cache keys and runtime attestation include the new inputs. Exercise cached and uncached builds, plus runtime installation into an existing app installation.

### 2. Hand off credentials without putting them in command lines

Comet's real CLI uses `--access-token`, `--refresh-token`, `--user-id`, and `--username`; the README contains outdated spellings. These flags put tokens in process arguments. `--from-heroic` reads a plaintext Heroic auth file, and its Linux parser uses `XDG_CONFIG_PATH`, not the usual `XDG_CONFIG_HOME`. Neither is the preferred production handoff. [CLI source](https://github.com/imLinguin/comet/blob/3d7488ee72f83b7e8462a70fd4c4638d6da71360/src/main.rs), [Heroic import source](https://github.com/imLinguin/comet/blob/3d7488ee72f83b7e8462a70fd4c4638d6da71360/src/import_parsers/heroic.rs)

Add a small Comet credential-input option that accepts JSON over stdin or an inherited file descriptor. The Android-owned supervisor launches Comet through the existing proot command builder, writes one validated snapshot into its stdin, closes it, and tracks the process. Prove this pipe survives the proot setup before committing to the plumbing; if it does not, use a short-lived private mode-600 file with immediate unlink and startup cleanup.

The snapshot needs a usable access token, refresh token, numeric GOG user ID and username. Existing logins may lack refresh token or user ID: current `saveLogin` accepts both as optional and only saves the username from its user-data lookup. Recover identity through GOG's authenticated user endpoint where possible. If no refresh token exists, show a specific GOG re-sign-in requirement and let the game launch without achievement support.

Comet exchanges the Galaxy refresh token for each game's SDK client credentials; the game's SDK handshake supplies its client ID/secret. Do not confuse the store product ID with the SDK client ID, and do not require those per-game secrets in a store sidecar. Explicit offline preloading is a separate case and can wait.

Patch or sanitize Comet's HTTP error logging as well: token requests encode credentials in URLs, and raw `reqwest` errors can include those URLs. Filter before stderr reaches session logs, crash reports, or exported artifacts. Passing secrets through stdin alone is insufficient. [Token exchange source](https://github.com/imLinguin/comet/blob/3d7488ee72f83b7e8462a70fd4c4638d6da71360/src/api/gog/users.rs)

### 3. One owned daemon for the session/account

Comet binds `127.0.0.1:9977`. Proot does not provide a private network namespace. Use a serialized ensure-running operation for one owned daemon per active session/account, shared by GOG games in that session. Verify process identity, session generation and GOG account before reuse; an occupied port is not proof that our daemon is ready. If another service owns the port, report unavailable and continue launching the game.

Prefer session ownership over `--quit`: upstream's idle quit requires a client to have connected, so games without Galaxy can leave it running indefinitely. Explicit lifecycle ownership also avoids stopping it while another GOG client is active. Readiness should come from the owned process's listening event plus liveness, with a bounded wait; a probe connection has side effects on Comet's client/idle accounting.

Give Comet a persistent, private data location outside the session request tree and game directory through process-local XDG settings. Upstream stores gameplay databases by `<SDK client ID>/<user ID>/gameplay.db`. Preserve these databases across session resets and APK updates. Stop the old daemon on sign-out/account change, retain pending data under the old account, and never upload it using the replacement account. Provide explicit data deletion separately from sign-out.

### 4. Provision the game's actual Wine prefix on its first launch

Some Galaxy SDK versions need the Windows `GalaxyCommunication` service even though Comet itself runs on Linux. The release ZIP contains `GalaxyCommunication.exe`, `install-dummy-service.bat`, and `update-permissions.exe`; packaging only the standalone service executable misses part of upstream's setup. [Dummy-service instructions](https://github.com/imLinguin/comet/blob/3d7488ee72f83b7e8462a70fd4c4638d6da71360/dummy-service/README.md)

Use the title's selected Proton build and `STEAM_COMPAT_DATA_PATH`, not a global prefix. After Proton has created/updated that prefix, copy the tools into the prefix and idempotently register the service with `sc`, including the upstream permission adjustment where necessary. Preserve an existing genuine Galaxy installation or service. Record completion only after service verification, with a versioned marker so tool updates can be applied safely.

This is an explicit first-launch problem: the existing `droiddeck-store-launch` runs before Proton creates a new prefix, and several existing registry helpers defer until a later launch. Add a guarded bootstrap/prelaunch stage using the selected Proton, prevent recursive store preparation, wait for its wineserver as needed, then register the service before starting the game. Do not accept "works on the second launch" as completion.

Test PE x64 service tools through DroidDeck's ARM64 Proton/FEX path and at least one 32-bit Galaxy game. Architecture fit of the Linux daemon does not prove the Windows helpers work.

### 5. Keep sync and teardown honest

Upstream persists changed achievements in SQLite, has a sync branch after ten seconds without another selected event, and performs a final sync when a client handler ends. This is not an unconditional ten-second timer or a guarantee of server delivery. Failed achievement PUTs retain database `changed` markers, but the examined sync routine clears its in-memory update flag after processing even when an individual upload fails. Require a targeted retry test and a narrow fix if it confirms that no retry occurs while the client remains connected. [Handler source](https://github.com/imLinguin/comet/blob/3d7488ee72f83b7e8462a70fd4c4638d6da71360/src/api/handlers.rs)

Allow disconnect/final sync before daemon shutdown, then use SIGINT (upstream explicitly handles Ctrl-C) with a bounded grace period. Add shutdown handling before `SessionService` tears down proot; adding it only to `finishSessionStop` is too late. Forced termination must leave durable pending data for the next valid game connection. Avoid promising a headless "sync everything" action: upstream's pending data is game-scoped and its CLI does not expose that operation.

Offline support depends on prior metadata/cache availability. First-ever offline starts are not guaranteed. Test cached offline unlocks, reconnection in the same launch, next online launch, forced stop, and app updates. Preserve both local dirty unlocks and unlocks made on another machine during reconciliation. [Offline issue](https://github.com/imLinguin/comet/issues/16)

### 6. Keep the first UI small

Add an opt-in **GOG achievements** setting and a per-game override. Keep this independent of the cloud-save toggle. Show actionable states such as sign-in needed, service unavailable, and connection established; distinguish local unlocks from uploads actually accepted by GOG. Include redacted diagnostics in the existing session report.

Defer a unified achievement browser, Android unlock toasts, GOG overlay downloads/injection, leaderboards UI, multiplayer and playtime claims. Upstream can implement more than achievements, but those features require their own validation. Comet itself downloads Galaxy peer redists in the background, so measure that network/cache behavior before deciding whether to defer or package it.

Keep a per-game escape hatch: upstream has reports of incompatible games and crashes when Comet is enabled, including [Rise of the Tomb Raider](https://github.com/imLinguin/comet/issues/80) and [unhandled message type 43](https://github.com/imLinguin/comet/issues/90). These are reports, not reproduced DroidDeck failures.

## Implementation order and acceptance gates

1. **Compatibility spike:** run the pinned ARM64 daemon's `--help` in the installed Thor runtime; prove localhost connectivity from Wine, proot stdin handoff and first-prefix dummy-service setup. Use a known Galaxy-achievement game owned by the test account. Do not change graphics or download behavior to mask a Comet failure.
2. **Build and credential foundation:** add reproducible assets, license notices, stdin/FD input, credential validation, refresh serialization and log redaction. Verify cached and uncached APK asset preparation.
3. **Launch and lifecycle integration:** add the serialized session supervisor, GOG prepare operation, first-launch prefix provisioning, persistent databases, sign-out handling and shutdown before guest teardown. Verify cloud-off still retains achievement lifecycle handling.
4. **Settings and targeted coverage:** add opt-in and per-game override, useful status, request timeout/error cases, process reuse/conflict cases, database preservation, and fake-secret leak checks. Extend `tools/tests/test_store_launch.py`, `test_store_shortcuts.py`, `test_game_environment.py`, `test_runtime_inputs.py` and relevant JVM tests rather than duplicating unrelated suites.
5. **Device acceptance:** verify an actual unlock appears on the correct GOG profile, then launch the same game twice and another game in a different prefix. Cover Steam Play, app launch, KDE, internal/SD installs, service-required and service-free titles, cloud-off, offline/reconnect, transient upload failure, suspend/resume, normal exit, drawer Stop, force stop, account change and app update. Record exact APK/runtime/Proton versions.

For the initial spike, use an upstream-listed service-free title such as Hollow Knight and a service-required title such as Cuphead, subject to ownership and the current installed build. The [compatibility table](https://github.com/imLinguin/comet/blob/19b32597019ea1e64f1916010441960bcaac6e49/docs/wiki/Game-Compatibility.md) is a starting point, not a DroidDeck compatibility guarantee.

A first GOG PR should include the complete minimal path through packaging, authentication, first launch, sync and teardown. Split Epic into a separate investigation/PR so a GOG success is not presented as fulfilling the Epic half of the request.

## Epic investigation

Choose a game with published Epic Games Store achievements and an easy unearned achievement. Establish current behavior before adding code: verify the exchange code, expected production sandbox/deployment, browser consent completion and successful EOS Auth/Connect identity. Observe the game's EOS achievement callback and verify the Epic profile independently.

Current `droiddeck-store-launch` deliberately removes an old `HKCU\Software\Epic Games\EOS\OverlayPath` because that overlay setup caused a launch exit. Do not blindly restore it or globally replace game EOS DLLs. Determine whether the selected title needs additional EOS redistributable/overlay support, and reproduce any missing authentication or unlock behavior before patching. Sync, profile visibility and notification overlays are separate acceptance criteria.

## Remaining uncertainties

The original research was static. The implementation validation and remaining device acceptance tests are recorded above; controlled GOG SDK unlock/upload and failed-upload retry are verified; Epic EOS behavior remains unverified. Forced Android process teardown, account replacement with pending data, and wider real-game coverage remain acceptance tests.

Additional source: [Heroic GOG launch integration](https://github.com/Heroic-Games-Launcher/HeroicGamesLauncher/blob/db5272043e74336b72bc60caad14ca74701082d8/src/backend/storeManagers/gog/games.ts), [Comet v0.3.2 release](https://github.com/imLinguin/comet/releases/tag/v0.3.2).
