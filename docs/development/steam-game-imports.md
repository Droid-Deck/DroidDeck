# Steam game imports

Second Library still registers the selected Steam library. It now also scans Windows
executables in its immediate game subfolders and `steamapps/common`, using the same
importer as Added Games. Existing Steam manifests are preserved.

`bannerlator-steam-games watch` runs beside the client. It identifies a game by an
existing manifest's install directory, a `steam_appid.txt` beside the game or selected
executable, or a unique exact normalized title from Steam's store search. Artwork's
fuzzy matching is deliberately not used for identity. Ambiguous or unresolved titles
remain non-Steam games.

Ownership comes from the running native ARM64 client's `BIsSubscribedApp`, through
versioned SteamClient020 / SteamUser021 / STEAMAPPS_INTERFACE_VERSION008 interfaces.
The helper gets the account from that connection, not from a public profile. No API
key, password, profile visibility change, or CEF debugging port is needed. Each probe
runs in a bounded subprocess so library failures cannot crash the importer or client.

The resulting snapshot is stored under that account's `userdata/<id>/config` and
refreshed every 30 seconds while logged in. Startup and post-exit import use the last
successful snapshot for the selected account. A newly identified title therefore
starts as a shortcut and becomes a Steam entry after Steam exits and starts again.
An unavailable client or offline first launch falls back to shortcuts; existing
snapshots remain usable offline. Steam still enforces the current license at launch.

For a confirmed game without a manifest, the importer links the source folder into
the internal Steam library and seeds an **update-required**, not installed, manifest.
Use Steam's Install/update flow to verify/reuse the files and fetch missing content.
Steam updates and uninstall operate on the linked game files, so imported folders
must be writable. The importer never overwrites an existing manifest or directory.
If registration fails, the game remains a shortcut. Once Steam takes over an install,
manage it in Steam; forgetting an Added Games folder only removes its shortcuts.

The account's `.droiddeck-routes.json` maps shortcut IDs to real Steam app IDs so the
Android launcher uses the same title and Proton prefix as Steam. Other accounts do
not inherit those routes. Managed shortcuts are replaced; user-created shortcuts and
unreadable shortcut files are preserved. Both routes use the existing ARM64 Proton
registration.

## Validation

Run `python3 -m unittest discover -s tools/tests -p 'test_*.py'` and Android unit tests.
Tests cover exact identity, account isolation, owned/unowned/unknown routing, manifest
preservation, filesystem collisions, shortcut preservation, and the versioned IPC
call/cleanup contract. Real-client validation still requires a device with the ARM64
Steam runtime: sign in, import one owned and one unowned game, wait for the snapshot,
exit/reopen Steam, verify the owned install, and launch both. Also check switching
accounts and an offline start. The mocked IPC test does not establish compatibility
with a particular Steam runtime build.

References: [Steam Apps API](https://partner.steamgames.com/doc/api/ISteamApps#BIsSubscribedApp),
[Valve's versioned interfaces](https://github.com/ValveSoftware/Proton/tree/proton_11.0/lsteamclient/steamworks_sdk_154).
