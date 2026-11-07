# Steam store engine: provenance and license notice

The Steam module of `libdroiddeckstores.so` (`app/src/main/rust/stores/src/steam/`) is a
**derivative work of the `wn-steam-client` crate from the WinNative project**,
<https://github.com/WinNative-Emu/WinNative>, directory
`app/src/main/cpp/wn-steam-client/rust`, taken at upstream commit `a2020b7` (2026-09-01).
The upstream crate is published under **GPL-3.0-or-later**; this derivative is distributed under
the same license as part of DroidDeck. Copyright in the original code remains with the WinNative
authors and contributors, including maxjivi05.

The shared fetch core (`src/fetch_core.rs`, `src/fetch_tuning.rs`) and `src/md5_small.rs`, which
this module uses, come from the same upstream crate.

## Modifications made in DroidDeck (GPL-3.0 §5(a) statement of changes)

Date of modification: 2026-11-07.

- Ported into the `droiddeckstores` crate as the module `steam`; identifiers, log tag
  (`DroidDeckSteam`) and thread name (`steam-dl`) follow DroidDeck naming.
- Kept, with import paths adapted: WebSocket transport (`ws_connection`, `transport`), channel
  encryption and handshake (`encrypted_channel`, `handshake_messages`, `crypto`, `key_dictionary`),
  CM server discovery (`cm_server`, `cm_server_list`, `steam_directory`), wire formats
  (`emsg`, `cmsg_protobuf_header`, `proto_envelope`, `proto_wire`, `wire_format`, `base64`, `vdf`),
  `heartbeat`, `job_manager`, `content_manifest`, `depot_chunk`, and the protobuf messages for
  logon, licenses, PICS, depot keys, manifest request codes, CDN servers and free licenses.
- `cm_client` reduced to logon with a refresh token, heartbeat, log-off and the download-store
  requests; the logon sends `machine_id` `DroidDeck-Steam`; logon and log-off EResults are kept
  for error reporting.
- `cm_runtime`: no persona or friends traffic after logon, the C-ABI bridge (`cm_bridge`) is
  removed, and outbound messages are cleared from memory once sent.
- Blocking HTTP replaced by a one-shot request helper (`http`); `cdn_client` reduced to `cdn`
  (URLs, manifest and HTTP-delivered appinfo fetches); CDN servers are de-duplicated by host,
  capped at 10 and filtered by `allowed_app_ids`.
- `depot_writer` rewritten on DroidDeck's shared fetch core: files are written in place without
  `fallocate` or zero-fill and trimmed with `set_len`; the number of decode/write workers is a
  parameter; a chunk used in several places is fetched once; on resume, chunks on disk are read
  back and checked before anything is fetched.
- `depot_config` / `depot_downloader`: the resume journal moved out of the game folder into a
  caller-given directory, keyed by app and build; the progress sidecar and clean-pause markers are
  dropped.
- New: `signin` (reuse of the Linux Steam client's saved login), `session` (one process-wide CM
  session), `library`, `plan` (depot selection), `register` (app manifests, shared depots,
  `libraryfolders.vdf`, takeover of half-finished client downloads, uninstall), `kv` (Valve
  KeyValues writer), `install` (download orchestration) and a new JNI layer (`jni`).
- Not ported: QR and password sign-in, auth sessions, friends, chat and chat images, persona,
  lobbies, cloud, user stats, achievements, inventory, published files and workshop, app and
  ownership tickets, playing-session control, the Wine bridge, the other stores' downloaders,
  the version module and the original JNI layer.
