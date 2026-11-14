# Rockstar launcher service

`Launcher.exe` starts the selected installed game and serves its launcher pipes. The Android app obtains title authorization from Rockstar. Refresh credentials stay in Android Keystore-backed storage.

The handoff is mode 0600, expires after 90 seconds and is deleted before starting the game. Legacy launches carry server-issued launch material. SDK launches also carry a short-lived ROS ticket and account identity; those values are kept in memory after the handoff is consumed and are not logged. Pipes reject remote clients and check the client process against the launched child. No game files are patched.

GTA III, Vice City and San Andreas use body-length MTLX replies. GTA IV and L.A. Noire use total-length, terminated replies. Titles with installed Social Club metadata or SDK binaries use the SDK IPC adapter, including user-info, login, ticket, initialization and authorized title-state synchronization. The title metadata supplies the ROS identity and principal executable; it is checked against Rockstar's catalog before title authorization. Other titles retain Play and Steam shortcuts and attempt the normal executable launch path.

The SDK runtime is extracted from the game's own Social-Club-Setup.exe when present. Architecture-specific payloads are staged into the prefix, archive paths and sizes are checked, interrupted copies are retried, and both registry views are registered. The helper discovers launcher identity pipe names in the game executable and answers bounded INIT challenges from the launched process. No vendor executables or runtime files are bundled with this tool. SDK initialization has a 90-second deadline. SDK wire tests and fixture clients do not establish gameplay compatibility for every title; per-title ROS ticket exchange and secondary SDK channels remain unverified.

GTA III defaults to full x87 precision for FEX before Wine starts, because reduced precision crashed new-game initialization on the test device. San Andreas defaults to WineD3D, because DXVK intermittently corrupted its textures; full precision and conservative memory ordering did not prevent that corruption. These defaults use DroidDeck's per-game settings and preserve explicit per-game overrides.

Raw launcher identity requests use `INIT:<nonce>:` with a terminating colon. Framed requests carry their payload length. The reply echoes the complete challenge after `TINI:launcher:`; partial reads do not complete a raw request.

`build.sh` builds 32-bit and 64-bit executables with the GNU Windows targets and stages them as APK resources. `legacy-cipher.bin` contains the fixed client protocol tables, not account credentials or APK signing keys.

Run the protocol tests with:

```sh
cargo test --locked --manifest-path tools/rockstar-launcher/Cargo.toml
```

The optional `capture` example records a first-party launcher reply to a new local file. Run it on Windows or Wine while launching the corresponding game in Rockstar's launcher:

```powershell
cargo run --release --example capture --manifest-path tools/rockstar-launcher/Cargo.toml -- --pipe GTA-VC_Pipe --output 'C:\captures\reply.bin'
```

On Linux, cross-compile the example and run it in the same Wine prefix as Rockstar's launcher:

```sh
cargo build --release --locked --example capture --target x86_64-pc-windows-gnu --manifest-path tools/rockstar-launcher/Cargo.toml
WINEPREFIX=/path/to/launcher-prefix wine tools/rockstar-launcher/target/x86_64-pc-windows-gnu/release/examples/capture.exe --pipe GTA-VC_Pipe --output 'Z:\tmp\reply.bin'
```

Captures contain live launch material. Keep them outside source control; the tool prints only the message tag and payload length.
