# Discord UI and session reports, 2026-10-11

Sources: [bug thread](https://discord.com/channels/1552707793247539220/1558928314956120174), [general](https://discord.com/channels/1552707793247539220/1552707794430459916), and [development](https://discord.com/channels/1552707793247539220/1552751182165049435). Reviewed the post-overhaul discussion on October 11, including screenshot-only messages and linked reports.

Branch: `fix/discord-ui-session-reports`, based on `origin/main` at `0cafa83e`.
Device: AYN Thor, selected by the full advertised ADB serial.

## Changes

| Report | Change |
| --- | --- |
| Settings needs an X and a larger handle | Add a 44dp close target in the sheet's controller focus group; enlarge the visible handle to 32dp and its label to 14sp. |
| Settings has too little height | Raise the sheet from 82/360 to 60/360 of the pane while preserving clearance above the primary action. |
| Reopening during the closing animation breaks settings | Cancel the outstanding drag settlement when a new gesture or open/close transition takes ownership. |
| Store navigation returns to the left column | Route Up/Down by the actual adaptive column count and focus the corresponding card after lazy composition. Incomplete final rows do not wrap. Prefetched offscreen cards are scrolled into view before focus moves. |
| A selection stays as a dot | Unpinch an interrupted long chip jump during the next adjacent move. Cancel an obsolete focus-ring flight and its fill animation on focus loss. |
| Performance opens with focus on Steam | Wait for Back to attach and take focus after the outgoing page leaves. |
| Desktop settings navigation jumps | Do not apply the main page's Down-to-first-app shortcut to settings-sheet buttons. |
| Display-driver navigation loses focus | Wait for the chooser to disappear before focusing the selected driver; initialize controller focus when switching back from touch. |
| Stores can no longer be hidden | Restore a persisted Stores switch under Setup > Launcher; keep it enabled by default. |
| Stores engine is clipped in the settings panel | Bound popup height to the available screen space, expose that limit to its scrollable body, and allow tall popup content to scroll. |
| Wine Sync renders vertically | Stack its label above the full-width sync choices. |
| Profile picker leaves too little room for Proton lists | Replace the large profile group with a compact 44dp picker row. |
| Theme accent is inconsistent | Paper's signal color now follows its white primary color rather than retaining blue. Store brand colors and controller-button tint remain separate settings. |

| Phone sign-in buttons clipped ([development report](https://discord.com/channels/1552707793247539220/1552751182165049435/1558947589997469820)) | Size the sign-in card to the pane and its content instead of enforcing a 320dp minimum. Decorative layers follow the measured card. |
| Force SSBS hard to find behind empty scrolling ([general report](https://discord.com/channels/1552707793247539220/1552707794430459916/1558976573758251029)) | Place shared Proton options before installed/downloadable lists; start each component layer's detail at the top when switching layers. |

## Validation

The release APK was prepared with `tools/build_local.sh`, built with `assembleRelease`, signed with the normal release lineage, and installed with `adb install -r` over Thor's existing app.

16 focused unit tests passed: LiftSheetTest, StoreGridNavigationTest, GlideShapeTest, StoreTabsTest, AppScaleTest, and the Compose ComponentsPageProfileTest. The first unrestricted unit-test run ended because the Gradle test executor exited with code 2 without an individual test failure report; it is not counted as a passing full suite.

Thor checks include the close control, eight close/reopen swipe cycles, 21 rapid long/adjacent Setup tab switches, hiding and restoring Stores, Paper theme colors, controller entry into Performance (Back focused, then Down to the first setting), driver chooser handoff and Up/Down scrolling, and Desktop Files navigation (Open > Choose > Uninstall > Choose > Open). The new close control is reachable and operable with a controller. The newer Stores engine clipping, compact profile picker, and horizontal Wine Sync label were also checked on Thor. The original Graphite theme and existing session preferences were restored after checks.

Device screenshots and UI dumps are saved locally under `captures/discord-reports/` (ignored build evidence). The final signed APK is `app/build/outputs/apk/release/app-release-signed.apk`.

The first pass saw no populated authenticated store grid. During the follow-up, Epic and Amazon loaded their retained accounts and libraries (402 and 150 titles). On Epic, six controller Down moves and six Up moves stayed in the third column, including transitions to offscreen rows, and returned to the original card. This caught and fixed the case where a prefetched offscreen card accepted focus without scrolling. The completed trace is `captures/discord-reports/epic-grid-navigation.json`.

Follow-up validation used both Thor's normal display and a temporary 2400x1080 / 540dpi phone-like layout. GOG's sign-in card and button were fully visible without scrolling. Epic and Amazon remained signed in, so their library layouts were inspected rather than signing the accounts out. Proton options appeared before the catalogs, Force SSBS was reachable by scrolling, and changing layers reset the detail scroll. The physical size/density were restored (1080x1920 / 369dpi). The final build and four focused tests passed; the final signed APK hash is `7dca710d081042ec68aeaf6e8b68371d5fdfa73eca5860ba4ca5f0134c21b9fc`. Wireless ADB file-sync stalled, so the APK was transferred over a temporary direct LAN socket, hash-verified on Thor, and installed with `pm install -r`. The transfer listener closed after delivery.

## Runtime reports still unresolved

**Second Steam launch fails or has sound with a black picture.** The two submitted log bundles reach a first frame and Steam focus, then record a guest exit with status 0. Their crash buffers contain no Android crash. Two consecutive launches of Thor's existing main build reached READY and displayed Steam correctly, using the same retained compositor and app process. Two consecutive Steam launches of the new branch also reached READY with visible Steam UI, and its desktop launch reached READY and stopped cleanly. These observations do not establish the cause of the reporters' failures, and no speculative compositor or Steam-startup change was made.

**Persona 3 Reload drops from 60 to 35 FPS and crashes.** The linked compatibility thread identifies an Odin 3 Max and a UE4 GameThread timeout waiting 120 seconds for RenderThread. Earlier working settings were Performance + TSO and VKD3D shader/feature levels 6_6 / 12_1. The new report has no session log, before/after build identifiers, or controlled comparison. Persona 3 Reload was not found in the inspected Thor library locations. Neither an Odin reproduction nor a Persona performance improvement is claimed. The RDR2 launcher governor matches Rockstar helper executable paths and is not a blanket game-core restriction.

## Related follow-ups outside launcher design

General's fullscreen desktop-app report matches the existing runtime contract: the software desktop hands Steam to a Steam session and wraps games/emulators in gamescope for GPU access. `droiddeck-desktop-gpu` preserves a “Run in a desktop window” action. Changing that GPU/window architecture is separate runtime work; no claim of fixing it here.

Development's request to update driver and Proton defaults without visiting Components is also runtime policy. Driver Auto already calls `ensureAuto` during activity startup. This branch does not replace manual driver selections or install/switch Proton builds as part of a layout fix.
