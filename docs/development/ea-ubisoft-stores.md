# EA app and Ubisoft Connect as stores: research and plan

Research as of 2026-11-07, against `origin/main` at `914b3588e`. Nothing here is implemented yet.

## Summary

Both stores can follow the path Battle.net took (#979, then #1602): first the vendor client in its own
Proton prefix, then more of it done by DroidDeck itself. Unlike Battle.net, both now have open-source
clients that already handle sign-in, library and downloads without the vendor app. So the second step
can go further than Battle.net's did.

| | EA | Ubisoft |
|---|---|---|
| Sign-in without the client | Yes: OAuth `JUNO_PC_CLIENT` in a WebView | Yes: UbiServices session (ticket + remember-me) through Ubisoft's WebAuth page |
| Owned library without the client | Yes: juno GraphQL `ownedGameProducts` | Yes: Demux ownership service (protobuf over TLS), or the GraphQL library |
| Downloads without the client | Yes: a build is one ZIP on EA's CDN, read with HTTP Range per entry (Maxima) | Yes: Demux download service → manifest → zstd/deflate slices (Optima, ubi-cli-mvp) |
| Launch without the client | **Yes, legitimately**: LSX server on `127.0.0.1:3216` plus a real OOA licence from `proxy.novafusion.ea.com` (Maxima) | **Only by replacing Ubisoft's DRM DLLs** (Optima, Re0xCat loaders). Don't ship that. Modern titles need the real Connect anyway |
| Cloud saves without the client | Yes: `cloudsync.juno.ea.com` (Maxima) | Not researched; Connect handles them |
| Effectively out of scope | Javelin kernel anti-cheat titles (Battlefield, FC, Apex) | Kernel AC and online-only titles that misbehave under Proton |

**Recommendation**

- **EA should go fully native in-app**: sign-in, library, downloads, launch and cloud saves in Kotlin
  and Rust, with no EA app in the prefix. The EA app stays as an optional fallback, like the Epic
  launcher option (`EpicClient`). The LSX and licence half also fixes Steam's EA titles, which today
  install the whole EA app (#2036) just to answer `link2ea://`.
- **Ubisoft should be hybrid**: native sign-in, library and downloads in Rust, then **register the
  files with the real Ubisoft Connect and launch through `uplay://`**. That means a Connect prefix
  managed the way Battle.net's is.
- Ubisoft's launch step is the biggest open question (see "Unknowns"). If Connect can't be made to
  adopt natively downloaded files cleanly, start with the Connect-driven download, as #979 did for
  Battle.net.

## What DroidDeck already has, and how much of it each store reuses

| Existing piece | Where | EA | Ubisoft |
|---|---|---|---|
| `Store` enum + `StoreBackend` (signIn, syncLibrary, loadShelves, install, uninstall) | `stores/Store.kt`, `stores/StoresState.kt:355` | Reuse as is: new `EA` entry | Reuse as is: new `UBISOFT` entry |
| WebView sign-in with redirect capture (`StoreLoginActivity.Flow`) | `stores/LoginFlows.kt`, `StoreLoginActivity.kt` | Reuse: the redirect is `qrc:///html/login_successful.html?code=…`, caught in `isRedirect` | Reuse: capture the `ubi_v1` ticket from the WebAuth page, then exchange it for a full session |
| Keystore-backed account store | `stores/StoreAccounts.kt`, `CredentialCipher.kt` | Reuse | Reuse |
| Rust store engine `libdroiddeckstores.so` (JNI, `fetch_core.rs`, hand-rolled protobuf in `amazon/proto.rs`) | `app/src/main/rust/stores` | Reuse: ZIP central directory + Range fetch + inflate (`flate2` is already a dependency) | Reuse `fetch_core`. **New:** a TLS socket client for Demux, a protobuf schema, and a zstd decoder (`ruzstd` is pure Rust; the `zstd` crate needs `cc`, which the crate already has) |
| Download queue: pause, resume, cancel, tiers, Downloads tab | `stores/download/*` | Reuse | Reuse |
| Install roots: internal, other storage, Games folder | `StoreInstallRoot.kt` | Reuse | Reuse |
| Cloud saves: plan, paths, transport | `stores/CloudSaves.kt`, `CloudPlan.kt`, `CloudSavePaths.kt` | Reuse with a new EA transport | Later, or leave to Connect |
| Vendor client in its own prefix, a guest Python driver, a headless helper under gamescope, FEX choice, memory policy | `runtime/BattleNet.kt`, `tools/linuxfs/overlay/usr/local/bin/droiddeck-battlenet` | Fallback only | **Needed**: `droiddeck-ubisoft` modelled on `droiddeck-battlenet` |
| "Use the vendor launcher instead" option | `stores/epic/EpicClient*.kt` | Pattern for the EA app fallback | Pattern for "Open Ubisoft Connect" |
| Steam install-script seeding that lets EA app and Ubisoft Connect installers run | `droiddeck-seed-redists` (#1449, #2036) | Replaced by the LSX stub for Steam EA titles | Unchanged |
| Ubisoft registry handoff for Ubisoft titles bought on Epic | open PR #1445 | n/a | Same registry key (`HKLM\SOFTWARE\WOW6432Node\Ubisoft\Launcher\Installs\<id>\InstallDir`); merge or share it |
| Library, Steam shortcuts, art, game page, sidecars | `frontend/Library.kt`, `SteamLiveShortcuts.kt`, `StoreArt.kt`, `StoreGameSidecar.kt` | Reuse | Reuse |
| Clean-room process for store modules | `docs/development/epic-cleanroom.md`, `amazon-cleanroom.md` | Use: spec from Maxima, then implement from the spec | Use: spec from Optima/ubi-cli-mvp/YoobieRE |

Genuinely new engineering:

1. **An LSX server (EA).** EA's local SDK protocol is XML over TCP on 3216, with a challenge handshake,
   GetConfig, GetProfile, GetGameInfo, license and entitlement requests. Add a Wine-side stub registered
   as the `link2ea://` / `origin2://` handler with EA app presence keys, plus OOA licence requests.
   This half is new; the other EA parts are the familiar store pattern.
2. **The EA machine hash.** Licences are bound to a `machineHash` built from WMI/BIOS/disk/CPUID
   values. They have to match what the game computes inside Wine under FEX on the phone.
   Nobody has shown that on Android yet, so it has to be measured on device.
3. **A Demux client (Ubisoft).** This is a TLS socket to `dmx.upc.ubisoft.com` speaking protobuf, with
   patch negotiation, auth, ownership tokens and download-service URL signing. No store we have uses
   a socket protocol.
4. **A way for Connect to adopt files (Ubisoft).** Connect keeps its own DPAPI-encrypted sign-in, tied
   to the Wine `MachineGuid`. Our session can't be handed to it (ubi-cli-mvp checked). Connect also has
   to consider the game installed before `uplay://launch` works.

## Prior art

### EA

| Project | Licence | State | What it gives us |
|---|---|---|---|
| [ArmchairDevelopers/Maxima](https://github.com/ArmchairDevelopers/Maxima) (Rust, `maxima-lib`) | GPL-3.0 | Pre-alpha by its own README; last commit 2026-01; open issues through 2026-11. Backs KYBER | The whole stack: OAuth (`JUNO_PC_CLIENT`, localhost 31033 redirect), juno GraphQL (`.gql` files for owned games, `downloadUrl`, `availableBuilds`, achievements), the ZIP/Range downloader, OOA licences (`ooa/mod.rs`, `machineHash`), the LSX server (`lsx/`), cloud sync, the hardware hash (`auth/hardware.rs`), Wine registry setup. Unsupported: Battlefield 3/4 (Battlelog), pre-DiP games (Dead Space 2, BFBC2), DLC installs |
| [Maxima #71](https://github.com/ArmchairDevelopers/Maxima/issues/71) | | 2026-11 | Games never connect to LSX unless `HKLM\SOFTWARE\WOW6432Node\Origin` is right, and `INSTALLED_LANGUAGE` must be answered. Check ours against it |
| [Maxima #49](https://github.com/ArmchairDevelopers/Maxima/issues/49) | | | ARM64: the hash must match what `Activation.dll` reads from CPUID under Rosetta/Box64/FEX. Directly our problem |
| unifideck `docs/feasibility/ea.md` | GPL-3.0 | 2026-07 | EA app installer runs unattended (`/quiet`), login renders on GE-Proton11-1, `origin2://game/launch?offerIds=<id>&autoDownload=1` is the install idiom, ~2.5 GB client, aggressive self-update |
| Lutris `services/ea_app.py` | GPL-3.0 | Maintained | juno queries, `installerdata.xml` scan for installed games |
| BellezaEmporium/galaxy-integration-ead | **none** | Maintained | Facts only: OAuth params, `pc_sign` fallback |
| erri120/GameFinder wiki | GPL-3.0 | | Encrypted `IS` file format (only for reading EA app installs) |
| Heroic | | wontfix #352 | No EA support beyond Epic-hosted EA titles |

### Ubisoft

| Project | Licence | State | What it gives us |
|---|---|---|---|
| [xXJSONDeruloXx/ubi-cli-mvp](https://github.com/xXJSONDeruloXx/ubi-cli-mvp) (yours, TypeScript) | MIT | 2026-07 | The best-tested data plane: session login with `Ubi-Challenge` replay, `ubi_2fa_v1`, refresh; Demux ownership, ownership tokens and signed URLs; manifest decode; slice layouts (`slices_v3/<bucket>/<HASH>` and flat `slices/`); zstd and both deflates; integrity and path safety. On the Connect side: `connect-install` (`uplay://install/<id>`), **`connect-seed`** (let Connect start the official download, pause, exit, then fill the staging area with verified files), remembered-auth migration between prefixes, `ubi play` via `uplay://`. Docs: `research.md`, `validation.md`, `DEMUX_JOURNAL.md`, `optima-comparison.md` |
| [Starkka15/Optima](https://github.com/Starkka15/Optima) (Rust) | GPL-3.0 | 0.2.0, 2026-08 | Rust version of the same data plane (`auth.rs`, `demux.rs`, `install.rs`, `proto/*.proto`), plus launcherless launch through replacement `uplay_r1_loader` / `ubiorbitapi_r2_loader` DLLs, a self-signed certificate that mimics Ubisoft's (imported into the prefix's Root store so Watch_Dogs accepts it), and an EAX shim. Powers GameVault's Ubisoft extension |
| YoobieRE: `ubisoft-demux-node`, `ubisoft-dmx-decode`, `ubisoft-manifest-downloader`, `uplay-install-reverse`, `product-db-archive` | mixed or none | 2022–2024 | The original protocol reversing that both of the above build on |
| Re0xCat `uplay-r1-loader`, `ubiorbitapi-r2-loader` | | | The DRM-DLL replacements (see Risks) |
| [mubaraknumann/unifideck](https://github.com/mubaraknumann/unifideck) `stores/ubisoft/` | GPL-3.0 | Shipping, active | The full Connect-wrapper archetype in Python: three-prefix model (auth / template / per-game, shared `MachineGuid`), credential capture and fan-out, parsing Connect's binary `configurations` and `ownership` catalogs, leveldb `spaceId ↔ ubisoftConnectGameId`, a manual-install watcher, `uplay://launch/<id>/0`, session capture after exit. `docs/ubisoft-store-spec.md` is a good map of what breaks |
| Heroic | | | Installs Ubisoft Connect automatically for Ubisoft titles from Epic; no Ubisoft store |
| DroidDeck #1445 (open), #1449, #2286 / #2295 / #2317 / #2292 / #2050 | | | Our own Ubisoft-under-Proton knowledge: the `Installs` registry key, `UplayLaunch.exe` being a stub, the bundled `UplayInstaller.exe`, legacy installer layouts, SD-card staging, helper processes during the exit watch |

Licences: Maxima, Optima, unifideck and Lutris are GPL-3.0, which is compatible with
DroidDeck (GPL-3.0). ubi-cli-mvp is MIT. Even so, the Epic and Amazon modules were written clean-room,
so do the same here: write the spec from these sources, implement from the spec, and credit them in
the README the way #1602 does.

## Design

### EA

```text
StoreLoginActivity (OAuth JUNO_PC_CLIENT) ─► EaAccount (access + refresh token, persona)
EaLibrary ── juno GraphQL ownedGameProducts / legacyOffers ─► CatalogItem(offerId, contentId, slug)
EaInstaller (DownloadQueue job) ── downloadUrl ─► build ZIP ─► Rust: central directory, Range per entry, inflate, CRC
                                                    └─ writes __Installer/installerdata.xml as EA's own layout expects
Launch: droiddeck-ea (guest) ── registers stub for link2ea:// origin2:// + Origin/EA Desktop keys
        app-side EaLsxServer on 3216  ◄── game (OriginSDK / EA SDK)
        before launch: OOA licence (novafusion, machineHash) → ProgramData/Electronic Arts/EA Services/License
        before/after: cloudsync pull / push through CloudSaves
```

- **Where the LSX server runs.** In the Android app. DroidDeck's guest shares
  the network namespace, so an app-side server on 127.0.0.1:3216 is reachable from Wine. Keeping it
  in Kotlin or Rust, not a guest daemon, matches how DroidDeck owns other store logic. Check this
  against proot networking first.
- **The stub.** A protocol handler for `link2ea://` / `origin2://` that hands the request to DroidDeck.
- **Steam's EA titles.** Use the same stub and LSX path, keyed by `link2ea://` in the launch config,
  plus entitlement refresh and Steam account linking. #2036's EA app install becomes
  the fallback.
- **The EA app fallback.** Its own prefix (a `compatdata` id like Battle.net's `2596113170`), installed
  with `EAappInstaller.exe /quiet`, opened from the store row. That's for titles the LSX path can't
  start (pre-DiP, Battlelog) and for buying games.

### Ubisoft

```text
StoreLoginActivity (Ubisoft WebAuth) ─► ticket ─► full session (expiry, rememberMe); refresh ~3 h
UbisoftLibrary ── Demux ownership ─► products + config YAML (name, exe, uplay id, space id)
UbisoftInstaller (DownloadQueue job) ── Rust Demux: ownership token → download_service urlReq
                                      → manifest (356-byte header, zlib) → slices (zstd/deflate) → files, verified
Register + launch: droiddeck-ubisoft (guest, like droiddeck-battlenet)
        Connect in its own prefix, signed in once in its own window (DPAPI/MachineGuid bound)
        adopt files: Installs\<id>\InstallDir registry (+ whatever state Connect checks)
                     or ubi-cli-mvp's connect-seed: official download starts, pause, seed, let Connect verify
        launch: upc.exe uplay://launch/<id>/0, Connect closed or minimised once the game runs (#979's 20 s rule)
```

- **One Connect prefix, not one per game.** unifideck uses per-game prefixes because Steam on the
  Deck gives each shortcut its own. DroidDeck already runs Battle.net games inside Battle.net's prefix,
  which avoids unifideck's whole credential fan-out problem.
- **Two stages, like Battle.net:**
  1. Connect in its prefix, install by `uplay://install/<id>`, and Downloads shows an indeterminate
     "Installing in Ubisoft Connect" until the folder settles. That is #979's level.
  2. Native downloads in the Rust engine with real progress, pause and resume, then Connect adopts the
     files. Like #1602, but we own the transfer instead of driving the vendor's downloader. If Connect
     has a scriptable local API (Battle.net's Agent had one), that's a cheaper middle stage, but none is
     known.
- **Launcherless DRM shims: don't ship them.** See Risks.

## Known

- EA sign-in, library, downloads, licences, LSX and cloud sync all work outside the EA app on Linux
  (Maxima).
- The EA app installs unattended and its login renders under GE-Proton 11 on x86 (unifideck).
- Ubisoft sign-in, refresh, Demux ownership, signed URLs, manifests and slice reconstruction work
  (ubi-cli-mvp `validation.md`, Optima). A session ticket lasts about 3 hours and remember-me renews it.
- Connect's sign-in can't be created from our session. It is DPAPI-encrypted and tied to
  `MachineGuid`, but it can be copied between prefixes that share that identity.
- Connect has repeatedly broken under Proton after its own updates (2023, and a single-source April 2026
  report). #1449 and the open Ubisoft PRs show the same churn here.
- Javelin titles don't run under Proton. EA's March 2026 job listing mentions future Linux/Proton work;
  nothing has shipped.

## Unknowns, in the order to settle them

| # | Question | How to find out | Blocks |
|---|---|---|---|
| U1 | Does an EA OOA licence fetched with our `machineHash` validate inside DroidDeck-Proton under FEX on Adreno phones? Which WMI/CPUID values does the game see? | Prototype: Maxima's hash code against values read through Wine in our prefix; one DiP single-player title and one Denuvo title. | EA launch |
| U2 | Can the app-side LSX server on 127.0.0.1:3216 be reached from proot + Wine, and do games connect with our registry keys (Maxima #71)? | Spike with a Steam EA title already in the library | EA launch, Steam EA titles |
| U3 | What does EA's CDN serve for a current title: one ZIP, multiple, or delta? How do language selection and `installerdata.xml` work? | Maxima `content/` + `downloadUrl.gql`, one real download through the Rust engine | EA install |
| U4 | Does Connect accept a tree we downloaded once the `Installs` registry key is written, or does it re-verify or re-download? Does `connect-seed` work under FEX on a phone? | Spike with a small owned title (ubi-cli-mvp used Splinter Cell / product 109). #1445's registry work is the start | Ubisoft stage 2 |
| U5 | Does Ubisoft Connect (Qt + CEF) run acceptably under FEX in our prefix? How much RAM does it hold while a game runs? | Install it like Battle.net; measure with the Memory readout from #979 | Ubisoft stage 1 |
| U6 | Ubisoft's WebAuth page in an Android WebView: captcha, 2FA, `Ubi-Challenge` | Real sign-in on device | Ubisoft sign-in |
| U7 | Demux over TLS from Android (certificate pinning, patch-version negotiation drift) | Rust spike against `dmx.upc.ubisoft.com` | Ubisoft library and downloads |
| U8 | Ubisoft+ subscription entitlements in Demux ownership | Account with Ubisoft+ | Library completeness |
| U9 | EA Play subscription entitlements in `ownedGameProducts` | Account with EA Play | Library completeness |
| U10 | Ubisoft cloud saves outside Connect | Not researched; Connect handles them while it's in the path | Later |

## Plan

Each step is its own PR, tested on device as usual (REDMAGIC tablet, Fold7, Thor).

### EA

1. **Spike (no PR):** settle U1–U3 with throwaway code against one owned DiP title and one Steam EA title.
2. **Steam EA titles without the EA app:** the `link2ea` stub, app-side LSX server, OOA licence and
   entitlement refresh. It replaces #2036's EA app install where it works and falls back to it otherwise.
   This ships value first and proves the hard half.
3. **EA store: sign-in, library, shelves:** a `Store.EA` entry, OAuth flow, juno library, art,
   a "not installable here" flag for Javelin titles.
4. **EA downloads:** a Rust `ea` module (ZIP/Range/inflate on `fetch_core`), with Downloads progress,
   pause, resume, cancel, updates and uninstall.
5. **EA cloud saves** through `CloudSaves`.
6. **EA app fallback** prefix and store-row button, for pre-DiP and Battlelog titles.
7. Clean-room record: `docs/development/ea-cleanroom.md`.

### Ubisoft

1. **Spike (no PR):** U4, U5 and U7.
2. **Ubisoft Connect in its prefix (#979 level):** `droiddeck-ubisoft`, the store row, sign-in inside
   Connect, the installed-games scan (registry `Installs` keys), Play by `uplay://launch`, Connect closed
   once the game runs, install handed to Connect with indeterminate progress. Fold in #1445.
3. **Ubisoft store sign-in and library natively:** the WebAuth flow, session refresh, a Rust Demux
   client, the owned library and art, without opening Connect.
4. **Native Ubisoft downloads:** a Rust `ubisoft` module (manifest, slices, zstd), then hand off to
   Connect for registration (U4). Updates come from the manifest version.
5. Clean-room record: `docs/development/ubisoft-cleanroom.md`.

The two stores share almost nothing beyond the existing store layer, so they can run in parallel.
EA step 2 has the best ratio of value to risk and could start first.

## Risks

- **DRM circumvention.** Optima's launcherless mode replaces Ubisoft's DRM loader DLLs, answers the
  game's ownership calls itself, and installs a certificate mimicking Ubisoft's so Authenticode checks
  pass. Gating it on real ownership doesn't change that it bypasses Ubisoft's access control. Keep it
  out of DroidDeck. EA is different: Maxima requests real licences from EA's licence
  service and speak EA's documented-by-reversing launcher protocol. Nothing is forged.
- **Terms of service.** Both publishers' terms broadly forbid unofficial clients. No bans are known
  for Linux or alt-launcher users (Maxima, Lutris, unifideck, GameVault). This is the same position as
  our Epic, GOG and Amazon modules.
- **Vendor churn:** juno schema changes (EA already killed the Origin-era integrations once), Demux
  patch versions, and Connect or EA app updates that break under Proton. Plan for tests on recorded
  responses, as Epic has.
- **Memory.** A resident Connect costs a phone RAM, so reuse #979's close-on-launch policy and zRAM work
  (#1882). EA's native path avoids the client entirely.
- **FEX hardware fingerprint (EA, U1)**, if FEX's CPUID differs from what licences are bound to.
- **Kernel anti-cheat:** Javelin, and whatever Ubisoft titles use one. Flag them in the catalog
  instead of letting launches fail.

## Sources

- DroidDeck: #979, #1602, #1610, #1611, #1662, #2087 (Battle.net); #1449, #2036 (Steam Ubisoft/EA
  installers); #1445, #2050, #2286, #2292, #2295, #2317 (Ubisoft); `docs/development/epic-cleanroom.md`.
- Maxima: README, `maxima-lib/src/{core/launch.rs, core/endpoints.rs, ooa/mod.rs, core/auth/*, content/*, lsx/*}`, issues #49, #71.
- Optima: README, `TODO-hardening.md`, `src/install.rs`, `src/launch.rs`.
- ubi-cli-mvp: README, `docs/optima-comparison.md`, `research.md`, `validation.md`.
- unifideck: `docs/feasibility/{README,ea,battlenet}.md`, `docs/ubisoft-store-spec.md`.
- [Heroic: Ubisoft Connect on Linux and Mac](https://github.com/Heroic-Games-Launcher/HeroicGamesLauncher/wiki/How-to-install-Ubisoft-Connect-on-Linux-and-Mac).
- [GamingOnLinux: EA Javelin job listing mentions Linux and Proton (2026-03)](https://gamingonlinux.com/2026/03/ea-javelin-anticheat-job-listing-mentions-future-support-for-linux-and-proton).
- [GamingOnLinux: KYBER goes open source (2026-01)](https://www.gamingonlinux.com/2026/01/star-wars-battlefront-ii-private-server-and-mod-launcher-kyber-goes-open-source).
- [PC Gamer: Ubisoft's launcher broke Steam games on Linux and Steam Deck](https://www.pcgamer.com/ubisofts-launcher-broke-steam-games-on-linux-and-steam-deck).
