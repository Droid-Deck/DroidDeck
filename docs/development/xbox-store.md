# Xbox / PC Game Pass on Thor

This integrates PC Microsoft Store MSIXVC packages,
not Xbox console emulation or UWP/APPX packages.

## Testing on Thor

1. Open DroidDeck, select Stores, then Xbox and Sign in. Use the Microsoft account
   that owns the game or has PC Game Pass access. An isolated Microsoft sign-in
   window opens; complete its verification and return to DroidDeck. On later
   starts, the saved account should restore without another sign-in prompt;
   initial account loading uses a neutral progress indicator.
2. Refresh the Xbox library. Check a known owned PC game and, if applicable, a
   Game Pass game. Subscription access is labeled PC Game Pass and does not
   become permanent ownership. Console-only and unsupported package types are
   filtered out. After the first successful sync, closing/reopening DroidDeck
   should restore the saved library without waiting for Microsoft. Metadata is
   scoped to account and market; on later starts, snapshots older than 15 minutes
   refresh quietly in the background. Refresh checks immediately.
   Downloading and launching still perform Microsoft licensing.
   A detail page can be opened from a store search result that is not in the
   library; it keeps the catalog metadata and, when the product's eligibility
   affirmations name a plan that includes PC games (PC Game Pass, Ultimate,
   Premium, Essential or Starter Edition), offers Install even before the title
   is in the entitlement list. The retired console-only plan does not offer a
   Windows install, and Microsoft licensing still decides at download time.
3. Start with a small licensed title such as LIMBO. Search `LIMBO` if needed;
   the verified US Product ID is `9PNG83MDD6SH`. Install through the normal store
   download queue. If an offer contains several PC base packages, choose the
   intended game. A package plan is not a license: Microsoft licensing must
   succeed before files can be installed.
4. Pause during transfer, resume, then repeat after closing/reopening DroidDeck.
   It should retain partial files, refresh authenticated URLs and resume only
   verified checkpoints. An error or cancelled process must never create an
   installed/Play entry. Cancel removes a new installation's partial files.
5. Press Play as soon as installation completes, including while the main library
   list is still refreshing. It should resolve the completed installation instead
   of showing the "Steam picks the game up when it next starts" toast.
   A launch taking over 20 seconds should not produce a duplicate Steam launch
   or a "Game already running" dialog while licensing is still preparing it.
   Steam/session startup uses the selected ARM64 Proton. Launch the installed
   Xbox game from either DroidDeck or Steam; both paths should prepare the same
   account-bound game service and licensed view while keeping account credentials
   inside the Android-owned backend.
   During preparation, opening the Xbox detail/content UI must remain responsive.
   Update / verify must report a problem if the game starts after its button was
   enabled, rather than crashing the app.
   First launch prepares that game's prefix. Check that a window appears, the
   game reaches its menu, controller buttons/axes work, and audio is audible.
   Record the selected Proton, game/version, and any startup failure.
6. For a game with Xbox identity/cloud saves, check the gamertag, save something,
   quit normally, wait for synchronization, then relaunch. Verify the save and,
   where available, compare it on another legitimate Xbox PC installation.
   In-game identity/cloud/multiplayer success is title-specific.
7. Close the game, then try Update / verify from its Xbox detail page. It uses
   fresh licensed streaming and the existing download queue. Interrupted updates
   remain resumable; do not judge an interrupted package as a complete install.
8. If you own related PC DLC, open Manage content. Only licensed, unambiguous
   MSIXVC content associated with this game's package family is offered. Install
   it, then relaunch and check that the game recognizes it. An account with no
   such content should get an empty-content message. Base-game uninstall also
   removes its managed content.
9. Stop a game and launch it again. A delayed release from the first launch must
   not clear the second launch's activity. Suspend/resume while playing; the
   launch lease should survive suspension. Stop the session during preparation
   and verify both backend processes exit and the host-owned launch directory
   under `files/xbox-runtime/views` is removed. A fresh app process recovers
   abandoned `launch-*` directories there before preparing another game;
   cleanup must preserve installed game files reached through view symlinks.
10. Sign out from Xbox. Installed files should remain, account library data should
   clear, and Play/download licensing should require sign-in again.

Do not uninstall DroidDeck or clear application data to troubleshoot this build.
The signed APK is installed in place to retain existing Steam and store data.

## Evidence and limits

Verified during development:

- Native AArch64 CLI execution on Thor's existing Linux runtime.
- Abstract-socket credential transport with a synthetic account on Thor; this
  does not establish a real Microsoft sign-in.
- Real Microsoft sign-in completed on Thor through the isolated Android host.
  Its credential file is an Android Keystore AES-GCM envelope.
- Signed-in library refresh on Thor returns 13 compatible PC titles. RFC 3339
  offset timestamps from the backend are parsed by Android's offset-date parser.
- Two force-stop/reopen tests restored all 13 titles from the saved library
  without a native Xbox account request. Cache tests cover account/market
  isolation, explicit and stale refreshes, sign-out removal and subscription
  metadata remaining distinct from purchases.
- Two further cold-start checks observed no sign-in prompt while the saved
  account loaded; both restored all 13 titles and the installed launcher.
- Licensed Minecraft Launcher package download and manifest-selected executable
  registration on Thor (`Minecraft.exe`, package version `2.6.2.0`). An initial
  Android DOM feature failure was fixed with Android's DTD-rejecting pull parser;
  retry reused the downloaded files. This launcher install does not validate
  gameplay.
- Public LIMBO package resolution on Thor: a Windows.Desktop MSIXVC package.
- Native AMD64 Windows module loading through Thor's ARM64 Proton/FEX, including
  the `InitializeApiImplEx2` Xbox runtime export. The original builtin markers
  caused error 126; the reproducible native-marker adaptation resolves that.
- Minecraft Launcher's licensed executable was materialized by Xodus, started
  through ARM64 Proton/FEX, completed its self-update, and rendered its full
  Windows launcher with a Microsoft account displayed on Thor. The account
  service remained running with the executable. Minecraft gameplay and in-game
  Xbox services remain unverified.
- Deep Rock Galactic's GDK executable also loads through Xodus/ARM64 Proton.
  Its default launch crashes in D3D12 ray-tracing pipeline creation. Disabling
  ray tracing in Unreal reaches the rendered title screen on Thor; this workaround
  is applied to `FSD-WinGDK-Shipping.exe`. Gameplay and online services remain
  unverified.
- Account-free tests cover purchase/subscription separation, stale/wrong-account
  responses, malformed parent licenses, manifest traversal/XXE rejection,
  fail-closed encrypted credential writes, download checkpoint behavior,
  private temporary launch views, bridge authentication, and ordinary store
  launch regressions.

Game rendering/input/audio, in-game account identity, cloud synchronization,
DLC activation and multiplayer require the account/game tests above. DLL
loading alone is not evidence of a playable game.
Native-module tests use a disposable prefix under
`/data/local/tmp/droiddeck-xbox-probe`; existing game prefixes are not used.

Build recipes, pinned source revisions, hashes, notices and runtime architecture
are documented in [tools/xbox](../../tools/xbox/README.md).
