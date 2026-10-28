# Store maintenance

The shared maintenance coordinator checks installed games, throttles successful
metadata checks, pins update targets, and persists operations through the
existing download queue. Install settings are restored from the original
sidecar after a successful apply. Interrupted or uncertain applications retain
their journal and block launch until recovery. A provider exposes only actions
its existing engine or client can perform.

## Shipped actions and limits

- **Epic Games:** metadata checks, pinned manifest updates, and repair from the
  retained installed manifest. Existing Epic install settings and locale tags
  are preserved. For upstream capability context, see [Legendary's CLI](https://github.com/legendary-gl/legendary/blob/master/README.md)
  and [download planner](https://github.com/legendary-gl/legendary/blob/master/legendary/downloader/mp/manager.py).
- **Amazon Games:** metadata checks, pinned manifest updates, and repair from
  the retained installed manifest. Its Rust engine is bundled with this
  checkout; it is not an optional engine pack. Upstream comparison: [Nile CLI](https://github.com/imLinguin/nile/blob/master/nile/cli.py),
  [download manager](https://github.com/imLinguin/nile/blob/master/nile/downloading/manager.py),
  and [active worker](https://github.com/imLinguin/nile/blob/master/nile/downloading/worker.py).
- **Battle.net:** checks and update handoff to the existing Agent workflow.
  When unattended repair is unavailable, the UI opens Battle.net for its
  Scan and Repair flow. The app does not claim the client repair already ran.
  See [Blizzard's Scan and Repair instructions](https://eu.support.blizzard.com/es/help/article/6572).
- **Xbox:** update through Xodus's licensed package streaming path. The UI
  reports an unknown package version when Xodus does not expose one; transfer
  and space amounts are client-determined. Metadata checks do not claim success
  when package identity or version information is missing. References: [Xodus](https://github.com/xodus-gaming/xodus),
  [Nexus](https://github.com/domsvoboda/xodus-nexus), and the [pinned package-update query](https://github.com/domsvoboda/xodus-nexus/blob/396e7fb8fe0e5f00d956091195c614b8a0732ab1/assets/xodus-store-updates.patch).
- **Steam:** navigation to Steam downloads/library and guidance to use the
  game's Properties → Installed Files → Verify integrity. The app does not run
  Steam validation itself. Native Steam apps are distinguished from
  third-party shortcuts. See [Valve's game-file validation instructions](https://help.steampowered.com/en/faqs/view/0C48-FCBD-DA71-93EB).
- **itch.io:** update, repair, and refresh from a pinned archive upload, with
  per-file commit recovery. Butler/Wharf migration is deferred; current archive
  operations do not migrate legacy Butler cave state or use Wharf deltas. See
  the [Wharf specification](https://itch.io/docs/wharf/), [Butler patch and verification primitives](https://itch.io/docs/butler/offline.html),
  and [Butler ARM64 release](https://github.com/itchio/butler/releases/tag/v15.25.0).

Unknown transfer or disk estimates use a negative sentinel and appear as
“Unknown (client-determined)”; zero remains a real zero-byte estimate.
