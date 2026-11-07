# Battle.net 国服最终排查：D3、WoW 与其余国服商品

日期：2026-11-06。基线：`Droid-Deck/DroidDeck` `main` @ `c8a76e7`（本次克隆）。依据：国服客户端日志（`osic` / `s2_cn` / `d3cn` / `anbs_cn`）、#1660/#1662/#1705 的实测记录，以及 `https://cn.version.battlenet.com.cn/v2/products/<product>/versions` 的当前返回。

## 结论

D3 和 D2R 下载到国际服资源，是 **#1705 未合并**造成的写侧缺口。D3 在 DroidDeck 里能入库但国服客户端不认；D2R 连 DroidDeck 也可能不入库，因为 `osi` 不是国服客户端记录的 uid。最终补充 PR 需要同时补齐三件事：

1. **D3 的国服别名**：最新 main 仍有一个独立的 `d3cn` 标题，`STORE`/`fetchCovers` 把它过滤掉，`notInstalled()` 又按 launch code 比较。结果 #1705 把 D3 装成 `d3cn` 后，它会落到一张单独的 “Diablo III” 卡，而正式的 `diablo3` 卡仍然显示“安装”。应改成 `d3cn` → `diablo3` 卡片（launch code `D3CN`、product `d3cn`）。
2. **SC2 的国服 product 改成 `s2c`**：#1705 只把 uid 改成 `s2_cn`，product 仍用 `s2`；`s2` 的 `cn` 行是旧的 5.0.14.93333，`s2c` 才是国服当前的 5.0.17.98370。
3. **国服安装的更新检查**：`.build.info` 的 branch 是 `cn` 时，`BattleNetInstall.check()` 仍用商店 product（`osi`/`s2`/`diablo3`）查 `cn` 行，会查不到或查到旧行；应按国服 product（`osic`/`s2c`/`d3cn`）查。

WoW、炉石、风暴英雄、守望、星际争霸重制版不需要 uid 映射：它们的国服商品就是同一个 product，且版本表里有 `cn` 行。最新 main 已收录 `wow_forever`（product `wowf`），但 `STORE` 有意排除 `WoWF`；客户端装好的 WoW Forever 仍会通过 `titleForProduct` 入库。

## 商品矩阵

| 游戏 | 商店 uid | 国服客户端 uid | 国服 product | 依据 | 本次代码 |
|---|---|---|---|---|---|
| 魔兽世界（正式服） | `wow` | `wow` 或 `wow_<locale>`（未观察到独立国服 uid） | `wow` | `cn` 行 12.1.5.70783，与 `us` 同 build | 无需映射 |
| 魔兽世界：熊猫人怀旧 | `wow_classic` | 同商店 uid（未观察到独立国服 uid） | `wow_classic` | `cn` 行 5.5.4.70631 | 无需映射；商店原本排除 `WoWC` |
| 魔兽世界：旧世经典 | `wow_classic_era` | 同商店 uid（未观察到独立国服 uid） | `wow_classic_era` | `cn` 行 1.15.9.70576 | 同上 |
| 魔兽世界：周年纪念 | `wow_classic_anniversary` | 同商店 uid（未观察到独立国服 uid） | `wow_anniversary` | `cn` 行 2.5.6.70577 | 同上 |
| 魔兽世界：Forever | `wow_forever` | `wow_forever` / 未知 uid + product `wowf` | `wowf` | `cn` 行 1.60.1.70790 | 已收录；`STORE` 排除 `WoWF` |
| 暗黑破坏神 III | `diablo3` | `d3cn` | `d3cn` | `d3cn` 的 `cn` 行 2.8.1.101167；`d3` 没有 `cn` 行 | **新增别名 + 写侧映射** |
| 暗黑破坏神 II：重制版 | `osi` | `osic` | `osic` | `osic` 只有 `cn` 行 3.3.93854；`osi` 是 `us/eu/kr` 3.3.93847 | #1705 已覆盖 |
| 星际争霸 II | `s2` | `s2_cn` | **`s2c`** | `s2c` 只有 `cn` 行 5.0.17.98370；`s2` 的 `cn` 行是旧的 5.0.14.93333 | **修正 product** |
| 暗黑破坏神：不朽 | `anbs` | `anbs_cn` | `anbs` | `anbs_cn` 没有独立 product；`anbs` 版本表没有 `cn` 行 | #1705 已覆盖；更新检查暂不可用 |
| 炉石传说 | `hs_beta` | 同商店 uid（未观察到独立国服 uid） | `hsb` | `cn` 行 37.0.2.256589.255790 | 无需映射 |
| 风暴英雄 | `heroes` | 同商店 uid（未观察到独立国服 uid） | `hero` | `cn` 行 2.55.3.89566 | 无需映射 |
| 守望先锋 2 | `prometheus` | 同商店 uid（未观察到独立国服 uid） | `pro` | `cn` 行 2.25.1.0.155284N | 无需映射 |
| 星际争霸：重制版 | `s1` | 同商店 uid（未观察到独立国服 uid） | `s1` | `cn` 行 1.23.10.11269 | 无需映射 |
| 魔兽争霸 III：重制版 | `w3` | 同商店 uid（未观察到独立国服 uid） | `w3` | 没有 `cn` 行；沿用全球 build | 无需映射；更新检查可能不可用 |
| 泰坦重铸（`wow_classic_titan`） | 未收录 | 未确认 | `wow_classic_titan` | `cn` 行 3.80.2.70522 | 未收录；需要客户端 uid/launch code 再定 |

