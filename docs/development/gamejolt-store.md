# Game Jolt store

The Game Jolt Store tab mirrors the itch.io one: browse, search, game page, build picker, installs, library, collections and updates.

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

Browse queries (`GameJoltBrowse.queryString`): `section=` is always sent first (featured, hot, best, new).
Game Jolt answers 404 to any browse request without it, even an empty one. The rest are `tag=`, `f_price=`,
and `f_os[]`, `f_browser[]`, `f_maturity[]`, `f_status[]`, `f_partners[]`, `page=`. Every page logs its query,
status and game count. The filters are drop-down menus (`BrowseDropdown`, shared with the itch bar). With mature
content off, `adult` is stripped from the query, including a stored one.

## Prices

`GameJoltPricing.of` reads `sellable.pricings[]` (amounts in cents). The promotional entry is the current price
and the regular entry the original, only when the original is higher; the percent uses integer math. USD (or no
currency) formats as `$12.34`, another currency as `12.34 EUR`. `CatalogItem` carries `finalPrice`,
`originalPrice` and `discountPercent`, so tiles and the game page show prices and sale badges.

## Library and collections

`GameJoltLibrary.sync` fetches `/web/library/games/owned/@<user>` (paged, at most every ten minutes, forced by
the refresh action) and merges the games into the store library as owned. `GameJoltCollections` lists the
read-only collections: followed games (`/web/library/games/followed/@<user>`) and the account's playlists
(`/web/library/games/playlist/<id>`). The Library page shows them through the same `CollectionsButton` as itch.
Ownership comes from `sellables[0].is_owned`; `packages[0].is_game_owner` is not reliable. Like every other store,
Game Jolt and Itch.io only appear among the Library chips when they have games in the list.

## Builds and installs

`GameJoltBuilds.parse` reads the overview payload, keeps the newest release of each package and marks
Windows builds installable. The picker (`GameJoltBuildPicker`) installs Windows builds of free games and of paid games the account owns;
Linux builds say "Not supported yet" and everything else, including paid games not owned, opens the Game Jolt page.
`GameJoltInstaller` asks `builds/get-download-url/<id>` for a signed link on every attempt, resumes with
`Range`, unpacks through `StoreArchiveUnpacker` (shared with itch) and picks the launch option or an exe.
An update downloads again and merges through `ItchStaging`, like itch.

## Updates

`GameJoltUpdates` works like `ItchUpdates` (see `itch-updates.md`). The install record keeps the release
`published_on` as `installVersion` and `gameId`, `buildId` and `packageId` in `extra`. `evaluate` compares them
with the current builds: a newer release of the same package is an update; if the package is gone, the first
installable Windows build is offered as a replaced build. The automatic check runs when the Game Jolt Library
source opens, at most every six hours, one game per second. A Check for updates button on an installed game's page
checks that game only. Updates stage through `ItchStaging` and the safe folder. Uninstalling or finishing an
install clears the record. Install folders use `Store.folder`, so the Itch.io display name never moves them.

## Controller

The menus, chips and buttons are the shared components (`MenuItem`, `AnchoredMenu`, `ValueChip`, `BrowseChip`,
`IconAction`) that already take focus and confirm from a controller. The first menu entry takes initial focus.
