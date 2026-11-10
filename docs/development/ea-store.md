# EA store

EA games install, update and start without the EA app. The research behind it is in
[ea-ubisoft-stores.md](ea-ubisoft-stores.md).

## Pieces

| Piece | Where | What it does |
|---|---|---|
| Sign-in | `stores/ea/EaAuth.kt`, `EaLogin` in `EaBackend.kt` | `JUNO_PC_CLIENT` OAuth with PKCE in the store login WebView; the code comes back on EA's `qrc:///html/login_successful.html` redirect. Tokens refresh with the refresh token. |
| Library | `EaApi.kt`, `EaLibrary.kt` | Owned games (`getPreloadedOwnedGames`, EA storefront), their offers (`getLegacyCatalogDefs`) and art (`GameImages`, SteamGridDB when EA has no portrait). Editions of one game collapse onto the base offer; trials, DLC and non-downloadable offers are left out. |
| Queries | `app/src/main/resources/ea/graphql/*.gql` | EA's own query texts, byte for byte. The gateway matches persisted queries by SHA-256, so these files must not be reformatted (`-text` in `.gitattributes`). |
| Downloads | Rust `src/ea/` in `libdroiddeckstores.so`, `EaInstaller.kt` | A build is one ZIP on EA's CDN. The engine reads its central directory with ranged reads, keeps files whose size and CRC match, and streams every other entry through inflate into place. `.droiddeck-ea-files` records what it placed, so a resume or update does not re-hash those files; files a new build drops are removed. |
| Updates | `EaUpdates` in `EaBackend.kt` | The game page compares the installed version with the offer's live `gameVersion` (every 30 minutes at most). Update runs the same install, which only fetches changed files. |
| Launch, guest | `droiddeck-store-launch` (`prepare_ea`) | Creates the prefix, reads the machine as Wine reports it (`wmic`, `vol`, `hostname`; cached in `pfx/.droiddeck-ea-hw.json`), writes EA app presence keys and the game's `Install Dir` keys in both registry views, runs the build's Touchup once per installed build, then asks the app for the session and writes any licence files. |
| Launch, app | `EaLaunch.kt`, `EaOoa.kt`, `EaLsx.kt` | Answers `ea-prepare`: starts the LSX endpoint on `127.0.0.1:3216`, mints the short-lived opaque token, writes `.droiddeck-ea-env` (read once and deleted by the launcher `.bat`), and for games with a `Core` folder requests an online-activation licence for the machine hash. |
| LSX | `EaLsx.kt` | The local endpoint EA's SDK talks to instead of the EA app: challenge, AES session key, config, profile, settings, game info, auth codes for the game's own client ids, licences with Denuvo tokens, entitlements. Friends, presence and the overlay answer empty. |

## Machine hash

The licence names a hash of the machine the game's activation layer computes itself. The hash
version follows the activation library's product version (`Core/activation.dll` or `awc.dll`):
5.x → 4, 4.7.6+ → 3, 4.7.x → 2, 4.x → 1. Versions 2 and 3 also need CPUID leaf 1 ECX, which Wine's
WMI does not expose, so those use `00000000` unless the probe learns it.

## Not done yet

- Cloud saves (`cloudsync.juno.ea.com`).
- Steam copies of EA games still install the EA app (#2036).
- Pre-DiP games (no `installerdata.xml`) fall back to the exe picker.
- Javelin anti-cheat games do not run under Proton.