## 版本内容与失败模式

### 1. 不能只看 HTTP 200

`osi` 的 versions 内容只有 `us`/`eu`/`kr` 行，Build 93847，没有 `cn` 行；`osic` 只有 `cn` 行，Build 93854。两者不是同一个版本序列：

| product | 行 | Build | 说明 |
|---|---|---|---|
| `osi` | `us` / `eu` / `kr` | 93847 | 没有 `cn` 行 |
| `osic` | `cn` | 93854 | 国服专用 |
| `s2` | `us` / `eu` / `cn` / `kr` / `tw` | `cn` 行 93333 | `cn` 行是旧的 |
| `s2c` | `cn` | 98370 | 国服当前 |
| `anbs` | `us` / `eu` / `kr` | 3935504 | 没有 `cn` 行 |

因此干净目录下用 `osi` 安装时，Agent 拿不到 `cn` 行，会回落到 `us`/`eu`/`kr` 的 93847：安装能成功，但比国服的 `osic` 93854 旧一个小版本。更麻烦的是 `.build.info` 从此记在 `osi` 的版本线上，后续更新、修复都追 `osi` 的序列；两边的 BuildConfig 哈希不同，不能把目录改个名就迁回 `osic`。

### 2. “装上了也是死的”双向不认

错配的安装不是只有 800 一种结局。`osi` 装成功后：

- Agent 的 `product.db` 记 `osi`，DroidDeck Stores 显示“已安装”；
- 国服客户端枚举的是 `osic`，显示“未安装”；
- 两边永远不会达成一致，更新、修复、卸载各走各的通道。

D3 的 `d3` 构建就是这个状态；把目录重命名后装的 D2R `osi` 是第二个样本。这个状态不报任何错，只是国服客户端里永远少一个游戏，比一次 800 冲突更难发现。

本次 PR 修的是**新安装**的 uid/product；已经存在的 `osi`/`d3` 错配安装不会被自动迁移。恢复时优先用 DroidDeck 自己的卸载（它会按 `product.db` 里记录的 uid 调 Agent `/uninstall`），然后再用 `osic`/`d3cn` 重装。只重命名或手工删目录不够：`product.db` 的登记还在，下一次安装仍可能 800。重命名也不能迁移，因为 BuildConfig 和产品登记都不同。

### 3. 800 的触发条件比“国服副本冲突”宽

