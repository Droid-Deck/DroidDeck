# Amazon Games module: clean-room rewrite

The Amazon Games integration (the Rust `amazon` module in `libdroiddeckstores.so` and the Kotlin
package `com.droiddeck.launcher.stores.amazon`) was rewritten so that it carries no code from the
earlier implementation. This page records how that was done and every judgement call the new code
makes.

## Process

1. **Spec, written separately.** A functional specification (six documents: summary and unknowns,
   service protocol, manifest format, install behaviour, on-disk compatibility, and the app-facing
   interface) was written from the old code by a separate pass. It describes behaviour and data
   formats only, not code structure.
2. **Implementation from the spec only.** The implementer worked from those documents and from
   code that is not Amazon-specific:
   - read: the spec; the crate's `Cargo.toml`, `Cargo.lock`, `lib.rs`, `stores_jni.rs`,
     `priority.rs`, `fetch_tuning.rs` and the shared `fetch_core.rs`; the clean-room Epic module
     (Rust and Kotlin) as the structural template; `JNI.md`; the shared Kotlin store code (account
     store, install records, download queue, login activity, catalog and state); the signatures and
     call sites of `StoreSizes`' Amazon branch (to keep them compiling); build files and CI;
   - not read: anything under `stores/amazon/` (Kotlin, Java, tests) or `src/amazon*` (Rust) of the
     old tree, other branches or checkouts, or any Amazon client source. The old files were deleted
     with `git rm` without being opened.
