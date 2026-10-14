# Epic Games Store module: clean-room rewrite

The Epic integration (the Rust `epic` module in `libdroiddeckstores.so` and the Kotlin package
`com.droiddeck.launcher.stores.epic`) was rewritten so that it carries no code from the earlier
implementation, whose Rust module stated derivation from third-party Epic clients. This page
records how that was done and every judgement call the new code makes.

## Process

1. **Spec, written separately.** A functional specification (six documents: summary and unknowns,
   service protocol, manifest and chunk formats, install behaviour, on-disk compatibility, and the
   app-facing interface) was written from the old code by a separate pass. It describes behaviour
   and data formats only, not code structure.
2. **Implementation from the spec only.** The implementer worked from those documents and from
   code that is not Epic-specific:
   - read: the spec; the crate's `Cargo.toml`, `lib.rs`, `stores_jni.rs`, `priority.rs`,
     `fetch_tuning.rs`, the shared `fetch_core.rs` and the GOG module (for crate, threading and JNI
     conventions); `JNI.md`; the Kotlin code outside `stores/epic/` that calls Epic (to keep its API);
     build files and CI;
   - not read: anything under `stores/epic/` (Kotlin, Java, tests) or `src/epic*` (Rust) of the old
     tree, the body of the old `EpicCloud` class and of `CloudSavePaths.epic`, other branches or
     checkouts, or any Epic client source. The old files were deleted with `git rm` without being
     opened. (A search over shared files incidentally printed two lines of the old `EpicCloud`, its
     base URL and User-Agent constant; both values are in the spec and only the URL is used.)
3. **Fresh code.** Every file of the new module has its own structure, names and comments, with a
   plain description and `SPDX-License-Identifier: GPL-3.0-or-later` (the crate's licence). Where
   the spec left a fact open, the choice was made from general knowledge of Epic's public services
   and is listed below.

## Layout

| Rust (`app/src/main/rust/stores/src/epic/`) | |
|---|---|
| `net.rs` | blocking HTTP helper, the one User-Agent, percent/base64 encoders |
| `account.rs` | sign-in with an authorization code, refresh, exchange codes, redirect-page parsing |
| `library.rs`, `catalog.rs` | owned items (all pages), catalog details and attributes |
| `build_info.rs` | live build lookup, CDN list, manifest download |
| `manifest.rs`, `chunk.rs` | binary and JSON manifests, chunk files |
| `locale.rs` | device language → install tags, tag selection |
| `install/` | the install engine: `staging`, `delta`, `space`, `cache`, `schedule`, `writer`, `mod` (orchestration), end-to-end `tests` against a loopback server |
| `shop.rs`, `achievements.rs`, `savesync.rs`, `launch.rs`, `timefmt.rs` | shelves, achievements, cloud saves, launch arguments, ISO dates |
| `service.rs`, `ffi.rs` | the JSON operations and the JNI entry points |

| Kotlin (`stores/epic/`) | |
|---|---|
| `EpicBackend` | `StoreBackend` + `EpicLaunchSupport`; installs the login flow |
| `EpicLogin` | the `StoreLoginActivity.Flow` |
| `EpicCredentialStore`, `EpicPrefs` | account (shared Keystore store), `stores.epic` prefs |
| `EpicLibrary`, `EpicShelves` | library sync and cache, shop shelves |
| `EpicInstaller` | the download-queue job |
| `EpicApiClient`, `EpicDownloadManager`, `EpicInstallTags`, `EpicAchievements` | API kept for callers outside the package |
| `EpicSaveSync`, `EpicSaveTemplate` | cloud-save transfers for `EpicCloud`, template parsing for `CloudSavePaths.epic` |
| `EpicNative` | JNI wrapper (see `rust/stores/JNI.md`) |

## Judgement calls

Numbers in brackets refer to the spec's list of unknowns.

**Fixed where clearly correct**

1. OAuth form values (authorization code, refresh token) are percent-encoded [9].
2. A library page that fails ends the sync with an error and keeps the cached library instead of
   caching a partial one; an HTTP 401 is reported as "session expired: sign in again" rather than
   an empty library [7].

**Chosen where the spec left it open**

3. **User-Agent** [1]: every HTTP request (account, library, catalog, build info, CDNs, store
   GraphQL, promotions, cloud saves) sends `DroidDeck/<versionName>`, defined once in
   `epic/net.rs` (`USER_AGENT_PRODUCT`); changing that constant is the one place to adjust.
   Achievements are the exception: Epic's achievements service rejects other agents, so
   `epic/achievements.rs` sends Epic's launcher agent (`net.rs::EPIC_LAUNCHER_USER_AGENT`). The sign-in **WebView** keeps the launcher-style UA it used
   before, because a sign-in page that rejects the UA would lock every user out; that string lives
   in `epic/account.rs` (`WEBVIEW_USER_AGENT`).
4. **Shared fetch core** [14]: chunk downloads reuse the crate's store-agnostic `fetch_core` (also
   used by GOG and Amazon; it came from Bannerlator's own crate and, unlike the old Epic module,
   states no derivation from an Epic client). It already provides the adaptive window, CDN
   rotation, retries with back-off, 429 and stall cooldowns the spec asks for. If its lineage is
   ever in doubt, it is the one shared dependency to replace.