#1659/#1705 把 800 归因于“目录里躺着国服客户端装的完整副本”。实测触发条件更宽：目录在 `product.db` 里登记过、或有残留文件（哪怕看起来是“空文件夹”）都会 800。之前反复失败，可能不是国服副本冲突，而是上次失败尝试留下的目录登记/残留。

所以测试 800 时不能只准备“干净目录 + 国服副本”两种样本；还要覆盖：

- 目录存在但为空；
- 目录里有失败尝试的残留文件；
- `product.db` 里已有该路径的登记，但目录已被手工清空；
- 同一路径先后被 `osi`、`osic` 登记过。

本 PR 不改变 800 的触发条件，也不自动清理这些登记；遇到 800 仍需要先移除旧目录/登记。错误文案的可见性依赖 #1661（本 PR 未包含），否则仍可能显示成 `exit 0`。

### 4. s2 / anbs 的同类问题

- `s2` 有 `cn` 行，但停留在 5.0.14.93333；`s2c` 才是当前国服 5.0.17.98370。本 PR 已把 `s2` 的国服 product 改成 `s2c`，这一条是修掉的。
- `anbs` 没有 `cn` 行，`anbs_cn` 也没有独立 product（404）。#1705 把 uid 改成 `anbs_cn` 但 product 仍是 `anbs`，因此它和 `osi` 属于同一类风险：Agent 可能回落到 `us`/`eu`/`kr`，客户端是否认 `anbs_cn` 尚未验证。本 PR 不声称修复暗黑不朽；需要真机安装一次，检查 Agent 请求、`product.db` 和客户端枚举结果。

## 本次改动（基于 `c8a76e7`）

- `BattleNet.kt`：`CN_UIDS` 改为带 code/product 的别名；`s2_cn` 带 product `s2c`，`osic` 带 product `osic`，新增 `d3cn` → `diablo3`（code `D3CN`，product `d3cn`）；删除独立的 `d3cn` 标题，`STORE`/`fetchCovers` 去掉 `d3cn` 过滤；`notInstalled()` 改为按 store uid 比较；别名保留 `flavor`。
- `BattleNetInstall.kt`：新增 `chinaProduct()`（`osi→osic`、`s2→s2c`、`diablo3→d3cn`）；`check()` 在 `.build.info` 的 branch 为 `cn` 时用它构造 version URL。
- `droiddeck-battlenet`：新增 `CHINA` / `CHINA_PRODUCT` 表（`s2: "s2c"`）；`zone == cn` 的所有 install 都发送 `CHN`/`CN`，不再只对四个映射商品生效；`s2_cn` 有独立仓库、`anbs_cn` 共用 `anbs`。
- `BattleNetInstall.kt` / `StoresState` / `GameDetail` / `GameRemoval`：更新/卸载/移除按 `InstalledStoreGame.folder` 和标题选对应的安装；folder 匹配不到时不再回退到另一个安装，歧义时拒绝操作。取消只清理本次任务从“无登记”变成“有登记”的安装；原始登记查询不依赖目录存在。
- 测试：`BattleNetTest` 覆盖 `osic`/`s2_cn`/`anbs_cn`/`d3cn` 的 product 与 locale 后缀、两个 `diablo3` 安装按目录选择，以及目录缺失时仍能读到原始登记；`BattleNetInstallTest` 覆盖 `chinaProduct()`、`s2c` URL 和 `osi`/`osic`、`s2`/`s2c`、`anbs` 的 versions 行内容；`test_battlenet.py` 覆盖驱动映射和 `CHN`/`CN`。

## 仍未覆盖