3. **Fresh code.** Every file has its own structure, names and comments, with a plain description
   and `SPDX-License-Identifier: GPL-3.0-or-later` (the crate's licence). Where the spec left a fact
   open, the choice was made from general knowledge of Amazon's public services or from
   unauthenticated probes of the live endpoints (below), and is listed here.

## Layout

Rust, `app/src/main/rust/stores/src/amazon/`:

| File | Role |
|---|---|
| `net.rs` | blocking HTTP (reqwest + rustls), the three User-Agent identities, hex / percent / base64url helpers, URL scrubbing for logs |
| `auth.rs` | per-attempt device serial, client id, PKCE verifier and challenge; sign-in URL; code from the return URL; registration, refresh, deregistration |
| `distribution.rs` | the distribution service call shape (`X-Amz-Target`, `x-amzn-token`, `Content-Encoding: amz-1.0`), refusal detection, the download lookup |
| `library.rs` | entitlement pages, field fallbacks, de-duplication, add-on filter, sort |
| `cdn.rs` | path insertion before the signed query, file URLs, the manifest GET |
| `codec.rs` | manifest framing (big-endian header length) and xz / LZMA-alone decompression (`lzma-rs`) |
| `proto.rs` | a strict protobuf wire reader (and test writers) |
| `manifest.rs` | packages → files, path normalisation and safety, duplicate handling |
| `install/` | `mod.rs` (the run), `check.rs` (completeness, stray `.tmp`, free space), `writer.rs` (streamed download, SHA-256, atomic placement), `tests.rs` (loopback end-to-end) |
| `launch.rs` | `fuel.json`, exe scoring, the FuelPump environment |
| `service.rs`, `ffi.rs` | named operations and the JNI boundary |

Kotlin, `app/src/main/java/com/droiddeck/launcher/stores/amazon/`: `AmazonBackend` (the
`StoreBackend`, installs the login flow), `AmazonLogin` (the WebView flow), `AmazonCredentialStore`
(the account through `StoreAccounts`, single-flight refresh), `AmazonPrefs`, `AmazonLibrary` (sync,
cache, posters, publishing), `AmazonInstaller` (the queue job), `AmazonNative` (JNI), and two thin
wrappers kept for `StoreSizes`: `AmazonApiClient` (`getGameDownload`, `getBytes`, `appendPath`) and
`AmazonManifest` (`parse(bytes).totalInstallSize`).

The JNI surface (`nativeConfigure`, `nativeCall`, `nativeFetch`, `nativeInspect`, `nativeInstall`,
`nativeCancel`, `nativeRelease`) is documented in `app/src/main/rust/stores/JNI.md`. The old
`nativeStart` symbol is gone. The Java xz library stays in `app/build.gradle`: commons-compress
needs it for the `.tar.xz` Windows components, so only Amazon stopped using it.

## Compatibility kept exactly

- Credentials (`stores/amazon/credentials.json` through `StoreAccounts`): the same keys
  (`access_token`, `refresh_token`, `device_serial`, `client_id`, `expires_at`, `display_name`). A
  refresh merges `access_token`, `device_serial`, `client_id`, `expires_at` into the stored record
  and keeps every other key; a new sign-in writes the whole record. Registration and refresh keep
  the launcher's device constants, so existing refresh tokens keep working.
- Preferences `stores.amazon`: `library_cache` (same element keys), `library_synced_at`,
  `vcover_<id>`, `size_<id>`, `version_<id>`; sign-out removes only the first two.
- Install folders, the sidecar keys (`extra.entitlementId`, `extra.sku`, no `cloud` key), the
  launcher name `.droiddeck-launch.bat`, the `.tmp` naming and the two marker files.
- The catalog item's `extra` (`entitlementId`, `sku`, `version` = `""`) so the shared size cache and
  the saved queue keep working.

## User-Agents and what the probes showed

Unauthenticated probes of the live services (2026-10-14):

- **Distribution service** (`gaming.amazon.com`): `GetEntitlements`, `GetGameDownload` and
  `GetLiveVersionIds` answered identically for `AGSLauncher/1.0.0`, `DroidDeck/…`, a curl UA, with
  and without the `UserAgent` header. A bad token gives HTTP 401 `<NotAuthorizedException/>`; no
  token gives HTTP 400 with `com.amazon.coral.service#NotAuthorizedException`. The probes cannot
  show whether the UA matters for an authorised call, so the launcher's `User-Agent` and
  `UserAgent` values are kept (Amazon's own client identifiers, one constant each in `net.rs`).
- **Identity API** (`api.amazon.com`): register, token and deregister gave the same validation
  errors whatever the UA (Firefox, `DroidDeck/…`, none). The desktop Firefox string is kept for
  these calls, the same as the WebView: it is a generic browser label, not a client name, and
  existing refresh tokens are known to work with it.
- **Sign-in page** (`www.amazon.com/ap/signin`): every UA got Amazon's bot check from the probe
  host, so nothing could be learned; the WebView keeps the desktop Firefox string (a sign-in page
  that misbehaves would lock every user out).
- **CDN**: no URL is reachable without a token. Manifest and file requests now send
  `DroidDeck/<versionName>`, the only third-party label the old integration sent; this needs the
  device test below.

Refusal detection follows the probes: 401, 403, and 400 whose body names `NotAuthorizedException`
mean "sign in again".

## Judgement calls

Recommended fixes from the spec, adopted:

1. **Complete = size and SHA-256.** One test for the space check and the download plan; a
   same-size corrupt file is repaired. Files without a SHA-256 (another algorithm) are judged by
   size. No cross-run "verified" cache: every present file is hashed on each run.
2. **A failed library page fails the sync** and keeps the old cache; a refused token is reported
   with the shared "session expired: sign in again" line, also when a cache is shown.
3. **Single-flight refresh** (`AmazonCredentialStore`); a forced refresh after a failed library call
   skips the refresh when another caller already replaced the token.
4. **Mark updating** (`beforeReplace` → `StoreInstalls.markUpdating`) once, before the first file
   is put in place (new or replacing; the shared step only acts on an installed record).
5. **Manifest path safety**: absolute paths, any `:` (drive letters and stream names; Windows file
   names cannot contain it), NUL, `.` / `..` and empty segments, and DroidDeck's own root files
   (`.droiddeck-store.json` and its `.tmp`, `.droiddeck-launch.bat`, the two markers, compared
   ignoring case) fail the install naming the path. A manifest that lists both `X` and `X.tmp` is
   refused (the second would collide with the first's temporary file). The protobuf reader rejects
   truncation, over-long varints, groups and unknown wire types, field number 0, wrong wire types
   on the fields used, and a non-empty file without a hash or a SHA-256 that is not 32 bytes.
6. **Exe scoring on relative paths.** The Unreal-folder rule is tested against `"/" + relative
   path` so a top-level `Binaries/Win64/…` still matches. The "first five letters" rule compares the
   title with the file name **without** `.exe` (the extension's letters would otherwise count). An
   empty title never matches the "path contains the title" rule. Ties: larger file, then the earlier
   path. Only regular files are candidates; symlinks are not followed; depth is capped at 16.
7. **CDN User-Agent** is `DroidDeck/<versionName>` (see above).
8. **Empty files** whose hash is absent or the SHA-256 of empty input are created locally; stray
   `.tmp` siblings of manifest files are removed at run start (other `.tmp` files are untouched).
9. **Markers** `.amazon_download_in_progress` / `.amazon_download_complete` are still written.
10. **Duplicate paths**: the later entry wins with a log warning; files sharing one hash are fetched
    once per path (no copy optimisation).

Other choices:

- **Install size** is the sum over distinct paths (equal to the spec's "duplicates included" sum
  for every manifest without duplicate paths, which none have been seen to have); it is the
  progress total, the disk size and `size_<id>`.
- **Free space** is measured in Rust (`statvfs` on the folder or its nearest existing parent),
  which is what the shared probe measures; unknown never blocks, a measured zero does.
- **Pre-download check** is reported in the queue's Manifest stage ("Checking files…" with byte
  progress), like GOG and Epic: the Downloads UI orders stages Manifest → Download → Verify →
  Install, so a Verify stage before Download would read backwards.
- **Concurrency**: one streamed GET per file through the shared fetch core in stream mode (the
  speed tier's window clamped to 1–128, per-host cap `max(6, ceil(window / hosts))`, 120 s to the
  response headers, the core's 30 s idle cap on the body, its five attempts with back-off and its
  stall and 429 handling). A piece at offset 0 restarts the file (a retry); an out-of-order piece,
  a body longer than the manifest size, a short body or a hash mismatch deletes the `.tmp` and is
  retried; a write error, a refused `beforeReplace` or a failed rename fails the run.
- **Decompression** uses the `lzma-rs` crate (pure Rust; xz with any check type and LZMA-alone),
  with a 256 MiB cap on the decompressed manifest.
- **Live version lookup** (`GetLiveVersionIds`) is not implemented; nothing used it.
- **DLC filter**: add-ons are hidden only when they also name a parent product (as the spec's sync
  contract says), unless that would hide everything; the Rust call returns the filtered, sorted list
  plus the unfiltered count.
- **Sign-in code**: the authorization code is read from the return URL's query (`%XX` decoded, `+`
  kept literally; codes are URL-safe). The WebView flow passes the URL to Rust and never logs it.
- **Randomness** for the serial and verifier comes from `/dev/urandom`.
- **`fuel.json`** is found by exact name, else a case-insensitive match at the folder root; a UTF-8
  BOM is accepted. `Main.Command` is accepted after stripping leading `/` and `./`, and must be a
  safe relative path naming a regular file.
- **Logs**: the Rust side strips the query string and fragment of every URL in a line before it
  reaches `onLog`; the shared redaction runs after that as before.
- **No engine, no store**: without the native library, sign-in, sync and install report
  "Amazon Games: the store engine is not available"; there is no Java fallback.

## What needs device testing

- An existing signed-in user stays signed in after the update: the library shows from cache at once,
  and a forced sync refreshes the token with the stored refresh token and lists the games.
- A fresh sign-in through the WebView (registration, display name), and sign-out (deregistration
  fired, library cache cleared).
- A library with many pages; add-ons hidden; posters.
- Install, pause, resume, cancel (folder removed); repair of an installed game (nothing downloaded
  when intact; a corrupted file fetched again; "Resume install" offered when a repair is
  interrupted); an SD-card install; out-of-space refusal.
- Every endpoint with the chosen User-Agents, the CDN with `DroidDeck/…` in particular.
- Launch of an existing install through its existing Steam shortcut (same shortcut, same prefix)
  and of a fresh install (exe choice, `fuel.json` arguments, the five FuelPump variables in
  `.droiddeck-launch.bat`); uninstall.