5. **Out-of-package Epic code** [14]: `EpicCloud` and `CloudSavePaths.epic` were rewritten to call
   the new module. `StoreLaunch`'s exchange-code handling, `EpicOptions`, `WinCompSources.epic` and
   `SessionPrefs.storesEpicMode` are shared code and were left as they are.
6. **Save-path template** parsing is Kotlin (`EpicSaveTemplate`), not Rust: the prefix walk it feeds
   is shared Kotlin code and its existing unit test runs on the JVM without the native library. The
   Epic walk matches folder names case- and punctuation-insensitively (`MyGame` finds `My-Game`), as
   that test expects.
7. **One engine** [12, 13]: the hidden `storesEpicMode` switch is not read and there is no Java
   fallback; without the native library the Epic store reports itself unavailable. When the full
   space plan does not fit, the engine runs in a narrow mode (one file staged at a time, no chunk
   cache), which needs only the net peak of the pending files.
8. **Schedule and cache**: the cache budget is enforced by the plan (eviction of the chunk needed
   furthest in the future). At run time files are admitted up to 48 downloads ahead of the
   furthest dispatched one, so the cache can briefly hold a few entries more than the plan's peak.
   A chunk is first written into every admitted file that uses it; it touches the cache only when a
   not-yet-admitted file still needs it.
9. **Space probing** uses `statvfs` (available blocks × fragment size, the same figure as Android's
   `StatFs.getAvailableBytes`) from Rust; a failed probe is "unknown". A scratch directory on the same
   filesystem as the game is treated as "beside the game".
10. **File hashing** is done in file order while parts land; parts that arrive early are read back
    from the stage file. Files without a manifest hash are not hashed.
11. **Staging folder recovery**: an *empty* staging folder without its marker (a run stopped between
    creating the folder and writing the marker) is adopted and the marker written; any other missing
    or foreign marker fails the run without deleting.
12. **Unsafe manifest paths** (absolute, `..`, empty segments) fail the install.
13. **Encrypted manifests** (stored-as bit 1) are refused with an explicit message.
14. **Meta section prerequisite ids** are all read (no cap at 64), so the following fields stay aligned.
15. **Store GraphQL shelves** are requested with their arguments written into the query text
    (country reduced to two letters first) instead of typed GraphQL variables, so the query does not
    depend on the server's variable type names.
16. **Achievements**: when an account id is stored and the player-progress query gets no answer, the
    result is "unreachable" rather than a list with everything locked.
17. **Token refresh** is single-flight (one refresh at a time; a waiting caller re-reads the stored
    account first), so a rotated refresh token is never spent twice. A fresh sign-in replaces the
    stored account instead of merging with it.
18. **Download progress** reported to the queue never moves backwards, although samples come from
    several native threads.
19. **Network window** is the speed tier's value clamped to 1–128; the per-host cap is
    `max(6, ceil(window / hosts))`.

**Kept at parity (known weak spots)**

- Chunk requests do not carry the CDN's signing query; only the manifest request does; the
  `cloudflare.epicgamescdn.com` CDN stays excluded [2].
- The manifest file name comes from the first manifest location and is requested from every CDN [3].
- The manifest header SHA-1 and the chunk header GUID/hash are not checked (chunk size and chunk
  SHA-1 are) [4].
- Per-file flags, symlink targets and custom fields are ignored [5]; JSON manifests carry no hashes
  as read, so their files are rewritten on every run [6].
- The refresh token's own expiry is not tracked; a failed refresh still returns the stored token [8].
- The cloud `hash` is compared as if it were an MD5 [10].
- `AdditionalCommandLine` is one argument, frozen at install [11].
- Shop search, an unused "wants an exchange code" helper and per-game uninstall work are not
  ported; DLC stay hidden and are never installed separately; optional (non-language) install tags
  are never selected; resume restarts a partially written file from zero [15–18].
- Library details are fetched one game at a time, as before.

## What needs device testing

- Sign-in through the WebView, and that an **existing** saved account (sealed `credentials.json`)
  stays signed in after the update.
- Every endpoint with the DroidDeck User-Agent: library, catalog, build info, CDN downloads, store
  GraphQL and promotions, cloud saves (achievements use the launcher agent, see above).
- Library sync and shelves; install, pause/resume, cancel, update and repair; a card install with
  the internal scratch cache; launch with an exchange code; cloud-save up/down.
