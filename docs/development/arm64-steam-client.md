# Native arm64 Steam client: channel and runtime

The default client is Valve's arm64 Linux build (`steamrtarm64/steam`). Valve does not support it, but
publishes it on the Steam Deck client channels. `droiddeck-steam-install` installs it and
`droiddeck-session` starts it with `STEAM_RUNTIME=1`.

## Channel

- The default is `steamdeck_publicbeta` (manifest `steam_client_steamdeck_publicbeta_linuxarm64`).
  Valve also publishes `steam_client_steamdeck_stable_linuxarm64`.
- `droiddeck-steam-install --channel` prints the channel in use. The session passes that value as
  `-clientbeta` and writes it to `package/beta`. The two must match, or the client loops on rc 42.
- Setting: `BL_STEAM_CHANNEL`, otherwise `~/.config/droiddeck/steam-channel`, set to `beta` or
  `stable`. A set channel never changes on its own.
- With no setting, the client falls back to `steamdeck_stable` automatically. It stays on stable
  (`package/droiddeck-channel`) until a setting says otherwise. The fallback happens:
  - when the client was started two more times without being shown after the start-failure repair
    (`package/droiddeck-unconfirmed`: two unconfirmed starts trigger a repair, which writes
    `repaired`; two more unconfirmed starts trigger the fallback);
  - when the beta manifest has no `*_linuxarm64_linuxarm64` component. The install log then lists the
    manifest's block names, so a renamed or removed arm64 build can be seen.
- The x86-64 client keeps `steamdeck_publicbeta` (see [x86-64-steam-client.md](x86-64-steam-client.md)).

## Steam Runtime container

Valve's 2026-10-15 beta made the x86 Linux client 64-bit and put it in a Steam Runtime container
(pressure-vessel). The container needs unprivileged user and mount namespaces and a D-Bus session bus,
which an Android app does not have. The x86-64 path already works around this. The arm64 client gets the
same treatment if Valve containerizes it too:

- `STEAM_STEAMRT_RECURSION_GUARD=1` is exported for both clients. It tells the client that it is
  already inside its container, so after an update it does not re-execute itself through
  `pressure-vessel-wrap` (bwrap).
- `droiddeck-steamrt-shim <client dir> <shim dir>` builds the shim when the client carries
  `pv-runtime/steam-runtime-steamrt` or a `steam-runtime-check-requirements` probe:
  - Valve's runtime is linked file by file;
  - the probe is replaced with `exit 0`;
  - its own `steam-runtime-launch-client` runs commands directly.

  For the arm64 client the session then starts it with `STEAM_RUNTIME_STEAMRT` and `PATH` pointing
  at `~/.droiddeck-steamrt-shim` and logs "Valve containerized the arm64 Steam client". Without those
  files the shim is not built and the start is unchanged.

## What Valve's manifests carry (checked 2026-10-22)

- `steam_client_steamdeck_publicbeta_linuxarm64` (1792626604) and `steam_client_steamdeck_stable_linuxarm64`
  (1792618285) have the same blocks. Three platforms share them:
  - `*_linuxarm64_linuxarm64`: the native client (`steamrtarm64/`, `linuxarm64/`). No pressure-vessel, no
    `steam-runtime-check-requirements`. These are the only platform blocks the installer keeps.
  - `*_steamrt_linuxarm64`: Valve's x86-64 SteamRT 3c client (`steamrt64/steam` is an x86-64 ELF) with
    `steamrt64/pv-runtime/steam-runtime-steamrt` (pressure-vessel, `steam-runtime-check-requirements`,
    `steam-runtime-launch-client`). This is the containerized client the 2026-10-15 beta made the default on
    x86. Valve ships it to arm64 hosts as well, presumably to run under FEX. The installer skips it.
  - `bins_androidarm64_linuxarm64`: `androidarm64/libsteamclient.so`, `steamservice.so` and friends.
- `steam_client_steamdeck_publicbeta_ubuntu12` has stopped at 1792108788, while stable `ubuntu12` follows
  stable (1792618285). On beta the 32-bit `ubuntu12` client no longer gets updates.
- If a future manifest drops `*_linuxarm64_linuxarm64`, the installer logs the block list and moves to
  stable. If stable drops it too, the remaining option is the x86-64 client under FEX
  ([x86-64-steam-client.md](x86-64-steam-client.md)).
