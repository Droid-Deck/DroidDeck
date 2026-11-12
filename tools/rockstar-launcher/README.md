# Rockstar launcher service

`Launcher.exe` starts the selected installed game and serves its launcher pipe. The Android app checks ownership and retrieves the launch response from Rockstar. Account credentials and ROS tickets stay in the app.

The handoff is deleted before starting the game. It expires after 90 seconds. The pipe accepts the child process's greeting and returns the server-issued launch material. No game files are patched.

GTA III and San Andreas default to full x87 precision for FEX in DroidDeck's per-game settings, before Wine starts. Reduced precision crashed GTA III's new-game initialization and corrupted San Andreas textures on the test device. Explicit per-game settings take precedence.

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
