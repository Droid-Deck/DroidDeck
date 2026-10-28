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
  are preserved.
- **Amazon Games:** metadata checks, pinned manifest updates, and repair from
  the retained installed manifest. Its Rust engine is bundled with this
  checkout; it is not an optional engine pack.
- **Battle.net:** checks and update handoff to the existing Agent workflow.
  When unattended repair is unavailable, the UI opens Battle.net for its
  Scan and Repair flow. The app does not claim the client repair already ran.
- **Xbox:** update through Xodus's licensed package streaming path. The UI
  reports an unknown package version when Xodus does not expose one; transfer
  and space amounts are client-determined. Metadata checks do not claim success
  when package identity or version information is missing.
- **Steam:** navigation to Steam downloads/library and guidance to use the
  game's Properties → Installed Files → Verify integrity. The app does not run
  Steam validation itself. Native Steam apps are distinguished from
  third-party shortcuts.
- **itch.io:** update, repair, and refresh from a pinned archive upload, with
  per-file commit recovery. Butler cave migration is deferred; refresh uses
  the current upload archive and does not migrate legacy Butler cave state.

Unknown transfer or disk estimates use a negative sentinel and appear as
“Unknown (client-determined)”; zero remains a real zero-byte estimate.
