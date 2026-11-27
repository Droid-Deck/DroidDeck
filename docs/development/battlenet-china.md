# Battle.net China install, update and cleanup mapping

Last reviewed: 2026-11-16, against `main` `a20528c` with this change applied. The China
version rows were observed on 2026-11-06 at
`cn.version.battlenet.com.cn/v2/products/<product>/versions`; installs use
`cn.patch.battlenet.com.cn`. #1660 added the China uid aliases to the catalogue and #1661
made the Agent driver report Battle.net's own refusal reason instead of `exit 0`; #1705 was
closed in favour of this change. This document describes the merged state and supersedes the
earlier plan that treated #1705 as pending.

## Product mapping

| Game | Store uid | China uid | China product | China version | Handling |
|---|---|---|---|---|---|
| World of Warcraft | `wow` | same uid | `wow` | 12.1.5.70783 | none |
| World of Warcraft Classic | `wow_classic` | same uid | `wow_classic` | 5.5.4.70631 | none |
| World of Warcraft Classic Era | `wow_classic_era` | same uid | `wow_classic_era` | 1.15.9.70576 | none |
| World of Warcraft Classic Anniversary | `wow_classic_anniversary` | same uid | `wow_anniversary` | 2.5.6.70577 | none |
| World of Warcraft: Forever | `wow_forever` | same uid, or unknown uid with product `wowf` | `wowf` | 1.60.1.70790 | in `TITLES`, excluded from `STORE` |
| Diablo III | `diablo3` | `d3cn` | `d3cn` | 2.8.1.101167 | alias; install and update use `d3cn` |
| Diablo II: Resurrected | `osi` | `osic` | `osic` | 3.3.93854 | alias; install and update use `osic` |
| StarCraft II | `s2` | `s2_cn` | `s2c` | 5.0.17.98370 | alias; install maps uid to `s2_cn` and product to `s2c` |
| Diablo Immortal | `anbs` | `anbs_cn` | `anbs` | no `cn` row | uid alias only; unverified |
| Hearthstone | `hs_beta` | same uid | `hsb` | 37.0.2.256589.255790 | none |
| Heroes of the Storm | `heroes` | same uid | `hero` | 2.55.3.89566 | none |
| Overwatch 2 | `prometheus` | same uid | `pro` | 2.25.1.0.155284N | none |
| StarCraft: Remastered | `s1` | same uid | `s1` | 1.23.10.11269 | none |
| Warcraft III: Reforged | `w3` | same uid | `w3` | no `cn` row | none |
| `wow_classic_titan` | not in the catalogue | unconfirmed | `wow_classic_titan` | not checked | out of scope |

No separate China uid has been observed for `wow`, the Classic flavors, `hs_beta`,
`heroes`, `prometheus`, `s1` or `w3`; the client reports the store uid for those.

## What the change does

### Catalogue

`BattleNet.CN_UIDS` maps the China uids to the existing cards instead of adding titles of
their own:

- `d3cn` resolves to the `diablo3` card with launch code `D3CN` and product `d3cn`. The
  standalone `d3cn` title and the `STORE`/`fetchCovers` filters for it are gone.
- `osic` resolves to `osi` with product `osic`; `s2_cn` resolves to `s2` with product
  `s2c`; `anbs_cn` resolves to `anbs` with product `anbs`.
- Locale suffixes resolve through the base alias, so `osic_zhcn` and `s2_cn_zhcn` name the
  same cards.

A `BattleNet.Game` keeps the store uid in `uid` and the Agent's uid in `installUid`. The
Library, the desktop shortcut, the store "installed" state, covers and the launch code
therefore agree; a China Diablo III install still lands on the `diablo3` card and launches
with `D3CN`.

### Install driver

On a China agent (`region == "cn"`) every install sends `account_country = CHN` and
`geo_ip_country = CN`, not only the remapped products. Products `osi`, `s2` and `diablo3`
become `osic`, `s2c` and `d3cn`; uids become `osic`, `s2_cn` and `d3cn` (`anbs` becomes
`anbs_cn`). Updates and uninstalls use the uid the Agent recorded, so they are not
remapped.

### Update checks

`BattleNetInstall.check()` reads `.build.info`. When its active branch is `cn`, the version
query uses the China product (`osic`, `s2c`, `d3cn`); other branches keep the store
product. `checkedAt` and `updates` are keyed by `id@folder` (`updateKey`), so an
international and a China copy of Diablo III neither suppress nor offer each other's
update. `anbs` has no China product mapping, so its China check still asks `anbs`, which
has no `cn` row.

### Install identity

Update and uninstall select the installed record by its exact folder and use the Agent uid
recorded for that folder. A folder with no matching registration is not retargeted to
another copy; a game detail opened from a shortcut passes its folder through, so two
Diablo III installs, or WoW flavors sharing one folder, do not act on each other.

### Cancellation ownership

