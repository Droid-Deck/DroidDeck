# Bottom screen: guides tab

Part of the [bottom screen research](bottom-screen.md). Researched 2026-11-26 against `main` at `d788afc12`. Not implemented.

A dashboard tab that follows the running game and shows its guides, achievements, maps and fixes, reopens where the user left off, and saves pages for offline use.

## Use cases

- Stuck on a puzzle or boss: the walkthrough for this game is already open on the bottom screen at the last section read.
- Achievement hunting: a checklist of locked achievements with missable ones flagged, spoilers blurred until tapped.
- Collectibles: a map image pinned with pan and zoom, markers saved per map.
- Fixes: PCGamingWiki and ProtonDB notes for the game when it misbehaves.
- Offline play on a plane with guides saved earlier.

## Prior art

| Project | Matching | Offline | Reader and extras | Complaints and requests |
| --- | --- | --- | --- | --- |
| [Game Twin](https://github.com/alpha-minus/game-twin) (GPL-3, 157★) | Bundled catalogs (GameFAQs, StrategyWiki, VGMaps, Steam guides) rebuilt offline; Jaccard title match at 0.6 with a sequel guard (numbers must match); hand-mapped series sites (Zelda Dungeon, Bulbapedia); site search URLs for the rest | `WebView.saveWebArchive` after forcing lazy images to load; MHT split into HTML and assets served via `shouldInterceptRequest`; scripts stripped; OkHttp with WebView cookies for side downloads. Also imports PDF, TXT, EPUB | Reading mode with per-host cleanup, auto table of contents, find, bookmarks. Resume by the text at the top of the viewport plus its occurrence, so it survives reflow. Maps with markers, checklists, RetroAchievements and Steam achievements, folder sync, optional Gemini with a screenshot | Battery and fan noise from live pages left running ([#12](https://github.com/alpha-minus/game-twin/issues/12), [#24](https://github.com/alpha-minus/game-twin/issues/24)); saved guides unreadable in dark mode ([#9](https://github.com/alpha-minus/game-twin/issues/9)); cookie banners ([#2](https://github.com/alpha-minus/game-twin/issues/2)); requests for notes ([#28](https://github.com/alpha-minus/game-twin/issues/28)) and a visible capture indicator for AI ([#22](https://github.com/alpha-minus/game-twin/issues/22)) |
| [Pixel Guide](https://github.com/rexmont/Pixel-Guide-Android) (closed source) | Manual | TXT, single-file HTML, multi-page Neoseeker | ASCII reflow with auto table of contents, per-guide font, resume, find, controller scrolling, 14 themes, manuals, translation, AI | Ads in saved pages ([#46](https://github.com/rexmont/Pixel-Guide-Android/issues/46)), very large guides fail ([#58](https://github.com/rexmont/Pixel-Guide-Android/issues/58)); requests for stick scrolling ([#35](https://github.com/rexmont/Pixel-Guide-Android/issues/35)), prev/next ([#27](https://github.com/rexmont/Pixel-Guide-Android/issues/27)), checklists ([#44](https://github.com/rexmont/Pixel-Guide-Android/issues/44)), remembering the game association ([#20](https://github.com/rexmont/Pixel-Guide-Android/issues/20)) |
| [WalkThor](https://github.com/nerf-herder-29/WalkThor) (MIT) | Fuzzy match of ROM name to local files | Local PDF and TXT only | Position stored as a character offset; 80-column pages for ASCII tables; L1/R1 page turns. Bottom window is non-focusable while a game runs because taking focus "can halve its frame rate" | n/a |
| Steam Deck overlay Guides (SteamOS 3.3) | Exact app id | No | Favourites and popular guides; later fixes for oversized images and spoiler tags. Gone from the desktop overlay, where the 2023 overlay instead added pinned windows and Game Notes ([PC Gamer](https://pcgamer.com/new-steam-feature-lets-you-pin-windows-over-games-including-guides-notes-and-web-pages)) | n/a |

DroidDeck's advantage: we know the exact Steam app id of the focused game, so we need no title matching for Steam games.

## Sources

| Source | Access | Terms | Use |
| --- | --- | --- | --- |
| **Steam Community guides** | Listing: `steamcommunity.com/app/<appid>/guides/?browsefilter=toprated\|trend&requiredtags[]=Walkthroughs`. API: `IPublishedFileService/QueryFiles` with `filetype=11` needs a key or the signed-in user's token. Guide pages (`sharedfiles/filedetails/?id=`) are server-rendered; sections are `div.subSection` with numeric ids | Authors own their guides under the Steam Subscriber Agreement. Cache per user, never redistribute | First source. Native section reader with stable anchors for chapter jumps and resume |
| **Steam achievements** | `IPlayerService/GetGameAchievements/v1/?appid=&language=` needs no key and returns names, icons, the hidden flag, global unlock percentage, and descriptions even for hidden achievements (checked on Elden Ring, Hollow Knight, Baldur's Gate 3). It is undocumented. `GetGlobalAchievementPercentagesForApp` also needs no key. The player's unlocks should come from the local client (`SteamClient.Apps` through CDP, or the client's stats cache); not yet tested | Valve | Achievement checklist with spoiler blur and difficulty from unlock % |
| **Wikidata** | SPARQL by Steam app id (P1733), or Epic (P6278) or GOG (P2725) id, returns the Fandom wiki (P4073), PCGamingWiki (P6337), GameFAQs (P4769), IGN (P5385), HowLongToBeat (P2816) and speedrun.com ids | CC0 | Crosswalk from the running game to every other site, including non-Steam games |
| **StrategyWiki** | MediaWiki API | CC BY-SA 4.0 | Full offline caching allowed |
| **Fandom wikis** | MediaWiki `api.php` per wiki | CC BY-SA (media excluded) | Per-game wiki pages, found through Wikidata |
| **PCGamingWiki** | `pcgamingwiki.com/api/appid.php?appid=` redirects to the page; MediaWiki API (anonymous Cargo queries are refused now) | CC BY-NC-SA 3.0 | Fixes and settings, in a "Fixes" section |
| **ProtonDB** | `protondb.com/api/v1/reports/summaries/<appid>.json`, no key | ProtonDB | A tier badge next to fixes |
| **GameFAQs, Neoseeker, IGN** | No API. GameFAQs terms forbid unauthorized scraping; its robots signals allow reference use but not AI training | Authors' copyright; Ziff Davis bans automated retrieval including AI use without permission | Browse in a WebView; save a page only when the user asks; never prefetch or send to AI |
| **VGMaps, Map Genie** | Images; Map Genie is an interactive site (Ziff Davis) | Maps belong to the games' owners; Map Genie bans automated retrieval | Save VGMaps images on request into the map viewer; Map Genie only as a live WebView |
| **YouTube** | Search URL (free) or Data API (100 quota units per search) | YouTube | Search link and embedded player, no downloads |
| **HowLongToBeat, TrueSteamAchievements** | No stable API ([unofficial library breaks often](https://github.com/ScrappyCocco/HowLongToBeat-PythonAPI/issues)) | Ziff Davis / site owners | Links only |

Recommended order for the tab: Steam guides → achievements → StrategyWiki or the game's Fandom wiki (via Wikidata) → PCGamingWiki and ProtonDB → curated series sites → GameFAQs, Neoseeker and IGN in a WebView → maps → YouTube.

## What DroidDeck already has

Paths are under [`app/src/main/java/com/droiddeck/launcher/`](../../app/src/main/java/com/droiddeck/launcher/).

- **Game identity.** `AgentGuest.activeSteamAppId` from gamescope focus (ignoring the Steam client, 769), passed to bottom pages as `runningGame`. Non-Steam store games are Steam shortcuts; `resolveAchievementsInFront` already maps the focused id to the store and store id through the library cache.
- **WebView base.** [`ui/BottomChatPage.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/BottomChatPage.kt) loads Steam Community with a host allow-list, desktop user agent, fit-to-screen CSS and crash recovery. [`session/SteamWebLogin.kt`](../../app/src/main/java/com/droiddeck/launcher/session/SteamWebLogin.kt) imports the Steam client's own `steamcommunity.com` cookies, so guides load signed in without another login.
- **Achievements.** [`stores/StoreAchievements.kt`](../../app/src/main/java/com/droiddeck/launcher/stores/StoreAchievements.kt) is a provider base with a per-game cache; GOG and Epic implement it. [`ui/StoreAchievementsUi.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/StoreAchievementsUi.kt) has the rows, tab and session panel. A Steam provider plugs in as source `steam`.
- **Per-game storage.** `SessionPrefs` already keys settings by game (for example `fpsLimit.game.<id>`); the guide workspace fits the same pattern.
- **Game frame without MediaProjection.** `SessionActivity.copyGameFrame` and `AgentAttachments.gameFrame` capture the game image directly, which an optional AI helper could use with no capture permission.
- **Controller.** The dashboard forwards controller input to the game and has no D-pad focus today. The docked session menu has focus handling (`SecondScreenPresentation.dispatchMenuKey`, the `dockedMenuController` setting) and is the pattern to follow.

## Design

**Workspace per game.** One workspace per Steam app id (or store key): open guide, section, text anchor, recent searches, pinned maps, checklist ticks. Restored when the game gains focus. Cleared with the game profile.

**Home of the tab** for the running game:

1. Continue: the last guide at the last section.
2. Steam guides, top rated and trending, filterable by tag (Walkthroughs, Achievements, Secrets, Maps).
3. Achievements: locked first, missable flagged, hidden descriptions blurred.
4. Wiki: StrategyWiki or Fandom, from Wikidata.
5. Fixes: PCGamingWiki and the ProtonDB tier.
6. Saved: offline pages and maps for this game.
7. Search the web: GameFAQs, Neoseeker, IGN and YouTube with the game name prefilled.

**Reader.**

- Steam guides and MediaWiki pages are rendered natively from their HTML (sections, images, spoilers), not as a full web page: smaller, dark by default, and reflowed for the panel.
- Other sites open in a WebView with cleanup CSS, forced dark mode and a cookie-banner dismiss.
- Resume by text anchor (Game Twin's approach), falling back to scroll ratio.
- Table of contents from Steam section ids or the MediaWiki outline; find in page.
- Spoiler-safe by default: Steam `[spoiler]` spans and hidden achievements blurred until tapped.

**Offline.**

- Steam guides and wiki pages: save the parsed HTML and images per user.
- Other sites: MHT saved on request, with scripts stripped.
- Saved pages are listed per game and work without network.

**Controller.**

- The guide never takes focus or priority from the game (WalkThor's lesson).
- A dedicated chord or the docked-menu controller mode gives it the pad: right stick scrolls, L1/R1 change section, D-pad moves through links and the contents, B returns the pad to the game.
- Touch always works.

**Battery.** Pause or destroy WebViews when the tab is not visible; the top complaint about Game Twin is a live page draining the battery behind the game.

## Progress-aware help (later)

Signals we can use without reading game memory:

- Steam achievement unlocks with timestamps, matched to guide section titles.
- Steam rich presence (`steam_display`) and, for games that report it, [Steam Timeline](https://partner.steamgames.com/doc/api/ISteamTimeline) game phases and tooltips. Whether the client exposes these to CDP needs checking.

An optional AI helper ("Where am I, what next?") would send the game frame plus those signals. Rules from Game Twin's feedback and the criticism of Xbox Gaming Copilot ([Tom's Hardware](https://www.tomshardware.com/video-games/pc-gaming/microsoft-says-gaming-copilot-uses-screenshots-to-understand-in-game-events-not-for-training-ai-models-optional-feature-can-be-turned-off-but-not-easily-uninstalled)):

- opt-in, user's own key;
- one frame per question, with a visible indicator;
- crop out notifications and overlays;
- never send cached text from sources that forbid AI use.

## Open questions

- The most reliable way to read the player's Steam unlocks locally (CDP versus the client's stats cache).
- Whether Steam guide listings load reliably without the web API key, using the signed-in cookies.
- How much of a Steam guide's formatting (tables, embedded video) the native reader must support.
- Where the tab lives in swapped mode (it follows the panel to the top screen).

## Milestones

1. Steam guides list and WebView reader for the running app id, with a per-game last page.
2. Steam achievements provider and checklist with spoiler blur.
3. Native section reader, offline saving and the controller mode.
4. Wikidata crosswalk, wiki and fixes sections, maps viewer.
5. Progress-aware suggestions and the optional AI helper.
