# Game Jolt store

The Game Jolt Store tab mirrors the itch.io one: browse, search, game page, build picker, free installs.

## Sign-in

Game Jolt shows the normal sign-in card until an account is signed in. Signing in opens
`StoreLoginActivity` on `https://gamejolt.com/login` (`GameJoltLogin`). The flow is not a token redirect:
Game Jolt is a single-page site, so `StoreLoginActivity.Client.doUpdateVisitedHistory` also reports
navigations, and any gamejolt.com page outside the sign-in, sign-up and legal paths counts as signed in.
`finish` reads the WebView cookies, asks `/web/search/games?q=a` for the top-level `user`, and stores
the cookie header and the user through `GameJoltAuth` (encrypted, `StoreAccounts`). The log names the
cookies and the user keys, never their values.

## Data

`GameJoltApi` talks to `https://gamejolt.com/site-api/web/...` with the stored cookies. Games come back as
`{payload, user}`; `GameJoltCatalog` turns a game into a `CatalogItem` (paid only when `sellable.type` is
`paid`).

Browse queries (`GameJoltBrowse.queryString`): `section=` (hot, best, new; featured has none), `tag=`,
`f_price=`, and `f_os[]`, `f_browser[]`, `f_maturity[]`, `f_status[]`, `f_partners[]`, `page=`. Every
page logs its query, status and first ids.

## Builds and installs

`GameJoltBuilds.parse` reads the overview payload, keeps the newest release of each package and marks
Windows builds installable. The picker (`GameJoltBuildPicker`) installs Windows builds of free games;
Linux builds say "Not supported yet" and everything else, including paid games, opens the Game Jolt page.
`GameJoltInstaller` asks `builds/get-download-url/<id>` for a signed link on every attempt, resumes with
`Range`, unpacks through `StoreArchiveUnpacker` (shared with itch) and picks the launch option or an exe.
An update downloads again and merges through `ItchStaging`, like itch.