Before an install starts, a raw `product.db` and `system.reg` query records whether the
target folder already has a registration. The query does not require the folder to exist
and does not deduplicate by launch code. The result is `PREEXISTING = pending | 0 | 1`; the
installer worker resolves it once, persists it through `SavedQueue`, and a shared
`Ownership` holder keeps the decision across pause and resume. `SavedQueue` only receives
the item after `DownloadQueue.enqueue()` accepts it (`onAccepted`), so a rejected enqueue
cannot leave ownership for a task that will not run.

Cancel uninstalls only a registration that appeared after the task recorded "no
registration"; a pre-existing registration is never removed. If either registration source
cannot be read, the decision stays conservative (no destructive cleanup) and is not
persisted as final. Cleanup is skipped when the worker has not stopped after the wait.
`clear()` and `cancel()` share one cleanup claim, and a new enqueue for the same game is
rejected while that cleanup runs.

## Why the version lines differ

| Product | Observed rows | Build | Note |
|---|---|---|---|
| `osi` | `us`, `eu`, `kr` | 3.3.93847 | no `cn` row |
| `osic` | `cn` | 3.3.93854 | China only |
| `s2` | `cn` | 5.0.14.93333 | older China line |
| `s2c` | `cn` | 5.0.17.98370 | current China line |
| `d3` | `us`, `eu`, `kr`, `tw` | 2.8.0.99920 | no `cn` row |
| `d3cn` | `cn` | 2.8.1.101167 | China only |
| `anbs` | no `cn` row | - | no separate `anbs_cn` product |
| `w3` | no `cn` row | - | keeps the global build |

### Products without a `cn` row

`osi`, `d3`, `anbs` and `w3` have no `cn` row, and `anbs_cn` has no product or repository
of its own. For `osi` and `d3`, an install made with the store product in a clean folder
misses the China line and the Agent falls back to the international build: for Diablo II:
Resurrected that is 3.3.93847 instead of the China 3.3.93854. The install succeeds, but
`.build.info` and `product.db` now follow the international line, and the BuildConfig
differs, so renaming the folder cannot migrate it. `w3` has no China line at all and keeps
the global build; `anbs` is unverified (see below).

### The two sides then disagree

When the wrong product is installed, `product.db` records `osi` while the China client
enumerates `osic`. DroidDeck shows the game as installed, the client shows it as missing,
and update, repair and uninstall each follow a different product. No error is reported,
which makes this state harder to notice than a visible failure. `s2` is the milder variant:
it does have a `cn` row, but that row is the older 5.0.14.93333 line, while `s2c` is the
current 5.0.17.98370.

### Error 800

Error 800 is not only a China-copy conflict. A folder registered in `product.db`, an empty
directory, a directory with residual files from an earlier attempt, or a path earlier
registered by a different product can trigger it. This change does not alter those triggers
and does not clean them automatically; #1661 now surfaces Battle.net's own refusal reason,
so the failure is reported instead of looking like `exit 0`.

## Automated coverage

- `BattleNetTest`: China uid aliases including locale suffixes, the `d3cn` alias on the
  `diablo3` card, folder selection between two Diablo III installs, WoW flavors in one folder,
  registrations for a missing folder, pre-existing registration protection,
  created-registration cleanup, registration read failure, restored ownership, per-folder
  update state, and the cancelled install's folder hold against uninstall.
- `BattleNetInstallTest`: `chinaProduct()`, the `s2c` version URL, the `osic`, `s2c` and
  `anbs` version rows, and per-folder update keys.
- `DownloadQueueRetryTest`: double `clear()` single-flight, a rejected enqueue not running
  its accepted callback, a rejected update preserving the saved ownership snapshot,
  factoryless cleanup, client-download exclusion, a removal hold waiting for a cancelled
  Battle.net cleanup, and the restore, install, enqueue chain.
- `tools/tests/test_battlenet.py`: the driver's China uid and product maps, the
  install-only condition, and the `CHN`/`CN` request fields.

## Not covered

- Migration of existing wrong-product installs (`osi`, `d3`, `s2`): uninstall through
  DroidDeck (it calls the Agent with the uid recorded for the folder) and install again;
  renaming or deleting the folder alone leaves the `product.db` registration behind and can
  hit 800 on the next attempt.
- Automatic cleanup of the wider 800 triggers; #1661 surfaces the reason, it does not
  remove the registration or the folder.
- Diablo Immortal: `anbs` has no `cn` row and `anbs_cn` has no separate product, so whether
  the Agent falls back to the international build and whether the client accepts `anbs_cn`
  is unverified. This change does not claim to fix it.
- Store region filtering: the store is a static global catalogue and there is no separate
  China "owned" feed.
- WoW Classic family cards and deduplication: `STORE` keeps one `WoWC` card and the game
  list keeps one folder per launch code. This is an existing design, not a China uid issue.
- `wow_forever` (in `TITLES`, excluded from `STORE`) and `wow_classic_titan` (not in
  `TITLES`) catalogue entries.
- Covers for titles without a Steam app id still depend on `us.shop.battle.net`.
- The Battle.net client installer's region; it is pinned to `downloader.battle.net`.

Refs: #1660, #1661, #1705 (closed).