- **商店按区域出商品**：商店仍是全球静态表；国服没有独立的“已购”接口。
- **WoW 怀旧服**：`STORE` 仍用 `code == "WoWC"` 整族过滤，`games()` 仍按 launch code 去重；多个怀旧服安装只会显示一个。这是既有设计，不是国服 uid 问题。
- **WoW Forever**：`wow_forever` 已在 `TITLES`，但 `STORE` 排除 `WoWF`；客户端装好的能入库，商店不提供安装卡。要改商店需要单独决定。
- **泰坦重铸（`wow_classic_titan`）**：不在 `TITLES`；`wow_classic_titan` 的 `cn` 行存在，但需要确认它是独立游戏还是 beta/PTR，不能只凭版本表猜 launch code。
- **暗黑不朽**：`anbs` 版本表没有 `cn` 行，`anbs_cn` 也没有独立 product；安装可走 `anbs_cn`/`anbs`，但是否会像 `osi` 一样回落到国际构建、客户端是否认 `anbs_cn`，尚未验证。本 PR 不声称修复。
- **已存在的错配安装**：`osi`/`d3`/`s2` 旧安装不会被自动迁移；应先用 DroidDeck 卸载（按记录的 uid 调 Agent `/uninstall`）再重装。只重命名/手工删目录会留下 `product.db` 登记，下一次仍可能 800。
- **800 的宽触发条件**：目录登记、空目录、残留文件都可能触发；本 PR 不自动清理，仍需要 #1661 把真实原因显示出来。
- **封面**：无 Steam appid 的标题仍依赖不可达的 `us.shop.battle.net`。
- **Battle.net 客户端安装器**：仍固定 `downloader.battle.net`。

## PR 正文（英文，可直接用）

标题：

```text
fix: finish Battle.net China's install and update mapping
```

正文：

````markdown
This is the complete China mapping, based on current `main` (`c8a76e7`). It includes #1705's
driver change (with the StarCraft II product corrected to `s2c`) and the Diablo III alias that
waited on #1660. If #1705 is merged first, this can be rebased as its follow-up.

### Problem

On a China Battle.net client (`region: cn`), the Stores install path still asks the Agent for the
international product for Diablo III, Diablo II: Resurrected and StarCraft II, so the games download
from the global line and the China client does not record them as its own installs.

The three pieces that were missing:

- **Diablo III**: the China client uses `d3cn` (launch code `D3CN`, product `d3cn`). `main` still
  carries a standalone `d3cn` title that the store hides, so #1705's install lands on a separate
  card and the real `diablo3` card keeps offering Install.
- **StarCraft II**: #1705 changes the uid to `s2_cn` but leaves the product as `s2`. The China
  repository's current StarCraft II line is `s2c` (5.0.17.98370); `s2` only has an older `cn` row
  (5.0.14.93333).
- **Update checks**: `.build.info` has branch `cn`, but `BattleNetInstall.check()` still asks
  `cn.version.battlenet.com.cn` for the store product (`osi`, `s2`, `diablo3`). `osi` and `diablo3`
  have no `cn` row at all, and `s2`'s `cn` row is the old 5.0.14 line, so a China install never checks
  against its own build.

A clean folder is not the only failure mode. When a product has no `cn` row (`osi`), the Agent falls
back to the `us`/`eu`/`kr` build (93847 instead of the China 93854). The install succeeds, but
`product.db` records `osi` while the China client enumerates `osic`: DroidDeck shows the game as
installed, the client shows it as not installed, and updates/repair/uninstall follow different
products. An 800 conflict can also come from a directory that is registered in `product.db` or has
residual files, not only from a complete China copy in the folder.

This PR fixes new installs. It does not migrate an existing `osi`/`d3` install; uninstall it through
DroidDeck (Agent `/uninstall` with the recorded uid) and install again. Renaming or deleting the
folder alone can leave the `product.db` registration behind and hit 800 on the next attempt, and it
cannot migrate the build because the BuildConfig and product differ.

### What the China client uses

| Game | Store uid | China uid | China product |
|---|---|---|---|
| Diablo II: Resurrected | `osi` | `osic` | `osic` |
| StarCraft II | `s2` | `s2_cn` | `s2c` |
| Diablo III | `diablo3` | `d3cn` | `d3cn` |
| Diablo Immortal | `anbs` | `anbs_cn` | `anbs` |
| WoW / Classic / HS / Heroes / OW2 / SC:R | — | same uid | same product |

