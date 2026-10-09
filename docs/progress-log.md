# DroidDeck - Progress Log

Running engineering log for the DroidDeck app (`com.droiddeck.launcher`; called SteamDeck,
`com.steamdeck.launcher`, until 2026-09-23 - entries below that date keep the old name). Newest state first, then
the timeline, then lessons and backlog. Companion to the README (what the app *does*) and to
`docs/releases/` (what each version said) - this is *how it got here and where it stands*.

---

## 2026-10-08 - `feat/stores`: GOG, Epic Games and Amazon Games in the launcher (in progress)

A new **Stores** section - the three storefronts' libraries and public catalogs, one download
queue for them, and every install registered in the Steam client exactly like an added folder.
Ported from Bannerlator's store clients (auth, API, library sync, download managers); its
Activities and XML are not ported, the UI is Compose, after the approved preview. The native
download engine is the Rust side's work (`libdroiddeckstores.so`, loaded lazily; the app runs
without it and Setup says "Stores engine not built").

- **Where it lives.** Rail item `stores` under Games, gated by Setup › Stores › "Show Stores in
  the rail" (`SessionPrefs.gameStoresEnabled`, off by default); a count badge while anything is
  downloading. The page is full width: four chips (GOG · Epic · Amazon · Downloads, a signed-in
  dot per store) and a cog at the row's end whose popup holds the section's own settings - which
  tab a store opens on (`storesOpenTab`, Library by default; applied on chip tap, never while the
  user is switching tabs by hand) and the speed tier (`gameStoresSpeedTier`, Balanced / Fast /
  Max = 16 / 32 / 96 requests in flight). Setup repeats the rows under the gate. A signed-out store
  shows its sign-in card first. Every install is added to Steam - the user's call: "Add to Steam
  shouldn't be optional, automatic" - so the switch an earlier build had (`gameStoresAddToSteam`,
  the sidecar's `addToSteam`, the page's "Add to Steam" button) is gone; an older sidecar's field
  is read past and ignored, and the game page shows a passive "In Steam" chip. The Games hero's
  eyebrow for a never-played store game reads "Games storage" (the chip names the store), and
  "Custom game" for a hand-added folder; Steam titles keep their library label.
- **A store game is an added game.** The install lands at `<Games storage>/Games/<Store>/<title>`
  (the SD library's folder when one is chosen, else the runtime's own `/root/Games/Stores`) with a
  `.droiddeck-store.json` sidecar (store, id, title, exe, launcher, args, env, version,
  addToSteam, art). `AddedGames.scan` now walks those folders too and `scanGame` reads the sidecar
  for the exe and the name, so the game goes down the existing path: `session/added-games.json`
  → `droiddeck-steam-shortcuts` at the client's next start. The listing is rewritten at install
  time, and with a client running the shortcut is added live over the client's DevTools port
  (`SteamClient.Apps.AddShortcut` + our tag + the compat tool; `SteamLiveShortcuts`), best-effort.
  Uninstall removes the folder, the sidecar and the shortcut.
- **Launch.** A shortcut can only name an exe and the listing carries no launch options, so a game
  that needs arguments or environment gets a `.droiddeck-launch.bat` beside it (Proton's steam.exe
  shim hands it to Wine's cmd): Epic's identity arguments, Amazon's FuelPump variables. Epic's
  per-launch exchange code is minted by the app right before a launch into `.droiddeck-epic-code`,
  which the script reads once and deletes; with no code the game starts in its offline identity
  mode. The EOS overlay is never provisioned.
- **Games tab badges.** `Library.SteamGame.source` (steam / gog / epic / amazon / added, from the
  sidecar) with a coloured chip on every row and in the hero eyebrow; `LibraryCache` keeps it.
- **Downloads.** `DownloadQueue` runs 1-3 at a time, stages Manifest → Download → Verify →
  Install, pause = stop and keep the files (every engine skips verified files on the rerun),
  cancel deletes; `StoreDownloadService` keeps the process alive meanwhile; the page shows speed,
  ETA, the tier, downloads-at-a-time and the engine log.
- Tests: `StoreGameSidecarTest`, `AddedGamesStoreScanTest` (JVM), `tools/tests/test_store_shortcuts.py`
  (the writer with a launcher .bat as Exe, the appid the app computes, removal).
- Files: `stores/*` (Store, StoreGameSidecar, StoreInstallRoot, StoreInstalls, StoreLaunch,
  StoreAccounts, StoresState, StoresNative, SteamLiveShortcuts, download/*), `ui/StoresPage.kt`,
  `ui/StoresDetail.kt`, `ui/StoresDownloads.kt`, `ui/StoreChips.kt`, `ui/StoresWidgets.kt`;
  `frontend/AddedGames.kt`, `frontend/Library.kt`, `frontend/LibraryCache.kt`,
  `ui/FrontEndRail.kt`, `ui/FrontEndContent.kt`, `ui/FrontEndSetup.kt`, `ui/FrontEndGames.kt`,
  `ui/FrontEndScreen.kt`, `MainActivity.kt`, `session/SessionPrefs.kt`, `AndroidManifest.xml`
  (the download service), `res/values/strings.xml`.
- **The three stores** (`stores/gog`, `stores/epic`, `stores/amazon`), ported from Bannerlator's
  clients with their storage replaced: GOG's OAuth page (Galaxy identity, a browser identity for
  the social-login hop), library from `embed.gog.com` + `api.gog.com`, shelves from
  `catalog.gog.com`, gen2 chunks / gen1 ranges / plain installer; Epic's web login (the code off
  the redirect's JSON page), library service + catalog, the store's GraphQL and the free-games
  feed, ChunksV4 manifests with install tags and a delta pass, the manifest's launch exe; Amazon's
  PKCE device sign-in, entitlements, `manifest.proto` (the app's xz library), SHA-256 per file,
  `fuel.json` / exe scoring, FuelPump variables; Amazon has no public catalog, so its Store tab is
  cut from the account's own games. Each manager hands the byte-fetch loop to the native engine
  when `libdroiddeckstores.so` is there (`GogNative` / `EpicNative` / `AmazonNative`, JNI.md's
  names and signatures) and runs its Java pool otherwise.
- Left out on purpose: GOG DLC installs and GOG redistributable installs (Steam seeds the shared
  redistributables in the prefix as for any shortcut), Epic's Denuvo ownership token and overlay,
  Amazon's SDK DLL deployment into the prefix (the prefix does not exist before the first launch),
  cloud saves, and the stores' social tabs - none of them in the preview.
- **Card art (device feedback on `61a9a48`, GOG › Library washed out to white):** the GOG library
  had drawn the product's `images.background` - the store page's fade-out backdrop, white once its
  alpha is gone. The library now takes `api.gog.com/v2/games/<id>` `_links.galaxyBackgroundImage`
  (the Galaxy client's dark 16:9 library art) and `boxArtImage` (the vertical cover), cached with
  the sync (cache key bumped so old caches re-fetch); the catalog's `productId` filter was tried
  and ignored by the service, so it is one call per owned game. Epic cards prefer `DieselGameBox` /
  `DieselGameBoxTall` over the offer images; Amazon prefers `backgroundUrl2` and never the logo.
  Cards draw over a dark surface with a fade into the card at the foot. On install `StoreArt`
  places `cover.jpg`, `hero.jpg` and `header.jpg` in the game folder, re-encoded as opaque JPEGs
  over a dark ground, under the names `AddedGameArt` looks for - so the Games tab and the client's
  grid (through the listing) get real art for store games.
- **Device feedback on `9f5a2f3` (GOG › DOOM I Enhanced page):** (1) the pad lost focus when a
  card opened its page (the card left composition; the next press landed on the rail). The page
  now follows the Flathub store's pattern: a focus move is recorded on each change and
  `focusWithinFrames` lands it - the game page's main action (or its back link), the card the page
  was opened from on the way back, the first card after a tab or chip change (`firstTile`); the
  page is a `focusGroup`, so Left is the way to the rail. (2) GOG's account endpoints return
  template keys (`product_description_2015545325<br>product_feature_…`) for some products; the
  v2 record has the same keys for them. `cleanStoreText` drops those keys, strips tags, decodes
  entities; the library tries the product's lead, then v2 `overview`, then v2 `description`, and
  shows nothing when all are templates. Epic's and Amazon's text goes through the same cleaner.
- **Speed shown = install rate.** Device evidence (Epic › Metalstorm, 1.68 GB on Max to the SD
  card): the engine's windows burst at 0.4–20 MB/s with 0–3 of 20 requests in flight while the card
  writes at 26 MB/s - the fetchers wait on the write side, so the engine's `speedBps` jumped while
  the bar advanced at the write rate. `DownloadQueue` now measures the shown speed itself from the
  byte deltas (an exponential average over 3 s, sampled at most every 250 ms) and derives the ETA
  from it, for all three stores; the engine's own figure stays in its log lines, and the detail
  line under the bar has it stripped. The tier and downloads-at-a-time controls sit on one compact
  row, on the Downloads page and in the cog's popup.
- **Install location (user decision):** the default is the app's internal storage for all three
  stores, whatever the session's Game storage says. With a card in the device, Install asks
  "Install to" first (internal / the card, free space per target, pad-focusable, last pick as the
  default only). A card that is not the Steam library gets a bind of its own in the session
  (`/mnt/droiddeck-stores/<volume uuid>`; the library's own bind covers a library card, so installs
  already registered there keep their appid); every root is scanned and uninstall works for
  either. The Downloads
  row shows where a game is going; the game page names the
  place. Epic installs on a card keep their in-flight chunks in the app's cache: the engine's
  `chunkCacheDir` (fetch and the new `nativeAssemble`, which drops each chunk after its last use)
  and the manager's own loops read the same path; internal installs pass `""` as before. Cleaned on
  cancel-with-delete and uninstall.
- **Half-installed Epic game shown as Custom (device, Metalstorm on the SD card).** The folder had
  2.85 of 4.4 GB of files, a 0-byte file where the assembly stopped (19:15:10, four minutes after it
  began), 9.1 GB of `.chunks`, and no sidecar. The run stopped inside the assembly loop - before the
  `.chunks` removal and the sidecar, both of which only ran at the very end; the events log does
  not reach back far enough to say whether the process died or the loop threw. Either way three
  things made it silent: the sidecar was only written last, the queue lives in memory (a process
  death drops the row), and `AddedGames.scan` listed any sidecar-less folder under a store root as
  a Custom game. Now: the sidecar is written at the start in state `installing` and finished at the
  end; unfinished folders (installing, or no sidecar) are never scanned as games; the Stores card
  reads **Resume install**, which reuses the folder and its `.chunks`; launcher/sidecar write
  failures fail the download, art and Steam registration are best-effort. The 9.1 GB cache is
  whole 1 MiB chunk windows shared with files outside this device's install tags - the free-space
  check now counts cache and files; a successful run removes the cache (Kotlin loop and
  `nativeAssemble` both).
- **The cog popup is capped at 440 dp**, like the Install dialog.
- **Store cards are art + one line** (maintainer feedback): no source chip (the tab says the
  store), no "In library" chip, no action button. The title sits left; right-aligned is a check
  when installed, Resume, the download percentage, the install size, or Free / price / discount.
  A thin bar over the art while downloading. Install, Resume, Uninstall, Get and Buy live on the
  game page; A on a card opens it.
- **No log panel:** the Downloads page's engine log is gone. Store lines go to logcat and to
  app-private daily files (`filesDir/logs/stores/stores-<date>.log`, seven days, ~2 MB rolling to
  `.1`), pruned as the app starts; the session's Share logs zip includes them under `stores/`.
- **Store log lines are redacted at the one choke point** (`StoresState.logLine`, which the engines'
  lines reach too): every URL is cut to scheme, host and a plain first path segment (a GOG secure
  link's token sits in the path, so it goes), no query, fragment or userinfo; token-like values
  (`token`, `__token__`, `f_token`, `hdnts`, access/refresh/id tokens, `code=`) and Authorization /
  Cookie headers are blanked outside URLs too. `StoreLogTest` covers a GOG secure link and an Epic
  Akamai URL. The engine's own logcat lines (tag `EpicNative` etc.) are written on the native side.
- **GOG downloads use every CDN host:** the secure link lists several hosts; only the first was
  taken (`used=1/1 servers`). All are parsed now and kept as one set, refreshed together on
  expiry; the Java pool takes them in turn. The progress bars glide to each new value over 250 ms. With the engine's `0849a7c` the
  native start takes the whole set (`cdnBases`; the engine spreads its window across the hosts) and
  its `onBytes` - every 250 ms, including files still in flight - drives the bar and the speed, so a
  large file no longer moves the bar in one jump.
- **Cloud saves device-proven (AYANEO Pocket FIT, `6a175f1`, ELDERBORN on GOG):** the launch logged
  `cloud gog 1732383191 down result=ok files=5 bytes=237499 reason=downloaded` (template found) and
  the in-game slot shows the cloud save (Catacombs: Tutorial part 1, 00:08:40).
- **Every owned card shows its size** at the right of its title strip, in the title's type: GOG's from
  its catalog; Epic's (build manifest, the files this device installs) and Amazon's (download
  manifest) looked up once a card is on screen or close, three at a time in the background, kept in
  the store's prefs (`size_<id>`). Nothing shows until it is known; a marker (installed, Resume, a
  download's percent) keeps the slot.
- **Two tabs, Store and Library** (the All count mixed owned games with whatever shelves had loaded:
  GOG Library 33 / All 120): Library's dropdown filters All (every owned game, the default, the
  tab's count) or Installed, and the tab reads the filter ("Library (33)", "Installed (1)"); the old
  All view is gone. Amazon has Library alone. Search follows the filter; the shoulders cycle Store
  and Library. The dropdown opens under the Library tab, left edges aligned, as wide as its
  entries (at least the tab, at most 260 dp) - `AnchoredMenu(compact = true)`.
- **The Stores chip row paints nothing** (maintainer: a black box behind the chips): it sits over the
  page's own background; the content scrolling under it is clipped at its edge and faded there by
  the content's alpha once scrolled, never by a colour.
- **Pre-PR device checks pass (AYANEO Pocket FIT):** GOG install, launch and cloud saves down and up
  (the session-end upload included); Epic with EOS sign-in; Amazon (Dread Templar); an SD-card install
  (DOOM I Enhanced) launched from `/mnt/droiddeck-sd`.
- **Downloads page, icons and one Clear** (maintainer): the row actions are round icon buttons -
  pause, resume, play, retry, and a cancel that asks with a red delete and an undo - their labels as
  descriptions. Downloads under way sit on top; finished ones below a line whose X clears them all
  (rows only; a failed download's kept files stay for its game page's Clear), installed ones as a
  compact row with Play.
- **Saves menu without explanations:** Import / Export rows (Steam, Custom, GOG and Epic alike) lose
  their descriptive subtitles; only live values remain (last sync, "…", conflict count, the save
  folder and its size). The three strings are gone from every locale.
- **One place for a store game's saves:** a GOG or Epic game's hero button reads "Cloud saves" and
  opens its saves view, which now holds the Cloud saves switch, the sync status, Upload / Download
  and the conflict actions ("No cloud saves" when the store keeps none). The switch is gone from the
  cards; GOG has no card left, Epic's keeps sign-in, offline and Resolve Epic sign-in. Steam and
  Custom games keep "Manage saves". The view is laid out in full from the first frame - the switch (focused),
  Upload and Download with "…" until the cloud check is back - and only subtitles change after; a
  conflict turns the two actions into Keep local / Keep cloud in place.
- **Background downloads:** while a download runs the service holds a Wi-Fi lock and a partial wake
  lock (timed, renewed with the progress, released when nothing runs). The notification shows a
  real progress bar and "<game> · 42% · 1.2 GB/2.5 GB · 12.3 MB/s · 3 min" (the stage while
  installing), with Pause / Resume and a two-step Cancel (Delete / Keep, ten seconds); a paused
  download leaves a notice to resume from; "<game> installed" opens it in Games, "<game> download
  failed" opens Downloads. Notifications are asked for once on Android 13+. The Android 15 dataSync
  time limit (and its `onTimeout`) applies only to apps targeting 35+: this app targets 28 and
  compiles against 34, so there is nothing to handle yet.
- **Cloud uploads do not depend on the exit hook:** a dirty mark per game launch; uploaded on the
  first of exit request, session end, or the next app start (`trigger=exit|session-end|recovery`).
- **Cloud saves, device round 1 (ELDERBORN, `b323a0b`): nothing ran** - a GOG game without arguments
  gets no launcher .bat, Steam runs its exe, and `droiddeck-store-launch` only recognised the .bat.
  It now recognises the exe too (searching the folders above it for the sidecar), and every path
  logs a result line. A first launch has no prefix yet: the download is deferred until Proton makes
  it, without holding the launch. Uploads are now safe: none before a download has set this
  device's baseline, a file changed on both sides is a conflict left for Keep cloud / Keep local,
  and the cloud copy is backed up before an upload replaces it (`CloudPlan`, tests for each rule).
- **GOG and Epic cloud saves** (ported from Bannerlator's managers, device-proven there in August):
  down before every launch where the cloud copy is newer (the compat tool waits up to 15 s), up
  after the game exits (the compat tool waits for Proton for such a game, then asks without
  waiting), newest wins per file with an MD5 check, a backup of what a download overwrites (last
  three). The save folder comes from GOG's remote-config template or Epic's CloudSaveFolder,
  expanded inside the game's prefix (`CloudSavePaths`, Bannerlator's cases as tests). A "Cloud
  saves" switch on the GOG / Epic card, and cloud rows in Manage saves (last sync, Upload,
  Download, "No cloud saves"). Not yet device-tested.
- **Epic sign-in device-proven without the overlay (AYANEO Pocket FIT, `bf81104`, Metalstorm):** EOS's
  corrective-action browser flow → `droiddeck xdg-open: https://www.epicgames.com` → Chrome
  `epicgames.com/id/authorize?user_code=…` → approved → `[eos] got logged in`, product user id
  created, the game in its hangar at 92 fps. The EOS overlay crashed the game on every overlay-on
  launch, so it is removed (user decision): no overlay switch, no download, no `OverlayPath`; a
  launch removes a pointer an earlier build wrote, and the app deletes an earlier 656 MB download
  once. The work is parked on `park/epic-eos-overlay` (at `bf81104`). Kept: the browser hand-off,
  the Epic card (sign-in, offline, Resolve Epic sign-in) and `BL_DEBUG_BROWSER`.
- **A game launched from the Games tab with no session running sat at "Launching"** (device,
  2026-10-09-04-steam, and 2026-10-08-05 before it). The session put `steam://rungameid/<id>` on
  the client's command line; the client took it seconds before its interface was up (focus went to
  769 at 04:29:07, the launch at 04:29:09) and its launch stopped at `LaunchApp waiting for user
  response to ShowInterstitials` with nothing to answer - the next launch from inside Steam went
  straight through. Not store-specific: the 2026-10-08 cold launch of another shortcut stopped the
  same way. `droiddeck-session` now holds such a URL back and hands it over through the same
  `steam-game` request an in-session launch uses, once gamescope's focused app is the interface
  (769) and four seconds have passed, or after two minutes regardless. The `rc=139` at the end of
  both sessions is the client crashing in its own shutdown, after "Shutdown", unrelated to the
  launch. The shortcuts writer keeps a compat tool the user chose (`plan_mapping` skips an entry
  that is not one it set itself), and the live add sets one only for a new shortcut.
- **Why EOS's browser flow never reached Android:** the hand-off script was never in the APK. The
  build copies only `usr/local/bin/droiddeck-*`, `steam-compatibility` and `usr/bin/**` of the overlay
  into the assets, so `usr/local/lib/droiddeck/browser/xdg-open` was skipped, the session staged
  nothing, the wrapper did not put it on the PATH, and Wine's winebrowser ran the runtime's own
  `/usr/bin/xdg-open`, which has no browser to open in a Steam session. GE-Proton 11-7's winebrowser
  (Metalstorm's tool) is Wine's own - registry `Browsers` list, then `xdg-open` by name - with no
  steam://openurl route. The script is now `usr/local/bin/droiddeck-open-url`: named as Wine's
  browser in the prefix (`"Browsers"="/usr/local/bin/droiddeck-open-url,xdg-open"`, an absolute path
  that does not depend on Wine's PATH), linked as `xdg-open` first on the game's PATH, and reading
  the session channel from `/tmp/droiddeck-browser/launch-dir` when Wine does not pass
  `BL_LAUNCH_DIR` on. Whether EOS calls ShellExecute at all in its browser flow (rather than handing
  a verification URI and code to the game to show) is what the next run's `droiddeck xdg-open:` line,
  or `BL_DEBUG_BROWSER=1` in droiddeck-env (`WINEDEBUG=+shell,+winebrowser`), will show.
- **Resolve Epic sign-in** (the Epic card): opens Epic's account page behind its sign-in in Android's
  browser, where a pending corrective action (privacy policy, EULA) is presented.
- **Epic overlay off by default** (device A/B, Metalstorm: overlay pointer set → exits while
  loading, twice; off → signs in to its Starform account and runs at 118 fps). `EpicOptions.overlay`
  defaults to false; the sidecar's `epic` block carries `"v": 2`, and an older block (the old default
  wrote `"overlay": true` unasked) reads as off - in the app and in `droiddeck-store-launch` alike.
  The 656 MB overlay is fetched only for a game that turns it on (the card's switch, or a launch of
  such a game), no longer after every library sync.
- **The card's switches are saved where both launch paths read them:** each toggle reads the
  sidecar fresh from disk, writes it and reads it back (`StoreGameSidecar.updateEpic`), and the card
  shows what was read back; the app logs `epic options <id> eos= offline= overlay=`.
- **Registry lines that a running wineserver would drop:** `droiddeck-store-launch` waits up to 8 s
  for a wineserver still holding the prefix to exit before appending to `user.reg` (it rewrites the
  file from memory when it exits, which is how `OverlayPath` vanished), reads the value back, and
  logs `overlay-set`, `overlay-off`, `overlay-not-written` or `overlay-still-set`, plus
  `server-running` when one never left. The helper's docstring is raw (no SyntaxWarning).
- **Browser hand-off diagnostics:** the game-PATH `xdg-open` logs every call as `scheme://host`
  only and accepts `steam://openurl/<address>`. On the device it was never called while EOS waited
  on its browser flow, so how EOS opens the browser under this Proton is still to be seen in the
  next log.
- **The exchange code no longer reaches the game's environment** (device: Metalstorm's Player.log
  dumped `DD_AUTH: … -AUTH_PASSWORD=<code>`). The launcher read the code into a variable the game
  inherited. It now clears the variable on the same line that starts the game - cmd has expanded it
  there already - so the code is on the command line only; the variable has a plain name. An old
  launcher is rewritten before the next Epic launch (`StoreLaunch.refreshLauncher`, from both the
  app and the Steam-launch request).
- **A store game page shows no description that is only its title again** (Metalstorm's).
- **Epic launch card** (Games tab › Launch settings, Epic games only): "Epic sign-in (EOS)" on,
  "Launch offline" off, "Epic overlay" on, stored in the game's sidecar (`"epic": {"eos", "offline",
  "overlay"}`, kept across updates) so both launch paths honour them alike - the app's
  `StoreLaunch.epicCode` and `droiddeck-store-launch` for a launch from the Steam client: no code
  when sign-in is off or offline, the overlay pointer written or removed per the switch. The card
  reads e.g. "EOS · Overlay". Bannerlator's forced ownership token (`-epicovt`) is not ported, so
  there is no switch for it.
- **EOS overlay** (device: Metalstorm's EOS grant answered `corrective_action_required`, the overlay
  was not configured, and EOS fell back to a browser flow that never finished). Epic's own overlay
  component ("EpicOnlineServicesOverlay", the mechanism from Legendary's `lfs/eos.py`, credits in
  `EpicOverlay.kt`) is downloaded once, with the Epic account's token, to
  `/root/.local/share/droiddeck/epic-overlay` in the runtime - after an Epic library sync, or at the
  first launch that finds it missing. `droiddeck-store-launch` then writes the one pointer,
  `HKCU\Software\Epic Games\EOS` `OverlayPath` = that folder through Z:, into the game's prefix
  (`STEAM_COMPAT_DATA_PATH/pfx/user.reg`, appended as DirectAudio's key is; a prefix Proton has not
  created yet gets it on the next launch). Logged as `epic overlay installed=<build> prefix=<appid>`.
  A game's web links now open on Android: Wine's browser is set to xdg-open, and on a game's PATH
  `droiddeck-open-url` hands http(s) addresses to the app, which opens them
  with Android's browser - so a device-code sign-in can be finished on the phone.
- **Epic sign-in from Steam's own Play button (device, Metalstorm "Guest Account"):** the game was
  started from the Steam client, so the app never minted its exchange code - the code was only
  written when a launch began in the app. Now the compat tool (`droiddeck-proton` and
  `droiddeck-proton-wrap`, `steam-compatibility`) runs `droiddeck-store-launch` on every real launch:
  it recognises the store launcher among Steam's arguments, and for an Epic game asks the app
  through `<session>/stores/req|resp` and waits up to 5 s; the app (`StoreLaunchRequests` →
  `StoreLaunch.epicCode`) refreshes the token, mints the code and writes it into the game's own
  folder - the code never crosses the channel or the log. No answer: the game starts with its
  offline identity. A code file older than five minutes is dropped first. Every attempt logs
  `epic launch id=<app> code=yes|no reason=<ok|signed-out|sign-in-expired|exchange-failed|timeout|…>`;
  a sign-in that ran out dims the Epic chip and shows the sign-in card. The Games tab and Stores
  still prepare a code before they launch; the compat tool's request is the one every path shares.
- **Amazon has Installed and Library only:** with no public catalog its Store tab repeated the
  library (Trending = Your library) and All equalled Library. The open-on Store choice falls back to
  Library for Amazon. Amazon's library API carries no install size, so a size shows only once a
  game has been installed (the manifest gives it then); no placeholder otherwise.
- **Shelves are titles only** (no "Just landed" / "On sale this week" lines); the chip row sits on
  the page's own ground with room under it, and what scrolls passes beneath it clipped, with a short
  fade where it meets the row.
- **Show mature content** (Setup › Stores and the cog; off by default): the storefront's shelves
  and search leave out titles the store itself rates or tags as adult; Library, Installed and owned
  games always show, and a title without rating data stays. GOG: `ratings[].ageRating` 17+ (ESRB M
  17, PEGI / USK / GOG 18) or the tags `mature`, `nsfw`, `sexual-content`, `nudity` - the catalog
  API has no server-side filter for either, so it is client-side. Epic: its public store GraphQL
  carries no age rating on an offer (no age-gating or rating field on CatalogOffer or StoreConfig;
  introspection is off), so only an adult content tag would mark one, and none of the probed
  titles have one - Epic's storefront is effectively unfiltered. Amazon's Store tab is the user's
  own games.
- **UI slow while downloading (device):** engines report per chunk or file, and each report
  published a new queue that the whole front end read (the rail badge was computed from it in the
  root composition). Now progress-only changes reach the UI at most four times a second (stage and
  state changes at once), each row and card reads only its own entry, the list replaces itself only
  when rows come, go or change state, the badge count changes only when it changes, and the
  notification redraws at most once a second. Download threads run at background priority (the
  queue's runner and every Java pool), the tier leaves two cores free (`processWorkers` capped at
  cores - 2, at least 2), and the stores log is written in half-second batches. The native engine's
  own threads need the same priority on its side.
- **Installed tab empty under "Installed (2)" (device):** the count came from the installs on disk,
  the grid from the library filtered by id. Both now come from `installedCards`: each install joined
  to its library item by id (or title), else a card from the sidecar's title and art.
- **GOG rows (device, DOOM + DOOM II):** bytes read "0 B" instead of blank; GOG and Amazon report
  their size on disk from the manifest; their Install stage counts its finishing steps (record and
  launcher, art, Steam) instead of sitting empty - GOG writes files in place while downloading, so
  there is no file move to count. A stage a store skips reads complete once a later one starts.
  The tier's window does reach GOG's engine (Max = 96 as its ceiling); the plan line now says so
  (`gog: plan … window=96 … tier=max`). The per-host spread is the engine's and was not changed.
- **Stages named for what they do; both sizes; Cancel deletes.** Epic's check of the files already
  there is part of Manifest ("Checking 120/515"), never a 100% Verify before anything is fetched.
  Install has its own write rate and ETA from the bytes the assembler writes, and files n/N. The
  row shows the download (compressed) as "1.6 GB / 2.5 GB" and the size on disk beside the
  location; Epic remembers the disk size for the card and page. Cancel asks twice ("Delete
  download?") and removes everything the install wrote - folder, sidecar, `.chunks`, scratch,
  `.gog_chunks` - except a finished install being repaired, which keeps itself; the row says
  Cancelled for three seconds, then goes, and the game reads Install. Pause keeps everything. A
  failed row reads "Failed · 2.1 GB kept" (the reason is in the log) with Resume and Clear (Clear
  deletes like Cancel); the game page offers Resume install and Clear for an unfinished install.
  Library sync is a thin bar and "25/55", no sentence.
- **Per-stage progress** (device: Epic DOOMBLADE, 13 GB, ~3 min of assembly with a still bar). The
  entry carries the active stage's own count (amount + files) and which stages are passed; the
  Downloads row draws one segment per stage (Manifest, Download, Verify, Install), and its line
  reads e.g. "Installing 42% · 120/515 files" (speed and ETA only during Download). Fed by Epic's
  verify pass, its native and fallback assembly, and GOG's chunk-cache removal; Amazon writes while
  it downloads and has no long stage after it. Leaving Download clears the measured speed. Cards
  and the game page follow the active stage's percent.
- **The art is the card** (maintainer feedback): a store card is its art edge to edge, sharp above
  and, under the one line, a blurred and darkened copy of the same picture (blur on API 31+, a
  stronger scrim below it). A Downloads row is the game's wide art cropped to the row with a dark
  wash from the left, the copy in white there and the actions on the right; no thumbnail.
- **Install dialog:** one plain line "Install <game>:", then one row per place (icon, name, free
  space), Cancel small in the title line; 440 dp wide.
- **No flavor text** (standing rule from the maintainer): Setup › Stores and the cog popup keep
  only control labels; the download-controls caption, the Downloads footnote, the sign-in blurbs
  and footnote, the engine note and the Install-to caption are gone; empty states read "No games",
  "Nothing installed", "No downloads", "No catalog". A Custom game's hero has no eyebrow (its chip
  says it).
- **Logos reverted** (user decision, no third-party marks): back to the text source chips and the
  coloured dots.
- **Device-proven on the AYANEO Pocket FIT, 2026-10-08 (GOG):** sign-in, the library (33 owned),
  DOOM I Enhanced installed through the GOG engine to the SD Games root, shown in Games with the
  GOG chip and real art, registered as a non-Steam game in the Steam client (grid art present), and
  launched from the Games tab through the shortcut and droiddeck-proton to the main menu at 143 fps.
- Status: CI green (build, JVM and python tests). Epic and Amazon are still unproven on device -
  their sign-in pages, an install, and for Epic the launcher .bat with the exchange code are the
  next things to prove on hardware.

## 2026-10-08 - `feat/directaudio-from-release`: DirectAudio from its own release, picked by interface, and the client gets the real engine

Three things were wrong with audio at once, and one tidy-up @xXJSONDeruloXx asked for.

- **Proton-CachyOS games never got DirectAudio.** Both CachyOS Protons we offer carry Wine's newer
  *system-thread* audio interface (`dlls/mmdevapi/unixlib.h` differs from Valve's); the one driver
  we shipped was built for the classic table, and the wrapper only asked "is this Wine 11?", which
  CachyOS passes. Now two driver sets ship (`linux-wine11` for Valve Proton 11 / Experimental /
  GE-Proton, `linux-wine11-systhread` for Proton-CachyOS) and `steam-compatibility` picks by what
  the Proton's own `winepulse.so` imports (`PsCreateSystemThread` only on the system-thread table) -
  never by the version string. No set for the interface, or no `winepulse.so`: DirectAudio stays
  off and Proton's audio runs. Confirmed on the FIT: Experimental 20260924 and GE-Proton11-7
  classic, cachyos-11.0-20261005-slr system-thread.
- **CachyOS also overrode the registry** (device log: `init_driver Loading driver list
  L"pulse,alsa"`): its launcher sets `WINE_AUDIO_DRIVER=pulse,alsa` (setdefault) and its mmdevapi
  reads that variable before the registry; Valve's and GE's mmdevapi ignore it. The wrapper now
  exports `WINE_AUDIO_DRIVER=directaudio,pulse` as well as writing the registry. On CachyOS that
  also means DirectAudio engages on a game's first launch, no registry value needed.
- **Some games ran on PulseAudio while the log said DirectAudio.** Two prefixes on the FIT had the
  driver linked into `system32` but a pristine `user.reg` with no `Audio` value: Steam runs the
  compat tool several times per launch, and a wineserver from an earlier verb still holding the
  registry saves its copy over the line we appended. The wrapper now says so when a server is
  live. Two stronger fixes were tried on the device and withdrawn the same day: writing the value
  through the live server with a bare `wine reg add`, and having Proton create a missing prefix
  first (`proton run wineboot`) - the second Proton run inside Steam's launch sequence got the
  launch aborted (`KeyboardInterrupt` in Proton, games bounced back to their page, a half-made
  prefix). On Valve/GE a first launch therefore still takes DirectAudio from the second run.
- **The Steam client's own sound was "DirectAudio" in name only.** PulseAudio → sink → relay →
  AAudio is one hop more than a plain AAudio sink; the only gain was sharing the games' stream.
  The daemon runs on the Android side, so DirectAudio's engine can run *in* it:
  `module-directaudio-native-sink` (from the DirectAudio release) - the game driver's adaptive
  buffer, two-burst floor, primed ring with fades, route-change reopen, watchdog and limiter, one
  step from Android. It is now the only route: the *Steam client audio* choice is gone from the
  menu (the relay route was one hop more for no gain, and the old "Classic" sink is the automatic
  fallback). If the native sink cannot open its stream the daemon keeps running (`.nofail`) and
  `PulseAudioComponent.ensureClientSink` loads the plain AAudio sink instead, saying so in
  `audio.log`. The relay helper now starts only for games and the microphone.
- **Binaries out of tree** (@xXJSONDeruloXx): the driver sets, the relay helper and both sinks come
  from the pinned release in `tools/directaudio/release.env` (`directaudio-linux-v1.1.0`, four
  zips, sha256-checked) through `tools/directaudio/fetch.sh`, in CI and `build_local.sh`, like
  gamescope. Deleted: `app/src/main/assets/directaudio/` (now generated, ignored),
  `tools/directaudio-relay/`, `tools/aaudio-sink/module-directaudio-sink.c` + `da_relay_proto.h`.
  `tools/aaudio-sink` keeps only `module-aaudio-sink` (the fallback). The release carries
  MaxsTechReview's #338 fixes, credited there.
- Audio bundle stamp bumped so an installed app re-unpacks the modules.
- **Device-proven on the FIT, four cases:** the client's sink opened at 12 ms / 4 ms bursts with
  0 underruns after 10 s (`client sink: DirectAudio (module-directaudio-native-sink)`); FEZ on
  `cachyos-11.0-20261005-slr` took the system-thread set - Proton log `Loading driver list
  L"directaudio,pulse"` → `Selecting driver L"directaudio"`, relay `hello from FEZ.exe`, 12 ms;
  FEZ on Proton Experimental 20260924 on `linux-wine11`, relay hello, 12 ms (no regression);
  Curse of the Dead Gods on CachyOS from a cold prefix - DirectAudio on its first launch, one
  early xrun and the buffer grew 12 → 16 ms. Open: a Thor (20 ms bursts). An added game (Noita,
  a Winlator-era folder never launched here before) bounced on launch with and without this
  branch's wrapper steps, before Proton wrote a log - its own issue.

## 2026-09-29 - `feat/controller-input`: the pad the way SteamOS has it

Device-tested on the AYN Thor (Katamari under Proton Experimental ARM64).

- **Why**: the client read the pad as an Xbox 360 controller, and games read the *same* node
  wearing Steam Input's virtual identity (28de:11ff), because the client's own virtual pad needs
  `/dev/uinput` and never appeared. Steam Input's layouts therefore never reached a game, and the
  QAM was a timed Guide+A chord. InputPlumber itself cannot run in the sandbox (root, uinput, uhid,
  udev, D-Bus), so what it provides is emulated in the interposer instead.
- **`/dev/uinput` stand-in** (`FAKE_EVDEV_UINPUT=1`, libfakeinput): a gamepad the client makes
  becomes `/dev/input/event16+`, backed by a ring of the app's own format, with the client's name,
  ids and bits; its writes are that ring's events. Other devices (virtual keyboard and mouse) are
  accepted and dropped. `inputudev.c` describes these nodes to Wine's HID bus from a `.uevent` file
  beside each one, and while one exists describes the app's pads as the Xbox 360 pad they are, so
  Wine reads only Steam Input's output. Verified: the client makes "Microsoft X-Box 360 pad 0",
  re-makes it at game launch, and the game binds it (`Controller 0 uses xinput : true`).
- **Steam Deck controller** (`FAKE_EVDEV_DECK=1`, Steam sessions): `/dev/hidraw16`, found through a
  sysfs tree `SteamDeckPad.kt` binds in (usb_device → interface 2 → hid 28de:1205 → hidraw), streams
  the Deck's 64-byte state report every 4 ms from ring 0 and answers feature reports as InputPlumber
  does. systemd 261's libudev refuses a syspath not on sysfs, so `fstatfs`/`statfs` report sysfs
  for that tree. The app's evdev nodes are withdrawn while it is on, and only the `steam` process
  sees the Deck. QAM is a real button (snapshot bit 11). Verified: the client lists a Steam Deck
  Controller (V1 HID protocol) and runs its handshake (0x83, 0xAE, 0x81/0x87 lizard off, 0x8F).
- **Setting**: Steam page → Touch & controls → **Controller**: *Steam Deck controller* (default) or
  *Xbox 360 controller* (the pad of earlier versions, QAM by Guide+A; games still get Steam Input's
  virtual pad). Debug switches: `droiddeck-no-uinput` (back to the 28de:11ff disguise),
  `droiddeck-no-deck-pad` (forces the Xbox 360 pad).
- **Gyro**: `PadMotion.kt` feeds the handheld's own gyro and accelerometer (4 ms sampling, while
  the session is on screen) into an IMU block after ring 0's events, turned to the screen and then
  to the Deck's axes and units; the Deck report carries them. Verified: Steam's Gyro Calibration
  page moves with the Thor (sh5001 IMU); at rest the accelerometer reads 1 g.
- **Deck grips + trackpads on the second screen** (`DeckControlsPanel.kt`, offered while the pad
  is a Deck controller): tabs for the four grips (2x2), either trackpad with a click bar, and both
  trackpads over the grips in a row; a second finger on a trackpad clicks it. True black for OLED.
  Grips, pad positions, touch, click and pressure go through the same block after ring 0's events
  as the gyro (`DeckControls.kt`) into the Deck report.
- **Not yet**: a Bluetooth pad's own IMU (DualSense); the
  game's rumble on the virtual pad goes straight to the vibrator rather than back through the client.
- **Prior art checked**: WinNative and Bannerlator never emulated uinput or hidraw; Bannerlator's
  `-steamdeck` mode stopped at "the virtual pad never arrives", which is this missing uinput.

## 2026-09-25 - main `8cdedbe`: melonDS out of the box, touch in Big Picture, log privacy, Decky

State: **main = `8cdedbe`** (PR #22 merge). Device-tested on the AYANEO Pocket FIT (repacked test builds)
unless marked. gamescope now staged from release **`gamescope-3.16.29-p3`**.

- **PR #18 → `9369dc3`** (`feat/melonds-preset`) - melonDS and the session:
  - **melonDS preset** (`bannerlator-pad-defaults`): the top screen as big as the panel allows with the bottom
    screen alone beside it, centred (Horizontal + Emphasize top; Hybrid repeats the top screen), sharp pixels;
    full screen from the front end (`-f`) at the panel's own resolution; the guide button toggles full
    screen, leaving to a 1x-wide 16:9 window whose menus gamescope stretches to a usable size; DS/DSi BIOS,
    firmware and NAND found in `ROMs/nds/{bios,firmware}` (BIOS by checksum). Existing installs moved once.
  - **Desktop**: `QT_QPA_PLATFORM="wayland;xcb"` (the melonDS AppImage ships only xcb and exited at once);
    a labwc window rule opens melonDS at 800x600, centred.
  - **HUD** follows the game window, not gamescope's 1x1 cursor or Qt menus (read 0 fps at 60).
  - **Cursor**: hides on pad / on-screen-control input (Bannerlator's 1.2 s rule), and shows the program's own
    shape - labwc's resize arrows, I-beams, a hide (Bannerlator's `wl_pointer.set_cursor` port; new here:
    labwc's GPU cursor is a UBWC dma-buf, read back through `vkp_image_readback`).
  - **Steam desktop switching**: "Steam Desktop UI" opens the desktop with Steam's desktop client in it (under
    gamescope the client picks Big Picture regardless); the drawer's **DESKTOP** button does it from Big
    Picture; relaunches restart the singleTop activity in place (`startActivity` from it was swallowed).
    Big Picture's own Power → Switch to Desktop still hangs: it waits on SteamOS Manager before its
    `steamos-session-select` fallback (stub included; a session bus did not help - tried and reverted).
- **PR #19 → `ce0da01`** (`fix/log-privacy`) - no credentials or identities in session logs: the device's
  public addresses (the client's IPv6 check logs "external address" ~20x a session), `network.txt` addresses
  as their kind and the MAC masked, every Steam AccountName/PersonaName (plain account names leaked in the
  UI log's login lines), and every text file scrubbed at session end and in the share-logs zip. Verified:
  0 hits across 83 session folders.
- **PR #16 → `5270574`** (Decky Loader, Kurt) - merged with the Steam CEF debugging marker tied to the
  supervisor toggle (it was created at install; on Android any app can reach 127.0.0.1:8080 - proven from
  another uid). Decky's own updater still points at the official project (no ARM64 build there).
- **PR #20 → `036317f`** (Kurt) - local builds carry the CI payload (NDK proot, patched gamescope).
- **PR #22 → `8cdedbe`** (`feat/steam-touch`) - touch in Big Picture: gamescope `0110` binds `wl_touch` in the
  nested backend and feeds wlserver's touch path (Big Picture sets Passthrough: tap and swipe rows as on a
  Deck); `0111` makes the nested pointer warp in Passthrough (touchpad mode: hover and tap-to-click work);
  the compositor's pointer fallback no longer jams on a lost finger-up.
- **In design, not built**: the drawer's page dots as QAM-style icons (Display / Controls / Settings), the
  selected one "stepping forward" in a line; mock `droiddeck-drawer-tabs-preview.html`.

## 2026-09-24 (afternoon) - main `94f9935`: PS1, controller focus, own signing key, CI hardening

State: **main = `94f9935`** (PR #41 merge), main build run 36036219740 - the first one signed with
DroidDeck's own key (`droiddeck-signed-standard`). No tag, no release since 0.1.6. Device-tested on
the AYANEO Pocket FIT unless marked. **main is now protected** (below).

- **PR #34 → `63d7051`, PR #38 → `c7998a8`** (`feat/duckstation`, `feat/duckstation-play`) - PS1 and the front end:
  - **DuckStation like ARMSX2**: Player 1 on the controller, BIOS from `ROMs/psx/bios`, `psx` as the game
    list, Vulkan, guide → pause menu, `-batch -bigpicture -fullscreen` from the rail. Crash Bandicoot and
    Tekken 3 at 60 fps from the desktop and from the front end.
  - **One tap to play**: DuckStation's and ARMSX2's first-time setup is answered for the user
    (`SetupWizardIncomplete = false`); ARMSX2 names its BIOS dump itself (`[Filenames] BIOS`, a US dump
    first, re-checked before every launch) - PCSX2 needs a named file, DuckStation finds one in its folder.
    DuckStation no longer warns it cannot inhibit a screensaver (no `org.freedesktop.ScreenSaver` here).
  - **One tile per disc**: a `.cue`/`.gdi`/`.m3u` hides the files it names (Tekken 3 showed 4×, one per
    `(Track N).bin`); `bios`/`firmware` folders are skipped (the BIOS showed as a game).
  - **Controller focus**: the rail item is outlined on open (an app starts in touch mode, where clickables
    refuse focus - the app now asks for keyboard mode first); Right enters a page on its main button,
    Left from the page's edge returns to the rail, Right again returns to the tile or button last used
    (explicit tracking - `FocusRequester.saveFocusedChild` restored into the grid, not the tile); a page
    opened from a tile takes focus on its main button (focus used to vanish with the tile and the next
    press landed on Steam); Down from a page's buttons goes to the first tile.
- **PR #41 → `94f9935`** (`feat/release-signing`) - signing, file access, hardening before going public:
  - **Own signing key** (RSA 4096, made on device by `tools/release/make-release-key.sh`, digest
    `b241ea7d…5d3f0` in `keystore/release-signer.sha256`). Every build before it - 0.1.6 included - was
    signed with the public AOSP testkey, so anyone could sign an "update" that installs over DroidDeck.
  - **Hand-over** (APK Signature Scheme v3 rotation): v1/v2 by the testkey, v3 by our key with a lineage
    testkey → ours, testkey rollback off, `--rotation-min-sdk-version 28`. Proven on the FIT with a throwaway
    key and package: a 0.1.6-style install updates and keeps its data; an apk signed with the testkey alone
    is refused; so is one with an attacker's own lineage from the testkey; an apk signed with our key alone
    (what MT Manager / APK Tool M make) installs over a hand-over install and fresh, not over a testkey one.
  - **CI**: `sign.yml` (reusable, a matrix job per package) runs after every non-pull-request build
    (environment `signing-main` on main, `signing-branch` elsewhere; the key is an environment secret).
    `release.yml` (dispatch, version) signs the exact Build APK artifact of a main commit as four packages:
    `com.droiddeck.launcher`, `com.tencent.ig`, `com.antutu.benchmark.full`, `com.ludashi.benchmark`
    (`tools/release/variants.txt`; the manifest rename also covers `.documents` and the `.home` task affinity).
  - **Documents provider** (`files/AppFilesProvider.kt`): the app's data folder in Android's file picker
    ("Open from" → DroidDeck, per-package title), confined to the data folder (a `../` id is refused, links
    out of it are hidden), and deleting never follows a link.
  - **Hardening**: pull requests build with `pull_request`, not `pull_request_target` (a pull request could
    write the caches main's signed builds restore); a build fails if a private key file is committed;
    `.gitignore` covers key files; **main is protected** - pull request + passing `build` + one approval,
    no force-push or deletion, the owner can override for his own pull requests. History scanned before
    going public: no secrets.
  - Max and Kurt get the key (jks, p12, pk8 + x509.pem, lineage) with a password and three plain guides
    (MT Manager / APK Tool M signing, the hand-over, how pull requests / test builds / releases work).
- **Also merged today** (Kurt): #20 Back shortcuts (session menu, Steam QAM), #24 build identity on the
  launcher, #33 per-launch Android display choice, #35 CI build-cleanliness snapshot, #37 the split-D logo,
  #40 faster held-stick navigation in the session drawer; (The412Banner) #32 stretch games to fill the
  screen, Quake-engine titles windowed at the session's size.

## 2026-09-24 - main `08a6769`: GPU desktop, emulators like PS3, ARMSX2, game art

State: **main = `08a6769`** (PR #29 merge), main build run 35983098806, staged as `Download/DroidDeck-main-08a6769(-Genshin).apk`. No tag, no release since 0.1.6. **Device-tested on the AYANEO Pocket FIT** (Adreno 750) unless marked.

- **PR #25 → `f6f7470`** (`feat/desktop-gpu`) - the desktop on the GPU:
  - **Patched wlroots** (`tools/wlroots`, release `wlroots-0.20.2-p1`, staged at `/usr/local/lib/droiddeck-wlroots`): a Vulkan DMA-BUF allocator for the KGSL stand-in node, which is not a DRM device - stock wlroots failed at `drmModeCreateLease` → "unable to create allocator"; linux-dmabuf skips its GEM import check on such a node. labwc's **vulkan** and **gles2** renderers start; **vulkan is the default**.
  - Compositor: a toplevel's **initial commit is answered with a configure** (labwc on vulkan waited for ever - wlroots moves its toplevel to a private queue after that commit); **`xdg_positioner` / `xdg_popup` / `wl_output` requests no longer abort** the app (NULL implementations; a gamescope beside labwc took the app down); without a Wine desktop a new window takes the keys.
  - Fallback: labwc that exits or does not answer its socket within 20 s → pixman, remembered per renderer + wlroots md5 + driver (`~/.droiddeck-renderer-failed`, cleared by choosing a renderer). On pixman, Game/Emulator menu entries run through `droiddeck-gpu` (their own gamescope on wayland-0).
  - Emulators: the pad is a plain Xbox 360 controller outside Steam (SDL ignores Steam's virtual gamepad outside Steam); `bannerlator-pad-defaults` seeds Player 1 per emulator (source-verified formats) and never overwrites a user's profile; big-core pinning (`program_cores`, game cores when chosen); rail programs use the desktop's Linux driver (one shader cache); RPCS3 moved once to `Async Recompiler (multi-threaded)` (no 6650-variant interpreter precompile each boot).
  - **PC keyboard** (drawer: Keyboard [Hardware] [Android]): Esc, F1-F12, editing block, modifiers (sticky), arrows; real evdev key presses; 47% of the screen.
  - Proven: Vulkan and gles2 desktops, Firefox on Vulkan, RPCS3 God of War II HD in a window and from the rail (pad bound, no precompile, cores 2-7), pixman fallback path, Steam session.
- **PR #26 → `6980428`** (`feat/armsx2`) - **PS2 is ARMSX2** (PCSX2 fork with ARM64 recompilers, GPL-3.0). Upstream PCSX2 only has `pcsx2/arm64/RecStubs.cpp`: NFS Underground 2 ran at 17 fps with one core at 100%; ARMSX2 runs it at **59.9 fps / 100%**, all recompilers + fastmem on under proot. PCSX2 left the front end and the catalog.
- **PR #29 → `08a6769`** (`feat/armsx2-polish`):
  - ARMSX2 from the rail: `-batch -bigpicture -fullscreen`; guide → pause menu → Close Game returns to the app. First run: PCSX2 settings + memory cards copied once; a new player gets ARMSX2's setup with the BIOS folder in `ROMs/ps2/bios` (or `ps2/firmware`), `ps2` as game list, Vulkan, controller on Player 1. Users bring their own BIOS and games.
  - Catalog `pages` - a file per memory page size; ARMSX2 4K + 16K.
  - On-screen controls: Auto for rail games and Steam when no controller, off on the desktop; setting in the drawer everywhere; drawer headed by the emulator for rail games.
  - Front end: Back/B steps back a level (emulator → Desktop, game → emulator) instead of leaving the app; games found up to 3 folders deep (`ps2/games/<game>/`); **box art** (libretro-thumbnails by name exact/loose, xlenore/ps2-covers by PS2 serial, GameTDB by GC/Wii ID, PS3 discs' own `ICON0.PNG`), kept in `files/covers`; wide art shown whole over a blurred copy; titles without dump tags.
- **winlator-contents** (the catalog): ARMSX2 `nightly-20260923-92aa2174da` mirrored (`armsx2.AppImage` 4K, `armsx2-16k.AppImage`), PCSX2 dropped from `desktop.json` (PRs #12, #13); the mirror workflow lists only the file being added (a re-run re-fetches and clobbers every listed file, and DuckStation's upstream link moves).
- **Not yet device-played**: DuckStation, Dolphin, Cemu, melonDS, PPSSPP, RetroArch (profiles + covers written from their sources). Known gap: a keyboard's Esc does not open ARMSX2's pause menu in a rail game (guide does).

## 2026-09-23 (evening) - renamed SteamDeck → DroidDeck

- **App name, app ID and code package**: DroidDeck, `com.droiddeck.launcher` (was `com.steamdeck.launcher`), sources under `app/src/main/java/com/droiddeck/launcher`, the 68 JNI functions renamed with them. A new app ID means DroidDeck installs **beside** SteamDeck, not over it: the runtime, the Steam sign-in and the settings start fresh.
- **Downloads**: session logs go to `Download/DroidDeck/`; the switch files are `droiddeck-env`, `-osc`, `-driver`, `-tu-debug`, `-no-pad`, `-pad-log`, `-no-hud`, `-wlr-renderer`. The old `steamdeck-*` names are not read.
- **Inside the runtime**: `droiddeck-desktop`, Firefox's `droiddeck.js`, `~/.droiddeck-desktop-debug`, `~/.cache/droiddeck`, the shortcuts tag `droiddeck-app`, `X-DroidDeck-AppId`, the `droiddeck-rumble` socket. "Desktop installed" now tests for `usr/bin/labwc` (it tested for the launcher, which the app stages itself).
- **Kept on purpose** - Valve's Steam Deck: the `-steamdeck` flag, `steamdeck_publicbeta`/`steamdeck_stable`, "Steam Deck mode", `steamdeck-packages.steamos.cloud`. And names inside the hosted desktop package: `steamdeck.png`, `steamdeck-steam(.desktop)`, `steamdeck-bigpicture.desktop` (renaming them means re-hosting it).
- CI artifact `droiddeck-apk`; a stray committed `__pycache__/*.pyc` removed.

## 2026-09-23 (afternoon) - main `90c7574`: first-run onboarding, Desktop & apps page, 4:3 display

State: **main = `90c7574`** (PR #14 merge), main build run 35895701784. No tag, no release; next version is still open. **Nothing below is device-tested** except the audio default (Kurt, AYN Thor).

- **PR #11 → `f79dc2d`** (`fix/directaudio-default`): the Steam client's audio is back on the 0.1.5 classic sink by default (`module-aaudio-classic-sink.so`, byte-identical to 0.1.5's arm64 module); DirectAudio is a choice in the Steam cog ("Steam client audio", pref `clientDirectAudio`, default off). Games keep DirectAudio on by default; mic on by default, RECORD_AUDIO asked once. Old armhf sink out of the bundle; the bundle re-unpacks when its contents change (stamp = BUNDLE_STAMP + CRC32 of pulseaudio.tzst). Relay logs a heartbeat line; Setup › Session logs › Share latest logs. Kurt on the Thor: classic "works perfectly".
- **PR #12 → `0ddb0be`** (`feat/play-installs-runtime`):
  - `586c9c2` Play and Desktop UI are enabled with no runtime; the session's loading screen downloads, checks and unpacks it ("downloading the Linux runtime · 332 of 791 MB"), then starts the session. A failure ends on the loading screen with the reason.
  - `f437957` Desktop does the same: the runtime if missing, then the `desktop` package from `desktop.json` (426 MB), then LXQt. Play, Desktop UI and Desktop all give the non-Adreno warning before any download (`MainActivity.startSession`).
  - `cbd3bc9` Desktop & apps is a page in the pane (pageKey `apps`), not a dialog: a rail-style dropdown per tier with an installed/total pill (Native ARM64 open), per-package Install/Remove, and the installing package's step line and bar in its own row. Leaving the page does not stop an install.
- **PR #14 → `90c7574`** (`fix/exact-panel-shape`), from a user report on a 4:3 handheld (RP Nova) - "the panel's shape" still gave 16:9:
  - Cause: `SessionActivity` sized the display at `maxOf(panel aspect, 16:9)` (the foldable guard), so on a 4:3 panel both choices were 16:9 with bars.
  - `9c47095` Shape › **Exactly this panel (4:3, 3:2…)** - the panel's own aspect, no floor. The other two unchanged; foldables still default to 16:9. In the cog and the in-session menu (`SessionPrefs.shapeChoices`).
  - `ad2f9f8` Resolution › **Custom…** per mode (`customRes.<mode>`, 320×240-3840×2160, evened) with 4:3 / 16:10 / 16:9 presets. It replaces the cap and the shape (Shape greys out); picking a cap clears it; the device report lists it. Not in the in-session menu.
  - Note: the branch was cut from `cbd3bc9`, not `0ddb0be` - same tree.
- Staged: `SteamDeck-play-installs-runtime-586c9c2.apk` (`21460acd…`), `…-f437957.apk` (`174e74a6…`), `SteamDeck-apps-page-cbd3bc9.apk` (`c2cfbebe…`), `SteamDeck-custom-res-ad2f9f8.apk` (`d02ee5ce…`, = main's content). HTML preview of the onboarding flow: `/sdcard/Download/SteamDeck-onboarding-preview.html`.
- CI note: a branch push does not build (`build.yml` = main + PRs); dispatch it with `gh workflow run build.yml --ref <branch>`. A newer dispatch on the same branch cancels the older run.
- Open: Kurt's PR #13 "Add Steam second-screen controls" (`steamdeck-second-screen`) - not reviewed; now needs to merge onto `90c7574`.
- **Next:** (1) fresh-install pass: uninstall, install, press Desktop first (runtime + desktop, ~1.2 GB), then Play with no download; (2) the Desktop & apps page on device (dropdowns, in-row bar, Back mid-install); (3) the Nova reporter tests Exactly this panel / Custom 960×720; (4) PR #13.

## 2026-09-23 - branch `feat/armada-and-lineage`

- Steam Deck mode: `-steamdeck` only; SteamOS helper stubs staged from the apk under `/usr/bin` and `/usr/bin/steamos-polkit-helpers` (the missing `steamos-update` there was the "Update Error" dialog; `jupiter-dock-updater --check` answers 7 so no dock firmware row); Deck mode defaults the `steamdeck_publicbeta` channel (on `publicbeta` every start reinstalled the client and lost the launch URL); client branch row in the Steam cog.
- QAM battery: `session/BatteryComponent.kt` writes BAT0/BAT1 from BatteryManager, bound over `/sys/class/power_supply`. Cloud saves checked on device: in sync, nothing was broken.
- Added games: any number of Games folders, each bound under `/root/Games`; one shortcut per subfolder in the client's `shortcuts.vdf` under the ARM64 Proton; exe choice per game; art from the folder, else Steam's store (capsule, header, hero, logo) copied into the client's grid.
- gamescope: the runtime's 3.16.29 rebuilt in CI (`tools/gamescope`, release `gamescope-3.16.29-p1`) with Armada's ARM64 client fixes, a realtime-queue switch and the gamepad cursor fix; staged over `/usr/local/bin`. Game windows forced fullscreen in Steam mode (a game came back in the top-left corner after the Steam menu).
- Audio: own PulseAudio sinks in `tools/aaudio-sink`, built into the bundle by CI. With DirectAudio on, the client's sound goes through the relay's shared ring (`module-directaudio-sink`, sink named DirectAudio); off, `module-aaudio-sink`. Inside proot AAudio only gave 20 ms bursts, the relay gets 4 ms.
- Winlator code rewritten or removed (SessionPart, HostEnvironment, HostProcess, PadState, cpp/framegen); rumble now served by `session/RumbleComponent.kt`.
- Front end shelf laid out whole so the d-pad reaches every tile.
- Hosted runtime and desktop packages untouched.

##  0.1.5 RELEASED 2026-09-23 03:17 - known-good point

- **Tag `0.1.5` = `d2f4a84`** (bump commit, versionCode 6), notes `docs/releases/0.1.5.md` on main after it. Private release [0.1.5](https://github.com/The412Banner/SteamDeck/releases/tag/0.1.5) and public [`SteamDeck-0.1.5`](https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.5) on winlator-contents, both Latest, asset `SteamDeck-0.1.5.apk` 21,093,959 bytes, sha256 `85b42954…`, run 35813465422. Staged as `/sdcard/Download/SteamDeck-0.1.5.apk`.
- What went in since 0.1.4 (58 commits): the motion front end, settings as pages with anchored menus, the session drawer, three themes (Paper default) and the new icon, run-as-a-game + libpci fix (117 fps Big Picture on a Fold), client-interface switches, FEX presets, 720p default, TZ, non-Adreno gate, 16 KB app libs, two on-screen sticks + bar-aware layout, folded categories at launch, Kurt's #3 (Back key) / #4 (icon) / #5 (local build helpers), pull-request CI + contributions ledger.
- Untested at release: sticks/bar layout, FEX presets, Deck mode, desktop → Steam hand-off, the `perf:` line. Rollback: `git reset --hard 8e58e8e` (0.1.4) + `SteamDeck-0.1.4.apk`.
- After the cut: the public release on winlator-contents got the private title, and that repo's README a SteamDeck section (latest release, `linuxfs.json`, `desktop.json`) - refresh both at every cut. Verified on the FIT's client binary: there is no `-steampal` flag; the Deck flags are `-steamdeck` / `-steamos3` (Deck mode on the Performance page), `-gamepadui`, `-steamos`.
- **Next, in order:** (1) device pass of 0.1.5 - sticks in a game and the bar layout on the Fold, the `perf:` line and 720p in a session log, a FEX preset, the desktop → Steam hand-off; (2) decide the stick click gesture (double tap or long press); (3) Kurt's draft PR #1 (Decky installer) builds when he marks it ready - it compiles against main, minus two stray `.kotlin` files; (4) rebuild the six PulseAudio prebuilts 16 KB-aligned; (5) optional FIFO/mailbox switch on the compositor's Vulkan present path; (6) the rename away from Valve marks is still open (icon is now the "A").

## 2026-09-22 (late) - motion front end + run-as-a-game, branch `feat/frontend-motion` (unmerged)

- Front end rebuilt around a motion system (`ui/FrontEndScreen.kt`, same state/actions API): the rail's selection is one pill that springs between rows; sub-lists unfold and stagger their children; a page change sinks out and cascades in over a blurred wash of the selection's art; tiles lift/ring/shine on focus and pop a play badge; the launch button sweeps a sheen and squashes on press; the session activity rises over the front end (`res/anim/session_*`). Durations follow the system animator scale.
- The session now runs as a game: manifest `appCategory=game` + `game_mode_config` (Performance mode welcome; the OS FPS cap and downscaling refused), sustained performance mode, the panel's fastest mode at its size, GameManager game state, and an ADPF hint session over the compositor thread fed with each presented frame's interval (`session/PerfMode.kt`, `session/PerfHints.kt`, `nativeCompositorTid`). One `perf:` line in the session log says what took on the device.
- App libraries link 16 KB-aligned. Still 4 KB-only (prebuilt, need a rebuild): libpulse, libpulseaudio, libpulsecommon-13.0, libpulsecore-13.0, libsndfile, libltdl.
- The session's display caps at 720p by default now, client and desktop alike (`SessionPrefs.defaultResolutionCap`); a cap the user chose still wins. The cog's list marks the default per mode.
- Builds: r1 `7ddfb57` (motion only), r2 `546c063` (+ run-as-a-game), r3 `d1ced87` (+ client 720p), r4 `fb2a3d4` (+ desktop 720p) - all CI-green, none device-tested. Staged as `SteamDeck-motion-r1..r4.apk`; r4 has everything.
- r5 `1ba803b` + TZ in the guest; r6 `39272cf` Setup folds, "Linux desktop environment", help link above the grid (r4 seen running on the FIT); r7 `af3f377` settings without a pop-up: the cog and Performance are pages in the pane with anchored menus under each value, frame generation / logs / offline open in place on the rail. **r7 merged fast-forward to `main` (`af3f377`) and main's auto-build staged as `SteamDeck-r52.apk`; no tag, no release.**
- r8 `90ff23a` (branch only, unmerged): the session drawer in the front end's dress with the same rows and menus (Now / Next session / leave), FEXCore presets carried over from Bannerlator (`core/FexPreset`, Steam settings page + drawer, FEX_* into the Steam session's environment; default = FEX defaults as before), file manager and picker locked to landscape.
- Collaborators: `maxjivi05` (push) and `xXJSONDeruloXx` (push, invited 2026-09-22) on the private repo.
- CI for pull requests (2026-09-23 early): `Build APK` runs on `pull_request_target` against main (a conflicting pull request never fires `pull_request`), checks the pull request out at its head with full history, merges main itself, and leaves one comment kept current: mergeable + built (apk on the run), or the conflicting files and hunks, or the compiler's errors from the kept Gradle log. Drafts wait. Fork pull requests may run without approval (repo setting). Proven with a throwaway pull request (#2, closed) both ways. `Contributions ledger` rewrites the README's contributors table on open/merge/close via `.github/scripts/contributions.sh`, committed to main by the Actions bot.

##  Checkpoint 2026-09-22 22:xx - known-good point for the front end

- **`main` @ `36cde7d`**, APK `SteamDeck-r51.apk` staged (sha `adb5acf5…`, run 35761437514) =
  frontend-r6 + docs. Same versionCode 5 / 0.1.4 inside; **0.1.5 not cut yet**.
- **Proven on the Pocket FIT this evening:** the front end renders and navigates (rail, art,
  square emulator icons, focus outline); RPCS3 lists Tomb Raider (ISO in a subfolder) and God of
  War II HD (its HDD);  God of War II HD boots the game under gamescope at ~40 fps
  (`session-20260922-133044`: `run: rpcs3.AppImage --no-gui …NPUA80491/USRDIR/EBOOT.BIN`, 399
  frames on screen in 10 s).
- **Not yet proven:** desktop→Steam hand-off after the three fixes (r47–r50);  FlatOut from the
  rail (Steam session with rungameid); Running tile; volume keys; automatic SD game storage
  (Install drive drop-down); Steam desktop-UI session; Tomb Raider ISO boot.
- **Open decision:** rename the app away from Valve's marks before it spreads (shortlist offered:
  Linuxlator / Pocketscope / Portascope); rename = label, icon text, `applicationId` (fresh
  install + runtime re-download), `Download/SteamDeck/` log folder, repo, release-tag pattern.
- **Rollback:** `git checkout 36cde7d` (or 0.1.4 tag `8e58e8e` for the last release), reinstall
  `SteamDeck-r51.apk` / `SteamDeck-0.1.4.apk` from Downloads.
- Loose ends: `ui/MainScreen.kt` keeps ConfirmDialog/CreditsDialog/EmulatorHelpDialog but its
  `MainScreen`/tiles are dead; worktree `~/steamdeck-frontend` still exists (branch merged).

## Current state (2026-09-22, evening)

- **`main` @ the front end merge** (`feat/frontend` fast-forwarded): the launcher main screen -
  Steam ▸ games, Desktop ▸ emulators ▸ games, Running tile, focus outline.  First emulator game
  proven from the app: God of War II HD in RPCS3 under gamescope at ~40 fps on the Pocket FIT.
- Since 0.1.4 on main, unreleased: volume keys to Android;  emulators under gamescope; ROMs chip
  + ? explainer; sysmem copy + ENOSYS hint; automatic game storage (a card = a Steam library);
  the client's games on the desktop with a `steam` shim; desktop→Steam hand-off (three causes
  fixed from FIT logs: Surface detach race, the replaced session's exit ending the new one,
  shared log folder); the front end. Next release = 0.1.5 once the hand-off is seen working.

## State at 0.1.4 (2026-09-22)

- **Latest release: 0.1.4** - private: https://github.com/The412Banner/SteamDeck/releases/tag/0.1.4
  · public: https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.4
  `main` @ `8e58e8e`, CI run 35682844106, versionCode 5, APK sha256 `4b53ce6a…` (20,957,823 B).
- **Runtime:** `linuxfs-r9` on winlator-contents (790 MB), the only runtime release left; desktop
  packages from `steamdeck-desktop-r1`. The app rewrites its own scripts (`bannerlator-*`), the
  driver, `bannerlator-netmanager` and the DirectAudio pieces into the installed runtime at every
  launch, so a fix in those reaches an installed runtime without a re-download.
- **Shipped feature set:** the native ARM64 Steam client under gamescope on the in-app Wayland
  compositor; a LXQt-on-labwc desktop with Firefox and a shelf of emulators; Proton tools
  (GE / CachyOS) as downloads; graphics drivers importable per mode; DirectAudio + microphone;
  client/game core masks + four session switches; NetworkManager stand-in (Max's); overlay
  restored; ROMs folder + Storage bound into the session's home; Bannerlator's File Manager;
  a cog per launch mode (resolution, shape, HDR10, drivers, touch, OSC/audio, renderer); per-session
  log folders that survive a crash; leftover-process sweep; frame generation (Win-FG, LSFG).
- **Verification level:** every release is CI-green and staged to the maintainer's device. Proven
  on hardware (Pocket FIT / Fold): sign-in, store, install + launch (FlatOut 144 Hz), frame
  generation, controllers + OSC, sound, desktop + Firefox, folding mid-session, driver import, the
  log folder, the ANR fix, the network page. **Not yet proven:** everything 0.1.4 added (ROMs
  folder inside an emulator, File Manager, resolution cap, HDR10, crash sweep, orphan sweep, the
  drawer's Steam menu), DirectAudio *voice*, the core masks' effect, offline start, the soft
  keyboard, any emulator with a game, Adreno 710.
- **Open field reports (Thor Pro, 8 Gen 2):** trackpad-mode tap does not click (code sends the
  click; cause not found by reading); crash after 1–2 min in Ballionaire / Geometry Wars (no log
  survived - 0.1.4's sweep will leave `crash.log`); a Fold's 10–20 fps client menus (Chromium →
  ANGLE → Zink on an experimental A8xx Turnip); Fold crash loop (xalia ENOSYS storm - switches
  shipped, untested); FlatOut shrinking after Resume (gamescope forcing the swapchain extent).

## Release pipeline (how every version is cut)
1. Work on `main`, pushed → `Build APK` runs `assembleRelease` (AOSP test key, v1+v2+v3 re-signed
   by CI with zipalign + apksigner) → artifact `steamdeck-apk`. Dev builds keep the last release's
   versionCode/versionName; a release bumps both in `app/build.gradle` in its own commit.
2. Verify: `gh run view <id> --json conclusion` (never trust a run listing's first row - a
   `workflow_dispatch` run gets cancelled by concurrency in favour of the push-triggered one),
   download the artifact, sha256 it.
3. Stage: `cp` to `/sdcard/Download/SteamDeck-rN.apk` (dev) or `SteamDeck-X.Y.Z.apk` (release).
   Never `pm install` - the maintainer installs.
4. Private release `X.Y.Z` targeting the **full 40-char sha** (a short sha is refused), notes from
   the draft in the session scratchpad, `--latest`. Public release `SteamDeck-X.Y.Z` on
   winlator-contents with the README-style notes; one public release per version.
5. README ledger, this log, memory.

## Timeline
- **2026-09-19 - 0.1 groundwork.** Lifted out of Bannerlator's gamescope runtime on the user's
  ask: one screen, one button. r2 reached Big Picture sign-in on the Pocket FIT. Package renamed to
  `com.steamdeck.launcher` (r4), foreground service, test key, on-screen controls.
- **2026-09-20 - 0.1.** The Linux Steam client and a desktop; runtime r6→r9 in a day (Proton
  self-selection, proot shipped, refresh rate, video, first run reaches a game). Public explainer
  page for non-Linux users.
- **2026-09-21 - 0.1.1.** Catch-up with Bannerlator: driver selection (two lists, per mode),
  overlay restored + GameHub force-stop, DirectAudio + microphone, core masks, four session
  switches as toggles, per-session log folders with credentials scrubbed, Adreno 710 path via
  Banners-Turnip r3.
- **2026-09-22 - 0.1.2.** The teardown ANR (log collection on the main thread) fixed; two
  compositor log lines (frame-size change, window rename); README ledger.
- **2026-09-22 - 0.1.3.** Max's NetworkManager stand-in ported (the client's network page); shim
  audit vs WinNative = zero functional drift.
- **2026-09-22 (later) - after 0.1.4, on main.** Volume keys;  emulators under gamescope (the
  desktop cannot: labwc on pixman offers no dma-buf); ROMs chip; sysmem warning + ENOSYS hint;
  automatic game storage; the client's games on the desktop (`steam` shim hands off to a
  gamescope session); the hand-off's three faults found in FIT logs and fixed; the front end
  merged (branch `feat/frontend`, r1–r6); God of War II HD booted in RPCS3 from the rail.
- **2026-09-22 - 0.1.4.** ROMs folder + Storage in the session's home; Bannerlator's File Manager
  ported whole; a cog per mode (railed settings window) with resolution cap, shape, HDR10 gated on
  the panel, drivers, touch, OSC/audio/renderer; two-column main menu; crash-safe log folders +
  Session logs toggle; OrphanReaper; drawer Steam menu. Public releases reorganised: one per
  version, old `Steamdeck` release and runtimes r1–r8 deleted.

## Architecture (where things live)
- `MainActivity` / `ui/MainScreen.kt` - the main screen; `ui/ModeSettingsDialog.kt` the cogs;
  `ui/*Dialog.kt` the rest.
- `SessionActivity` - the Surface, input (touch, touchpad, pads, keyboard), the drawer, the HDR
  decision, the compositor start (`wayland/CompositorHost`, `wayland/WaylandCompositor`).
- `session/SessionService` - proot + gamescope/labwc + PulseAudio + DirectAudio relay + network
  link; binds (`/root/Storage`, `/root/ROMs`); env for the guest; teardown; `OrphanReaper`;
  `SessionArtifacts` + `CrashHandler` + `SessionPaths` for the log folders; `SessionPrefs`.
- `files/` - Bannerlator's File Manager (`FileManagerScreen.kt` and its helpers), the picker
  activity and `InAppFilePicker` intent API.
- `gpu/` - Turnip (bionic, for the compositor) and Linux Turnip (glibc, for the runtime) managers.
- `tools/linuxfs/overlay/usr/local/bin/bannerlator-*` - the guest scripts, staged into the
  runtime by `SessionFiles` at every launch (CI packages only `bannerlator-*` names).
- `app/src/main/cpp/waylandcomp/` - the compositor (shared lineage with Bannerlator).

## Lessons learned (don't repeat these)
- Nothing in teardown may do bulk filesystem work on the main thread (0.1.1's ANR).
- A CI closure check on a multi-line variable must flatten it first (`libaaudio.so` refusal).
- A runtime script must be named `bannerlator-*` or CI never packages it.
- `gh release create --target` needs the full sha.
- A proot bind is invisible to a program's "Computer" list; put what users need under home.
- The compositor decides its driver and its HDR gate once per app process - anything that changes
  them applies after the app is fully closed, and the UI must say so.
- Bannerlator's File Manager ports mechanically (`port_fm.py` anchors) once the container hooks
  are cut; do not hand-edit 2,500 lines.
- Deleting a release asset on GitHub can drop a sibling asset - re-list and restore.
- A resource without an implementation makes libwayland abort on its first request - every
  interface the compositor hands out needs one, even as no-ops (positioner, popup, output).
- The KGSL stand-in answers no DRM ioctl: `drmIsMaster()` reads that as "master"; check
  `drmGetVersion()` before treating a GPU fd as DRM.
- Verify an emulator's config values from its source: RPCS3's GUI says "Async Shader Recompiler",
  the config value is "Async Recompiler (multi-threaded)".
- Upstream PCSX2 has no ARM64 recompiler; a correct ARM64 build of it is still interpreter-slow.
- An Android app starts in touch mode, where Compose clickables refuse focus: `requestFocus()` on open
  does nothing until the app asks for keyboard input mode.
- `grep -q` (or `head`) at the end of a pipe exits early; under `pipefail` that fails the whole line at
  random. Capture the output first, then search it.
- A reusable workflow gets no more permission than its caller gives, and sees environment secrets only
  with `secrets: inherit`. A workflow naming an environment that does not exist creates it unprotected.
- `pull_request_target` shares main's caches with the pull request's code - never with signed builds.
- apksigner prints signers as "Signer #1", "Signer (minSdkVersion=…)" or "V3.0 Signer:" by version.

## Backlog / next
- Play-test the other emulators one by one on the FIT: Dolphin, Cemu, melonDS, PPSSPP, RetroArch
  (pad profiles and covers exist; nothing played yet). DuckStation done 2026-09-24.
- **Kurt's PR #39 (Non-Launcher flavor) breaks signing as written**: it replaces the `droiddeck-apk`
  artifact with `droiddeck-home-apk` / `droiddeck-non-launcher-apk`, which `sign.yml` and `release.yml`
  read, and it conflicts with today's `build.yml`. Adapt before merging: sign both editions (a release
  would then be editions × packages).
- After the repo is public: add a required reviewer to the `release` environment (not allowed on a
  private repo on the Pro plan).
- First release with the new key (0.1.7): run `release.yml`, publish; 0.1.6 users move over by the
  hand-over.
- Keyboard Esc in a rail game does not reach ARMSX2's pause menu (guide does).
- 0.1.7 pre-release when ready (everything above is on main only).
- Pin a newer ARMSX2 nightly only after testing it (it changes daily).
- Device-prove 0.1.4 on the FIT (list above).
- Thor Pro: trackpad tap; the 1–2 min in-game crash once a `crash.log` arrives.
- Xfce as a second desktop shell (Max's branch runs XFCE 4.20 on labwc) - a catalog package + a
  shell choice in the Desktop cog; comfort, not performance.
- Quick Access Menu for non-Deck pads - Back double-press (500 ms) now routes to the existing Guide+A
  chord; the in-session menu, Steam settings, and Setup › Session can swap the single- and double-Back
  actions. Device confirmation is still needed.
- FlatOut shrink: try `vk_wsi_force_swapchain_to_current_extent=false` via `steamdeck-env`.
- Max's stricter `winnative-directaudio` guards; `winnative-epic-launch` (a feature).
- Rename before anything truly public: "SteamDeck" is Valve's mark. (Done 2026-09-23: DroidDeck.)