`wow`, `wow_classic`, `wow_classic_era`, `wow_anniversary`, `hsb`, `hero`, `pro` and `s1` all have a
`cn` row of their own, so they need no remapping. `w3` has no `cn` row; it keeps the global build.
`anbs_cn` has no repository of its own and keeps `anbs`.

### Fix

- **Driver**: `s2` now becomes `s2_cn` with product `s2c`; the other China uids keep their existing
  products. Every `zone == "cn"` install sends `CHN`/`CN`, not just the four remapped products.
- **Catalogue**: `d3cn` resolves to the `diablo3` card with launch code `D3CN` and product `d3cn`;
  the standalone `d3cn` title and the store/cover filters are gone; `notInstalled()` compares the
  store uid, so a China install hides the right card.
- **Update checks**: when the install's branch is `cn`, the version check uses the China product
  (`osic`, `s2c`, `d3cn`); other regions are unchanged.
- **Installation identity**: a game detail opened from a shortcut selects the installed record by its
  folder, and update/uninstall resolve the Agent uid from that folder. Two Diablo III records that
  share `diablo3`, or two WoW flavors in one folder, no longer target each other. A supplied folder
  that matches nothing does not fall back to another installation; ambiguous updates/uninstalls stop.
- **Cancellation**: destructive cleanup is ownership-based. The raw `product.db`/registry snapshot
  ignores whether the game directory currently exists. A pending install resolves that snapshot on
  its worker thread, before `/install`, and persists it in the saved queue. Cancel only uninstalls a
  registration that appeared after a task recorded “no registration”. A pre-existing registration is
  never removed.
  Cleanup is skipped if the worker has not stopped after the wait.
- **Update checks**: `updates` and `checkedAt` are keyed by store id plus install folder, so the
  international and China Diablo III copies do not share a stale update marker.

### Testing

Endpoint checks (2026-11-06, `cn.version.battlenet.com.cn/v2/products/<product>/versions`):

```text
osic  cn 3.3.93854          osi  us/eu/kr 3.3.93847 (no cn)
s2c   cn 5.0.17.98370       s2   cn 5.0.14.93333 (older)
d3cn  cn 2.8.1.101167       d3   us/eu/kr/tw 2.8.0.99920 (no cn)
wow   cn 12.1.5.70783       hsb  cn 37.0.2.256589.255790
hero  cn 2.55.3.89566       pro  cn 2.25.1.0.155284N
s1    cn 1.23.10.11269
```

Unit tests: `BattleNetTest` (China uid aliases, locale suffixes, D3 card), `BattleNetInstallTest`
(`chinaProduct`, `s2c` version URL, the `osi`/`osic`, `s2`/`s2c` and `anbs` versions rows), and the
Python driver test for the China mapping and `CHN`/`CN`.

Device test plan: install D3, D2R and SC2 from a China store on a clean folder; expect the Agent
request to carry `d3cn`/`d3cn`, `osic`/`osic`, `s2_cn`/`s2c`; the client log to record the same uid;
the Library to show all three; and `checkUpdate()` to compare against the `cn` build. Also test the
800 samples: an empty directory, a directory with residual files, and a directory registered in
`product.db` but cleared by hand.

Diablo Immortal is not covered by this device plan: `anbs` has no `cn` row and no separate `anbs_cn`
product, so whether the Agent falls back and whether the client accepts `anbs_cn` is still unknown.

### Not in this PR

Migration/cleanup of existing wrong-product installs, the wider 800 trigger conditions, the error
surfacing in #1661, Diablo Immortal verification, store region filtering, WoW Classic store
cards/dedupe, the `wow_forever` store card, the `wow_classic_titan` title, covers, and the
Battle.net client installer's region are separate follow-ups.

Refs #1659, #1660, #1661, #1705
````
